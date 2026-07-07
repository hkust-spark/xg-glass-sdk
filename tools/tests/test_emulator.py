from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from xg_glass_cli import emulator


class FakeProcess:
    def __init__(self, *, poll_result=None, pid: int = 1234) -> None:
        self.poll_result = poll_result
        self.pid = pid

    def poll(self):
        return self.poll_result


def test_default_system_image_package_uses_arm64_on_arm_hosts(monkeypatch) -> None:
    monkeypatch.setattr(emulator.platform, "machine", lambda: "arm64")

    assert emulator._default_system_image_package() == "system-images;android-34;google_apis;arm64-v8a"


def test_default_system_image_package_uses_x86_64_otherwise(monkeypatch) -> None:
    monkeypatch.setattr(emulator.platform, "machine", lambda: "x86_64")

    assert emulator._default_system_image_package() == "system-images;android-34;google_apis;x86_64"


def test_default_system_image_dir_matches_selected_abi(monkeypatch) -> None:
    monkeypatch.setattr(emulator.platform, "machine", lambda: "aarch64")

    assert emulator._default_system_image_dir("/sdk") == Path("/sdk/system-images/android-34/google_apis/arm64-v8a")


def test_launch_emulator_process_detaches_on_posix(monkeypatch) -> None:
    captured = {}

    def fake_popen(cmd, **kwargs):
        captured["cmd"] = cmd
        captured["kwargs"] = kwargs

    monkeypatch.setattr(emulator.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(emulator.subprocess, "Popen", fake_popen)

    process, stderr_path = emulator._launch_emulator_process("/sdk/emulator/emulator", "xg_glass_avd", {"ANDROID_HOME": "/sdk"})

    assert captured["cmd"] == ["/sdk/emulator/emulator", "-avd", "xg_glass_avd", "-no-snapshot-load"]
    assert captured["kwargs"]["start_new_session"] is True
    assert captured["kwargs"]["env"] == {"ANDROID_HOME": "/sdk"}
    assert process is None
    assert stderr_path.name.startswith("xg-glass-emulator-")


def test_launch_emulator_process_appends_env_args(monkeypatch) -> None:
    captured = {}

    def fake_popen(cmd, **kwargs):
        captured["cmd"] = cmd
        captured["kwargs"] = kwargs

    monkeypatch.setattr(emulator.platform, "system", lambda: "Linux")
    monkeypatch.setattr(emulator.subprocess, "Popen", fake_popen)

    emulator._launch_emulator_process(
        "/sdk/emulator/emulator",
        "xg_glass_avd",
        {
            "ANDROID_HOME": "/sdk",
            "XG_EMULATOR_ARGS": "-no-window -gpu swiftshader_indirect -no-audio -no-boot-anim",
        },
    )

    assert captured["cmd"] == [
        "/sdk/emulator/emulator",
        "-avd",
        "xg_glass_avd",
        "-no-snapshot-load",
        "-no-window",
        "-gpu",
        "swiftshader_indirect",
        "-no-audio",
        "-no-boot-anim",
    ]
    assert captured["kwargs"]["env"]["XG_EMULATOR_ARGS"] == "-no-window -gpu swiftshader_indirect -no-audio -no-boot-anim"


def test_ensure_emulator_waits_for_requested_serial_not_other_ready_emulator(monkeypatch, tmp_path) -> None:
    _arrange_emulator_launch(monkeypatch, tmp_path)
    device_outputs = iter(
        [
            "List of devices attached\nemulator-5554\tdevice\n",
            "List of devices attached\nemulator-5554\tdevice\n",
            "List of devices attached\nemulator-5554\tdevice\nemulator-5556\tdevice\n",
        ]
    )
    getprop_cmds = []

    def fake_check_output(cmd, **kwargs):
        if cmd == ["adb", "devices"]:
            assert kwargs["timeout"] == emulator._ADB_WAIT_TIMEOUT_SECONDS
            return next(device_outputs)
        getprop_cmds.append(cmd)
        if cmd == ["adb", "-s", "emulator-5556", "shell", "getprop", "sys.boot_completed"]:
            assert kwargs["timeout"] == emulator._ADB_WAIT_TIMEOUT_SECONDS
            return "1\n"
        pytest.fail(f"unexpected getprop command: {cmd}")

    monkeypatch.setattr(emulator.subprocess, "check_output", fake_check_output)
    monkeypatch.setattr(emulator.time, "monotonic", _monotonic_counter())

    emulator._ensure_emulator_running(serial="emulator-5556")

    assert getprop_cmds == [["adb", "-s", "emulator-5556", "shell", "getprop", "sys.boot_completed"]]


def test_ensure_emulator_getprop_uses_detected_serial_with_multiple_devices(monkeypatch, tmp_path) -> None:
    _arrange_emulator_launch(monkeypatch, tmp_path)
    device_outputs = iter(
        [
            "List of devices attached\n",
            "List of devices attached\nemulator-5554\tdevice\n102f25a6\tdevice\n",
        ]
    )
    getprop_cmds = []

    def fake_check_output(cmd, **kwargs):
        if cmd == ["adb", "devices"]:
            assert kwargs["timeout"] == emulator._ADB_WAIT_TIMEOUT_SECONDS
            return next(device_outputs)
        getprop_cmds.append(cmd)
        if cmd == ["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"]:
            return "1\n"
        pytest.fail(f"unexpected getprop command: {cmd}")

    monkeypatch.setattr(emulator.subprocess, "check_output", fake_check_output)
    monkeypatch.setattr(emulator.time, "monotonic", _monotonic_counter())

    emulator._ensure_emulator_running()

    assert getprop_cmds == [["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"]]


def test_ensure_emulator_adb_devices_timeout_respects_deadline(monkeypatch, tmp_path) -> None:
    _arrange_emulator_launch(monkeypatch, tmp_path, process=FakeProcess(pid=7777))

    def fake_check_output(cmd, **kwargs):
        if cmd == ["adb", "devices"]:
            assert kwargs["timeout"] == emulator._ADB_WAIT_TIMEOUT_SECONDS
            raise subprocess.TimeoutExpired(cmd, kwargs["timeout"])
        pytest.fail(f"unexpected command: {cmd}")

    monkeypatch.setattr(emulator.subprocess, "check_output", fake_check_output)
    monkeypatch.setattr(emulator.time, "monotonic", _monotonic_values([0, 1, 181]))

    with pytest.raises(RuntimeError, match="Emulator did not finish booting within 3 minutes"):
        emulator._ensure_emulator_running()


def test_ensure_emulator_dead_process_short_circuits_with_stderr(monkeypatch, tmp_path) -> None:
    stderr_path = tmp_path / "emulator-stderr.log"
    stderr_path.write_text("PANIC: Broken AVD lock\n", encoding="utf-8")
    _arrange_emulator_launch(
        monkeypatch,
        tmp_path,
        process=FakeProcess(poll_result=42, pid=4242),
        stderr_path=stderr_path,
    )
    monkeypatch.setattr(
        emulator.subprocess,
        "check_output",
        lambda cmd, **_kwargs: "List of devices attached\n" if cmd == ["adb", "devices"] else "",
    )
    monkeypatch.setattr(emulator.time, "monotonic", _monotonic_counter())

    with pytest.raises(RuntimeError) as exc:
        emulator._ensure_emulator_running()

    message = str(exc.value)
    assert "exit code 42" in message
    assert "PANIC: Broken AVD lock" in message


def test_ensure_emulator_timeout_message_includes_pid(monkeypatch, tmp_path) -> None:
    _arrange_emulator_launch(monkeypatch, tmp_path, process=FakeProcess(pid=9876))
    monkeypatch.setattr(
        emulator.subprocess,
        "check_output",
        lambda cmd, **_kwargs: "List of devices attached\n" if cmd == ["adb", "devices"] else "",
    )
    monkeypatch.setattr(emulator.time, "monotonic", _monotonic_values([0, 1, 181]))

    with pytest.raises(RuntimeError) as exc:
        emulator._ensure_emulator_running()

    assert "Emulator PID: 9876" in str(exc.value)


def _arrange_emulator_launch(
    monkeypatch,
    tmp_path: Path,
    *,
    process: FakeProcess | None = None,
    stderr_path: Path | None = None,
) -> None:
    process = process or FakeProcess()
    if stderr_path is None:
        stderr_path = tmp_path / "emulator.log"
        stderr_path.write_text("", encoding="utf-8")
    monkeypatch.setattr(emulator, "_adb_has_device", lambda serial=None: False)
    monkeypatch.setattr(emulator, "_find_android_sdk", lambda: "/sdk")
    monkeypatch.setattr(emulator, "_find_emulator_cmd", lambda: "/sdk/emulator/emulator")
    monkeypatch.setattr(emulator, "_list_avds", lambda: ["xg_glass_avd"])
    monkeypatch.setattr(emulator, "_find_adb_cmd", lambda: "adb")
    monkeypatch.setattr(emulator, "_launch_emulator_process", lambda *_args: (process, stderr_path))
    monkeypatch.setattr(emulator.time, "sleep", lambda _seconds: None)


def _monotonic_counter():
    value = {"current": -1}

    def monotonic() -> int:
        value["current"] += 1
        return value["current"]

    return monotonic


def _monotonic_values(values: list[int]):
    remaining = iter(values)

    def monotonic() -> int:
        return next(remaining)

    return monotonic
