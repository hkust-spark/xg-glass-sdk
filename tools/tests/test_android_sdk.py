from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from xg_glass_cli import android_sdk


def test_auto_download_android_sdk_verifies_matching_checksum(monkeypatch, tmp_path) -> None:
    payload = b"known command-line tools archive"
    expected = hashlib.sha256(payload).hexdigest()
    calls: list[str] = []

    _arrange_auto_download(monkeypatch, tmp_path, payload=payload, checksums={"mac": expected}, calls=calls)
    monkeypatch.setattr(android_sdk.platform, "system", lambda: "Darwin")

    assert android_sdk._auto_download_android_sdk() == str(tmp_path)

    assert "extract" in calls
    assert not (tmp_path / "cmdline-tools.zip").exists()


def test_auto_download_android_sdk_mismatch_raises_and_deletes_archive(monkeypatch, tmp_path) -> None:
    payload = b"unexpected archive bytes"
    calls: list[str] = []

    _arrange_auto_download(monkeypatch, tmp_path, payload=payload, checksums={"mac": "0" * 64}, calls=calls)
    monkeypatch.setattr(android_sdk.platform, "system", lambda: "Darwin")

    with pytest.raises(RuntimeError) as exc:
        android_sdk._auto_download_android_sdk()

    message = str(exc.value)
    assert "SHA-256 mismatch" in message
    assert "expected " + ("0" * 64) in message
    assert hashlib.sha256(payload).hexdigest() in message
    assert "https://developer.android.com/studio" in message
    assert "extract" not in calls
    assert not (tmp_path / "cmdline-tools.zip").exists()


def test_auto_download_android_sdk_unknown_os_tag_warns_and_continues(monkeypatch, tmp_path, capsys) -> None:
    calls: list[str] = []

    _arrange_auto_download(monkeypatch, tmp_path, payload=b"unverified", checksums={}, calls=calls)
    monkeypatch.setattr(android_sdk.platform, "system", lambda: "Darwin")

    assert android_sdk._auto_download_android_sdk() == str(tmp_path)

    captured = capsys.readouterr()
    assert "no SHA-256 checksum pinned" in captured.err
    assert "os_tag=mac" in captured.err
    assert "extract" in calls


def _arrange_auto_download(
    monkeypatch,
    tmp_path: Path,
    *,
    payload: bytes,
    checksums: dict[str, str],
    calls: list[str],
) -> None:
    monkeypatch.setattr(android_sdk, "_MANAGED_ANDROID_SDK_DIR", tmp_path)
    monkeypatch.setattr(android_sdk, "_ANDROID_COMMANDLINE_TOOLS_SHA256", checksums)

    def fake_download(_url: str, dest: Path) -> None:
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(payload)

    def fake_extract(archive: Path, dest: Path) -> None:
        assert archive.exists()
        calls.append("extract")
        sdkmanager = dest / "cmdline-tools" / "bin" / "sdkmanager"
        sdkmanager.parent.mkdir(parents=True, exist_ok=True)
        sdkmanager.write_text("#!/bin/sh\n", encoding="utf-8")

    monkeypatch.setattr(android_sdk, "_download_file", fake_download)
    monkeypatch.setattr(android_sdk, "_extract_archive", fake_extract)
    monkeypatch.setattr(android_sdk, "_ensure_executable", lambda _path: None)
    monkeypatch.setattr(android_sdk, "_ensure_java_runtime", lambda _env: None)
    monkeypatch.setattr(android_sdk, "_run_quiet", lambda *_args, **_kwargs: None)
