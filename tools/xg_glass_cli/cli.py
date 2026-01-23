from __future__ import annotations

import argparse
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SDK = REPO_ROOT
DEFAULT_TEMPLATE = REPO_ROOT / "templates" / "kotlin-app"
DEFAULT_CONFIG_FILE = "xg-glass.yaml"


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

    p_build = sub.add_parser("build", help="Build the phone-side APK.")
    _add_common_project_args(p_build)
    p_build.add_argument("--config", default=DEFAULT_CONFIG_FILE, help="Config file name/path (default: xg-glass.yaml).")
    p_build.add_argument("--entry-class", help="Override entry class (optional).")
    p_build.add_argument("--sdk", help="Override sdkPath (optional).")
    p_build.add_argument("--rayneo-aar-dir", help="Override RayNeo mercuryAarDir (optional).")
    p_build.add_argument("--sim", action="store_true", help="Build an emulator-compatible APK (enables x86_64 + simulator mode).")

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

    # Generate local.properties from env if possible (do not copy template's machine-specific file).
    _write_local_properties(dst)

    print(f"Created project: {dst}")
    print("Next:")
    print(f"  cd {dst}")
    print("  ./gradlew :app:assembleDebug")
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
    _ensure_flutter_module_ready(project, cfg)

    if bool(getattr(args, "sim", False)):
        _apply_simulator_build_settings(project, enabled=True)

    variant = cfg.variant
    module = cfg.module
    gradlew = _gradlew_path(project)
    task = f":{module}:assemble{_cap(variant)}"
    _run([str(gradlew), task], cwd=project)
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

    cmd = ["adb"]
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

    cmd = ["adb"]
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
    cmd = ["adb"]
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


def _apply_simulator_build_settings(project: Path, *, enabled: bool) -> None:
    """
    Patch a generated (or existing) template-based project to run well on Android Emulator.

    - Adds x86_64 ABI to splits (so Emulator can install the APK)
    - Flips BuildConfig.XG_SIMULATOR, used by the template host app to select simulator backend
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


def _replace_include_build(settings_text: str, path_regex: str, new_rel_path: str) -> str:
    # Replace includeBuild("...") occurrences matching path_regex.
    def repl(m: re.Match[str]) -> str:
        return f'includeBuild("{new_rel_path}")'

    return re.sub(
        rf'includeBuild\("({path_regex})"\)',
        repl,
        settings_text,
    )


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


def _maybe_clean_flutter_caches(project: Path, cfg: XgConfig) -> None:
    # Backward-compatible shim; keep for old callers (now replaced by _ensure_flutter_module_ready).
    _ensure_flutter_module_ready(project, cfg)


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
        raise RuntimeError(
            "Flutter module is present but not initialized (missing .dart_tool/package_config.json), "
            "and `flutter` was not found on PATH.\n"
            f"Please install Flutter or run `flutter pub get` manually in: {fm}"
        )

    _run([flutter, "pub", "get"], cwd=fm)

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


def _find_flutter_cmd() -> str | None:
    # Allow explicit override via env var
    env = os.environ.get("FLUTTER")
    if env:
        return env
    p = shutil.which("flutter")
    return p


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


def _write_local_properties(project: Path) -> None:
    sdk_dir = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    lp = project / "local.properties"
    if sdk_dir:
        lp.write_text(
            "## Auto-generated by xg-glass init\n"
            "## This file must *NOT* be checked into VCS.\n"
            f"sdk.dir={sdk_dir}\n",
            encoding="utf-8",
        )
    else:
        lp.write_text(
            "## Created by xg-glass init\n"
            "## Please set sdk.dir to your Android SDK location, e.g.:\n"
            "## sdk.dir=/Users/<you>/Library/Android/sdk\n",
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


def _run(cmd: list[str], cwd: Path) -> None:
    print("+", " ".join(cmd))
    subprocess.check_call(cmd, cwd=str(cwd))


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


