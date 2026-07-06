from __future__ import annotations

import pytest

from xg_glass_cli import commands
from xg_glass_cli import sdk_fetch
from xg_glass_cli.constants import CliUsageError


def _make_sdk(root):
    sdk = root / "sdk"
    template = sdk / "templates" / "kotlin-app"
    template.mkdir(parents=True)
    (sdk / "settings.gradle.kts").write_text("pluginManagement {}\n", encoding="utf-8")
    return sdk, template


def test_init_template_defaults_from_explicit_sdk(tmp_path) -> None:
    sdk, template = _make_sdk(tmp_path)

    resolved_sdk, resolved_template = commands._resolve_init_paths(sdk, None)

    assert resolved_sdk == sdk.resolve()
    assert resolved_template == template.resolve()


def test_init_template_defaults_from_default_sdk(monkeypatch, tmp_path) -> None:
    sdk, template = _make_sdk(tmp_path)
    monkeypatch.setattr(sdk_fetch, "DEFAULT_SDK", sdk)

    resolved_sdk, resolved_template = commands._resolve_init_paths(None, None)

    assert resolved_sdk == sdk.resolve()
    assert resolved_template == template.resolve()


def test_init_default_missing_checkout_uses_friendly_error(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(sdk_fetch, "DEFAULT_SDK", tmp_path / "site-packages")
    monkeypatch.setattr(sdk_fetch, "_installed_sdk_version", lambda: "9.9.9")

    def fail_download(_version):
        raise RuntimeError("offline")

    monkeypatch.setattr(sdk_fetch, "_download_and_cache_sdk", fail_download)

    with pytest.raises(CliUsageError) as exc:
        commands._resolve_init_paths(None, None)

    message = str(exc.value)
    assert "xg-glass was installed without an SDK checkout" in message
    assert "xg-glass init ... --sdk /path/to/xg-glass-sdk" in message
    assert "auto-download xg-glass-sdk 9.9.9" in message
    assert "offline" in message
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

    assert commands._resolve_quick_run_template(sdk) == template
