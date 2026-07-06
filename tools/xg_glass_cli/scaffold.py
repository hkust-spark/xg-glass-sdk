from __future__ import annotations

import re
import shutil
from pathlib import Path

from .config import XgConfig
from .devices import DeviceSelection, filter_template_for_devices


_DEVICE_FILTER_SUFFIXES = frozenset(
    {
        ".kt",
        ".kts",
        ".xml",
        ".toml",
        ".yaml",
        ".yml",
        ".md",
        ".properties",
        ".txt",
    }
)


def _replace_project_placeholders(project: Path, *, rel_sdk: str, entry_class: str) -> None:
    settings_file = project / "settings.gradle.kts"
    if settings_file.exists():
        s = settings_file.read_text(encoding="utf-8")
        s = s.replace("__XG_SDK_PATH__", rel_sdk)
        settings_file.write_text(s, encoding="utf-8")

    app_gradle = project / "app" / "build.gradle.kts"
    if app_gradle.exists():
        g = app_gradle.read_text(encoding="utf-8")
        g = g.replace("__XG_ENTRY_CLASS__", entry_class)
        g = g.replace("__XG_SDK_PATH__", rel_sdk)
        app_gradle.write_text(g, encoding="utf-8")

    manifest = project / "app" / "src" / "main" / "AndroidManifest.xml"
    if manifest.exists():
        m = manifest.read_text(encoding="utf-8")
        m = m.replace("__XG_ENTRY_CLASS__", entry_class)
        manifest.write_text(m, encoding="utf-8")

    cfg_file = project / "xg-glass.yaml"
    if cfg_file.exists():
        c = cfg_file.read_text(encoding="utf-8")
        c = c.replace("__XG_SDK_PATH__", rel_sdk)
        c = c.replace("__XG_ENTRY_CLASS__", entry_class)
        cfg_file.write_text(c, encoding="utf-8")


def _filter_project_devices(project: Path, selection: DeviceSelection) -> None:
    for path in project.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in _DEVICE_FILTER_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if "xg:device:" not in text:
            continue
        filtered = filter_template_for_devices(text, selection)
        if filtered != text:
            path.write_text(filtered, encoding="utf-8")


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
    if enabled:
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
        lambda _m: f'buildConfigField("boolean", "XG_SIMULATOR", "{desired}")',
        s,
    )
    if s2 == s:
        # Insert into defaultConfig if missing.
        s2 = re.sub(
            r"(defaultConfig\s*\{\s*)",
            lambda m: (
                f'{m.group(1)}\n'
                f'        buildConfigField("boolean", "XG_SIMULATOR", "{desired}")\n'
            ),
            s,
            count=1,
        )
    s = s2

    app_gradle.write_text(s, encoding="utf-8")


def _apply_sim_video_build_setting(project: Path, device_video_path: str) -> None:
    """
    Add a BuildConfig.XG_SIM_VIDEO_PATH string field so that the simulator
    knows to read frames from a video file instead of the camera.
    """
    app_gradle = project / "app" / "build.gradle.kts"
    if not app_gradle.exists():
        return

    s = app_gradle.read_text(encoding="utf-8")

    # Replace existing field if present.
    # The value in the gradle file looks like: buildConfigField("String", "XG_SIM_VIDEO_PATH", "\"...\"")
    s2 = re.sub(
        r'buildConfigField\("String",\s*"XG_SIM_VIDEO_PATH",\s*"[^)]*"\)',
        lambda _m: (
            f'buildConfigField("String", "XG_SIM_VIDEO_PATH", "\\"{device_video_path}\\"")'
        ),
        s,
    )
    if s2 == s:
        # Insert into defaultConfig if missing.
        s2 = re.sub(
            r"(defaultConfig\s*\{\s*)",
            lambda m: (
                f'{m.group(1)}\n'
                f'        buildConfigField("String", "XG_SIM_VIDEO_PATH", "\\"{device_video_path}\\"")\n'
            ),
            s,
            count=1,
        )
    s = s2

    app_gradle.write_text(s, encoding="utf-8")


def _apply_cfg_to_project(project: Path, cfg: XgConfig) -> None:
    # Apply entry class to phone host manifest (MainActivity reflection).
    if cfg.entry_class:
        manifest = project / "app" / "src" / "main" / "AndroidManifest.xml"
        if manifest.exists():
            s = manifest.read_text(encoding="utf-8")
            s = re.sub(
                r'(android:name="com\.xgglass\.app_entry_class"\s+android:value=")([^"]*)(")',
                lambda m: f"{m.group(1)}{cfg.entry_class}{m.group(3)}",
                s,
            )
            manifest.write_text(s, encoding="utf-8")

    # Apply RayNeo plugin extension values in app/build.gradle.kts.
    app_gradle = project / "app" / "build.gradle.kts"
    if app_gradle.exists():
        s = app_gradle.read_text(encoding="utf-8")
        if cfg.entry_class:
            s = re.sub(
                r'appEntryClass\.set\(".*?"\)',
                lambda _m: f'appEntryClass.set("{cfg.entry_class}")',
                s,
            )
        if cfg.rayneo_mercury_aar_dir:
            # Normalize to a File(rootDir, "...").absolutePath style.
            s = re.sub(
                r'mercuryAarDir\.set\(File\(rootDir,\s*".*?"\)\.absolutePath\)',
                lambda _m: (
                    f'mercuryAarDir.set(File(rootDir, "{cfg.rayneo_mercury_aar_dir}").absolutePath)'
                ),
                s,
            )
        elif cfg.sdk_path:
            s = re.sub(
                r'mercuryAarDir\.set\(File\(rootDir,\s*".*?"\)\.absolutePath\)',
                lambda _m: (
                    f'mercuryAarDir.set(File(rootDir, "{cfg.sdk_path}/third_party/rayneo/aar").absolutePath)'
                ),
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
                lambda _m: f'includeBuild("{cfg.sdk_path}/build-logic")',
                s,
            )
            # 2) composite build includeBuild(...) anchored by the comment block
            s = re.sub(
                r'(^\s*//\s*Use\s+the\s+xg\.glass\s+SDK\s+as\s+a\s+composite\s+build.*\n)\s*includeBuild\(".*?"\)',
                lambda m: f'{m.group(1)}includeBuild("{cfg.sdk_path}")',
                s,
                flags=re.MULTILINE,
            )
            settings_file.write_text(s, encoding="utf-8")


def _copy_tree(src: Path, dst: Path) -> None:
    if not src.is_dir():
        raise FileNotFoundError(f"Missing directory: {src}")
    shutil.copytree(src, dst, dirs_exist_ok=True, ignore=shutil.ignore_patterns("build", ".gradle", ".idea", ".kotlin"))


def _infer_entry_class_from_kt(path: Path) -> str | None:
    text = path.read_text(encoding="utf-8")
    pkg_match = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)\s*$", text, flags=re.MULTILINE)
    cls_match = re.search(r"^\s*(class|object)\s+([A-Za-z_]\w*)\b", text, flags=re.MULTILINE)
    if not (pkg_match and cls_match):
        return None
    return f"{pkg_match.group(1)}.{cls_match.group(2)}"


def _copy_kt_into_project(project_dir: Path, kt_file: Path) -> None:
    """
    Copy a developer-provided .kt file into xgglass_app_logic module, respecting its declared package.
    """
    text = kt_file.read_text(encoding="utf-8")
    pkg_match = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)\s*$", text, flags=re.MULTILINE)
    if not pkg_match:
        raise RuntimeError("Quick mode requires a `package ...` line in the Kotlin file (or pass --entry-class and use a normal project).")
    pkg = pkg_match.group(1)

    # Remove the template example entry to avoid accidental class/package collisions.
    template_example = project_dir / "xgglass_app_logic" / "src" / "main" / "java" / "com" / "example" / "xgglassapp" / "logic" / "ExampleAppEntry.kt"
    if template_example.exists():
        template_example.unlink()

    rel_dir = Path(*pkg.split("."))
    dst_dir = project_dir / "xgglass_app_logic" / "src" / "main" / "java" / rel_dir
    dst_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(kt_file, dst_dir / kt_file.name)
