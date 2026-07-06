from __future__ import annotations

import shutil
import tarfile

import pytest

from xg_glass_cli import sdk_fetch
from xg_glass_cli.constants import CliUsageError


def _make_sdk(root, name: str = "sdk"):
    sdk = root / name
    (sdk / "templates" / "kotlin-app").mkdir(parents=True)
    (sdk / "settings.gradle.kts").write_text("pluginManagement {}\n", encoding="utf-8")
    return sdk


def _make_sdk_archive(tmp_path, version: str, *, valid: bool = True):
    src = tmp_path / "archive-src" / f"xg-glass-sdk-{version}"
    src.mkdir(parents=True)
    if valid:
        (src / "templates" / "kotlin-app").mkdir(parents=True)
        (src / "settings.gradle.kts").write_text("pluginManagement {}\n", encoding="utf-8")
    else:
        (src / "README.md").write_text("not an sdk\n", encoding="utf-8")

    archive = tmp_path / f"xg-glass-sdk-{version}.tar.gz"
    with tarfile.open(archive, "w:gz") as tf:
        tf.add(src, arcname=src.name)
    return archive


def test_resolve_sdk_explicit_path_wins(monkeypatch, tmp_path) -> None:
    explicit = tmp_path / "explicit"
    explicit.mkdir()
    monkeypatch.setattr(sdk_fetch, "DEFAULT_SDK", tmp_path / "missing-default")
    monkeypatch.setattr(sdk_fetch, "_download_and_cache_sdk", lambda _version: pytest.fail("download called"))

    assert sdk_fetch.resolve_sdk(explicit, subcommand="init") == explicit.resolve()


def test_resolve_sdk_checkout_wins_over_cache(monkeypatch, tmp_path) -> None:
    checkout = _make_sdk(tmp_path, "checkout")
    cache_root = tmp_path / "cache"
    cached = cache_root / "9.9.9" / "xg-glass-sdk-9.9.9"
    _make_sdk(cached.parent, cached.name)
    monkeypatch.setattr(sdk_fetch, "DEFAULT_SDK", checkout)
    monkeypatch.setattr(sdk_fetch, "_SDK_CACHE_ROOT", cache_root)
    monkeypatch.setattr(sdk_fetch, "_installed_sdk_version", lambda: "9.9.9")
    monkeypatch.setattr(sdk_fetch, "_download_and_cache_sdk", lambda _version: pytest.fail("download called"))

    assert sdk_fetch.resolve_sdk(None, subcommand="init") == checkout.resolve()


def test_resolve_sdk_cache_hit_skips_download(monkeypatch, tmp_path) -> None:
    cache_root = tmp_path / "cache"
    cached = cache_root / "9.9.9" / "xg-glass-sdk-9.9.9"
    _make_sdk(cached.parent, cached.name)
    monkeypatch.setattr(sdk_fetch, "DEFAULT_SDK", tmp_path / "missing-default")
    monkeypatch.setattr(sdk_fetch, "_SDK_CACHE_ROOT", cache_root)
    monkeypatch.setattr(sdk_fetch, "_installed_sdk_version", lambda: "9.9.9")
    monkeypatch.setattr(sdk_fetch, "_download_and_cache_sdk", lambda _version: pytest.fail("download called"))

    assert sdk_fetch.resolve_sdk(None, subcommand="run") == cached.resolve()


def test_download_and_cache_sdk_extracts_tarball_atomically(monkeypatch, tmp_path) -> None:
    version = "9.9.9"
    archive = _make_sdk_archive(tmp_path, version)
    cache_root = tmp_path / "cache"
    monkeypatch.setattr(sdk_fetch, "_SDK_CACHE_ROOT", cache_root)

    def fake_download(download_version, dest):
        assert download_version == version
        shutil.copy2(archive, dest)

    monkeypatch.setattr(sdk_fetch, "_download_sdk_archive", fake_download)

    resolved = sdk_fetch._download_and_cache_sdk(version)

    assert resolved == cache_root / version / f"xg-glass-sdk-{version}"
    assert (resolved / "settings.gradle.kts").is_file()
    assert (resolved / "templates" / "kotlin-app").is_dir()
    assert not list((cache_root / version).glob(".download-*"))


def test_resolve_sdk_download_failure_is_friendly(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(sdk_fetch, "DEFAULT_SDK", tmp_path / "missing-default")
    monkeypatch.setattr(sdk_fetch, "_SDK_CACHE_ROOT", tmp_path / "cache")
    monkeypatch.setattr(sdk_fetch, "_installed_sdk_version", lambda: "9.9.9")

    def fail_download(_version):
        raise RuntimeError("offline")

    monkeypatch.setattr(sdk_fetch, "_download_and_cache_sdk", fail_download)

    with pytest.raises(CliUsageError) as exc:
        sdk_fetch.resolve_sdk(None, subcommand="init")

    message = str(exc.value)
    assert "auto-download xg-glass-sdk 9.9.9" in message
    assert "offline" in message
    assert "pass --sdk /path/to/xg-glass-sdk" in message


def test_sdk_version_env_override_is_respected(monkeypatch, tmp_path) -> None:
    cache_root = tmp_path / "cache"
    cached = cache_root / "8.8.8" / "xg-glass-sdk-8.8.8"
    _make_sdk(cached.parent, cached.name)
    monkeypatch.setenv("XG_GLASS_SDK_VERSION", "8.8.8")
    monkeypatch.setattr(sdk_fetch, "DEFAULT_SDK", tmp_path / "missing-default")
    monkeypatch.setattr(sdk_fetch, "_SDK_CACHE_ROOT", cache_root)
    monkeypatch.setattr(sdk_fetch.metadata, "version", lambda _name: pytest.fail("metadata version called"))

    assert sdk_fetch.resolve_sdk(None, subcommand="init") == cached.resolve()


def test_download_and_cache_rejects_invalid_archive(monkeypatch, tmp_path) -> None:
    version = "9.9.9"
    archive = _make_sdk_archive(tmp_path, version, valid=False)
    cache_root = tmp_path / "cache"
    monkeypatch.setattr(sdk_fetch, "_SDK_CACHE_ROOT", cache_root)
    monkeypatch.setattr(sdk_fetch, "_download_sdk_archive", lambda _version, dest: shutil.copy2(archive, dest))

    with pytest.raises(RuntimeError, match="valid xg-glass-sdk checkout"):
        sdk_fetch._download_and_cache_sdk(version)

    assert not (cache_root / version / f"xg-glass-sdk-{version}").exists()


def test_download_and_cache_treats_concurrent_final_path_as_success(monkeypatch, tmp_path) -> None:
    version = "9.9.9"
    archive = _make_sdk_archive(tmp_path, version)
    cache_root = tmp_path / "cache"
    monkeypatch.setattr(sdk_fetch, "_SDK_CACHE_ROOT", cache_root)

    def fake_download(_version, dest):
        shutil.copy2(archive, dest)
        final = cache_root / version / f"xg-glass-sdk-{version}"
        _make_sdk(final.parent, final.name)

    monkeypatch.setattr(sdk_fetch, "_download_sdk_archive", fake_download)

    assert sdk_fetch._download_and_cache_sdk(version) == cache_root / version / f"xg-glass-sdk-{version}"
