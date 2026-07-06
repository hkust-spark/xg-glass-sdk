from __future__ import annotations

import os
import shutil
import tempfile
from importlib import metadata
from pathlib import Path

from .constants import DEFAULT_SDK, CliUsageError, _XG_GLASS_HOME, missing_sdk_checkout_message
from .downloads import _download_file, _extract_archive

_SDK_VERSION_ENV = "XG_GLASS_SDK_VERSION"
_SDK_CACHE_ROOT = _XG_GLASS_HOME / "sdk"


def _template_under_sdk(sdk: Path) -> Path:
    return sdk / "templates" / "kotlin-app"


def _is_sdk_checkout(path: str | Path) -> bool:
    sdk = Path(path).expanduser().resolve()
    return sdk.is_dir() and (sdk / "settings.gradle.kts").is_file() and _template_under_sdk(sdk).is_dir()


def _installed_sdk_version() -> str:
    override = os.environ.get(_SDK_VERSION_ENV, "").strip()
    if override:
        return override
    return metadata.version("xg-glass")


def _cached_sdk_path(version: str) -> Path:
    return _SDK_CACHE_ROOT / version / f"xg-glass-sdk-{version}"


def _sdk_archive_url(version: str) -> str:
    return f"https://github.com/hkust-spark/xg-glass-sdk/archive/refs/tags/{version}.tar.gz"


def _download_sdk_archive(version: str, dest: Path) -> None:
    # GitHub-generated tag archives are not byte-stable, so there is no checksum pin.
    _download_file(_sdk_archive_url(version), dest)
    print()


def _find_extracted_sdk(extract_dir: Path, version: str) -> Path:
    preferred = extract_dir / f"xg-glass-sdk-{version}"
    if _is_sdk_checkout(preferred):
        return preferred

    candidates = [p for p in extract_dir.iterdir() if p.is_dir() and _is_sdk_checkout(p)]
    if len(candidates) == 1:
        return candidates[0]
    raise RuntimeError("Downloaded archive did not contain a valid xg-glass-sdk checkout.")


def _download_and_cache_sdk(version: str) -> Path:
    final = _cached_sdk_path(version)
    if _is_sdk_checkout(final):
        return final

    final.parent.mkdir(parents=True, exist_ok=True)
    tmp_root = Path(tempfile.mkdtemp(prefix=".download-", dir=str(final.parent)))
    archive = tmp_root / f"xg-glass-sdk-{version}.tar.gz"
    extract_dir = tmp_root / "extract"
    extract_dir.mkdir(parents=True, exist_ok=True)

    try:
        _download_sdk_archive(version, archive)
        _extract_archive(archive, extract_dir)
        extracted = _find_extracted_sdk(extract_dir, version)
        if final.exists():
            if _is_sdk_checkout(final):
                return final
            raise RuntimeError(f"SDK cache path exists but is not a valid checkout: {final}")
        try:
            os.replace(str(extracted), str(final))
        except FileExistsError:
            if _is_sdk_checkout(final):
                return final
            raise
        if not _is_sdk_checkout(final):
            raise RuntimeError(f"Downloaded SDK cache is not valid: {final}")
        return final
    finally:
        shutil.rmtree(tmp_root, ignore_errors=True)


def _resolve_explicit_sdk(raw_sdk: str | Path) -> Path:
    sdk = Path(raw_sdk).expanduser().resolve()
    if sdk.is_dir():
        return sdk
    raise CliUsageError(f"SDK not found: {sdk}")


def resolve_sdk(raw_sdk: str | Path | None, *, subcommand: str) -> Path:
    if raw_sdk:
        return _resolve_explicit_sdk(raw_sdk)

    default_sdk = DEFAULT_SDK.expanduser().resolve()
    if _is_sdk_checkout(default_sdk):
        return default_sdk

    try:
        version = _installed_sdk_version()
        cached = _cached_sdk_path(version)
        if _is_sdk_checkout(cached):
            return cached

        print(f"Downloading xg-glass-sdk {version} (first run)...")
        sdk = _download_and_cache_sdk(version)
        print(f"xg-glass-sdk {version} ready at: {sdk}")
        return sdk
    except Exception as exc:
        raise CliUsageError(
            missing_sdk_checkout_message(
                subcommand,
                sdk_version=locals().get("version"),
                download_error=exc,
            )
        ) from exc
