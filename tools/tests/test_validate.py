from __future__ import annotations

import argparse

from xg_glass_cli import cli, validate
from xg_glass_cli.doctor import DoctorRow


def test_step_table_derivation_per_device() -> None:
    assert _step_ids("rokid") == ["connect", "capture", "display", "mic"]
    assert _step_ids("rayneo") == ["connect", "capture", "display", "mic", "video"]
    assert _step_ids("meta") == ["connect", "capture", "mic"]
    assert _step_ids("frame") == ["connect", "capture", "display", "mic", "tap"]
    assert _step_ids("omi") == ["connect", "capture", "mic", "tap"]
    assert _step_ids("even") == ["connect", "display", "mic", "tap", "long_press"]
    assert _step_ids("inmo") == ["connect", "capture", "display", "mic", "video", "tap", "long_press"]
    assert _step_ids("simulator") == ["connect", "capture", "display", "mic", "video", "tap", "long_press"]


def test_logcat_marker_parsing_returns_matching_line() -> None:
    logcat = "\n".join(
        [
            "07-08 00:00:00.000 I/XgGlassApp: connect(SIMULATOR) => true",
            "07-08 00:00:01.000 I/XgGlassApp: capture_photo: 42 bytes source=SIMULATOR",
        ]
    )

    assert validate.parse_logcat_marker(logcat, r"capture_photo: [1-9][0-9]* bytes") == (
        "07-08 00:00:01.000 I/XgGlassApp: capture_photo: 42 bytes source=SIMULATOR"
    )
    assert validate.parse_logcat_marker(logcat, r"LONG_PRESS") is None


def test_report_rendering_contains_environment_steps_and_issue_url() -> None:
    env = validate.ValidationEnvironment(
        cli_version="0.2.1",
        os_name="macOS-test",
        adb_devices="List of devices attached\nemulator-5554\tdevice\n",
        phone_model="Google / sdk_gphone64 / 16 / arm64-v8a",
        serial="emulator-5554",
        preflight_rows=(
            DoctorRow("OK", "jdk", "JDK 21", "No action needed."),
            DoctorRow("OK", "adb-devices", "emulator-5554=device", "No action needed."),
        ),
    )
    results = [
        validate.StepResult("connect", "Connect", "PASS", "connect(SIMULATOR) => true", "auto-check"),
        validate.StepResult("tap", "Tap event", "SKIP", "", "tester skipped"),
    ]

    report = validate.render_report("simulator", env, results)

    assert "# xg.glass hardware validation: simulator" in report
    assert "| Connect | PASS | connect(SIMULATOR) => true | auto-check |" in report
    assert "| Tap event | SKIP |  | tester skipped |" in report
    assert "emulator-5554\tdevice" in report
    assert validate.ISSUE_URL in report


def test_cli_validate_dispatches(monkeypatch) -> None:
    captured = {}

    def fake_cmd_validate(args):
        captured["devices"] = args.devices
        captured["serial"] = args.serial
        return 0

    monkeypatch.setattr(cli, "cmd_validate", fake_cmd_validate)

    assert cli.main(["validate", "--devices", "even", "--serial", "abc123"]) == 0
    assert captured == {"devices": "even", "serial": "abc123"}


def test_cli_validate_rejects_multiple_devices(capsys) -> None:
    code = cli.main(["validate", "--devices", "even,simulator"])

    assert code == 2
    assert "--devices accepts exactly one concrete device" in capsys.readouterr().err


def test_cmd_validate_uses_existing_pipeline_with_monkeypatches(monkeypatch, tmp_path) -> None:
    calls = []
    report = tmp_path / "report.md"
    sdk = tmp_path / "sdk"
    template = sdk / "templates" / "kotlin-app"

    monkeypatch.setattr(validate, "resolve_sdk", lambda raw_sdk, subcommand: calls.append(("resolve_sdk", raw_sdk, subcommand)) or sdk)
    monkeypatch.setattr(
        validate.commands,
        "_resolve_quick_run_template",
        lambda resolved_sdk: calls.append(("template", resolved_sdk)) or template,
    )
    monkeypatch.setattr(
        validate.commands,
        "_init_project",
        lambda **kwargs: calls.append(("init", kwargs["devices"], kwargs["sim"])),
    )
    monkeypatch.setattr(validate.commands, "cmd_build", lambda args: calls.append(("build", args.project)))
    ready_serials = iter([None, "emulator-5554"])
    monkeypatch.setattr(validate, "_ensure_emulator_running", lambda serial=None: calls.append(("emulator", serial)))
    monkeypatch.setattr(validate, "_choose_ready_serial", lambda *, prefer_emulator: next(ready_serials))
    monkeypatch.setattr(validate.commands, "cmd_install", lambda args: calls.append(("install", args.serial)))
    monkeypatch.setattr(validate.commands, "cmd_run", lambda args: calls.append(("run", args.serial, args.package)))
    monkeypatch.setattr(validate, "_read_application_id", lambda project, module: "com.example.xgglassapp")
    monkeypatch.setattr(validate, "_grant_runtime_permissions", lambda package, device, *, serial: calls.append(("grant", package, device, serial)))
    monkeypatch.setattr(validate, "_clear_logcat", lambda *, serial: calls.append(("clear_logcat", serial)))
    monkeypatch.setattr(validate, "collect_rows", lambda *, offline, env: (DoctorRow("OK", "jdk", "ok", "none"),))
    monkeypatch.setattr(
        validate,
        "collect_environment",
        lambda serial, *, preflight_rows: validate.ValidationEnvironment(
            cli_version="test",
            os_name="test-os",
            adb_devices="List of devices attached\n",
            phone_model="test-phone",
            serial=serial,
            preflight_rows=preflight_rows,
        ),
    )
    monkeypatch.setattr(
        validate,
        "run_step_table",
        lambda profile, *, serial: [validate.StepResult("connect", "Connect", "PASS", "connect", "auto")],
    )

    code = validate.cmd_validate(
        argparse.Namespace(
            devices="simulator",
            serial=None,
            sdk=None,
            report=str(report),
            keep_project=False,
        )
    )

    assert code == 0
    assert report.exists()
    assert ("init", "simulator", True) in calls
    assert ("emulator", "emulator-5554") in calls
    assert ("install", "emulator-5554") in calls
    assert ("run", "emulator-5554", "com.example.xgglassapp") in calls
    assert "Connect" in report.read_text(encoding="utf-8")


def test_find_text_center_uses_clickable_bounds() -> None:
    xml = """
    <hierarchy>
      <node text="Capture photo" clickable="true" enabled="true" bounds="[10,20][110,80]" />
    </hierarchy>
    """

    assert validate._find_text_center(xml, "Capture photo", clickable_only=True) == (60, 50)


def _step_ids(device: str) -> list[str]:
    return [step.id for step in validate.derive_steps(device)]
