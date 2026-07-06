from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

import pytest

from xg_glass_cli import adb, commands
from xg_glass_cli.constants import DEFAULT_CONFIG_FILE, _DEVICE_VIDEO_PATH


def test_generated_project_run_sim_builds_boots_installs_and_launches(monkeypatch, tmp_path) -> None:
    calls = []
    args = _run_args(tmp_path, sim=True, serial="emulator-5554")

    monkeypatch.setattr(commands, "_apply_simulator_build_settings", lambda project, enabled: calls.append(("sim", project, enabled)))
    monkeypatch.setattr(commands, "_resolve_sim_video", lambda run_args: calls.append(("resolve_video", run_args.local_video)) or None)
    monkeypatch.setattr(
        commands,
        "_apply_sim_video_build_setting",
        lambda project, device_path: calls.append(("video_build_config", project, device_path)),
    )
    monkeypatch.setattr(commands, "cmd_build", lambda build_args: calls.append(("build", build_args.project, build_args.variant)))
    monkeypatch.setattr(commands, "_ensure_emulator_running", lambda serial=None: calls.append(("ensure_emulator", serial)))
    monkeypatch.setattr(commands, "cmd_install", lambda install_args: calls.append(("install", install_args.project, install_args.serial)))
    monkeypatch.setattr(commands, "_run_project", lambda run_args: calls.append(("launch", run_args.project, run_args.serial)) or 0)

    assert commands.cmd_run(args) == 0

    assert calls == [
        ("sim", tmp_path.resolve(), True),
        ("resolve_video", None),
        ("video_build_config", tmp_path.resolve(), ""),
        ("build", str(tmp_path.resolve()), "debug"),
        ("ensure_emulator", "emulator-5554"),
        ("install", str(tmp_path.resolve()), "emulator-5554"),
        ("launch", str(tmp_path.resolve()), "emulator-5554"),
    ]


def test_generated_project_run_sim_wires_local_video_before_build_and_pushes_after_boot(monkeypatch, tmp_path) -> None:
    calls = []
    video = tmp_path / "clip.mp4"
    args = _run_args(tmp_path, sim=True, local_video=str(video))

    monkeypatch.setattr(commands, "_apply_simulator_build_settings", lambda project, enabled: calls.append(("sim", enabled)))
    monkeypatch.setattr(commands, "_resolve_sim_video", lambda run_args: calls.append(("resolve_video", run_args.local_video)) or video)
    monkeypatch.setattr(
        commands,
        "_apply_sim_video_build_setting",
        lambda project, device_path: calls.append(("video_build_config", project, device_path)),
    )
    monkeypatch.setattr(commands, "cmd_build", lambda _args: calls.append(("build",)))
    monkeypatch.setattr(commands, "_ensure_emulator_running", lambda serial=None: calls.append(("ensure_emulator", serial)))
    monkeypatch.setattr(commands, "_push_video_to_device", lambda path, serial=None: calls.append(("push_video", path, serial)))
    monkeypatch.setattr(commands, "cmd_install", lambda _args: calls.append(("install",)))
    monkeypatch.setattr(commands, "_run_project", lambda _args: calls.append(("launch",)) or 0)

    assert commands.cmd_run(args) == 0

    assert calls == [
        ("sim", True),
        ("resolve_video", str(video)),
        ("video_build_config", tmp_path.resolve(), _DEVICE_VIDEO_PATH),
        ("build",),
        ("ensure_emulator", None),
        ("push_video", video, None),
        ("install",),
        ("launch",),
    ]


def test_generated_project_run_without_sim_keeps_launch_only(monkeypatch, tmp_path) -> None:
    calls = []
    args = _run_args(tmp_path, sim=False)

    monkeypatch.setattr(commands, "_apply_simulator_build_settings", lambda *_args, **_kwargs: calls.append(("sim",)))
    monkeypatch.setattr(commands, "cmd_build", lambda _args: calls.append(("build",)))
    monkeypatch.setattr(commands, "_ensure_emulator_running", lambda serial=None: calls.append(("ensure_emulator", serial)))
    monkeypatch.setattr(commands, "cmd_install", lambda _args: calls.append(("install",)))
    monkeypatch.setattr(commands, "_run_project", lambda run_args: calls.append(("launch", run_args.project)) or 0)

    assert commands.cmd_run(args) == 0

    assert calls == [("launch", str(tmp_path))]


def test_generated_project_run_sim_without_video_resets_stale_build_config(monkeypatch, tmp_path) -> None:
    _write(
        tmp_path / "app" / "build.gradle.kts",
        """
        android {
            defaultConfig {
                buildConfigField("boolean", "XG_SIMULATOR", "false")
                buildConfigField("String", "XG_SIM_VIDEO_PATH", "\\"/data/local/tmp/old.mp4\\"")
            }
            splits {
                abi {
                    include("arm64-v8a", "armeabi-v7a")
                }
            }
        }
        """,
    )
    monkeypatch.setattr(commands, "_resolve_sim_video", lambda _args: None)

    assert commands._prepare_simulator_run_project(tmp_path, _run_args(tmp_path, sim=True)) is None

    app_gradle = (tmp_path / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'buildConfigField("String", "XG_SIM_VIDEO_PATH", "\\"\\"")' in app_gradle


def test_run_project_prefers_template_main_activity(monkeypatch, tmp_path) -> None:
    calls = []
    args = _run_args(tmp_path, sim=False)

    monkeypatch.setattr(commands, "_find_adb_cmd", lambda: "adb")

    def fake_run(cmd, **kwargs):
        calls.append((cmd, kwargs["cwd"]))
        return subprocess.CompletedProcess(cmd, 0, stdout="Starting: Intent { cmp=com.example.xgglassapp/.MainActivity }\n")

    monkeypatch.setattr(commands.subprocess, "run", fake_run)

    assert commands._run_project(args) == 0

    assert calls == [
        (
            ["adb", "shell", "am", "start", "-n", "com.example.xgglassapp/.MainActivity"],
            str(tmp_path.resolve()),
        )
    ]


def test_run_project_falls_back_to_monkey_for_custom_activity(monkeypatch, tmp_path) -> None:
    am_calls = []
    fallback_calls = []
    args = _run_args(tmp_path, sim=False, package="com.example.custom")

    def fake_am_start(cmd, **kwargs):
        am_calls.append((cmd, kwargs["cwd"]))
        return subprocess.CompletedProcess(
            cmd,
            0,
            stdout="Error type 3\nError: Activity class {com.example.custom/.MainActivity} does not exist.\n",
        )

    monkeypatch.setattr(commands, "_find_adb_cmd", lambda: "adb")
    monkeypatch.setattr(commands.subprocess, "run", fake_am_start)
    monkeypatch.setattr(commands, "_run", lambda cmd, cwd: fallback_calls.append((cmd, cwd)))

    assert commands._run_project(args) == 0

    assert am_calls == [
        (
            ["adb", "shell", "am", "start", "-n", "com.example.custom/.MainActivity"],
            str(tmp_path.resolve()),
        )
    ]
    assert fallback_calls == [
        (
            ["adb", "shell", "monkey", "-p", "com.example.custom", "-c", "android.intent.category.LAUNCHER", "1"],
            tmp_path.resolve(),
        ),
    ]


def test_adb_has_device_respects_requested_serial(monkeypatch) -> None:
    monkeypatch.setattr(adb, "_find_adb_cmd", lambda: "adb")

    def fake_check_output(_cmd, **_kwargs):
        return "List of devices attached\nemulator-5554\tdevice\n102f25a6\tdevice\n"

    monkeypatch.setattr(adb.subprocess, "check_output", fake_check_output)

    assert adb._adb_has_device(None) is True
    assert adb._adb_has_device("emulator-5554") is True
    assert adb._adb_has_device("missing-serial") is False


def test_adb_has_device_rejects_offline_or_unauthorized_requested_serial(monkeypatch) -> None:
    monkeypatch.setattr(adb, "_find_adb_cmd", lambda: "adb")

    def fake_check_output(_cmd, **_kwargs):
        return (
            "List of devices attached\n"
            "emulator-5554\toffline\n"
            "102f25a6\tunauthorized\n"
            "emulator-5556\tdevice\n"
        )

    monkeypatch.setattr(adb.subprocess, "check_output", fake_check_output)

    assert adb._adb_has_device("emulator-5554") is False
    assert adb._adb_has_device("102f25a6") is False
    assert adb._adb_has_device("emulator-5556") is True


def test_build_install_run_project_propagates_video_push_failure(monkeypatch, tmp_path) -> None:
    video = tmp_path / "clip.mp4"
    args = _run_args(tmp_path, sim=True)
    calls = []

    monkeypatch.setattr(commands, "cmd_build", lambda _args: calls.append("build"))
    monkeypatch.setattr(commands, "_ensure_emulator_running", lambda serial=None: calls.append(("ensure", serial)))

    def fail_push(_path, serial=None):
        calls.append(("push", serial))
        raise subprocess.CalledProcessError(1, ["adb", "push"])

    monkeypatch.setattr(commands, "_push_video_to_device", fail_push)
    monkeypatch.setattr(commands, "cmd_install", lambda _args: pytest.fail("install should not run after push failure"))
    monkeypatch.setattr(commands, "_run_project", lambda _args: pytest.fail("launch should not run after push failure"))

    with pytest.raises(subprocess.CalledProcessError):
        commands._build_install_run_project(tmp_path, args, ensure_simulator=True, sim_video=video)

    assert calls == ["build", ("ensure", None), ("push", None)]


def _run_args(
    project: Path,
    *,
    sim: bool,
    serial: str | None = None,
    local_video: str | None = None,
    package: str | None = None,
) -> argparse.Namespace:
    return argparse.Namespace(
        project=str(project),
        variant="debug",
        module="app",
        config=DEFAULT_CONFIG_FILE,
        serial=serial,
        package=package,
        kt_file=None,
        save=None,
        keep_tmp=False,
        entry_class=None,
        sdk=None,
        sim=sim,
        local_video=local_video,
        video_url=None,
    )


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
