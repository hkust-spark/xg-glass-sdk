from __future__ import annotations

import argparse
from pathlib import Path

import pytest

from xg_glass_cli import cli, commands
from xg_glass_cli.constants import CliUsageError
from xg_glass_cli.devices import (
    filter_template_for_devices,
    format_devices_yaml_value,
    parse_device_selection,
)

_ENTRY_CLASS = "com.example.xgglassapp.logic.ExampleAppEntry"


def test_parse_device_selection_default_is_all() -> None:
    selection = parse_device_selection(None, sim=False)

    assert selection.is_all is True
    assert selection.explicit is False
    assert selection.devices[0] == "rokid"
    assert selection.devices[-1] == "simulator"


def test_parse_device_selection_explicit_all() -> None:
    selection = parse_device_selection("all", sim=True)

    assert selection.is_all is True
    assert selection.explicit is True
    assert selection.yaml_devices() == ("all",)


def test_parse_device_selection_is_case_insensitive_and_stable_order() -> None:
    selection = parse_device_selection("EVEN,rokid", sim=False)

    assert selection.is_all is False
    assert selection.devices == ("rokid", "even")


def test_parse_device_selection_trims_whitespace_padded_tokens() -> None:
    selection = parse_device_selection("  EVEN , simulator  ", sim=False)

    assert selection.devices == ("even", "simulator")


def test_parse_device_selection_deduplicates_repeated_entries() -> None:
    selection = parse_device_selection("even,even", sim=False)

    assert selection.devices == ("even",)


def test_parse_device_selection_sim_adds_simulator() -> None:
    selection = parse_device_selection("even", sim=True)

    assert selection.devices == ("even", "simulator")


def test_parse_device_selection_rejects_unknown_device() -> None:
    with pytest.raises(CliUsageError) as exc:
        parse_device_selection("even,nope", sim=False)

    message = str(exc.value)
    assert "Unknown device for --devices: nope" in message
    assert "rokid, rayneo, meta, frame, omi, even, inmo, simulator, all" in message


def test_parse_device_selection_rejects_all_mixed_with_devices() -> None:
    with pytest.raises(CliUsageError, match="Use --devices all by itself"):
        parse_device_selection("all,even", sim=False)


def test_format_devices_yaml_value_uses_inline_list() -> None:
    selection = parse_device_selection("even", sim=True)

    assert format_devices_yaml_value(selection) == "[even, simulator]"


def test_filter_template_default_keeps_all_blocks_and_strips_markers() -> None:
    text = "a\n// xg:device:all:begin\nb\n// xg:device:all:end\n"
    selection = parse_device_selection(None, sim=False)

    assert filter_template_for_devices(text, selection) == "a\nb\n"


def test_filter_template_partial_keeps_selected_device_and_partial_blocks() -> None:
    text = (
        "// xg:device:all:begin\nall\n// xg:device:all:end\n"
        "// xg:device:partial:begin\npartial\n// xg:device:partial:end\n"
        "// xg:device:even:begin\neven\n// xg:device:even:end\n"
        "// xg:device:rokid:begin\nrokid\n// xg:device:rokid:end\n"
    )
    selection = parse_device_selection("even", sim=False)

    assert filter_template_for_devices(text, selection) == "partial\neven\n"


def test_filter_template_nested_markers_respect_parent_block() -> None:
    text = (
        "// xg:device:rokid:begin\n"
        "rokid\n"
        "// xg:device:even:begin\n"
        "nested\n"
        "// xg:device:even:end\n"
        "// xg:device:rokid:end\n"
    )
    selection = parse_device_selection("even", sim=False)

    assert filter_template_for_devices(text, selection) == ""


def test_filter_template_inline_marker_removes_marker_text() -> None:
    text = "keep // xg:device:even:line\ndrop // xg:device:rokid:line\n"
    selection = parse_device_selection("even", sim=False)

    assert filter_template_for_devices(text, selection) == "keep\n"


def test_filter_template_inline_marker_tolerates_trailing_whitespace() -> None:
    text = "keep // xg:device:even:line   \ndrop // xg:device:rokid:line\t\n"
    selection = parse_device_selection("even", sim=False)

    assert filter_template_for_devices(text, selection) == "keep\n"


def test_filter_template_rejects_malformed_marker_trailing_text() -> None:
    text = "// xg:device:rokid:begin -- demo\nleak\n// xg:device:rokid:end -- demo\n"

    with pytest.raises(CliUsageError, match="Malformed xg device marker"):
        filter_template_for_devices(text, parse_device_selection("even", sim=False))


def test_filter_template_rejects_unclosed_begin_at_eof() -> None:
    text = "// xg:device:even:begin\nx\n"

    with pytest.raises(CliUsageError, match="Unclosed xg device marker"):
        filter_template_for_devices(text, parse_device_selection("even", sim=False))


def test_filter_template_rejects_mismatched_marker() -> None:
    text = "// xg:device:even:begin\nx\n// xg:device:rokid:end\n"

    with pytest.raises(CliUsageError, match="Mismatched xg device marker"):
        filter_template_for_devices(text, parse_device_selection("even", sim=False))


def test_filter_template_rejects_unknown_marker_selector() -> None:
    text = "// xg:device:nope:begin\nx\n// xg:device:nope:end\n"

    with pytest.raises(CliUsageError, match="Unknown xg device marker selector"):
        filter_template_for_devices(text, parse_device_selection("even", sim=False))


@pytest.mark.parametrize("selector", ["all,rokid", "partial,rokid", "all,partial"])
def test_filter_template_rejects_special_selector_combos(selector: str) -> None:
    text = f"// xg:device:{selector}:begin\nx\n// xg:device:{selector}:end\n"

    with pytest.raises(CliUsageError, match="must not be combined"):
        filter_template_for_devices(text, parse_device_selection("even", sim=False))


def test_init_parser_passes_devices(monkeypatch, tmp_path) -> None:
    captured = {}

    def fake_cmd_init(args):
        captured["devices"] = args.devices
        captured["sim"] = args.sim
        return 0

    monkeypatch.setattr(cli, "cmd_init", fake_cmd_init)

    code = cli.main(["init", str(tmp_path / "app"), "--sdk", "/tmp/sdk", "--sim", "--devices", "even"])

    assert code == 0
    assert captured == {"devices": "even", "sim": True}


def test_cmd_init_partial_even_simulator_filters_generated_files(monkeypatch, tmp_path) -> None:
    _disable_bootstrap(monkeypatch)
    repo = _repo_root()
    dst = tmp_path / "app"

    code = commands.cmd_init(
        argparse.Namespace(
            dir=str(dst),
            template=str(repo / "templates" / "kotlin-app"),
            sdk=str(repo),
            entry_class=_ENTRY_CLASS,
            sim=True,
            devices="even",
            no_shell_setup=True,
        )
    )

    assert code == 0
    assert "devices: [even, simulator]" in (dst / "xg-glass.yaml").read_text(encoding="utf-8")

    app_gradle = (dst / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert "xgglass-universal-full" not in app_gradle
    assert "xgglass-core:0.1.0" in app_gradle
    assert "xgglass-core-android:0.1.0" in app_gradle
    assert "xgglass-app-contract:0.1.0" in app_gradle
    assert "xgglass-device-even:0.1.0" in app_gradle
    assert "xgglass-device-simulator:0.1.0" in app_gradle
    assert "xgglass-device-rokid" not in app_gradle
    assert "xgglass-device-frame-embedded" not in app_gradle
    assert "xgglass-device-meta" not in app_gradle
    assert "com.xgglass.rayneo.app" not in app_gradle

    activity = (
        dst / "app" / "src" / "main" / "java" / "com" / "example" / "xgglassapp" / "MainActivity.kt"
    )
    main_activity = activity.read_text(encoding="utf-8")
    assert "import com.xgglass.device.even.EvenGlassesClient" in main_activity
    assert "import com.xgglass.device.sim.SimulatorGlassesClient" in main_activity
    assert "import com.xgglass.device.rokid.RokidGlassesClient" not in main_activity
    assert "import com.xgglass.device.frame.embedded.EmbeddedFrameGlassesClient" not in main_activity
    assert "RayNeoDeviceManager" not in main_activity
    assert "devices: [" not in app_gradle
    _assert_no_device_markers(dst)


def test_cmd_init_default_recursively_strips_all_markers(monkeypatch, tmp_path) -> None:
    _disable_bootstrap(monkeypatch)
    repo = _repo_root()
    dst = tmp_path / "app"

    code = commands.cmd_init(
        argparse.Namespace(
            dir=str(dst),
            template=str(repo / "templates" / "kotlin-app"),
            sdk=str(repo),
            entry_class=_ENTRY_CLASS,
            sim=False,
            devices=None,
            no_shell_setup=True,
        )
    )

    assert code == 0
    _assert_no_device_markers(dst)


def test_cmd_init_rokid_selection_keeps_rokid_and_drops_even_frame(monkeypatch, tmp_path) -> None:
    _disable_bootstrap(monkeypatch)
    repo = _repo_root()
    dst = tmp_path / "app"

    code = commands.cmd_init(
        argparse.Namespace(
            dir=str(dst),
            template=str(repo / "templates" / "kotlin-app"),
            sdk=str(repo),
            entry_class=_ENTRY_CLASS,
            sim=False,
            devices="rokid",
            no_shell_setup=True,
        )
    )

    assert code == 0
    main_activity = (
        dst / "app" / "src" / "main" / "java" / "com" / "example" / "xgglassapp" / "MainActivity.kt"
    ).read_text(encoding="utf-8")
    assert "import com.xgglass.device.rokid.RokidGlassesClient" in main_activity
    assert "import com.xgglass.device.even.EvenGlassesClient" not in main_activity
    assert "import com.xgglass.device.frame.embedded.EmbeddedFrameGlassesClient" not in main_activity
    assert "devices: [rokid]" in (dst / "xg-glass.yaml").read_text(encoding="utf-8")
    _assert_no_device_markers(dst)


def test_template_rayneo_selection_includes_installer_and_runtime_artifacts() -> None:
    template = _repo_root() / "templates" / "kotlin-app" / "app" / "build.gradle.kts"
    filtered = filter_template_for_devices(
        template.read_text(encoding="utf-8"),
        parse_device_selection("rayneo", sim=False),
    )

    assert "xgglass-device-rayneo-installer:0.1.0" in filtered
    assert "xgglass-device-rayneo-runtime:0.1.0" in filtered
    assert "xgglass-universal-full" not in filtered


def test_cmd_init_default_keeps_universal_full_and_no_devices_yaml(monkeypatch, tmp_path) -> None:
    _disable_bootstrap(monkeypatch)
    repo = _repo_root()
    dst = tmp_path / "app"

    code = commands.cmd_init(
        argparse.Namespace(
            dir=str(dst),
            template=str(repo / "templates" / "kotlin-app"),
            sdk=str(repo),
            entry_class=_ENTRY_CLASS,
            sim=False,
            devices=None,
            no_shell_setup=True,
        )
    )

    assert code == 0
    assert "devices:" not in (dst / "xg-glass.yaml").read_text(encoding="utf-8")
    app_gradle = (dst / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert "xgglass-universal-full:0.1.0" in app_gradle
    assert "xgglass-device-even:0.1.0" not in app_gradle
    assert "xg:device:" not in app_gradle

    settings = (dst / "settings.gradle.kts").read_text(encoding="utf-8")
    assert "xgglass-universal-full" in settings
    assert "xgglass-device-even" not in settings
    assert "xg:device:" not in settings


def _disable_bootstrap(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(commands, "_ensure_java_runtime", lambda _env: None)
    monkeypatch.setattr(commands, "_resolve_android_sdk", lambda: None)
    monkeypatch.setattr(commands, "_write_local_properties", lambda _project, _android_sdk: None)
    monkeypatch.setattr(commands, "_ensure_flutter_module_ready", lambda _project, _cfg, **_kwargs: None)
    monkeypatch.setattr(commands, "_find_flutter_cmd", lambda: None)
    monkeypatch.setattr(commands, "_persist_env", lambda **_kwargs: None)
    monkeypatch.setattr(commands, "_print_manual_shell_setup", lambda **_kwargs: None)


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _assert_no_device_markers(root: Path) -> None:
    offenders = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if "xg:device:" in text:
            offenders.append(path.relative_to(root).as_posix())
    assert offenders == []
