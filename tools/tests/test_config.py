from __future__ import annotations

import pytest

from xg_glass_cli.config import XgConfig, _load_config, _parse_simple_yaml


def test_parse_simple_yaml_happy_path() -> None:
    data = _parse_simple_yaml(
        """
        # comment
        sdkPath: "../sdk"
        entryClass: 'com.example.Entry'
        variant: debug
        applicationId: "com.example.app"
        """
    )

    assert data == {
        "sdkPath": "../sdk",
        "entryClass": "com.example.Entry",
        "variant": "debug",
        "applicationId": "com.example.app",
    }


def test_load_config_happy_path(tmp_path) -> None:
    (tmp_path / "xg-glass.yaml").write_text(
        "\n".join(
            [
                'sdkPath: "../sdk"',
                'entryClass: "com.example.Entry"',
                'rayneoMercuryAarDir: "../sdk/third_party/rayneo/aar"',
                'variant: "release"',
                'module: "phone"',
                'applicationId: "com.example.app"',
                "",
            ]
        ),
        encoding="utf-8",
    )

    cfg = _load_config(tmp_path, "xg-glass.yaml")

    assert cfg.sdk_path == "../sdk"
    assert cfg.entry_class == "com.example.Entry"
    assert cfg.rayneo_mercury_aar_dir == "../sdk/third_party/rayneo/aar"
    assert cfg.variant == "release"
    assert cfg.module == "phone"
    assert cfg.application_id == "com.example.app"


def test_load_config_missing_file_returns_defaults(tmp_path) -> None:
    assert _load_config(tmp_path, "missing.yaml") == XgConfig()


def test_parse_simple_yaml_ignores_malformed_lines() -> None:
    data = _parse_simple_yaml(
        """
        sdkPath: ../sdk
        not yaml
        : missing-key
        empty:
        nested: unsupported: value
        """
    )

    assert data["sdkPath"] == "../sdk"
    assert "not yaml" not in data
    assert data[""] == "missing-key"
    assert data["empty"] == ""
    assert data["nested"] == "unsupported: value"


def test_load_config_rejects_invalid_entry_class(tmp_path) -> None:
    (tmp_path / "xg-glass.yaml").write_text('entryClass: "NotFullyQualified"\n', encoding="utf-8")

    with pytest.raises(ValueError, match="entryClass in config"):
        _load_config(tmp_path, "xg-glass.yaml")
