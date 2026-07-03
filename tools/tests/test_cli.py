from __future__ import annotations

import pytest

from xg_glass_cli import cli


def test_help_exits_zero(capsys) -> None:
    with pytest.raises(SystemExit) as exc:
        cli.main(["--help"])

    assert exc.value.code == 0
    assert "xg-glass" in capsys.readouterr().out


def test_init_missing_sdk_returns_usage_error(tmp_path, capsys) -> None:
    code = cli.main(["init", str(tmp_path / "app"), "--sdk", str(tmp_path / "missing-sdk")])

    assert code == 2
    assert "SDK not found:" in capsys.readouterr().err


def test_init_parser_leaves_template_omitted(monkeypatch, tmp_path) -> None:
    captured = {}

    def fake_cmd_init(args):
        captured["template"] = args.template
        captured["sdk"] = args.sdk
        return 0

    monkeypatch.setattr(cli, "cmd_init", fake_cmd_init)

    code = cli.main(["init", str(tmp_path / "app"), "--sdk", "/tmp/sdk"])

    assert code == 0
    assert captured == {"template": None, "sdk": "/tmp/sdk"}
