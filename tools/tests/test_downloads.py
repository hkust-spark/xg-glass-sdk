from __future__ import annotations

import io
import tarfile

import pytest

from xg_glass_cli.downloads import _extract_archive


@pytest.fixture(params=[False, True], ids=["stdlib-filter", "legacy-fallback"])
def tar_extractor(request, monkeypatch):
    if request.param:
        monkeypatch.delattr(tarfile, "data_filter", raising=False)
    elif not hasattr(tarfile, "data_filter"):
        pytest.skip("Python has no backported extraction filter")
    return _extract_archive


def archive_with(tmp_path, members):
    archive = tmp_path / "download.tar.gz"
    with tarfile.open(archive, "w:gz") as tf:
        for name, kind, contents in members:
            member = tarfile.TarInfo(name)
            member.type = kind
            if kind == tarfile.REGTYPE:
                data = contents.encode()
                member.size = len(data)
                tf.addfile(member, io.BytesIO(data))
            else:
                member.linkname = contents
                tf.addfile(member)
    return archive


@pytest.mark.parametrize("name", ["../escaped", "sdk/../../escaped"])
def test_tar_rejects_parent_traversal(tar_extractor, tmp_path, name):
    archive = archive_with(tmp_path, [(name, tarfile.REGTYPE, "unexpected")])
    with pytest.raises((RuntimeError, tarfile.TarError)):
        tar_extractor(archive, tmp_path / "extract")
    assert not (tmp_path / "escaped").exists()


@pytest.mark.parametrize("kind", [tarfile.SYMTYPE, tarfile.LNKTYPE])
def test_tar_rejects_escaping_links(tar_extractor, tmp_path, kind):
    outside = tmp_path / "outside"
    outside.mkdir()
    marker = outside / "marker"
    marker.write_text("original")
    archive = archive_with(tmp_path, [
        ("redirect", kind, "../outside"),
        ("redirect/marker", tarfile.REGTYPE, "overwritten"),
    ])
    with pytest.raises((RuntimeError, tarfile.TarError)):
        tar_extractor(archive, tmp_path / "extract")
    assert marker.read_text() == "original"


def test_tar_rejects_existing_symlink_escape(tar_extractor, tmp_path):
    dest = tmp_path / "extract"
    dest.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    try:
        (dest / "redirect").symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("Creating symlinks is not permitted on this platform")
    archive = archive_with(tmp_path, [("redirect/escaped", tarfile.REGTYPE, "bad")])
    with pytest.raises((RuntimeError, tarfile.TarError)):
        tar_extractor(archive, dest)
    assert not (outside / "escaped").exists()


def test_tar_rejects_special_files(tar_extractor, tmp_path):
    archive = archive_with(tmp_path, [("pipe", tarfile.FIFOTYPE, "")])
    with pytest.raises((RuntimeError, tarfile.TarError)):
        tar_extractor(archive, tmp_path / "extract")


def test_tar_rejects_duplicate_symlink_retarget_outside(tar_extractor, tmp_path):
    archive = archive_with(tmp_path, [
        ("inner/file", tarfile.REGTYPE, "safe"),
        ("alias", tarfile.SYMTYPE, "inner/file"),
        ("alias", tarfile.SYMTYPE, "../outside"),
    ])
    dest = tmp_path / "extract"
    with pytest.raises((RuntimeError, tarfile.TarError)):
        tar_extractor(archive, dest)
    alias = dest / "alias"
    if alias.is_symlink():
        assert alias.resolve() == dest / "inner/file"
    else:
        # Windows without symlink privileges may copy the internal referent.
        assert alias.read_text() == "safe"


def test_tar_preserves_regular_files_and_internal_links(tar_extractor, tmp_path):
    archive = archive_with(tmp_path, [
        ("sdk/bin/java", tarfile.REGTYPE, "executable"),
        ("sdk/java", tarfile.SYMTYPE, "bin/java"),
        ("sdk/java-copy", tarfile.LNKTYPE, "sdk/bin/java"),
    ])
    dest = tmp_path / "extract"
    tar_extractor(archive, dest)
    assert (dest / "sdk/bin/java").read_text() == "executable"
    assert (dest / "sdk/java").read_text() == "executable"
    assert (dest / "sdk/java-copy").read_text() == "executable"
