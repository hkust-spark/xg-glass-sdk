from __future__ import annotations

import os
import platform
import subprocess
import time
from pathlib import Path

from .adb import _adb_has_device, _adb_line_is_ready_device, _adb_not_found_error, _find_adb_cmd
from .prereqs import _ensure_java_runtime, _find_android_sdk, _run_quiet


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

    system = platform.system().lower()

    # Locate sdkmanager (needed for installing emulator/system-images/AVD creation).
    sdkmanager_name = "sdkmanager.bat" if system == "windows" else "sdkmanager"
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
                [str(sdkmanager), f"--sdk_root={sdk}", "emulator", "system-images;android-34;google_apis;x86_64"],
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
        sys_img = Path(sdk) / "system-images" / "android-34" / "google_apis" / "x86_64"
        if not sys_img.is_dir() and sdkmanager is not None:
            print("  Installing system image...")
            try:
                _run_quiet(
                    [str(sdkmanager), f"--sdk_root={sdk}", "system-images;android-34;google_apis;x86_64"],
                    input_text="y\n" * 20,
                    env=env,
                    check=True,
                    timeout=600,
                    verbose_env="XG_VERBOSE_SDKMANAGER",
                )
            except subprocess.CalledProcessError:
                pass

        print(f"  Creating AVD '{avd_name}'...")
        avdmanager_name = "avdmanager.bat" if system == "windows" else "avdmanager"
        avdmanager = Path(sdk) / "cmdline-tools" / "latest" / "bin" / avdmanager_name
        if not avdmanager.exists():
            avdmanager = Path(sdk) / "tools" / "bin" / avdmanager_name
        if avdmanager.exists():
            try:
                subprocess.run(
                    [
                        str(avdmanager), "create", "avd",
                        "-n", avd_name,
                        "-k", "system-images;android-34;google_apis;x86_64",
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

    # Launch emulator in background.
    print(f"  Launching emulator (AVD: {avd_name})...")
    env = {**os.environ, "ANDROID_HOME": sdk, "ANDROID_SDK_ROOT": sdk}
    subprocess.Popen(
        [emu, "-avd", avd_name, "-no-snapshot-load"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env=env,
    )

    # Wait for device to come online and finish booting.
    adb = _find_adb_cmd()
    print("  Waiting for emulator to boot", end="", flush=True)
    deadline = time.monotonic() + 180  # 3 minute timeout
    while time.monotonic() < deadline:
        time.sleep(3)
        print(".", end="", flush=True)
        try:
            out = subprocess.check_output(
                [adb, "devices"], text=True, stderr=subprocess.DEVNULL,
            )
            if any(
                _adb_line_is_ready_device(line)
                and line.strip().split()[0].startswith("emulator-")
                for line in out.splitlines()
            ):
                # Check boot_completed
                try:
                    boot = subprocess.check_output(
                        [adb, "shell", "getprop", "sys.boot_completed"],
                        text=True, stderr=subprocess.DEVNULL, timeout=5,
                    ).strip()
                    if boot == "1":
                        print(" Ready!")
                        return
                except FileNotFoundError as exc:
                    raise _adb_not_found_error() from exc
                except Exception:
                    pass
        except FileNotFoundError as exc:
            raise _adb_not_found_error() from exc
        except RuntimeError:
            raise
        except Exception:
            pass
    print()
    raise RuntimeError(
        "Emulator did not finish booting within 3 minutes.\n"
        "Please start the emulator manually and re-run."
    )
