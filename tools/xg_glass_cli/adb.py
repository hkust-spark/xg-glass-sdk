from __future__ import annotations

import platform
import re
import shutil
import subprocess
from pathlib import Path

from .constants import _DEVICE_VIDEO_PATH
from .prereqs import _find_android_sdk


def _pick_apk(project: Path, module: str, variant: str, serial: str | None) -> Path:
    apk_dir = project / module / "build" / "outputs" / "apk" / variant
    if not apk_dir.is_dir():
        raise FileNotFoundError(f"APK output dir not found (did you run build?): {apk_dir}")

    apks = sorted(apk_dir.glob("*.apk"))
    if not apks:
        raise FileNotFoundError(f"No APKs found under: {apk_dir}")
    if len(apks) == 1:
        return apks[0]

    # If ABI-split APKs exist, try to pick the one matching the connected device ABI.
    abi = _adb_getprop("ro.product.cpu.abi", serial=serial)
    if abi:
        for p in apks:
            if abi in p.name:
                return p

    # Fallback: prefer universal/debug-looking APKs.
    for hint in ("universal", f"{variant}.apk", f"-{variant}.apk"):
        for p in apks:
            if hint in p.name:
                return p
    return apks[0]


def _adb_not_found_error() -> RuntimeError:
    return RuntimeError(
        "adb not found. Install Android SDK platform-tools or set ANDROID_SDK_ROOT/ANDROID_HOME "
        "to an Android SDK that contains platform-tools/adb."
    )


def _adb_line_is_ready_device(line: str) -> bool:
    fields = line.strip().split()
    return len(fields) >= 2 and fields[0].lower() != "no" and fields[1] == "device"


def _adb_getprop(prop: str, serial: str | None) -> str | None:
    cmd = [_find_adb_cmd()]
    if serial:
        cmd += ["-s", serial]
    cmd += ["shell", "getprop", prop]
    try:
        out = subprocess.check_output(cmd, stderr=subprocess.DEVNULL, text=True).strip()
        return out or None
    except FileNotFoundError as exc:
        raise _adb_not_found_error() from exc
    except subprocess.CalledProcessError:
        return None


def _read_application_id(project: Path, module: str) -> str | None:
    f = project / module / "build.gradle.kts"
    if not f.exists():
        return None
    s = f.read_text(encoding="utf-8")
    m = re.search(r'applicationId\s*=\s*"([^"]+)"', s)
    return m.group(1) if m else None


def _adb_has_device(serial: str | None = None) -> bool:
    """Return True if at least one device/emulator is connected via adb."""
    adb = _find_adb_cmd()
    try:
        out = subprocess.check_output([adb, "devices"], text=True, stderr=subprocess.DEVNULL)
        for line in out.strip().splitlines()[1:]:
            if _adb_line_is_ready_device(line):
                return True
    except FileNotFoundError as exc:
        raise _adb_not_found_error() from exc
    except subprocess.CalledProcessError:
        pass
    return False


def _find_adb_cmd() -> str:
    """Locate the ``adb`` binary, checking PATH and the managed Android SDK."""
    p = shutil.which("adb")
    if p:
        return p
    sdk = _find_android_sdk()
    if sdk:
        name = "adb.exe" if platform.system() == "Windows" else "adb"
        candidate = Path(sdk) / "platform-tools" / name
        if candidate.exists():
            return str(candidate)
    return "adb"  # fallback – let the OS raise a clear error


def _push_video_to_device(video_path: Path, serial: str | None = None) -> None:
    """Push a video file to the emulator/device via adb."""
    adb = _find_adb_cmd()
    cmd = [adb]
    if serial:
        cmd += ["-s", serial]
    cmd += ["push", str(video_path), _DEVICE_VIDEO_PATH]
    print(f"Pushing video to device: {_DEVICE_VIDEO_PATH}")
    subprocess.check_call(cmd)
