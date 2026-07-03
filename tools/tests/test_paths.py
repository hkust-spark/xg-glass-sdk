from __future__ import annotations

import pytest

from xg_glass_cli import commands
from xg_glass_cli.constants import CliUsageError


def _make_sdk(root):
    sdk = root / "sdk"
    template = sdk / "templates" / "kotlin-app"
    template.mkdir(parents=True)
    return sdk, template


def test_init_template_defaults_from_explicit_sdk(tmp_path) -> None:
    sdk, template = _make_sdk(tmp_path)

    resolved_sdk, resolved_template = commands._resolve_init_paths(sdk, None)

    assert resolved_sdk == sdk.resolve()
    assert resolved_template == template.resolve()


def test_init_template_defaults_from_default_sdk(monkeypatch, tmp_path) -> None:
    sdk, template = _make_sdk(tmp_path)
    monkeypatch.setattr(commands, "DEFAULT_SDK", sdk)

    resolved_sdk, resolved_template = commands._resolve_init_paths(None, None)

    assert resolved_sdk == sdk.resolve()
    assert resolved_template == template.resolve()


def test_init_default_missing_checkout_uses_friendly_error(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(commands, "DEFAULT_SDK", tmp_path / "site-packages")

    with pytest.raises(CliUsageError) as exc:
        commands._resolve_init_paths(None, None)

    message = str(exc.value)
    assert "xg-glass was installed without an SDK checkout" in message
    assert "xg-glass init ... --sdk /path/to/xg-glass-sdk" in message
    assert "--template" not in message


def test_init_explicit_missing_sdk_is_path_error(tmp_path) -> None:
    with pytest.raises(CliUsageError, match="SDK not found:"):
        commands._resolve_init_paths(tmp_path / "missing-sdk", None)


def test_init_explicit_missing_template_is_path_error(tmp_path) -> None:
    sdk, _template = _make_sdk(tmp_path)

    with pytest.raises(CliUsageError, match="Template not found:"):
        commands._resolve_init_paths(sdk, tmp_path / "missing-template")


def test_init_missing_template_under_explicit_sdk_is_path_error(tmp_path) -> None:
    sdk = tmp_path / "sdk"
    sdk.mkdir()

    with pytest.raises(CliUsageError) as exc:
        commands._resolve_init_paths(sdk, None)

    assert str(exc.value) == f"Template not found: {sdk.resolve() / 'templates' / 'kotlin-app'}"


def test_quick_run_template_can_fall_back_to_sdk_template(monkeypatch, tmp_path) -> None:
    sdk, template = _make_sdk(tmp_path)
    monkeypatch.setattr(commands, "DEFAULT_TEMPLATE", tmp_path / "missing-template")

    assert commands._resolve_quick_run_template(sdk, sdk_from_default=False) == template
