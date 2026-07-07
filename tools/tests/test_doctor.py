from __future__ import annotations

import io
import subprocess
from pathlib import Path

from xg_glass_cli import cli, doctor


def test_cli_doctor_offline_dispatches(monkeypatch) -> None:
    captured = {}

    def fake_run_doctor(*, offline):
        captured["offline"] = offline
        return 0

    monkeypatch.setattr(cli, "run_doctor", fake_run_doctor)

    assert cli.main(["doctor", "--offline"]) == 0
    assert captured == {"offline": True}


def test_check_jdk_ok_uses_discover_existing_jdk(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_discover_existing_jdk", lambda env: "/jdk17")
    monkeypatch.setattr(doctor, "_java_home_major", lambda java_home: 17)

    row = doctor.check_jdk({"JAVA_HOME": "/jdk17"})

    assert row.status == "OK"
    assert "JAVA_HOME at /jdk17" in row.detail


def test_check_jdk_missing_fails(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_discover_existing_jdk", lambda env: None)

    row = doctor.check_jdk({})

    assert row.status == "FAIL"
    assert "No compatible JDK" in row.detail
    assert "Install JDK" in row.hint


def test_check_jdk_unsupported_major_fails(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_discover_existing_jdk", lambda env: "/jdk25")
    monkeypatch.setattr(doctor, "_java_home_major", lambda java_home: 25)

    row = doctor.check_jdk({})

    assert row.status == "FAIL"
    assert "JDK 25" in row.detail


def test_check_android_sdk_ok(monkeypatch, tmp_path) -> None:
    sdk = _make_sdk(tmp_path)
    monkeypatch.setattr(doctor, "_find_android_sdk", lambda: str(sdk))

    row = doctor.check_android_sdk()

    assert row.status == "OK"
    assert str(sdk) in row.detail


def test_check_android_sdk_partial_warns(monkeypatch, tmp_path) -> None:
    sdk = _make_sdk(tmp_path)
    (sdk / "emulator" / doctor._exe("emulator")).unlink()
    monkeypatch.setattr(doctor, "_find_android_sdk", lambda: str(sdk))

    row = doctor.check_android_sdk()

    assert row.status == "WARN"
    assert "partially provisioned" in row.detail
    assert "emulator" in row.detail


def test_check_android_sdk_missing_fails(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_find_android_sdk", lambda: None)
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    monkeypatch.delenv("ANDROID_SDK_ROOT", raising=False)

    row = doctor.check_android_sdk()

    assert row.status == "FAIL"
    assert "No Android SDK" in row.detail


def test_check_adb_ok_reports_version(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_find_adb_cmd", lambda: "/sdk/platform-tools/adb")

    def fake_run(cmd, **kwargs):
        assert cmd == ["/sdk/platform-tools/adb", "version"]
        assert kwargs["timeout"] == 10
        return subprocess.CompletedProcess(cmd, 0, stdout="Android Debug Bridge version 1.0.41\n", stderr="")

    monkeypatch.setattr(doctor.subprocess, "run", fake_run)

    row = doctor.check_adb()

    assert row.status == "OK"
    assert "Android Debug Bridge version" in row.detail


def test_check_adb_missing_fails(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_find_adb_cmd", lambda: "adb")

    def fake_run(_cmd, **_kwargs):
        raise FileNotFoundError

    monkeypatch.setattr(doctor.subprocess, "run", fake_run)

    row = doctor.check_adb()

    assert row.status == "FAIL"
    assert "not executable" in row.detail


def test_check_adb_devices_warns_for_unauthorized(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_find_adb_cmd", lambda: "adb")
    monkeypatch.setattr(
        doctor.subprocess,
        "check_output",
        lambda *_args, **_kwargs: "List of devices attached\n102f25a6\tunauthorized\nemulator-5554\tdevice\n",
    )

    row = doctor.check_adb_devices()

    assert row.status == "WARN"
    assert "102f25a6=unauthorized" in row.detail
    assert "Authorize USB debugging" in row.hint


def test_check_adb_devices_empty_is_ok(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_find_adb_cmd", lambda: "adb")
    monkeypatch.setattr(doctor.subprocess, "check_output", lambda *_args, **_kwargs: "List of devices attached\n")

    row = doctor.check_adb_devices()

    assert row.status == "OK"
    assert "No attached devices" in row.detail


def test_check_emulator_binary_uses_resolver(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_find_emulator_cmd", lambda: "/sdk/emulator/emulator")

    row = doctor.check_emulator_binary()

    assert row.status == "OK"
    assert row.detail == "/sdk/emulator/emulator"


def test_check_emulator_binary_missing_warns(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_find_emulator_cmd", lambda: None)

    row = doctor.check_emulator_binary()

    assert row.status == "WARN"
    assert "No emulator binary" in row.detail


def test_check_emulator_acceleration_linux_missing_kvm_warns(monkeypatch) -> None:
    monkeypatch.setattr(doctor.platform, "system", lambda: "Linux")
    monkeypatch.setattr(doctor.Path, "exists", lambda _self: False)

    row = doctor.check_emulator_acceleration()

    assert row.status == "WARN"
    assert "/dev/kvm is missing" in row.detail


def test_check_flutter_missing_is_ok(monkeypatch) -> None:
    monkeypatch.setattr(doctor, "_find_flutter_cmd", lambda: None)

    row = doctor.check_flutter()

    assert row.status == "OK"
    assert "only needed for Frame" in row.detail


def test_check_sdk_resolution_uses_default_checkout(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(doctor.sdk_fetch, "DEFAULT_SDK", tmp_path)
    monkeypatch.setattr(doctor.sdk_fetch, "_is_sdk_checkout", lambda path: Path(path) == tmp_path.resolve())

    row = doctor.check_sdk_resolution()

    assert row.status == "OK"
    assert "checkout" in row.detail


def test_check_sdk_resolution_reports_cached_sdk(monkeypatch, tmp_path) -> None:
    cached = tmp_path / "cached"
    monkeypatch.setattr(doctor.sdk_fetch, "DEFAULT_SDK", tmp_path / "default")
    monkeypatch.setattr(doctor.sdk_fetch, "_installed_sdk_version", lambda: "1.2.3")
    monkeypatch.setattr(doctor.sdk_fetch, "_cached_sdk_path", lambda version: cached)
    monkeypatch.setattr(doctor.sdk_fetch, "_is_sdk_checkout", lambda path: Path(path) == cached)

    row = doctor.check_sdk_resolution()

    assert row.status == "OK"
    assert "cache version 1.2.3" in row.detail


def test_check_sdk_resolution_reports_would_download(monkeypatch, tmp_path) -> None:
    cached = tmp_path / "cached"
    monkeypatch.setattr(doctor.sdk_fetch, "DEFAULT_SDK", tmp_path / "default")
    monkeypatch.setattr(doctor.sdk_fetch, "_installed_sdk_version", lambda: "1.2.3")
    monkeypatch.setattr(doctor.sdk_fetch, "_cached_sdk_path", lambda version: cached)
    monkeypatch.setattr(doctor.sdk_fetch, "_is_sdk_checkout", lambda path: False)

    row = doctor.check_sdk_resolution()

    assert row.status == "OK"
    assert "would download version 1.2.3" in row.detail


def test_check_network_offline_skips() -> None:
    rows = doctor.check_network(offline=True)

    assert len(rows) == 1
    assert rows[0].status == "OK"
    assert "--offline" in rows[0].detail


def test_check_network_unreachable_warns(monkeypatch) -> None:
    def fake_urlopen(_request, **_kwargs):
        raise doctor.urllib.error.URLError("blocked")

    monkeypatch.setattr(doctor.urllib.request, "urlopen", fake_urlopen)

    row = doctor._check_url("https://dl.google.com/")

    assert row.status == "WARN"
    assert "proxy" in row.hint


def test_check_network_reachable_ok(monkeypatch) -> None:
    class FakeResponse:
        status = 200

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def getcode(self):
            return self.status

    monkeypatch.setattr(doctor.urllib.request, "urlopen", lambda *_args, **_kwargs: FakeResponse())

    row = doctor._check_url("https://repo1.maven.org/maven2/")

    assert row.status == "OK"
    assert "reachable" in row.detail


def test_run_doctor_exit_and_formatting(monkeypatch) -> None:
    rows = [
        doctor.DoctorRow("OK", "python", "Python 3.12", "No action needed."),
        doctor.DoctorRow("FAIL", "jdk", "missing", "Install JDK 17."),
    ]
    monkeypatch.setattr(doctor, "collect_rows", lambda *, offline, env: rows)
    output = io.StringIO()

    code = doctor.run_doctor(offline=False, out=output, env={})

    assert code == 1
    text = output.getvalue()
    assert "[ OK   ] python" in text
    assert "[ FAIL ] jdk" in text
    assert "Hint: Install JDK 17." in text
    assert "Summary: 1 FAIL" in text


def _make_sdk(tmp_path):
    sdk = tmp_path / "sdk"
    (sdk / "platform-tools").mkdir(parents=True)
    (sdk / "emulator").mkdir()
    (sdk / "cmdline-tools" / "latest" / "bin").mkdir(parents=True)
    for path in [
        sdk / "platform-tools" / doctor._exe("adb"),
        sdk / "emulator" / doctor._exe("emulator"),
        sdk / "cmdline-tools" / "latest" / "bin" / doctor._exe("sdkmanager"),
        sdk / "cmdline-tools" / "latest" / "bin" / doctor._exe("avdmanager"),
    ]:
        path.write_text("", encoding="utf-8")
    return sdk
