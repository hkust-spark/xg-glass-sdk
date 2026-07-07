from __future__ import annotations

import os
import platform
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from . import sdk_fetch
from .adb import _find_adb_cmd
from .android_sdk import _find_android_sdk
from .constants import _MANAGED_FLUTTER_DIR, _MANAGED_JDK_DIR, _MAX_AGP_JDK_MAJOR
from .emulator import _find_emulator_cmd
from .flutter import _PINNED_FLUTTER_VERSION, _find_flutter_cmd
from .java import _discover_existing_jdk, _java_home_major


@dataclass(frozen=True)
class DoctorRow:
    status: str
    name: str
    detail: str
    hint: str


def run_doctor(*, offline: bool = False, out=None, env: dict[str, str] | None = None) -> int:
    output = sys.stdout if out is None else out
    env = dict(os.environ if env is None else env)
    rows = collect_rows(offline=offline, env=env)
    print_rows(rows, out=output)
    failed = [row for row in rows if row.status == "FAIL"]
    warnings = [row for row in rows if row.status == "WARN"]
    print(f"Summary: {len(failed)} FAIL, {len(warnings)} WARN, {len(rows)} checks", file=output)
    return 1 if failed else 0


def collect_rows(*, offline: bool, env: dict[str, str]) -> list[DoctorRow]:
    rows = [
        check_python(),
        check_jdk(env),
        check_android_sdk(),
        check_adb(),
        check_adb_devices(),
        check_emulator_binary(),
        check_emulator_acceleration(),
        check_flutter(),
        check_sdk_resolution(),
    ]
    rows.extend(check_network(offline=offline))
    return rows


def print_rows(rows: list[DoctorRow], *, out) -> None:
    width = max([len(row.name) for row in rows] + [1])
    for row in rows:
        print(f"[ {row.status:<4} ] {row.name:<{width}} {row.detail} Hint: {row.hint}", file=out)


def check_python() -> DoctorRow:
    version = sys.version_info
    detail = f"Python {version.major}.{version.minor}.{version.micro} at {sys.executable}"
    if version >= (3, 9):
        return DoctorRow("OK", "python", detail, "Python >=3.9 satisfies the CLI requirement.")
    return DoctorRow("FAIL", "python", detail, "Install Python 3.9+ and rerun xg-glass doctor.")


def check_jdk(env: dict[str, str]) -> DoctorRow:
    java_home = _discover_existing_jdk(env)
    if not java_home:
        return DoctorRow(
            "FAIL",
            "jdk",
            "No compatible JDK resolved by the CLI.",
            "Install JDK 17 or 21, or let xg-glass manage one during build/init.",
        )
    major = _java_home_major(java_home)
    source = _jdk_source(java_home, env)
    if major is None:
        return DoctorRow("FAIL", "jdk", f"{source} at {java_home} did not report a Java version.", "Fix JAVA_HOME or install JDK 17/21.")
    detail = f"JDK {major} from {source} at {java_home}"
    if 17 <= major <= _MAX_AGP_JDK_MAJOR:
        return DoctorRow("OK", "jdk", detail, "No action needed.")
    return DoctorRow(
        "FAIL",
        "jdk",
        detail,
        f"Use JDK 17..{_MAX_AGP_JDK_MAJOR}; set JAVA_HOME to a supported JDK.",
    )


def _jdk_source(java_home: str, env: dict[str, str]) -> str:
    env_home = (env.get("JAVA_HOME") or "").strip()
    if env_home and Path(env_home).expanduser() == Path(java_home).expanduser():
        return "JAVA_HOME"
    if str(java_home).startswith(str(_MANAGED_JDK_DIR)):
        return "managed JDK"
    if "Android Studio.app/Contents/jbr" in java_home:
        return "Android Studio JBR"
    return "discovered JDK"


def check_android_sdk() -> DoctorRow:
    sdk = _find_android_sdk()
    if not sdk:
        partial = _partial_android_sdk_hint()
        detail = partial or "No Android SDK root resolved by the CLI."
        return DoctorRow("FAIL", "android-sdk", detail, "Install Android Studio/SDK or set ANDROID_HOME/ANDROID_SDK_ROOT.")

    root = Path(sdk).expanduser()
    adb = root / "platform-tools" / _exe("adb")
    emulator = root / "emulator" / _exe("emulator")
    sdkmanager = _sdk_tool(root, "sdkmanager")
    avdmanager = _sdk_tool(root, "avdmanager")
    missing = []
    if not adb.is_file():
        missing.append("platform-tools/adb")
    if not emulator.is_file():
        missing.append("emulator")
    if sdkmanager is None:
        missing.append("sdkmanager")
    if avdmanager is None:
        missing.append("avdmanager")

    if missing:
        return DoctorRow(
            "WARN",
            "android-sdk",
            f"{root} is partially provisioned; missing {', '.join(missing)}.",
            "Run sdkmanager for missing packages; xg-glass can auto-provision its managed SDK.",
        )
    return DoctorRow("OK", "android-sdk", f"{root}", "No action needed.")


def _partial_android_sdk_hint() -> str | None:
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        raw = (os.environ.get(name) or "").strip()
        if raw and Path(raw).expanduser().is_dir():
            return f"{name} points to {raw}, but platform-tools is missing."
    return None


def _sdk_tool(sdk: Path, name: str) -> Path | None:
    exe = _exe(name)
    for candidate in [
        sdk / "cmdline-tools" / "latest" / "bin" / exe,
        sdk / "tools" / "bin" / exe,
    ]:
        if candidate.is_file():
            return candidate
    for candidate in (sdk / "cmdline-tools").glob("*/bin/" + exe):
        if candidate.is_file():
            return candidate
    return None


def check_adb() -> DoctorRow:
    adb = _find_adb_cmd()
    try:
        completed = subprocess.run([adb, "version"], capture_output=True, text=True, timeout=10)
    except FileNotFoundError:
        return DoctorRow("FAIL", "adb", f"{adb} was not executable.", "Install Android SDK platform-tools and ensure adb is on PATH.")
    except Exception as exc:
        return DoctorRow("FAIL", "adb", f"{adb} failed: {exc}", "Fix the adb executable or Android SDK installation.")
    if completed.returncode != 0:
        return DoctorRow("FAIL", "adb", f"{adb} returned exit {completed.returncode}.", "Fix platform-tools or reinstall Android SDK.")
    first = ((completed.stdout or "") + "\n" + (completed.stderr or "")).strip().splitlines()
    version = first[0] if first else "version reported"
    return DoctorRow("OK", "adb", f"{version} at {adb}", "No action needed.")


def check_adb_devices() -> DoctorRow:
    adb = _find_adb_cmd()
    try:
        out = subprocess.check_output([adb, "devices"], text=True, stderr=subprocess.DEVNULL, timeout=10)
    except FileNotFoundError:
        return DoctorRow("FAIL", "adb-devices", f"{adb} was not executable.", "Install Android SDK platform-tools.")
    except Exception as exc:
        return DoctorRow("WARN", "adb-devices", f"Could not query devices: {exc}", "Start adb with `adb start-server` and reconnect devices.")

    devices = _parse_adb_devices(out)
    if not devices:
        return DoctorRow("OK", "adb-devices", "No attached devices or emulators.", "Connect a device or use `xg-glass run --sim`.")
    bad = [f"{serial}={state}" for serial, state in devices if state != "device"]
    rendered = ", ".join(f"{serial}={state}" for serial, state in devices)
    if bad:
        return DoctorRow("WARN", "adb-devices", rendered, "Authorize USB debugging or restart offline emulators.")
    return DoctorRow("OK", "adb-devices", rendered, "No action needed.")


def _parse_adb_devices(output: str) -> list[tuple[str, str]]:
    devices = []
    for raw in output.splitlines()[1:]:
        fields = raw.strip().split()
        if len(fields) >= 2:
            devices.append((fields[0], fields[1]))
    return devices


def check_emulator_acceleration() -> DoctorRow:
    system = platform.system()
    if system == "Linux":
        kvm = Path("/dev/kvm")
        if kvm.exists() and os.access(kvm, os.R_OK | os.W_OK):
            return DoctorRow("OK", "emulator-accel", "/dev/kvm is present and writable.", "No action needed.")
        if kvm.exists():
            return DoctorRow("WARN", "emulator-accel", "/dev/kvm exists but is not writable.", "Add the user to the kvm group or fix udev permissions.")
        return DoctorRow("WARN", "emulator-accel", "/dev/kvm is missing.", "Enable KVM on Linux hosts for emulator performance.")
    if system == "Darwin":
        return DoctorRow("OK", "emulator-accel", "Hypervisor.framework is available on supported Macs.", "No action needed.")
    if system == "Windows":
        return DoctorRow("OK", "emulator-accel", "WHPX/HAXM not probed by doctor.", "Enable Windows Hypervisor Platform if emulator boot fails.")
    return DoctorRow("OK", "emulator-accel", f"{system} acceleration not probed.", "Check emulator docs if boot fails.")


def check_flutter() -> DoctorRow:
    flutter = _find_flutter_cmd()
    if not flutter:
        return DoctorRow(
            "OK",
            "flutter",
            f"Not installed; managed pin is {_PINNED_FLUTTER_VERSION} and only needed for Frame.",
            "Install Flutter only if you use the Frame adapter.",
        )
    version = _flutter_version(flutter)
    managed = str(Path(flutter)).startswith(str(_MANAGED_FLUTTER_DIR))
    source = "managed Flutter" if managed else "Flutter"
    return DoctorRow("OK", "flutter", f"{source} {version} at {flutter}", "No action needed for Frame workflows.")


def _flutter_version(flutter: str) -> str:
    try:
        completed = subprocess.run([flutter, "--version"], capture_output=True, text=True, timeout=20)
    except Exception:
        return "version unknown"
    text = ((completed.stdout or "") + "\n" + (completed.stderr or "")).strip().splitlines()
    return text[0] if text else "version unknown"


def check_sdk_resolution() -> DoctorRow:
    default_sdk = sdk_fetch.DEFAULT_SDK.expanduser().resolve()
    if sdk_fetch._is_sdk_checkout(default_sdk):
        return DoctorRow("OK", "sdk", f"checkout at {default_sdk}", "No action needed.")

    try:
        version = sdk_fetch._installed_sdk_version()
    except Exception as exc:
        return DoctorRow("FAIL", "sdk", f"Could not determine installed SDK version: {exc}", "Reinstall the xg-glass CLI package.")

    cached = sdk_fetch._cached_sdk_path(version)
    if sdk_fetch._is_sdk_checkout(cached):
        return DoctorRow("OK", "sdk", f"cache version {version} at {cached}", "No action needed.")
    return DoctorRow(
        "OK",
        "sdk",
        f"would download version {version} to {cached}",
        "First init/run may download the SDK; pass --sdk to force a checkout.",
    )


def check_network(*, offline: bool) -> list[DoctorRow]:
    if offline:
        return [
            DoctorRow("OK", "network", "Skipped because --offline was set.", "Rerun without --offline to test download hosts.")
        ]
    rows = []
    for host in ("https://dl.google.com/", "https://repo1.maven.org/maven2/"):
        rows.append(_check_url(host))
    return rows


def _check_url(url: str) -> DoctorRow:
    try:
        request = urllib.request.Request(url, method="HEAD")
        with urllib.request.urlopen(request, timeout=5) as response:
            code = getattr(response, "status", None) or response.getcode()
    except (urllib.error.URLError, OSError, TimeoutError) as exc:
        return DoctorRow(
            "WARN",
            "network",
            f"{url} unreachable: {exc}",
            "Check proxy/firewall settings; set HTTPS_PROXY/HTTP_PROXY if needed.",
        )
    if 200 <= int(code) < 400:
        return DoctorRow("OK", "network", f"{url} reachable ({code}).", "No action needed.")
    return DoctorRow(
        "WARN",
        "network",
        f"{url} returned HTTP {code}.",
        "Check proxy/firewall settings; set HTTPS_PROXY/HTTP_PROXY if needed.",
    )


def check_emulator_binary() -> DoctorRow:
    emulator = _find_emulator_cmd()
    if emulator:
        return DoctorRow("OK", "emulator", f"{emulator}", "No action needed.")
    return DoctorRow("WARN", "emulator", "No emulator binary resolved.", "Install the Android SDK emulator package for --sim.")


def _exe(name: str) -> str:
    return name + ".exe" if platform.system() == "Windows" else name
