from __future__ import annotations

import argparse
import importlib.metadata
import os
import platform
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable

from . import commands
from .adb import _adb_getprop, _find_adb_cmd, _read_application_id
from .constants import DEFAULT_CONFIG_FILE, CliUsageError
from .devices import VALID_DEVICE_NAMES
from .doctor import DoctorRow, collect_rows
from .emulator import _ensure_emulator_running
from .sdk_fetch import resolve_sdk

ISSUE_URL = "https://github.com/hkust-spark/xg-glass-sdk/issues/63"
DEFAULT_ENTRY_CLASS = "com.example.xgglassapp.logic.ExampleAppEntry"
DEFAULT_PACKAGE = "com.example.xgglassapp"
LOGCAT_TAG = "XgGlassApp:I"


@dataclass(frozen=True)
class StepDefinition:
    id: str
    title: str
    instruction: str
    marker: str | None
    ui_text: str | None = None
    timeout_seconds: int = 60


@dataclass(frozen=True)
class DeviceProfile:
    device: str
    model: str
    capabilities: tuple[str, ...]
    connect_marker: str
    connect_instruction: str
    auto_tap_ui: bool = False


@dataclass(frozen=True)
class StepResult:
    id: str
    title: str
    verdict: str
    evidence: str
    note: str


@dataclass(frozen=True)
class ValidationEnvironment:
    cli_version: str
    os_name: str
    adb_devices: str
    phone_model: str
    serial: str | None
    preflight_rows: tuple[DoctorRow, ...]


DEVICE_PROFILES: dict[str, DeviceProfile] = {
    "rokid": DeviceProfile(
        device="rokid",
        model="ROKID",
        capabilities=("capture", "display", "mic"),
        connect_marker=r"connect\(ROKID\) => true",
        connect_instruction=(
            "Connect the Android host phone to Rokid, load any required SN license/client secret, "
            "then tap Connect in the app."
        ),
    ),
    "rayneo": DeviceProfile(
        device="rayneo",
        model="RAYNEO",
        capabilities=("capture", "display", "mic", "video"),
        connect_marker=r"install\(RAYNEO\) => true",
        connect_instruction="Enter the RayNeo glasses IP address if prompted, then tap Connect to install the glasses app.",
    ),
    "meta": DeviceProfile(
        device="meta",
        model="META",
        capabilities=("capture", "mic"),
        connect_marker=r"connect\(META\) => true",
        connect_instruction="Put Meta glasses in the expected connected state, grant permissions, then tap Connect in the app.",
    ),
    "frame": DeviceProfile(
        device="frame",
        model="FRAME",
        capabilities=("capture", "display", "mic", "tap"),
        connect_marker=r"connect\(FRAME\) => true",
        connect_instruction="Put Frame in pairing mode, then tap Connect in the app.",
    ),
    "omi": DeviceProfile(
        device="omi",
        model="OMI",
        capabilities=("capture", "mic", "tap"),
        connect_marker=r"connect\(OMI\) => true",
        connect_instruction="Put Omi in pairing mode near the phone, then tap Connect in the app.",
    ),
    "even": DeviceProfile(
        device="even",
        model="EVEN",
        capabilities=("display", "mic", "tap", "long_press"),
        connect_marker=r"connect\(EVEN\) => true",
        connect_instruction="Put both Even G1 arms in pairing mode, then tap Connect in the app.",
    ),
    "inmo": DeviceProfile(
        device="inmo",
        model="INMO",
        capabilities=("capture", "display", "mic", "video", "tap", "long_press"),
        connect_marker=r"connect\(INMO\) => true",
        connect_instruction="Run this on the INMO host device, then tap Connect in the app.",
    ),
    "simulator": DeviceProfile(
        device="simulator",
        model="SIMULATOR",
        capabilities=("capture", "display", "mic", "video", "tap", "long_press"),
        connect_marker=r"connect\(SIMULATOR\) => true",
        connect_instruction="Wait for the generated simulator app to auto-connect after launch.",
        auto_tap_ui=True,
    ),
}

CAPABILITY_STEPS: dict[str, StepDefinition] = {
    "capture": StepDefinition(
        id="capture",
        title="Capture photo",
        instruction="Tap Capture photo in the app and confirm an image byte count is logged.",
        marker=r"capture_photo: [1-9][0-9]* bytes",
        ui_text="Capture photo",
        timeout_seconds=60,
    ),
    "display": StepDefinition(
        id="display",
        title="Display hello",
        instruction="Tap Display hello in the app and confirm text appears on the glasses/display surface.",
        marker=r"display_hello: ok",
        ui_text="Display hello",
        timeout_seconds=30,
    ),
    "mic": StepDefinition(
        id="mic",
        title="Microphone recording",
        instruction="Tap Mic record 3s in the app and confirm non-empty microphone chunks are logged.",
        marker=r"mic_record: [1-9][0-9]* chunks, [0-9]+ bytes",
        ui_text="Mic record 3s",
        timeout_seconds=30,
    ),
    "video": StepDefinition(
        id="video",
        title="Video stream",
        instruction="Tap Video stream 3s in the app and confirm non-empty video frames are logged.",
        marker=r"video_stream: [1-9][0-9]* frames, [1-9][0-9]* bytes",
        ui_text="Video stream 3s",
        timeout_seconds=45,
    ),
    "tap": StepDefinition(
        id="tap",
        title="Tap event",
        instruction="Trigger a single tap on the glasses, or tap Simulate Tap when validating the simulator.",
        marker=r"TAP: 1",
        ui_text="Simulate Tap",
        timeout_seconds=45,
    ),
    "long_press": StepDefinition(
        id="long_press",
        title="Long-press event",
        instruction="Trigger the device long-press gesture, or tap Simulate Long-press when validating the simulator.",
        marker=r"LONG_PRESS",
        ui_text="Simulate Long-press",
        timeout_seconds=45,
    ),
}


def cmd_validate(args: argparse.Namespace) -> int:
    device = _parse_validate_device(args.devices)
    profile = DEVICE_PROFILES[device]
    report_path = _report_path(args.report, device)
    project_dir = _prepare_project_dir(device, keep=bool(args.keep_project))
    serial = getattr(args, "serial", None)
    sdk = resolve_sdk(getattr(args, "sdk", None), subcommand="validate")
    template = commands._resolve_quick_run_template(sdk)
    preflight_rows = tuple(collect_rows(offline=True, env=dict(os.environ)))

    print(f"Preparing validation project for {device}: {project_dir}")
    try:
        commands._init_project(
            dst=project_dir,
            template=template,
            sdk=sdk,
            entry_class=DEFAULT_ENTRY_CLASS,
            devices=device,
            sim=device == "simulator",
        )
        actual_serial = _build_install_launch(project_dir, profile, serial=serial)
        env = collect_environment(actual_serial, preflight_rows=preflight_rows)
        results = run_step_table(profile, serial=actual_serial)
        report = render_report(device, env, results)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(report, encoding="utf-8")
        print(f"Validation report written to: {report_path}")
        print(f"Paste the report into: {ISSUE_URL}")
        if bool(args.keep_project):
            print(f"Validation project kept at: {project_dir}")
        return 0
    finally:
        if not bool(args.keep_project):
            shutil.rmtree(project_dir, ignore_errors=True)


def derive_steps(device: str) -> list[StepDefinition]:
    profile = DEVICE_PROFILES[_parse_validate_device(device)]
    return [
        StepDefinition(
            id="connect",
            title="Connect",
            instruction=profile.connect_instruction,
            marker=profile.connect_marker,
            timeout_seconds=90,
        ),
        *(CAPABILITY_STEPS[capability] for capability in profile.capabilities),
    ]


def parse_logcat_marker(logcat: str, marker: str | None) -> str | None:
    if not marker:
        return None
    regex = re.compile(marker)
    for line in logcat.splitlines():
        if regex.search(line):
            return line.strip()
    return None


def render_report(device: str, env: ValidationEnvironment, results: list[StepResult]) -> str:
    rows = "\n".join(
        f"| {result.title} | {result.verdict} | {_md_cell(result.evidence)} | {_md_cell(result.note)} |"
        for result in results
    )
    preflight = "\n".join(
        f"| {row.status} | {_md_cell(row.name)} | {_md_cell(row.detail)} | {_md_cell(row.hint)} |"
        for row in env.preflight_rows
    )
    return "\n".join(
        [
            f"# xg.glass hardware validation: {device}",
            "",
            f"- Issue: {ISSUE_URL}",
            f"- CLI version: {env.cli_version}",
            f"- OS: {env.os_name}",
            f"- adb serial: {env.serial or '(not selected)'}",
            f"- Phone/device: {env.phone_model}",
            "",
            "## adb devices",
            "",
            "```text",
            env.adb_devices.rstrip() or "(unavailable)",
            "```",
            "",
            "## Preflight",
            "",
            "| Status | Check | Detail | Hint |",
            "| --- | --- | --- | --- |",
            preflight,
            "",
            "## Step Results",
            "",
            "| Step | Verdict | Auto evidence | Notes |",
            "| --- | --- | --- | --- |",
            rows,
            "",
            "## Notes",
            "",
            "No BLE automation is performed by `xg-glass validate`; auto-checks depend on generated-app logcat markers. "
            "Everything else is guided manual validation.",
            "",
            f"Paste this report into {ISSUE_URL}.",
            "",
        ]
    )


def collect_environment(serial: str | None, *, preflight_rows: tuple[DoctorRow, ...]) -> ValidationEnvironment:
    adb = _find_adb_cmd()
    try:
        adb_devices = subprocess.check_output([adb, "devices"], text=True, stderr=subprocess.DEVNULL, timeout=10)
    except Exception as exc:
        adb_devices = f"unavailable: {exc}"
    return ValidationEnvironment(
        cli_version=_cli_version(),
        os_name=platform.platform(),
        adb_devices=adb_devices,
        phone_model=_phone_model(serial),
        serial=serial,
        preflight_rows=preflight_rows,
    )


def run_step_table(
    profile: DeviceProfile,
    *,
    serial: str | None,
    input_func: Callable[[str], str] = input,
) -> list[StepResult]:
    results: list[StepResult] = []
    for step in derive_steps(profile.device):
        print()
        print(f"== {step.title} ==")
        print(step.instruction)
        if profile.auto_tap_ui and step.ui_text:
            tapped = tap_text(step.ui_text, serial=serial)
            if tapped:
                print(f"Tapped '{step.ui_text}' automatically.")
            else:
                print(f"Could not tap '{step.ui_text}' automatically; waiting for marker/manual input.")

        evidence = wait_for_logcat_marker(step.marker, serial=serial, timeout_seconds=step.timeout_seconds)
        if evidence:
            print(f"PASS: {evidence}")
            results.append(StepResult(step.id, step.title, "PASS", evidence, "auto-check"))
            continue

        verdict, note = _manual_verdict(step, input_func=input_func)
        results.append(StepResult(step.id, step.title, verdict, "", note))
    return results


def wait_for_logcat_marker(marker: str | None, *, serial: str | None, timeout_seconds: int) -> str | None:
    if not marker:
        return None
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        line = parse_logcat_marker(_read_logcat(serial), marker)
        if line:
            return line
        time.sleep(1)
    return None


def tap_text(text: str, *, serial: str | None, timeout_seconds: int = 45) -> bool:
    adb = _find_adb_cmd()
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        xml = _dump_ui_xml(adb, serial)
        coords = _find_text_center(xml, text, clickable_only=True) if xml else None
        if coords:
            subprocess.run(_adb_cmd(adb, serial, ["shell", "input", "tap", str(coords[0]), str(coords[1])]), check=False)
            return True
        time.sleep(1)
    return False


def _build_install_launch(project_dir: Path, profile: DeviceProfile, *, serial: str | None) -> str | None:
    if profile.device == "simulator":
        actual_serial = _ensure_simulator_serial(serial)
    else:
        actual_serial = serial or _choose_ready_serial(prefer_emulator=False)

    commands.cmd_build(
        argparse.Namespace(
            project=str(project_dir),
            variant="debug",
            module="app",
            config=DEFAULT_CONFIG_FILE,
            entry_class=None,
            sdk=None,
            rayneo_aar_dir=None,
        )
    )
    commands.cmd_install(
        argparse.Namespace(
            project=str(project_dir),
            variant="debug",
            module="app",
            config=DEFAULT_CONFIG_FILE,
            serial=actual_serial,
            apk=None,
        )
    )
    package_name = _read_application_id(project_dir, "app") or DEFAULT_PACKAGE
    _grant_runtime_permissions(package_name, profile.device, serial=actual_serial)
    _clear_logcat(serial=actual_serial)
    commands.cmd_run(
        argparse.Namespace(
            project=str(project_dir),
            variant="debug",
            module="app",
            config=DEFAULT_CONFIG_FILE,
            serial=actual_serial,
            package=package_name,
            kt_file=None,
            save=None,
            keep_tmp=False,
            entry_class=None,
            sdk=None,
            sim=False,
            devices=None,
            local_video=None,
            video_url=None,
        )
    )
    return actual_serial


def _ensure_simulator_serial(serial: str | None) -> str:
    if serial and not serial.startswith("emulator-"):
        raise CliUsageError("Simulator validation requires an emulator serial such as emulator-5554.")
    if serial:
        _ensure_emulator_running(serial=serial)
        return serial
    ready = _choose_ready_serial(prefer_emulator=True)
    if ready:
        return ready
    _ensure_emulator_running(serial="emulator-5554")
    ready = _choose_ready_serial(prefer_emulator=True)
    if ready:
        return ready
    raise CliUsageError("Simulator validation requires a ready Android Emulator; auto-start did not expose one.")


def _parse_validate_device(raw: str | None) -> str:
    if raw is None:
        raise CliUsageError("--devices is required.")
    device = raw.strip().lower()
    if "," in device or device == "all":
        raise CliUsageError("--devices accepts exactly one concrete device for validate.")
    if device not in VALID_DEVICE_NAMES:
        valid = ", ".join(VALID_DEVICE_NAMES)
        raise CliUsageError(f"Unknown device for --devices: {raw}. Valid values: {valid}.")
    return device


def _report_path(raw_report: str | None, device: str) -> Path:
    if raw_report:
        return Path(raw_report).expanduser().resolve()
    stamp = datetime.now().strftime("%Y%m%d")
    return (Path.cwd() / f"validate-report-{device}-{stamp}.md").resolve()


def _prepare_project_dir(device: str, *, keep: bool) -> Path:
    if keep:
        base = Path.cwd() / ".xg_glass_tmp"
        base.mkdir(parents=True, exist_ok=True)
        return Path(tempfile.mkdtemp(prefix=f"validate-{device}-", dir=str(base))).resolve()
    return Path(tempfile.mkdtemp(prefix=f"xg-glass-validate-{device}-")).resolve()


def _manual_verdict(step: StepDefinition, *, input_func: Callable[[str], str]) -> tuple[str, str]:
    while True:
        try:
            raw = input_func(f"Manual verdict for {step.id} [p=pass/f=fail/s=skip]: ").strip().lower()
        except EOFError:
            return "SKIP", "No interactive input available after auto-check timed out."
        if raw in {"p", "pass"}:
            verdict = "PASS"
            break
        if raw in {"f", "fail"}:
            verdict = "FAIL"
            break
        if raw in {"", "s", "skip"}:
            verdict = "SKIP"
            break
        print("Please enter p, f, or s.")
    try:
        note = input_func("Note (optional): ").strip()
    except EOFError:
        note = ""
    return verdict, note


def _read_logcat(serial: str | None) -> str:
    adb = _find_adb_cmd()
    try:
        return subprocess.check_output(
            _adb_cmd(adb, serial, ["logcat", "-d", "-s", LOGCAT_TAG]),
            text=True,
            stderr=subprocess.DEVNULL,
            timeout=10,
        )
    except Exception:
        return ""


def _clear_logcat(serial: str | None) -> None:
    adb = _find_adb_cmd()
    subprocess.run(_adb_cmd(adb, serial, ["logcat", "-c"]), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _grant_runtime_permissions(package_name: str, device: str, *, serial: str | None) -> None:
    permissions = {
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.ACCESS_FINE_LOCATION",
    }
    if device == "simulator":
        permissions = {"android.permission.CAMERA", "android.permission.RECORD_AUDIO"}
    adb = _find_adb_cmd()
    for permission in sorted(permissions):
        subprocess.run(
            _adb_cmd(adb, serial, ["shell", "pm", "grant", package_name, permission]),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )


def _choose_ready_serial(*, prefer_emulator: bool) -> str | None:
    devices = _ready_devices()
    if prefer_emulator:
        for serial, _state in devices:
            if serial.startswith("emulator-"):
                return serial
        return None
    if len(devices) == 1:
        return devices[0][0]
    return None


def _ready_devices() -> list[tuple[str, str]]:
    adb = _find_adb_cmd()
    try:
        out = subprocess.check_output([adb, "devices"], text=True, stderr=subprocess.DEVNULL, timeout=10)
    except Exception:
        return []
    devices: list[tuple[str, str]] = []
    for line in out.splitlines()[1:]:
        fields = line.strip().split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append((fields[0], fields[1]))
    return devices


def _phone_model(serial: str | None) -> str:
    parts = []
    for prop in ("ro.product.manufacturer", "ro.product.model", "ro.build.version.release", "ro.product.cpu.abi"):
        value = _adb_getprop(prop, serial=serial)
        if value:
            parts.append(value)
    return " / ".join(parts) if parts else "(unavailable)"


def _dump_ui_xml(adb: str, serial: str | None) -> str | None:
    remote = "/sdcard/window.xml"
    try:
        subprocess.run(
            _adb_cmd(adb, serial, ["shell", "uiautomator", "dump", remote]),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True,
            timeout=10,
        )
        return subprocess.check_output(
            _adb_cmd(adb, serial, ["shell", "cat", remote]),
            text=True,
            stderr=subprocess.DEVNULL,
            timeout=10,
        )
    except Exception:
        return None


def _find_text_center(xml: str, text: str, *, clickable_only: bool) -> tuple[int, int] | None:
    wanted = _normalize(text)
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        if clickable_only and node.attrib.get("clickable") != "true":
            continue
        if node.attrib.get("enabled", "true") != "true":
            continue
        node_text = _normalize(node.attrib.get("text", ""))
        content_desc = _normalize(node.attrib.get("content-desc", ""))
        if wanted not in {node_text, content_desc}:
            continue
        bounds = _parse_bounds(node.attrib.get("bounds", ""))
        if bounds is None:
            continue
        left, top, right, bottom = bounds
        return (left + right) // 2, (top + bottom) // 2
    return None


def _parse_bounds(raw: str) -> tuple[int, int, int, int] | None:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw)
    if not match:
        return None
    return tuple(int(group) for group in match.groups())  # type: ignore[return-value]


def _normalize(text: str) -> str:
    return " ".join(text.casefold().split())


def _adb_cmd(adb: str, serial: str | None, tail: list[str]) -> list[str]:
    cmd = [adb]
    if serial:
        cmd += ["-s", serial]
    cmd += tail
    return cmd


def _cli_version() -> str:
    try:
        return importlib.metadata.version("xg-glass")
    except importlib.metadata.PackageNotFoundError:
        return "(editable checkout)"


def _md_cell(text: str) -> str:
    if not text:
        return ""
    return text.replace("|", "\\|").replace("\n", "<br>")
