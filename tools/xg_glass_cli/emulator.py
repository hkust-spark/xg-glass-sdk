from __future__ import annotations

import os
import platform
import shlex
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from .adb import _adb_has_device, _adb_line_is_ready_device, _adb_not_found_error, _find_adb_cmd
from .prereqs import _ensure_java_runtime, _find_android_sdk, _run_quiet

_ADB_WAIT_TIMEOUT_SECONDS = 5


def _find_emulator_cmd() -> str | None:
    """Locate the Android emulator binary."""
    p = shutil.which("emulator")
    if p:
        return p
    sdk = _find_android_sdk()
    if sdk:
        name = "emulator.exe" if platform.system() == "Windows" else "emulator"
        candidate = Path(sdk) / "emulator" / name
        if candidate.exists():
            return str(candidate)
    return None


def _list_avds() -> list[str]:
    """List available Android Virtual Devices."""
    emu = _find_emulator_cmd()
    if not emu:
        return []
    try:
        out = subprocess.check_output([emu, "-list-avds"], text=True, stderr=subprocess.DEVNULL)
        return [line.strip() for line in out.strip().splitlines() if line.strip()]
    except Exception:
        return []


def _ready_device_serials(adb_devices_output: str) -> set[str]:
    serials = set()
    for line in adb_devices_output.splitlines():
        if _adb_line_is_ready_device(line):
            serials.add(line.strip().split()[0])
    return serials


def _ready_emulator_serials(adb_devices_output: str) -> set[str]:
    return {serial for serial in _ready_device_serials(adb_devices_output) if serial.startswith("emulator-")}


def _adb_devices_output(adb: str) -> str:
    return subprocess.check_output(
        [adb, "devices"],
        text=True,
        stderr=subprocess.DEVNULL,
        timeout=_ADB_WAIT_TIMEOUT_SECONDS,
    )


def _default_system_image_abi() -> str:
    """Return the Android emulator system-image ABI for this host."""
    machine = platform.machine().lower()
    if machine in {"arm64", "aarch64"}:
        return "arm64-v8a"
    return "x86_64"


def _default_system_image_package() -> str:
    return f"system-images;android-34;google_apis;{_default_system_image_abi()}"


def _default_system_image_dir(sdk: str) -> Path:
    return Path(sdk) / "system-images" / "android-34" / "google_apis" / _default_system_image_abi()


def _tail_file(path: Path, *, max_chars: int = 4000) -> str:
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""
    return text[-max_chars:].strip()


def _launch_emulator_process(emu: str, avd_name: str, env: dict[str, str]):
    stderr_file = tempfile.NamedTemporaryFile(
        prefix="xg-glass-emulator-",
        suffix=".log",
        delete=False,
    )
    stderr_path = Path(stderr_file.name)
    popen_kwargs = {
        "stdout": subprocess.DEVNULL,
        "stderr": stderr_file,
        "env": env,
    }
    if platform.system() == "Windows":
        creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        if creationflags:
            popen_kwargs["creationflags"] = creationflags
    else:
        popen_kwargs["start_new_session"] = True
    extra_args = shlex.split(env.get("XG_EMULATOR_ARGS", ""))
    cmd = [emu, "-avd", avd_name, "-no-snapshot-load", *extra_args]
    try:
        process = subprocess.Popen(cmd, **popen_kwargs)
    finally:
        stderr_file.close()
    return process, stderr_path


def _raise_if_emulator_exited(process, stderr_path: Path) -> None:
    exit_code = process.poll()
    if exit_code is None:
        return
    detail = _tail_file(stderr_path)
    message = f"Emulator process exited before boot completed (exit code {exit_code})."
    if detail:
        message += f"\nEmulator stderr tail ({stderr_path}):\n{detail}"
    else:
        message += f"\nEmulator stderr log: {stderr_path}"
    raise RuntimeError(message)


def _ensure_emulator_running(serial: str | None = None) -> None:
    """
    If ``--sim`` is active and no device/emulator is connected, auto-start an AVD.

    Downloads the emulator + a system image via sdkmanager if needed, creates a
    default AVD if none exists, launches it, and waits for it to boot.
    """
    if _adb_has_device(serial):
        return

    print("No connected device or emulator found. Starting Android Emulator...")

    sdk = _find_android_sdk()
    if not sdk:
        raise RuntimeError(
            "Cannot start emulator: Android SDK not found.\n"
            "Please connect a device or start an emulator manually."
        )

    system = platform.system()

    # Locate sdkmanager (needed for installing emulator/system-images/AVD creation).
    sdkmanager_name = "sdkmanager.bat" if system == "Windows" else "sdkmanager"
    sdkmanager: Path | None = Path(sdk) / "cmdline-tools" / "latest" / "bin" / sdkmanager_name
    if not sdkmanager.exists():
        sdkmanager = Path(sdk) / "tools" / "bin" / sdkmanager_name
    if not sdkmanager.exists():
        sdkmanager = None

    # Check if the emulator binary is already installed before trying sdkmanager.
    emu = _find_emulator_cmd()
    if not emu:
        if sdkmanager is None:
            raise RuntimeError(
                "Emulator binary not found and sdkmanager not available to install it.\n"
                "Please start an emulator manually, or install Android SDK command-line tools."
            )
        print("  Installing emulator package...")
        env = {**os.environ, "ANDROID_HOME": sdk, "ANDROID_SDK_ROOT": sdk}
        _ensure_java_runtime(env)
        try:
            _run_quiet(
                [str(sdkmanager), f"--sdk_root={sdk}", "emulator", _default_system_image_package()],
                input_text="y\n" * 20,
                env=env,
                check=True,
                timeout=600,
                verbose_env="XG_VERBOSE_SDKMANAGER",
            )
        except subprocess.CalledProcessError as exc:
            raise RuntimeError(
                f"Failed to install emulator packages: {exc}\n"
                "Please start an emulator manually."
            ) from exc
        emu = _find_emulator_cmd()
        if not emu:
            raise RuntimeError("Emulator binary not found after installation. Please start an emulator manually.")

    # Check / create AVD.
    avds = _list_avds()
    avd_name = avds[0] if avds else "xg_glass_avd"
    if not avds:
        env = {**os.environ, "ANDROID_HOME": sdk, "ANDROID_SDK_ROOT": sdk}
        _ensure_java_runtime(env)
        sys_img = _default_system_image_dir(sdk)
        if not sys_img.is_dir() and sdkmanager is not None:
            print("  Installing system image...")
            try:
                _run_quiet(
                    [str(sdkmanager), f"--sdk_root={sdk}", _default_system_image_package()],
                    input_text="y\n" * 20,
                    env=env,
                    check=True,
                    timeout=600,
                    verbose_env="XG_VERBOSE_SDKMANAGER",
                )
            except subprocess.CalledProcessError:
                pass

        print(f"  Creating AVD '{avd_name}'...")
        avdmanager_name = "avdmanager.bat" if system == "Windows" else "avdmanager"
        avdmanager = Path(sdk) / "cmdline-tools" / "latest" / "bin" / avdmanager_name
        if not avdmanager.exists():
            avdmanager = Path(sdk) / "tools" / "bin" / avdmanager_name
        if avdmanager.exists():
            try:
                subprocess.run(
                    [
                        str(avdmanager), "create", "avd",
                        "-n", avd_name,
                        "-k", _default_system_image_package(),
                        "-d", "pixel",
                        "--force",
                    ],
                    input="no\n",
                    text=True, env=env, check=True, timeout=60,
                )
            except subprocess.CalledProcessError as exc:
                raise RuntimeError(
                    f"Failed to create AVD: {exc}\n"
                    "Please create an AVD manually or start an emulator."
                ) from exc
        else:
            raise RuntimeError("avdmanager not found. Please create an AVD manually.")

    # Wait for device to come online and finish booting.
    adb = _find_adb_cmd()
    try:
        before_emulators = _ready_emulator_serials(_adb_devices_output(adb))
    except FileNotFoundError as exc:
        raise _adb_not_found_error() from exc
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        before_emulators = set()

    # Launch emulator in background.
    print(f"  Launching emulator (AVD: {avd_name})...")
    env = {**os.environ, "ANDROID_HOME": sdk, "ANDROID_SDK_ROOT": sdk}
    process, stderr_path = _launch_emulator_process(emu, avd_name, env)
    target_serial = serial

    print("  Waiting for emulator to boot", end="", flush=True)
    deadline = time.monotonic() + 180  # 3 minute timeout
    while time.monotonic() < deadline:
        _raise_if_emulator_exited(process, stderr_path)
        time.sleep(3)
        _raise_if_emulator_exited(process, stderr_path)
        print(".", end="", flush=True)
        try:
            out = _adb_devices_output(adb)
            ready_devices = _ready_device_serials(out)
            if target_serial is None:
                new_emulators = sorted(_ready_emulator_serials(out) - before_emulators)
                if new_emulators:
                    target_serial = new_emulators[0]

            if target_serial is not None and target_serial in ready_devices:
                # Check boot_completed
                try:
                    getprop_cmd = [adb, "-s", target_serial, "shell", "getprop", "sys.boot_completed"]
                    boot = subprocess.check_output(
                        getprop_cmd,
                        text=True,
                        stderr=subprocess.DEVNULL,
                        timeout=_ADB_WAIT_TIMEOUT_SECONDS,
                    ).strip()
                    if boot == "1":
                        print(" Ready!")
                        return
                except FileNotFoundError as exc:
                    raise _adb_not_found_error() from exc
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                    pass
        except FileNotFoundError as exc:
            raise _adb_not_found_error() from exc
        except RuntimeError:
            raise
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
    print()
    pid = getattr(process, "pid", "unknown")
    raise RuntimeError(
        "Emulator did not finish booting within 3 minutes.\n"
        f"Emulator PID: {pid}. Stderr log: {stderr_path}\n"
        "Please start the emulator manually and re-run."
    )
