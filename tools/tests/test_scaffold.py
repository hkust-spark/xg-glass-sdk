from __future__ import annotations

from xg_glass_cli.config import XgConfig
from xg_glass_cli.scaffold import (
    _apply_cfg_to_project,
    _apply_simulator_build_settings,
    _copy_tree,
    _infer_entry_class_from_kt,
    _replace_project_placeholders,
)


def _write(path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def test_replace_project_placeholders_updates_known_template_files(tmp_path) -> None:
    _write(tmp_path / "settings.gradle.kts", 'includeBuild("__XG_SDK_PATH__")\n')
    _write(
        tmp_path / "app" / "build.gradle.kts",
        'appEntryClass.set("__XG_ENTRY_CLASS__")\nval sdk = "__XG_SDK_PATH__"\n',
    )
    _write(
        tmp_path / "app" / "src" / "main" / "AndroidManifest.xml",
        '<meta-data android:value="__XG_ENTRY_CLASS__" />\n',
    )
    _write(tmp_path / "xg-glass.yaml", 'sdkPath: "__XG_SDK_PATH__"\nentryClass: "__XG_ENTRY_CLASS__"\n')

    _replace_project_placeholders(tmp_path, rel_sdk="../sdk", entry_class="com.example.Entry")

    assert (tmp_path / "settings.gradle.kts").read_text(encoding="utf-8") == 'includeBuild("../sdk")\n'
    assert "__XG_SDK_PATH__" not in (tmp_path / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert "__XG_ENTRY_CLASS__" not in (
        tmp_path / "app" / "src" / "main" / "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    assert (tmp_path / "xg-glass.yaml").read_text(encoding="utf-8") == (
        'sdkPath: "../sdk"\nentryClass: "com.example.Entry"\n'
    )


def test_copy_tree_skips_build_and_ide_caches(tmp_path) -> None:
    src = tmp_path / "src"
    dst = tmp_path / "dst"
    _write(src / "keep.txt", "keep")
    _write(src / "build" / "generated.txt", "skip")
    _write(src / ".gradle" / "cache.txt", "skip")
    _write(src / ".idea" / "workspace.xml", "skip")
    _write(src / ".kotlin" / "sessions" / "cache.txt", "skip")

    _copy_tree(src, dst)

    assert (dst / "keep.txt").read_text(encoding="utf-8") == "keep"
    assert not (dst / "build").exists()
    assert not (dst / ".gradle").exists()
    assert not (dst / ".idea").exists()
    assert not (dst / ".kotlin").exists()


def test_infer_entry_class_from_kotlin_file(tmp_path) -> None:
    kt = tmp_path / "Entry.kt"
    kt.write_text("package com.example.app\n\nobject MyEntry\n", encoding="utf-8")

    assert _infer_entry_class_from_kt(kt) == "com.example.app.MyEntry"


def test_apply_cfg_to_project_updates_manifest_gradle_and_settings(tmp_path) -> None:
    _write(
        tmp_path / "app" / "src" / "main" / "AndroidManifest.xml",
        '<meta-data android:name="com.xgglass.app_entry_class" android:value="old.Entry" />\n',
    )
    _write(
        tmp_path / "app" / "build.gradle.kts",
        'appEntryClass.set("old.Entry")\n'
        'mercuryAarDir.set(File(rootDir, "old/third_party/rayneo/aar").absolutePath)\n',
    )
    _write(
        tmp_path / "settings.gradle.kts",
        'includeBuild("old/build-logic")\n'
        "// Use the xg.glass SDK as a composite build (no publishing step required).\n"
        'includeBuild("old")\n',
    )

    _apply_cfg_to_project(tmp_path, XgConfig(sdk_path="../sdk", entry_class="com.example.Entry"))

    assert 'android:value="com.example.Entry"' in (
        tmp_path / "app" / "src" / "main" / "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    app_gradle = (tmp_path / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'appEntryClass.set("com.example.Entry")' in app_gradle
    assert 'mercuryAarDir.set(File(rootDir, "../sdk/third_party/rayneo/aar").absolutePath)' in app_gradle
    settings = (tmp_path / "settings.gradle.kts").read_text(encoding="utf-8")
    assert 'includeBuild("../sdk/build-logic")' in settings
    assert 'includeBuild("../sdk")' in settings


def test_apply_simulator_build_settings_adds_x86_64_and_flag(tmp_path) -> None:
    _write(
        tmp_path / "app" / "build.gradle.kts",
        """
        android {
            defaultConfig {
                buildConfigField("boolean", "XG_SIMULATOR", "false")
            }
            splits {
                abi {
                    include("arm64-v8a", "armeabi-v7a")
                }
            }
        }
        """,
    )

    _apply_simulator_build_settings(tmp_path, enabled=True)

    app_gradle = (tmp_path / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'include("arm64-v8a", "armeabi-v7a", "x86_64")' in app_gradle
    assert 'buildConfigField("boolean", "XG_SIMULATOR", "true")' in app_gradle
