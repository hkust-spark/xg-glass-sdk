from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SDK = REPO_ROOT
DEFAULT_TEMPLATE = REPO_ROOT / "templates" / "kotlin-app"
DEFAULT_CONFIG_FILE = "xg-glass.yaml"

# Managed Flutter SDK location
_XG_GLASS_HOME = Path.home() / ".xg-glass"
_MANAGED_FLUTTER_DIR = _XG_GLASS_HOME / "flutter"
_MANAGED_JDK_DIR = _XG_GLASS_HOME / "jdk"

# Highest JDK major version known to work with the project's AGP / Gradle toolchain.
# JDK 25 (LTS, Sep 2025) is too new for AGP 8.13.1 / Gradle 8.13 and causes a bare
# "25.0.2" build error.  Bump this constant when upgrading AGP to a version that supports it.
_MAX_AGP_JDK_MAJOR = 21


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="xg-glass",
        description="xg-glass: build/install/run universal_glasses-based Android host apps (MVP).",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_init = sub.add_parser("init", help="Create a new dev project from template (includeBuild-based).")
    p_init.add_argument("dir", help="Target directory for the new project.")
    p_init.add_argument(
        "--template",
        default=str(DEFAULT_TEMPLATE),
        help="Template project directory (default: ./templates/kotlin-app).",
    )
    p_init.add_argument(
        "--sdk",
        default=str(DEFAULT_SDK),
        help="Path to the SDK repo root (default: this repo).",
    )
    p_init.add_argument(
        "--entry-class",
        default="com.example.xgglassapp.logic.ExampleAppEntry",
        help="Fully-qualified UniversalAppEntry class name (default: com.example.xgglassapp.logic.ExampleAppEntry).",
    )
    p_init.add_argument(
        "--sim",
        action="store_true",
        help="Initialize the project in simulator mode (enables x86_64 + simulator backend).",
    )

    p_build = sub.add_parser("build", help="Build the phone-side APK.")
    _add_common_project_args(p_build)
    p_build.add_argument("--config", default=DEFAULT_CONFIG_FILE, help="Config file name/path (default: xg-glass.yaml).")
    p_build.add_argument("--entry-class", help="Override entry class (optional).")
    p_build.add_argument("--sdk", help="Override sdkPath (optional).")
    p_build.add_argument("--rayneo-aar-dir", help="Override RayNeo mercuryAarDir (optional).")

    p_install = sub.add_parser("install", help="Install the phone-side APK via adb.")
    _add_common_project_args(p_install)
    p_install.add_argument("--config", default=DEFAULT_CONFIG_FILE, help="Config file name/path (default: xg-glass.yaml).")
    p_install.add_argument("--serial", help="adb device serial (optional).")
    p_install.add_argument("--apk", help="Explicit APK path (optional).")

    p_run = sub.add_parser("run", help="Launch the phone-side app via adb.")
    _add_common_project_args(p_run)
    p_run.add_argument("--config", default=DEFAULT_CONFIG_FILE, help="Config file name/path (default: xg-glass.yaml).")
    p_run.add_argument("--serial", help="adb device serial (optional).")
    p_run.add_argument("--package", help="Override applicationId/package (optional).")
    p_run.add_argument("kt_file", nargs="?", help="Quick mode: a Kotlin entry file (.kt).")
    p_run.add_argument("--save", help="Quick mode: save the generated project to this directory.")
    p_run.add_argument("--keep-tmp", action="store_true", help="Quick mode: keep the temporary project directory.")
    p_run.add_argument("--entry-class", help="Quick mode: override inferred entry class (optional).")
    p_run.add_argument("--sdk", help="Quick mode: override sdkPath (optional).")
    p_run.add_argument("--sim", action="store_true", help="Build for Android Emulator (x86_64) and enable simulator backend.")

    args = parser.parse_args(argv)

    try:
        if args.cmd == "init":
            return cmd_init(args)
        if args.cmd == "build":
            return cmd_build(args)
        if args.cmd == "install":
            return cmd_install(args)
        if args.cmd == "run":
            return cmd_run(args)
        raise RuntimeError(f"Unknown command: {args.cmd}")
    except subprocess.CalledProcessError as e:
        print(e, file=sys.stderr)
        return e.returncode or 1
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1


def _add_common_project_args(p: argparse.ArgumentParser) -> None:
    p.add_argument(
        "--project",
        default=os.getcwd(),
        help="Android project root (default: current directory).",
    )
    p.add_argument("--variant", default="debug", help="Build variant (default: debug).")
    p.add_argument("--module", default="app", help="Android application module name (default: app).")


def cmd_init(args: argparse.Namespace) -> int:
    dst = Path(args.dir).expanduser().resolve()
    template = Path(args.template).expanduser().resolve()
    sdk = Path(args.sdk).expanduser().resolve()
    entry_class = str(args.entry_class).strip()
    if not entry_class:
        raise ValueError("--entry-class must be non-empty")

    if not template.is_dir():
        raise FileNotFoundError(f"Template not found: {template}")
    if not sdk.is_dir():
        raise FileNotFoundError(f"SDK not found: {sdk}")

    if dst.exists() and any(dst.iterdir()):
        raise RuntimeError(f"Target dir must be empty: {dst}")
    dst.mkdir(parents=True, exist_ok=True)

    # Copy a curated subset of the template (avoid build/ caches).
    _copy_tree(template / "app", dst / "app")
    _copy_tree(template / "ug_app_logic", dst / "ug_app_logic")
    _copy_tree(template / "gradle", dst / "gradle")

    for f in [
        ".gitignore",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "README.md",
        DEFAULT_CONFIG_FILE,
    ]:
        src = template / f
        if src.exists():
            shutil.copy2(src, dst / f)

    _ensure_executable(dst / "gradlew")

    # Patch includeBuild paths in settings.gradle.kts
    rel_sdk = Path(os.path.relpath(sdk, dst)).as_posix()
    settings_file = dst / "settings.gradle.kts"
    if settings_file.exists():
        s = settings_file.read_text(encoding="utf-8")
        # Stage2 template uses placeholders.
        s = s.replace("__XG_SDK_PATH__", rel_sdk)
        settings_file.write_text(s, encoding="utf-8")

    # Patch ugRayneo config in app/build.gradle.kts (entry class + mercury dir)
    app_gradle = dst / "app" / "build.gradle.kts"
    if app_gradle.exists():
        g = app_gradle.read_text(encoding="utf-8")
        g = g.replace("__XG_ENTRY_CLASS__", entry_class)
        g = g.replace("__XG_SDK_PATH__", rel_sdk)
        app_gradle.write_text(g, encoding="utf-8")

    manifest = dst / "app" / "src" / "main" / "AndroidManifest.xml"
    if manifest.exists():
        m = manifest.read_text(encoding="utf-8")
        m = m.replace("__XG_ENTRY_CLASS__", entry_class)
        manifest.write_text(m, encoding="utf-8")

    # Patch xg-glass.yaml (if template provides it).
    cfg_file = dst / DEFAULT_CONFIG_FILE
    if cfg_file.exists():
        c = cfg_file.read_text(encoding="utf-8")
        c = c.replace("__XG_SDK_PATH__", rel_sdk)
        c = c.replace("__XG_ENTRY_CLASS__", entry_class)
        cfg_file.write_text(c, encoding="utf-8")
    else:
        cfg_file.write_text(
            "\n".join(
                [
                    "# xg-glass config (generated by xg-glass init)",
                    f'sdkPath: "{rel_sdk}"',
                    f'entryClass: "{entry_class}"',
                    f'rayneoMercuryAarDir: "{rel_sdk}/third_party/rayneo/aar"',
                    'variant: "debug"',
                    'module: "app"',
                    'applicationId: "com.example.xgglassapp"',
                    "",
                ]
            ),
            encoding="utf-8",
        )

    # One-click bootstrap: ensure Java/Android SDK/Flutter are usable on a fresh machine,
    # then persist env vars for future shells (macOS: ~/.zshrc).
    env = {**os.environ}
    _ensure_java_runtime(env)
    android_sdk = _resolve_android_sdk()
    if android_sdk:
        env.setdefault("ANDROID_HOME", android_sdk)
        env.setdefault("ANDROID_SDK_ROOT", android_sdk)
    # Generate local.properties with sdk.dir + Rokid config.
    _write_local_properties(dst, android_sdk)
    # Ensure Flutter (downloads managed Flutter + runs pub get if needed).
    cfg = _load_config(dst, DEFAULT_CONFIG_FILE)
    _ensure_flutter_module_ready(dst, cfg)
    # Persist for future shells
    flutter = _find_flutter_cmd()
    _persist_env(java_home=env.get("JAVA_HOME"), android_sdk=android_sdk, flutter_bin=flutter)

    if bool(getattr(args, "sim", False)):
        _apply_simulator_build_settings(dst, enabled=True)

    print(f"Created project: {dst}")
    print("Next:")
    print(f"  cd {dst}")
    print("  xg-glass build")
    print("  # If you prefer Gradle directly in a new terminal:")
    if platform.system() == "Darwin":
        print("  #   source ~/.zshrc")
    else:
        print("  #   restart your terminal")
    print("  #   ./gradlew :app:assembleDebug")
    print("  xg-glass install")
    print("  xg-glass run")
    return 0


def cmd_build(args: argparse.Namespace) -> int:
    project = Path(args.project).expanduser().resolve()
    cfg = _load_config(project, args.config)
    cfg = _apply_overrides(
        cfg,
        sdk=args.sdk,
        entry_class=args.entry_class,
        rayneo_aar_dir=args.rayneo_aar_dir,
        variant=args.variant,
        module=args.module,
    )
    _apply_cfg_to_project(project, cfg)

    # Ensure all development dependencies are available.
    # This allows standalone `xg-glass build` (without prior `init`) on a fresh machine.
    env = {**os.environ}
    _ensure_java_runtime(env)
    android_sdk = _resolve_android_sdk()
    if android_sdk:
        env.setdefault("ANDROID_HOME", android_sdk)
        env.setdefault("ANDROID_SDK_ROOT", android_sdk)
        # Ensure project local.properties has sdk.dir (may be missing if user
        # cloned the project instead of using `xg-glass init`).
        _ensure_project_sdk_dir(project, android_sdk)
    _ensure_flutter_module_ready(project, cfg)

    # Persist env vars so future shells (e.g. manual ./gradlew) also work.
    # Idempotent – no-op if already written.
    flutter = _find_flutter_cmd()
    _persist_env(java_home=env.get("JAVA_HOME"), android_sdk=android_sdk, flutter_bin=flutter)

    # Gradle's Flutter plugin invokes engine binaries (impellerc, gen_snapshot).
    # On macOS these may carry quarantine; strip before Gradle runs.
    _ensure_flutter_executables()

    variant = cfg.variant
    module = cfg.module
    gradlew = _gradlew_path(project)
    task = f":{module}:assemble{_cap(variant)}"
    _run([str(gradlew), task], cwd=project, env=env)
    apk_dir = project / module / "build" / "outputs" / "apk" / variant
    print(f"Build OK. APK outputs under: {apk_dir}")
    return 0


def cmd_install(args: argparse.Namespace) -> int:
    project = Path(args.project).expanduser().resolve()
    cfg = _load_config(project, args.config)
    cfg = _apply_overrides(cfg, variant=args.variant, module=args.module)
    module = cfg.module
    variant = cfg.variant

    apk = Path(args.apk).expanduser().resolve() if args.apk else _pick_apk(project, module, variant, args.serial)
    if not apk.exists():
        raise FileNotFoundError(f"APK not found: {apk}")

    cmd = [_find_adb_cmd()]
    if args.serial:
        cmd += ["-s", args.serial]
    cmd += ["install", "-r", str(apk)]
    _run(cmd, cwd=project)
    print(f"Installed: {apk.name}")
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    # Stage 3: quick mode - run a single .kt file by generating a project.
    if getattr(args, "kt_file", None) and str(args.kt_file).lower().endswith(".kt"):
        kt = Path(args.kt_file).expanduser().resolve()
        if not kt.exists():
            raise FileNotFoundError(f"Kotlin file not found: {kt}")

        sdk = Path(args.sdk).expanduser().resolve() if getattr(args, "sdk", None) else DEFAULT_SDK
        if not sdk.exists():
            raise FileNotFoundError("SDK path not found. Provide --sdk pointing to your universal_glasses checkout.")

        entry_class = getattr(args, "entry_class", None) or _infer_entry_class_from_kt(kt)
        if not entry_class:
            raise RuntimeError(
                "Failed to infer entry class from .kt file. Please ensure the file has a `package ...` line and a top-level `class`, "
                "or pass --entry-class explicitly."
            )

        if getattr(args, "save", None):
            project_dir = Path(args.save).expanduser().resolve()
            if project_dir.exists() and any(project_dir.iterdir()):
                raise RuntimeError(f"--save directory must be empty: {project_dir}")
            project_dir.parent.mkdir(parents=True, exist_ok=True)
            project_dir.mkdir(parents=True, exist_ok=True)
            keep = True
        else:
            base = Path.cwd() / ".xg_glass_tmp"
            base.mkdir(parents=True, exist_ok=True)
            project_dir = Path(tempfile.mkdtemp(prefix="run-", dir=str(base)))
            keep = bool(getattr(args, "keep_tmp", False))

        _init_project(dst=project_dir, template=DEFAULT_TEMPLATE, sdk=sdk, entry_class=entry_class)
        _copy_kt_into_project(project_dir, kt)

        if bool(getattr(args, "sim", False)):
            _apply_simulator_build_settings(project_dir, enabled=True)

        # One-shot: build + install + run.
        cmd_build(
            argparse.Namespace(
                project=str(project_dir),
                variant=args.variant,
                module=args.module,
                config=DEFAULT_CONFIG_FILE,
                entry_class=None,
                sdk=None,
                rayneo_aar_dir=None,
            )
        )
        # When --sim, ensure an Android Emulator is running before installing.
        if bool(getattr(args, "sim", False)):
            _ensure_emulator_running(serial=args.serial)
        cmd_install(
            argparse.Namespace(
                project=str(project_dir),
                variant=args.variant,
                module=args.module,
                config=DEFAULT_CONFIG_FILE,
                serial=args.serial,
                apk=None,
            )
        )
        _run_project(
            argparse.Namespace(
                project=str(project_dir),
                variant=args.variant,
                module=args.module,
                config=DEFAULT_CONFIG_FILE,
                serial=args.serial,
                package=args.package,
                kt_file=None,
                save=None,
                keep_tmp=False,
                entry_class=None,
                sdk=None,
            )
        )

        if not keep:
            shutil.rmtree(project_dir, ignore_errors=True)
        else:
            print(f"Quick project kept at: {project_dir}")
        return 0

    return _run_project(args)


def _run_project(args: argparse.Namespace) -> int:
    project = Path(args.project).expanduser().resolve()
    cfg = _load_config(project, args.config)
    cfg = _apply_overrides(cfg, module=args.module, variant=args.variant)
    module = cfg.module
    package_name = args.package or cfg.application_id or _read_application_id(project, module) or "com.example.xgglassapp"

    cmd = [_find_adb_cmd()]
    if getattr(args, "serial", None):
        cmd += ["-s", args.serial]
    # Use monkey to avoid hardcoding activity component.
    cmd += ["shell", "monkey", "-p", package_name, "-c", "android.intent.category.LAUNCHER", "1"]
    _run(cmd, cwd=project)
    print(f"Launched: {package_name}")
    return 0


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


def _adb_getprop(prop: str, serial: str | None) -> str | None:
    cmd = [_find_adb_cmd()]
    if serial:
        cmd += ["-s", serial]
    cmd += ["shell", "getprop", prop]
    try:
        out = subprocess.check_output(cmd, stderr=subprocess.DEVNULL, text=True).strip()
        return out or None
    except Exception:
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
            if line.strip() and "device" in line:
                return True
    except Exception:
        pass
    return False


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

    # Ensure emulator + system image are installed.
    system = platform.system().lower()
    sdkmanager_name = "sdkmanager.bat" if system == "windows" else "sdkmanager"
    sdkmanager = Path(sdk) / "cmdline-tools" / "latest" / "bin" / sdkmanager_name
    if not sdkmanager.exists():
        # Try alternate location
        sdkmanager = Path(sdk) / "tools" / "bin" / sdkmanager_name
    if not sdkmanager.exists():
        raise RuntimeError(
            "sdkmanager not found. Cannot install emulator packages.\n"
            "Please start an emulator manually."
        )

    emu = _find_emulator_cmd()
    if not emu:
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
        # Ensure system image is installed
        env = {**os.environ, "ANDROID_HOME": sdk, "ANDROID_SDK_ROOT": sdk}
        _ensure_java_runtime(env)
        sys_img = Path(sdk) / "system-images" / "android-34" / "google_apis" / "x86_64"
        if not sys_img.is_dir():
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
                    input="no\n",  # don't customize hardware profile
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
            if any("emulator" in line and "device" in line for line in out.splitlines()):
                # Check boot_completed
                try:
                    boot = subprocess.check_output(
                        [adb, "shell", "getprop", "sys.boot_completed"],
                        text=True, stderr=subprocess.DEVNULL, timeout=5,
                    ).strip()
                    if boot == "1":
                        print(" Ready!")
                        return
                except Exception:
                    pass
        except Exception:
            pass
    print()
    raise RuntimeError(
        "Emulator did not finish booting within 3 minutes.\n"
        "Please start the emulator manually and re-run."
    )


def _apply_simulator_build_settings(project: Path, *, enabled: bool) -> None:
    """
    Patch a generated (or existing) template-based project for simulator mode.

    - Adds x86_64 ABI to splits (so Android Emulator can install the APK)
    - Flips BuildConfig.XG_SIMULATOR, used by the template host app to select the simulator backend
    """

    app_gradle = project / "app" / "build.gradle.kts"
    if not app_gradle.exists():
        return

    s = app_gradle.read_text(encoding="utf-8")

    # 1) Ensure x86_64 ABI is included for emulator installs.
    if enabled and "\"x86_64\"" not in s:
        # Prefer the exact template pattern first.
        s2 = s.replace(
            'include("arm64-v8a", "armeabi-v7a")',
            'include("arm64-v8a", "armeabi-v7a", "x86_64")',
        )
        if s2 == s:
            # Fallback: append to the first include(...) inside splits/abi block.
            def _repl(m: re.Match[str]) -> str:
                head = m.group(1)
                inner = m.group(2).strip()
                tail = m.group(3)
                if "x86_64" in inner:
                    return m.group(0)
                if not inner:
                    return f'{head}"x86_64"{tail}'
                return f'{head}{inner}, "x86_64"{tail}'

            s2 = re.sub(
                r"(splits\s*\{[\s\S]*?abi\s*\{[\s\S]*?include\()([^)]*)(\))",
                _repl,
                s,
                count=1,
            )
        s = s2

    # 2) Flip BuildConfig flag used by the template host UI.
    desired = "true" if enabled else "false"
    s2 = re.sub(
        r'buildConfigField\("boolean",\s*"XG_SIMULATOR",\s*"(true|false)"\)',
        f'buildConfigField("boolean", "XG_SIMULATOR", "{desired}")',
        s,
    )
    if s2 == s:
        # Insert into defaultConfig if missing.
        s2 = re.sub(
            r"(defaultConfig\s*\{\s*)",
            rf'\1\n        buildConfigField("boolean", "XG_SIMULATOR", "{desired}")\n',
            s,
            count=1,
        )
    s = s2

    app_gradle.write_text(s, encoding="utf-8")


@dataclass(frozen=True)
class XgConfig:
    sdk_path: str | None = None
    entry_class: str | None = None
    rayneo_mercury_aar_dir: str | None = None
    variant: str = "debug"
    module: str = "app"
    application_id: str | None = None


def _load_config(project: Path, config_arg: str) -> XgConfig:
    cfg_path = (project / config_arg) if not os.path.isabs(config_arg) else Path(config_arg)
    if not cfg_path.exists():
        return XgConfig()
    data = _parse_simple_yaml(cfg_path.read_text(encoding="utf-8"))
    return XgConfig(
        sdk_path=data.get("sdkPath"),
        entry_class=data.get("entryClass"),
        rayneo_mercury_aar_dir=data.get("rayneoMercuryAarDir"),
        variant=(data.get("variant") or "debug"),
        module=(data.get("module") or "app"),
        application_id=data.get("applicationId"),
    )


def _apply_overrides(
    cfg: XgConfig,
    *,
    sdk: str | None = None,
    entry_class: str | None = None,
    rayneo_aar_dir: str | None = None,
    variant: str | None = None,
    module: str | None = None,
) -> XgConfig:
    v = (variant or cfg.variant).strip() if (variant or cfg.variant) else "debug"
    m = (module or cfg.module).strip() if (module or cfg.module) else "app"
    return XgConfig(
        sdk_path=(sdk or cfg.sdk_path),
        entry_class=(entry_class or cfg.entry_class),
        rayneo_mercury_aar_dir=(rayneo_aar_dir or cfg.rayneo_mercury_aar_dir),
        variant=v,
        module=m,
        application_id=cfg.application_id,
    )


def _apply_cfg_to_project(project: Path, cfg: XgConfig) -> None:
    # Apply entry class to phone host manifest (MainActivity reflection).
    if cfg.entry_class:
        manifest = project / "app" / "src" / "main" / "AndroidManifest.xml"
        if manifest.exists():
            s = manifest.read_text(encoding="utf-8")
            s = re.sub(
                r'(android:name="com\.universalglasses\.app_entry_class"\s+android:value=")([^"]*)(")',
                rf'\g<1>{cfg.entry_class}\g<3>',
                s,
            )
            manifest.write_text(s, encoding="utf-8")

    # Apply RayNeo plugin extension values in app/build.gradle.kts.
    app_gradle = project / "app" / "build.gradle.kts"
    if app_gradle.exists():
        s = app_gradle.read_text(encoding="utf-8")
        if cfg.entry_class:
            s = re.sub(r'appEntryClass\.set\(".*?"\)', f'appEntryClass.set("{cfg.entry_class}")', s)
        if cfg.rayneo_mercury_aar_dir:
            # Normalize to a File(rootDir, "...").absolutePath style.
            s = re.sub(
                r'mercuryAarDir\.set\(File\(rootDir,\s*".*?"\)\.absolutePath\)',
                f'mercuryAarDir.set(File(rootDir, "{cfg.rayneo_mercury_aar_dir}").absolutePath)',
                s,
            )
        elif cfg.sdk_path:
            s = re.sub(
                r'mercuryAarDir\.set\(File\(rootDir,\s*".*?"\)\.absolutePath\)',
                f'mercuryAarDir.set(File(rootDir, "{cfg.sdk_path}/third_party/rayneo/aar").absolutePath)',
                s,
            )
        app_gradle.write_text(s, encoding="utf-8")

    # Apply sdkPath includeBuild in settings.gradle.kts (optional; mainly for init upgrades).
    if cfg.sdk_path:
        settings_file = project / "settings.gradle.kts"
        if settings_file.exists():
            s = settings_file.read_text(encoding="utf-8")
            # 1) pluginManagement includeBuild(.../build-logic)
            s = re.sub(
                r'includeBuild\(".*?/build-logic"\)',
                f'includeBuild("{cfg.sdk_path}/build-logic")',
                s,
            )
            # 2) composite build includeBuild(...) anchored by the comment block
            s = re.sub(
                r'(^\\s*//\\s*Use\\s+universal_glasses\\s+as\\s+a\\s+composite\\s+build.*\\n)\\s*includeBuild\\(".*?"\\)',
                rf'\\1includeBuild("{cfg.sdk_path}")',
                s,
                flags=re.MULTILINE,
            )
            settings_file.write_text(s, encoding="utf-8")


def _ensure_flutter_module_ready(project: Path, cfg: XgConfig) -> None:
    """
    Ensure the embedded Flutter module has a valid `.dart_tool/package_config.json`.

    The Gradle Flutter plugin may fail with:
      "<module>/.dart_tool/package_config.json does not exist"
    unless `flutter pub get` has been run in the module directory.
    """
    sdk_path_raw = cfg.sdk_path
    if not sdk_path_raw:
        return
    sdk = Path(sdk_path_raw)
    if not sdk.is_absolute():
        sdk = (project / sdk).resolve()

    # Ensure the SDK root has local.properties with sdk.dir so that the
    # Flutter module Gradle plugin can locate the Android SDK.
    _ensure_sdk_local_properties(sdk)

    fm = sdk / "third_party" / "frame" / "frame_module"
    pubspec = fm / "pubspec.yaml"
    if not pubspec.exists():
        return

    pkg_config = fm / ".dart_tool" / "package_config.json"

    # If an old cache references a previous directory layout, wipe it.
    if pkg_config.exists():
        try:
            s = pkg_config.read_text(encoding="utf-8", errors="ignore")
            if "../../Frame/frame_ble" in s or "../../Frame/frame_msg" in s:
                _wipe_flutter_caches(fm)
        except Exception:
            # If unreadable, just proceed.
            pass

    if pkg_config.exists():
        return

    # Missing package_config: run flutter pub get.
    flutter = _find_flutter_cmd()
    if not flutter:
        if os.environ.get("XG_NO_FLUTTER_DOWNLOAD", "").strip() not in ("", "0"):
            raise RuntimeError(
                "Flutter module is present but not initialized (missing .dart_tool/package_config.json), "
                "and `flutter` was not found on PATH.\n"
                f"Please install Flutter or run `flutter pub get` manually in: {fm}"
            )
        flutter = _auto_download_flutter()

    _run([flutter, "pub", "get"], cwd=fm)

    # flutter pub get downloads engine artifacts (impellerc, gen_snapshot, …)
    # into bin/cache/artifacts/.  On macOS these new files inherit the
    # com.apple.quarantine xattr and must be cleaned before Gradle can invoke them.
    if platform.system() != "Windows" and str(flutter).startswith(str(_MANAGED_FLUTTER_DIR)):
        _ensure_flutter_executables()

    if not pkg_config.exists():
        raise RuntimeError(
            "flutter pub get did not produce .dart_tool/package_config.json.\n"
            f"Please run `flutter pub get` manually in: {fm}"
        )


def _wipe_flutter_caches(fm: Path) -> None:
    for rel in [
        ".dart_tool",
        ".android/Flutter/.dart_tool",
        ".android/.dart_tool",
    ]:
        p = fm / rel
        if p.exists():
            shutil.rmtree(p, ignore_errors=True)


def _managed_flutter_bin() -> Path:
    """Return the expected path to the managed Flutter binary."""
    name = "flutter.bat" if platform.system() == "Windows" else "flutter"
    return _MANAGED_FLUTTER_DIR / "flutter" / "bin" / name


def _ensure_flutter_executables() -> None:
    """
    Ensure the managed Flutter SDK is actually executable on macOS/Linux.

    Two separate issues are fixed:

    1. **Missing +x bits** – Python's ``zipfile.extractall()`` may drop POSIX
       execute bits, so we explicitly ``chmod +x`` key entrypoints.

    2. **macOS quarantine** – files downloaded from the internet (both the initial
       zip *and* artifacts that ``flutter pub get`` fetches later) carry the
       ``com.apple.quarantine`` extended attribute.  macOS Gatekeeper blocks
       execution of unsigned binaries that have this xattr, even when ``+x`` is
       set.  We strip quarantine from the **entire** managed Flutter tree so that
       ``dart``, ``flutter``, and engine binaries like ``impellerc`` can all run.

    This function is safe to call repeatedly (idempotent).
    """
    if platform.system() == "Windows":
        return
    root = _MANAGED_FLUTTER_DIR / "flutter"
    if not root.is_dir():
        return

    # ── macOS: remove quarantine xattr ──────────────────────────────────
    # We target the entire managed Flutter root so that engine artifacts
    # downloaded by `flutter pub get` (e.g. bin/cache/artifacts/engine/
    # darwin-x64/impellerc) are also cleaned.  `xattr -cr` only touches
    # metadata, so it is fast even for large trees.
    if platform.system() == "Darwin":
        try:
            subprocess.run(
                ["xattr", "-cr", str(root)],
                capture_output=True,
                timeout=300,
            )
        except Exception:
            pass

    # ── Ensure +x on core entrypoints ───────────────────────────────────
    for p in [
        root / "bin" / "flutter",
        root / "bin" / "dart",
        root / "bin" / "internal" / "update_engine_version.sh",
        root / "bin" / "internal" / "shared.sh",
    ]:
        _ensure_executable(p)

    # All .sh scripts under bin/internal/
    internal = root / "bin" / "internal"
    if internal.is_dir():
        for p in internal.glob("*.sh"):
            _ensure_executable(p)

    # Dart SDK binaries in cache (pre-packaged or downloaded on first run).
    dart_bin = root / "bin" / "cache" / "dart-sdk" / "bin"
    if dart_bin.is_dir():
        for p in dart_bin.iterdir():
            if p.is_file():
                _ensure_executable(p)

    # Engine artifacts downloaded by flutter (impellerc, gen_snapshot, etc.).
    artifacts = root / "bin" / "cache" / "artifacts" / "engine"
    if artifacts.is_dir():
        for p in artifacts.rglob("*"):
            if p.is_file() and not p.suffix:
                _ensure_executable(p)


def _http_user_agent() -> str:
    # Some CDNs/WAFs return 403 for default Python urllib UA; use a browser-like UA.
    return "Mozilla/5.0 (xg-glass-cli) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"


def _download_file(url: str, dest: Path) -> None:
    """
    Download a URL to a file with a custom User-Agent and progress output.
    """
    req = urllib.request.Request(url, headers={"User-Agent": _http_user_agent(), "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        total = resp.headers.get("Content-Length")
        try:
            total_size = int(total) if total else 0
        except Exception:
            total_size = 0
        dest.parent.mkdir(parents=True, exist_ok=True)
        downloaded = 0
        with open(dest, "wb") as f:
            while True:
                chunk = resp.read(256 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if total_size > 0:
                    pct = min(100, downloaded * 100 // total_size)
                    mb_done = downloaded / (1024 * 1024)
                    mb_total = total_size / (1024 * 1024)
                    sys.stdout.write(f"\r  Progress: {pct}% ({mb_done:.1f} / {mb_total:.1f} MB)")
                    sys.stdout.flush()


def _download_json(url: str) -> object:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": _http_user_agent(),
            "Accept": "application/json,text/plain,*/*",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def _extract_archive(archive: Path, dest: Path) -> None:
    """Extract a zip / tar.gz / tar.xz archive into *dest*."""
    name = archive.name.lower()
    if name.endswith(".zip"):
        with zipfile.ZipFile(str(archive), "r") as zf:
            zf.extractall(str(dest))
    elif name.endswith((".tar.gz", ".tgz", ".tar.xz", ".tar")):
        with tarfile.open(str(archive), "r:*") as tf:
            if sys.version_info >= (3, 12):
                tf.extractall(str(dest), filter="fully_trusted")
            else:
                tf.extractall(str(dest))
    else:
        raise RuntimeError(f"Unknown archive format: {archive.name}")


def _is_truthy_env(name: str) -> bool:
    return os.environ.get(name, "").strip() not in ("", "0", "false", "False", "no", "No")


def _persist_env_enabled() -> bool:
    """
    Whether `xg-glass init` should persist env vars for future shells.
    Opt out via: XG_NO_PERSIST_ENV=1
    """
    return not _is_truthy_env("XG_NO_PERSIST_ENV")


def _homeify(path: str) -> str:
    """
    Replace the current user's home directory with $HOME for portability in shell profiles.
    """
    try:
        home = str(Path.home())
        return path.replace(home, "$HOME")
    except Exception:
        return path


def _upsert_profile_block(profile: Path, *, block_id: str, body: str) -> bool:
    """
    Idempotently upsert a marked block into a profile file. Returns True if modified.
    """
    start = f"# >>> xg-glass {block_id} >>>"
    end = f"# <<< xg-glass {block_id} <<<"
    new_block = "\n".join([start, body.rstrip(), end, ""])
    existing = ""
    if profile.exists():
        try:
            existing = profile.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            existing = ""
    if start in existing and end in existing:
        pre, rest = existing.split(start, 1)
        _, post = rest.split(end, 1)
        updated = pre.rstrip("\n") + "\n\n" + new_block + post.lstrip("\n")
    else:
        sep = "\n" if existing.endswith("\n") or existing == "" else "\n\n"
        updated = existing + sep + new_block
    if updated == existing:
        return False
    profile.parent.mkdir(parents=True, exist_ok=True)
    profile.write_text(updated, encoding="utf-8")
    return True


def _persist_env_macos_zshrc(*, java_home: str | None, android_sdk: str | None, flutter_bin: str | None) -> None:
    """
    Persist env vars into ~/.zshrc (macOS target per product requirement).
    """
    if not _persist_env_enabled():
        return
    zshrc = Path.home() / ".zshrc"
    force = "${XG_FORCE_ENV:-}"  # evaluated in shell

    managed_java = _homeify(java_home) if java_home else ""
    managed_android = _homeify(android_sdk) if android_sdk else ""
    flutter_dir = _homeify(str(Path(flutter_bin).parent)) if flutter_bin else ""

    lines: list[str] = [
        "# xg-glass: one-click environment bootstrap (Java/Android SDK/Flutter)",
        "# - This block is managed by `xg-glass init`.",
        "# - It does NOT override valid user settings by default.",
        "# - Set XG_FORCE_ENV=1 to force using xg-glass managed paths.",
        "",
        "xg_glass_prepend_path() {",
        '  case ":$PATH:" in',
        '    *":$1:"*) ;;',
        '    *) PATH="$1:$PATH" ;;',
        "  esac",
        "}",
        "",
    ]

    if managed_java:
        lines += [
            "# Java",
            f'if [[ "{force}" == "1" ]]; then',
            f'  export JAVA_HOME="{managed_java}"',
            'elif [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then',
            f'  export JAVA_HOME="{managed_java}"',
            "fi",
            'if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then',
            '  xg_glass_prepend_path "${JAVA_HOME}/bin"',
            "fi",
            "",
        ]

    if managed_android:
        lines += [
            "# Android SDK",
            f'if [[ "{force}" == "1" ]]; then',
            f'  export ANDROID_SDK_ROOT="{managed_android}"',
            f'  export ANDROID_HOME="{managed_android}"',
            'elif [[ -z "${ANDROID_SDK_ROOT:-}" || ! -d "${ANDROID_SDK_ROOT}/platform-tools" ]]; then',
            f'  export ANDROID_SDK_ROOT="{managed_android}"',
            f'  export ANDROID_HOME="{managed_android}"',
            "fi",
            'if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}/platform-tools" ]]; then',
            '  xg_glass_prepend_path "${ANDROID_SDK_ROOT}/platform-tools"',
            "fi",
            "",
        ]

    if flutter_dir:
        lines += [
            "# Flutter",
            f'if [[ "{force}" == "1" || -z "$(command -v flutter 2>/dev/null)" ]]; then',
            f'  xg_glass_prepend_path "{flutter_dir}"',
            "fi",
            "",
        ]

    modified = _upsert_profile_block(zshrc, block_id="env", body="\n".join(lines))
    if modified:
        print(f"  Updated shell profile: {zshrc}")
        print(f"  Restart your terminal (or run `source {zshrc}`) to apply.")


def _persist_env_windows(*, java_home: str | None, android_sdk: str | None, flutter_bin: str | None) -> None:
    """
    Persist user env vars on Windows (takes effect in new terminals).
    """
    if not _persist_env_enabled():
        return
    if platform.system() != "Windows":
        return
    java_home = java_home or ""
    android_sdk = android_sdk or ""
    flutter_dir = str(Path(flutter_bin).parent) if flutter_bin else ""

    ps = r"""
$ErrorActionPreference = "Stop"
$force = ($env:XG_FORCE_ENV -eq "1")
function Set-UserEnvIfMissing([string]$name, [string]$value) {
  if ([string]::IsNullOrEmpty($value)) { return }
  $cur = [Environment]::GetEnvironmentVariable($name, "User")
  if ($force -or [string]::IsNullOrEmpty($cur) -or (-not (Test-Path $cur))) {
    [Environment]::SetEnvironmentVariable($name, $value, "User")
  }
}
function Prepend-UserPathIfMissing([string]$dir) {
  if ([string]::IsNullOrEmpty($dir)) { return }
  $path = [Environment]::GetEnvironmentVariable("Path", "User")
  if ([string]::IsNullOrEmpty($path)) { $path = "" }
  $parts = $path -split ';' | Where-Object { $_ -ne "" }
  if ($parts -notcontains $dir) {
    [Environment]::SetEnvironmentVariable("Path", ($dir + ";" + $path), "User")
  }
}
"""
    if android_sdk:
        ps += f'Set-UserEnvIfMissing "ANDROID_SDK_ROOT" "{android_sdk}"\n'
        ps += f'Set-UserEnvIfMissing "ANDROID_HOME" "{android_sdk}"\n'
        ps += 'Prepend-UserPathIfMissing (Join-Path $env:ANDROID_SDK_ROOT "platform-tools")\n'
    if java_home:
        ps += f'Set-UserEnvIfMissing "JAVA_HOME" "{java_home}"\n'
        ps += 'Prepend-UserPathIfMissing (Join-Path $env:JAVA_HOME "bin")\n'
    if flutter_dir:
        ps += f'Prepend-UserPathIfMissing "{flutter_dir}"\n'
    ps += 'Write-Host "Updated user environment variables. Restart your terminal to apply."\n'
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps], check=False)


def _persist_env(*, java_home: str | None = None, android_sdk: str | None = None, flutter_bin: str | None = None) -> None:
    if not _persist_env_enabled():
        return
    sysname = platform.system()
    if sysname == "Windows":
        _persist_env_windows(java_home=java_home, android_sdk=android_sdk, flutter_bin=flutter_bin)
    elif sysname == "Darwin":
        _persist_env_macos_zshrc(java_home=java_home, android_sdk=android_sdk, flutter_bin=flutter_bin)

def _run_quiet(
    cmd: list[str],
    *,
    env: dict[str, str],
    timeout: int | None = None,
    input_text: str | None = None,
    check: bool = True,
    verbose_env: str = "XG_VERBOSE",
) -> subprocess.CompletedProcess[str]:
    """
    Run a command. By default, suppress stdout/stderr to avoid extremely noisy tools.
    Set the env var in `verbose_env` (e.g. XG_VERBOSE_SDKMANAGER=1) to stream output.
    """
    if _is_truthy_env(verbose_env):
        return subprocess.run(
            cmd,
            input=input_text,
            text=True,
            env=env,
            check=check,
            timeout=timeout,
        )
    p = subprocess.run(
        cmd,
        input=input_text,
        text=True,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        timeout=timeout,
    )
    if check and p.returncode != 0:
        tail = (p.stdout or "")[-8000:]
        raise subprocess.CalledProcessError(p.returncode, cmd, output=tail)
    return p


def _auto_download_flutter() -> str:
    """
    Download the latest stable Flutter SDK into ``~/.xg-glass/flutter/``
    and return the path to the ``flutter`` binary.
    """
    system = platform.system().lower()
    machine = platform.machine().lower()

    os_name = {"darwin": "macos", "windows": "windows"}.get(system, "linux")
    arch = "arm64" if machine in ("arm64", "aarch64") else "x64"

    print("Flutter SDK not found. Downloading latest stable Flutter SDK...")
    print(f"  Install location: {_MANAGED_FLUTTER_DIR}")

    # Fetch the release manifest for this platform.
    releases_url = (
        "https://storage.googleapis.com/flutter_infra_release/releases/"
        f"releases_{os_name}.json"
    )
    try:
        data = _download_json(releases_url)
    except (urllib.error.URLError, OSError) as exc:
        raise RuntimeError(
            f"Failed to fetch Flutter release information: {exc}\n"
            "Please install Flutter manually: https://docs.flutter.dev/get-started/install"
        ) from exc

    stable_hash = data["current_release"]["stable"]
    base_url = data["base_url"]

    # Pick the release that matches hash + arch.
    candidates = [r for r in data["releases"] if r["hash"] == stable_hash]
    release = None
    for c in candidates:
        if c.get("dart_sdk_arch", "x64") == arch:
            release = c
            break
    if release is None and candidates:
        release = candidates[0]
    if release is None:
        raise RuntimeError(
            "Could not find a stable Flutter release for your platform.\n"
            "Please install Flutter manually: https://docs.flutter.dev/get-started/install"
        )

    archive_url = base_url + "/" + release["archive"]
    version = release["version"]
    archive_name = release["archive"].rsplit("/", 1)[-1]

    _MANAGED_FLUTTER_DIR.mkdir(parents=True, exist_ok=True)
    archive_path = _MANAGED_FLUTTER_DIR / archive_name

    print(f"  Flutter version: {version}")
    print(f"  Downloading from: {archive_url}")

    try:
        _download_file(archive_url, archive_path)
        print()  # newline after progress bar
    except (urllib.error.URLError, OSError) as exc:
        archive_path.unlink(missing_ok=True)
        raise RuntimeError(
            f"Failed to download Flutter SDK: {exc}\n"
            "Please install Flutter manually: https://docs.flutter.dev/get-started/install"
        ) from exc

    print("  Extracting...")
    try:
        _extract_archive(archive_path, _MANAGED_FLUTTER_DIR)
    finally:
        archive_path.unlink(missing_ok=True)

    _ensure_flutter_executables()

    flutter_bin = _managed_flutter_bin()
    if not flutter_bin.exists():
        raise RuntimeError(
            f"Flutter SDK extracted but binary not found at: {flutter_bin}\n"
            "Please install Flutter manually: https://docs.flutter.dev/get-started/install"
        )

    print(f"  Flutter SDK {version} installed successfully.")
    return str(flutter_bin)


def _find_flutter_cmd() -> str | None:
    """Find Flutter: FLUTTER env-var -> PATH -> managed install."""
    # Allow explicit override via env var
    env = os.environ.get("FLUTTER")
    if env:
        return env
    p = shutil.which("flutter")
    if p:
        return p
    # Check managed installation
    managed = _managed_flutter_bin()
    if managed.exists():
        _ensure_flutter_executables()
        return str(managed)
    return None


def _parse_simple_yaml(text: str) -> dict[str, str]:
    """
    Minimal YAML subset parser:
    - top-level 'key: value'
    - ignores blank lines and lines starting with '#'
    - trims quotes around values
    """
    out: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        k = k.strip()
        v = v.strip()
        if (v.startswith('"') and v.endswith('"')) or (v.startswith("'") and v.endswith("'")):
            v = v[1:-1]
        out[k] = v
    return out


def _copy_tree(src: Path, dst: Path) -> None:
    if not src.is_dir():
        raise FileNotFoundError(f"Missing directory: {src}")
    shutil.copytree(src, dst, dirs_exist_ok=True, ignore=shutil.ignore_patterns("build", ".gradle", ".idea", ".kotlin"))


def _ensure_executable(path: Path) -> None:
    if not path.exists():
        return
    mode = path.stat().st_mode
    path.chmod(mode | 0o111)


def _maybe_infer_java_home(env: dict[str, str]) -> dict[str, str]:
    """
    Best-effort: infer JAVA_HOME if it's missing.

    This is mainly helpful on macOS where users may have a JDK installed but
    haven't exported JAVA_HOME in their shell.
    """
    if env.get("JAVA_HOME"):
        return env
    if platform.system() != "Darwin":
        return env
    java_home = "/usr/libexec/java_home"
    if not Path(java_home).exists():
        return env
    # Prefer JDK 17 (Android baseline), fall back to default.
    for args in ([java_home, "-v", "17"], [java_home]):
        try:
            p = subprocess.run(args, capture_output=True, text=True)
        except Exception:
            continue
        if p.returncode == 0:
            candidate = (p.stdout or "").strip()
            if candidate and Path(candidate).exists():
                env["JAVA_HOME"] = candidate
                return env
    return env


def _java_exe_name() -> str:
    return "java.exe" if platform.system() == "Windows" else "java"


def _java_cmd(env: dict[str, str]) -> str:
    """
    Prefer JAVA_HOME/bin/java when JAVA_HOME is set; otherwise fall back to `java` on PATH.
    """
    home = env.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / _java_exe_name()
        if exe.exists():
            return str(exe)
    return "java"


def _parse_java_major(java_version_output: str) -> int | None:
    """
    Parse Java major version from `java -version` output.

    Examples:
      - 'openjdk version "17.0.10" ...' -> 17
      - 'java version "1.8.0_321"' -> 8
    """
    m = re.search(r'version\s+"([^"]+)"', java_version_output)
    if not m:
        return None
    v = m.group(1).strip()
    if v.startswith("1."):
        parts = v.split(".")
        if len(parts) >= 2 and parts[1].isdigit():
            return int(parts[1])
        return None
    head = v.split(".", 1)[0]
    return int(head) if head.isdigit() else None


def _find_managed_java_home() -> str | None:
    """
    Find a previously downloaded managed JDK under ~/.xg-glass/jdk/.
    Returns JAVA_HOME path if found.
    """
    if not _MANAGED_JDK_DIR.is_dir():
        return None
    exe = _java_exe_name()
    try:
        children = [p for p in _MANAGED_JDK_DIR.iterdir() if p.is_dir()]
    except Exception:
        return None
    for child in sorted(children, key=lambda p: p.name):
        # Common layouts:
        # - <dir>/bin/java
        # - mac: <dir>/Contents/Home/bin/java (when extracting a .jdk bundle)
        for home in (child, child / "Contents" / "Home"):
            if (home / "bin" / exe).exists():
                return str(home)
    # Fallback: search deeper (handles nested top-level folder layouts).
    for child in children:
        try:
            for p in child.rglob(exe):
                if p.name == exe and p.parent.name == "bin":
                    return str(p.parent.parent)
        except Exception:
            continue
    return None


def _default_managed_jdk_major() -> int:
    """
    Pick the default managed JDK major version to download.

    - Must be >=17 (Android/AGP baseline).
    - Capped at _MAX_AGP_JDK_MAJOR (currently 21) because the project's AGP/Gradle
      toolchain may not yet support newer JDKs (e.g. JDK 25 causes "25.0.2" build errors).
    - Override via env var: XG_JAVA_MAJOR=17|21|25|...  (the cap is skipped when overriding)
    """
    raw = os.environ.get("XG_JAVA_MAJOR", "").strip()
    if raw:
        # Explicit override: trust the user, no upper cap.
        try:
            v = int(raw)
            return v if v >= 17 else 17
        except Exception:
            return 17

    # Upper bound: highest JDK major known to work with current AGP / Gradle.
    # Bump this when libs.versions.toml upgrades AGP to a version that supports newer JDKs.
    max_jdk = _MAX_AGP_JDK_MAJOR

    try:
        data = _download_json("https://api.adoptium.net/v3/info/available_releases")
        if isinstance(data, dict):
            lts = data.get("available_lts_releases")
            if isinstance(lts, list):
                lts_int = [
                    int(x) for x in lts
                    if isinstance(x, int) or (isinstance(x, str) and str(x).isdigit())
                ]
                # Pick the highest LTS that is within [17, max_jdk].
                valid = [x for x in lts_int if 17 <= x <= max_jdk]
                if valid:
                    return max(valid)
            # Fallback: most_recent_lts (capped).
            mr = data.get("most_recent_lts")
            if isinstance(mr, int) and 17 <= mr <= max_jdk:
                return mr
    except Exception:
        pass
    return 21  # safe default: JDK 21 (LTS, well-supported by AGP 8.x)


def _auto_download_jdk(major: int) -> str:
    """
    Download and extract a managed JDK into ~/.xg-glass/jdk/.
    Returns JAVA_HOME.
    """
    major = max(17, int(major))
    system = platform.system().lower()
    machine = platform.machine().lower()
    os_name = {"darwin": "mac", "windows": "windows"}.get(system, "linux")
    arch = "aarch64" if machine in ("arm64", "aarch64") else "x64"
    prefer_ext = ".zip" if os_name == "windows" else ".tar.gz"

    print(f"Java (JDK {major}) not found. Downloading a managed JDK for xg-glass...")
    print(f"  Install location: {_MANAGED_JDK_DIR}")

    _MANAGED_JDK_DIR.mkdir(parents=True, exist_ok=True)

    link: str | None = None
    name: str | None = None

    # Allow explicit override for air-gapped / mirrored environments.
    override_url = os.environ.get("XG_JDK_URL", "").strip()
    if override_url:
        link = override_url
        name = override_url.rsplit("/", 1)[-1] or "jdk17"
    else:
        # Try to fetch a package link (more stable naming) via the assets API.
        assets_url = (
            f"https://api.adoptium.net/v3/assets/latest/{major}/hotspot"
            f"?os={os_name}&architecture={arch}&image_type=jdk"
        )
        try:
            data = _download_json(assets_url)
            if isinstance(data, dict):
                data = [data]
            candidates: list[tuple[str, str]] = []
            for item in data:
                bins = item.get("binaries") or []
                if not bins and item.get("binary"):
                    bins = [item.get("binary")]
                for b in bins or []:
                    pkg = (b or {}).get("package") or {}
                    lnk = pkg.get("link")
                    nm = pkg.get("name") or ""
                    if lnk:
                        candidates.append((lnk, nm))
            if candidates:
                # Prefer expected extension for the platform.
                for lnk, nm in candidates:
                    if nm.endswith(prefer_ext):
                        link, name = lnk, nm
                        break
                if not link:
                    link, name = candidates[0]
        except Exception:
            link = None

    # Fallback: direct binary endpoint.
    if not link:
        link = f"https://api.adoptium.net/v3/binary/latest/{major}/ga/{os_name}/{arch}/jdk/hotspot/normal/eclipse"
        name = f"temurin-jdk{major}-{os_name}-{arch}{prefer_ext}"

    archive_path = _MANAGED_JDK_DIR / (name or "temurin-jdk17")
    print(f"  Downloading from: {link}")
    try:
        # Support local path override (air-gapped env), e.g. XG_JDK_URL=/tmp/jdk.tar.gz
        if "://" not in link:
            local = Path(link).expanduser()
            if local.exists() and local.is_file():
                shutil.copy2(local, archive_path)
            else:
                _download_file(link, archive_path)
        else:
            _download_file(link, archive_path)
        print()  # newline after progress
    except (urllib.error.HTTPError, urllib.error.URLError, OSError) as exc:
        archive_path.unlink(missing_ok=True)
        hint = ""
        if isinstance(exc, urllib.error.HTTPError) and getattr(exc, "code", None) == 403:
            hint = (
                "\nHint: HTTP 403 usually means the download host is blocked by your network/WAF.\n"
                "      You can set a proxy (HTTPS_PROXY/HTTP_PROXY) or provide a mirror URL via XG_JDK_URL.\n"
            )
        raise RuntimeError(
            f"Failed to download JDK {major}: {exc}{hint}\n"
            "Please install JDK 17+ manually.\n"
            "macOS: brew install --cask temurin@17\n"
            "Windows (PowerShell): winget install EclipseAdoptium.Temurin.17.JDK"
        ) from exc

    # Extract to a temp dir, then move into place.
    tmp_extract = _MANAGED_JDK_DIR / "_jdk_extract_tmp"
    if tmp_extract.exists():
        shutil.rmtree(tmp_extract, ignore_errors=True)
    tmp_extract.mkdir(parents=True, exist_ok=True)

    print("  Extracting...")
    try:
        _extract_archive(archive_path, tmp_extract)
    finally:
        archive_path.unlink(missing_ok=True)

    # Pick a single top-level directory if present; otherwise search under tmp_extract.
    top_dirs = [p for p in tmp_extract.iterdir() if p.is_dir()]
    install_dir = top_dirs[0] if len(top_dirs) == 1 else tmp_extract

    # Clear existing managed JDKs (keep it simple: one managed install).
    for p in _MANAGED_JDK_DIR.iterdir():
        if p.name.startswith("_"):
            continue
        if p.is_dir():
            shutil.rmtree(p, ignore_errors=True)
        else:
            p.unlink(missing_ok=True)

    final_dir = _MANAGED_JDK_DIR / (install_dir.name if install_dir != tmp_extract else "jdk17")
    if final_dir.exists():
        shutil.rmtree(final_dir, ignore_errors=True)
    shutil.move(str(install_dir), str(final_dir))
    shutil.rmtree(tmp_extract, ignore_errors=True)

    # Detect JAVA_HOME inside final_dir.
    exe = _java_exe_name()
    for home in (final_dir, final_dir / "Contents" / "Home"):
        if (home / "bin" / exe).exists():
            print(f"  JDK installed successfully: {home}")
            return str(home)
    # Fallback search:
    for p in final_dir.rglob(exe):
        if p.name == exe and p.parent.name == "bin":
            home = p.parent.parent
            print(f"  JDK installed successfully: {home}")
            return str(home)
    raise RuntimeError(
        f"JDK extracted but java executable not found under: {final_dir}\n"
        "Please install JDK 17+ manually."
    )


def _ensure_java_runtime(env: dict[str, str]) -> None:
    """
    Ensure Java runtime is available for Android SDK tools (sdkmanager/avdmanager).

    On macOS, `/usr/bin/java` can exist but still fail with:
      "Unable to locate a Java Runtime."
    """
    env = _maybe_infer_java_home(env)

    def check() -> tuple[bool, str, int | None]:
        cmd = _java_cmd(env)
        try:
            p = subprocess.run([cmd, "-version"], capture_output=True, text=True, env=env)
        except FileNotFoundError:
            return False, "java executable not found", None
        out = ((p.stdout or "") + "\n" + (p.stderr or "")).strip()
        major = _parse_java_major(out)
        ok = p.returncode == 0 and (major is None or major >= 17)
        return ok, out, major

    ok, out, major = check()
    if ok:
        return

    # If Java exists but is too old (<17), or Java is missing/broken, try managed JDK (opt-out supported).
    if os.environ.get("XG_NO_JAVA_DOWNLOAD", "").strip() not in ("", "0"):
        if major is not None and major < 17:
            raise RuntimeError(
                f"Java {major} detected, but JDK 17+ is required.\n"
                "Please install JDK 17+ and ensure `java -version` works.\n"
                "macOS: brew install --cask temurin@17 && export JAVA_HOME=$(/usr/libexec/java_home -v 17)\n"
                "Windows (PowerShell): winget install EclipseAdoptium.Temurin.17.JDK"
            )
        raise RuntimeError(
            "Java runtime is not available, and auto-download is disabled (XG_NO_JAVA_DOWNLOAD=1).\n"
            "Please install JDK 17+ and ensure `java -version` works.\n"
            "macOS: brew install --cask temurin@17 && export JAVA_HOME=$(/usr/libexec/java_home -v 17)\n"
            "Windows (PowerShell): winget install EclipseAdoptium.Temurin.17.JDK\n"
            f"`java -version` output:\n{out}"
        )

    managed = _find_managed_java_home()
    if not managed:
        managed = _auto_download_jdk(_default_managed_jdk_major())
    env["JAVA_HOME"] = managed
    env["PATH"] = str(Path(managed) / "bin") + os.pathsep + env.get("PATH", "")

    ok2, out2, major2 = check()
    if ok2:
        return
    raise RuntimeError(
        "Java runtime is still not available after setting up a managed JDK.\n"
        "Please install JDK 17+ manually and ensure `java -version` works.\n"
        f"`java -version` output:\n{out2}"
    )


# Default Android SDK packages required for building.
_ANDROID_SDK_PACKAGES = [
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0",
]

_MANAGED_ANDROID_SDK_DIR = _XG_GLASS_HOME / "android-sdk"


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


def _find_android_sdk() -> str | None:
    """Locate the Android SDK directory from environment or common default paths."""
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if sdk:
        return sdk
    # Check common default locations
    system = platform.system()
    candidates: list[Path] = []
    if system == "Windows":
        local_app = os.environ.get("LOCALAPPDATA", "")
        if local_app:
            candidates.append(Path(local_app) / "Android" / "Sdk")
        home = Path.home()
        candidates.append(home / "AppData" / "Local" / "Android" / "Sdk")
    elif system == "Darwin":
        candidates.append(Path.home() / "Library" / "Android" / "sdk")
    else:
        candidates.append(Path.home() / "Android" / "Sdk")
    # Also check managed install
    candidates.append(_MANAGED_ANDROID_SDK_DIR)
    for c in candidates:
        if c.is_dir() and (c / "platform-tools").is_dir():
            return str(c)
    return None


def _resolve_android_sdk() -> str | None:
    """Find an existing Android SDK or download one.  Respects ``XG_NO_ANDROID_DOWNLOAD``."""
    sdk = _find_android_sdk()
    if sdk:
        return sdk
    if os.environ.get("XG_NO_ANDROID_DOWNLOAD", "").strip() not in ("", "0"):
        return None  # user opted out
    return _auto_download_android_sdk()


def _auto_download_android_sdk() -> str:
    """
    Download the Android SDK command-line tools into ``~/.xg-glass/android-sdk/``
    and install the minimum required packages.  Returns the SDK root path.
    """
    system = platform.system().lower()
    os_tag = {"darwin": "mac", "windows": "win"}.get(system, "linux")

    print("Android SDK not found. Downloading Android SDK command-line tools...")
    print(f"  Install location: {_MANAGED_ANDROID_SDK_DIR}")

    url = f"https://dl.google.com/android/repository/commandlinetools-{os_tag}-11076708_latest.zip"

    _MANAGED_ANDROID_SDK_DIR.mkdir(parents=True, exist_ok=True)
    archive_path = _MANAGED_ANDROID_SDK_DIR / "cmdline-tools.zip"

    print(f"  Downloading from: {url}")
    try:
        _download_file(url, archive_path)
        print()  # newline after progress
    except (urllib.error.URLError, OSError) as exc:
        archive_path.unlink(missing_ok=True)
        raise RuntimeError(
            f"Failed to download Android SDK command-line tools: {exc}\n"
            "Please install the Android SDK manually: https://developer.android.com/studio"
        ) from exc

    print("  Extracting...")
    try:
        _extract_archive(archive_path, _MANAGED_ANDROID_SDK_DIR)
    finally:
        archive_path.unlink(missing_ok=True)

    # The zip extracts to cmdline-tools/ – sdkmanager expects the layout:
    #   <sdk>/cmdline-tools/latest/bin/sdkmanager
    extracted = _MANAGED_ANDROID_SDK_DIR / "cmdline-tools"
    dest = _MANAGED_ANDROID_SDK_DIR / "cmdline-tools" / "latest"
    if extracted.is_dir() and not dest.exists():
        # The archive puts files directly under cmdline-tools/ (with bin/, lib/).
        # Move them into cmdline-tools/latest/.
        tmp = _MANAGED_ANDROID_SDK_DIR / "_cmdline_tmp"
        extracted.rename(tmp)
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp.rename(dest)

    if system == "windows":
        sdkmanager = dest / "bin" / "sdkmanager.bat"
    else:
        sdkmanager = dest / "bin" / "sdkmanager"
        _ensure_executable(sdkmanager)

    if not sdkmanager.exists():
        raise RuntimeError(
            f"sdkmanager not found at: {sdkmanager}\n"
            "Please install the Android SDK manually: https://developer.android.com/studio"
        )

    # Accept licenses and install required packages.
    sdk_root = str(_MANAGED_ANDROID_SDK_DIR)
    env = {**os.environ, "ANDROID_HOME": sdk_root, "ANDROID_SDK_ROOT": sdk_root}
    _ensure_java_runtime(env)

    print("  Accepting licenses...")
    try:
        # Pipe "y" answers to accept all licenses.
        _run_quiet(
            [str(sdkmanager), f"--sdk_root={sdk_root}", "--licenses"],
            env=env,
            timeout=120,
            input_text="y\n" * 20,
            check=False,
            verbose_env="XG_VERBOSE_SDKMANAGER",
        )
    except Exception:
        pass  # best-effort – install will also prompt

    print(f"  Installing SDK packages: {', '.join(_ANDROID_SDK_PACKAGES)}")
    try:
        _run_quiet(
            [str(sdkmanager), f"--sdk_root={sdk_root}"] + _ANDROID_SDK_PACKAGES,
            env=env,
            check=True,
            timeout=600,
            input_text="y\n" * 20,
            verbose_env="XG_VERBOSE_SDKMANAGER",
        )
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            f"Failed to install Android SDK packages: {exc}\n"
            "Please install the Android SDK manually: https://developer.android.com/studio"
        ) from exc

    print("  Android SDK installed successfully.")
    return sdk_root


def _ensure_sdk_local_properties(sdk_root: Path) -> None:
    """
    Ensure ``local.properties`` exists in the SDK root with a valid ``sdk.dir``.

    The Flutter module Gradle plugin resolves inside the included SDK build
    and requires the Android SDK location.  Without ``local.properties`` at
    the SDK root Gradle fails with "SDK location not found".
    """
    lp = sdk_root / "local.properties"
    if lp.exists():
        # Check if it actually has sdk.dir set to a valid path.
        try:
            text = lp.read_text(encoding="utf-8", errors="ignore")
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith("sdk.dir=") and not stripped.startswith("sdk.dir=#"):
                    val = stripped.split("=", 1)[1].strip()
                    if val and Path(val.replace("/", os.sep)).is_dir():
                        return  # valid sdk.dir already set
        except Exception:
            pass
    sdk_dir = _resolve_android_sdk()
    if not sdk_dir:
        return
    # Normalise to forward slashes (Gradle properties file convention).
    sdk_dir_escaped = sdk_dir.replace("\\", "/")
    lp.write_text(
        "## Auto-generated by xg-glass CLI – do NOT commit.\n"
        f"sdk.dir={sdk_dir_escaped}\n",
        encoding="utf-8",
    )


def _ensure_project_sdk_dir(project: Path, sdk_dir: str) -> None:
    """Ensure project ``local.properties`` contains a valid ``sdk.dir``.

    If the file already has a valid ``sdk.dir``, this is a no-op.
    If the file exists but ``sdk.dir`` is missing/invalid, append it.
    If the file doesn't exist, create it.
    """
    lp = project / "local.properties"
    sdk_dir_escaped = sdk_dir.replace("\\", "/")
    if lp.exists():
        try:
            text = lp.read_text(encoding="utf-8", errors="ignore")
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith("sdk.dir=") and not stripped.startswith("sdk.dir=#"):
                    val = stripped.split("=", 1)[1].strip()
                    if val and Path(val.replace("/", os.sep)).is_dir():
                        return  # already valid
            # File exists but no valid sdk.dir – append.
            with lp.open("a", encoding="utf-8") as f:
                f.write(f"\nsdk.dir={sdk_dir_escaped}\n")
        except Exception:
            pass
        return
    # File does not exist – create a minimal one.
    lp.write_text(
        "## Auto-generated by xg-glass CLI – do NOT commit.\n"
        f"sdk.dir={sdk_dir_escaped}\n",
        encoding="utf-8",
    )


def _write_local_properties(project: Path, sdk_dir: str | None = None) -> None:
    if sdk_dir is None:
        sdk_dir = _resolve_android_sdk()
    lp = project / "local.properties"
    if sdk_dir:
        sdk_dir_escaped = sdk_dir.replace("\\", "/")
        lp.write_text(
            "## Auto-generated by xg-glass init\n"
            "## This file must *NOT* be checked into VCS.\n"
            f"sdk.dir={sdk_dir_escaped}\n"
            "\n"
            "## Rokid (optional): CXR-M v1.0.4 SN authorization\n"
            "## - Put sn_*.lc under app/src/main/res/raw/\n"
            "## - Set snRawName to the raw resource entry name (file name without extension)\n"
            "rokid.clientSecret=\n"
            "rokid.snRawName=\n"
            "\n"
            "## Meta Ray-Ban (optional): Mobile Wearables SDK v0.4.0\n"
            "## - Register at https://developers.facebook.com/apps/\n"
            "## - Copy App ID from Dashboard\n"
            "meta.applicationId=\n"
            "## - Generate a Personal Access Token (Classic) with 'read:packages' scope\n"
            "## - https://github.com/settings/tokens\n"
            "github_token=\n",
            encoding="utf-8",
        )
    else:
        lp.write_text(
            "## Created by xg-glass init\n"
            "## Please set sdk.dir to your Android SDK location, e.g.:\n"
            "## sdk.dir=/Users/<you>/Library/Android/sdk\n"
            "\n"
            "## Rokid (optional): CXR-M v1.0.4 SN authorization\n"
            "## - Put sn_*.lc under app/src/main/res/raw/\n"
            "## - Set snRawName to the raw resource entry name (file name without extension)\n"
            "rokid.clientSecret=\n"
            "rokid.snRawName=\n"
            "\n"
            "## Meta Ray-Ban (optional): Mobile Wearables SDK v0.4.0\n"
            "## - Register at https://developers.facebook.com/apps/\n"
            "## - Copy App ID from Dashboard\n"
            "meta.applicationId=\n"
            "## - Generate a Personal Access Token (Classic) with 'read:packages' scope\n"
            "## - https://github.com/settings/tokens\n"
            "github_token=\n",
            encoding="utf-8",
        )


def _gradlew_path(project: Path) -> Path:
    is_windows = platform.system().lower().startswith("win")
    p = project / ("gradlew.bat" if is_windows else "gradlew")
    if not p.exists():
        raise FileNotFoundError(f"gradlew not found in project root: {p}")
    if not is_windows:
        _ensure_executable(p)
    return p


def _cap(s: str) -> str:
    if not s:
        return s
    return s[0].upper() + s[1:]


def _run(cmd: list[str], cwd: Path, *, env: dict[str, str] | None = None) -> None:
    print("+", " ".join(cmd))
    subprocess.check_call(cmd, cwd=str(cwd), env=env)


def _infer_entry_class_from_kt(path: Path) -> str | None:
    text = path.read_text(encoding="utf-8")
    pkg_match = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)\s*$", text, flags=re.MULTILINE)
    cls_match = re.search(r"^\s*(class|object)\s+([A-Za-z_]\w*)\b", text, flags=re.MULTILINE)
    if not (pkg_match and cls_match):
        return None
    return f"{pkg_match.group(1)}.{cls_match.group(2)}"


def _copy_kt_into_project(project_dir: Path, kt_file: Path) -> None:
    """
    Copy a developer-provided .kt file into ug_app_logic module, respecting its declared package.
    """
    text = kt_file.read_text(encoding="utf-8")
    pkg_match = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)\s*$", text, flags=re.MULTILINE)
    if not pkg_match:
        raise RuntimeError("Quick mode requires a `package ...` line in the Kotlin file (or pass --entry-class and use a normal project).")
    pkg = pkg_match.group(1)

    # Remove the template example entry to avoid accidental class/package collisions.
    template_example = project_dir / "ug_app_logic" / "src" / "main" / "java" / "com" / "example" / "xgglassapp" / "logic" / "ExampleAppEntry.kt"
    if template_example.exists():
        template_example.unlink()

    rel_dir = Path(*pkg.split("."))
    dst_dir = project_dir / "ug_app_logic" / "src" / "main" / "java" / rel_dir
    dst_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(kt_file, dst_dir / kt_file.name)


def _init_project(*, dst: Path, template: Path, sdk: Path, entry_class: str) -> None:
    """
    Internal helper: same behavior as `xg-glass init`, but callable from quick mode.
    """
    cmd_init(
        argparse.Namespace(
            dir=str(dst),
            template=str(template),
            sdk=str(sdk),
            entry_class=str(entry_class),
        )
    )
