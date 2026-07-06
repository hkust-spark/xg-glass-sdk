from __future__ import annotations

import os
import platform
import shutil
import subprocess
import urllib.error
from pathlib import Path

from .android_sdk import _ensure_sdk_local_properties
from .config import XgConfig
from .constants import _MANAGED_FLUTTER_DIR
from .downloads import _download_file, _download_json, _extract_archive, _verify_sha256
from .gradle import _run
from .paths import _ensure_executable

_PINNED_FLUTTER_VERSION = "3.44.4"


def _ensure_flutter_module_ready(project: Path, cfg: XgConfig, *, needs_frame: bool | None = None) -> None:
    """
    Ensure the embedded Flutter module has a valid `.dart_tool/package_config.json`.

    The Gradle Flutter plugin may fail with:
      "<module>/.dart_tool/package_config.json does not exist"
    unless `flutter pub get` has been run in the module directory.
    """
    sdk_path_raw = cfg.sdk_path
    if not sdk_path_raw:
        return
    sdk = Path(sdk_path_raw)
    if not sdk.is_absolute():
        sdk = (project / sdk).resolve()

    # Ensure the SDK root has local.properties with sdk.dir so that the
    # Flutter module Gradle plugin can locate the Android SDK.
    _ensure_sdk_local_properties(sdk)

    fm = sdk / "third_party" / "frame" / "frame_module"
    pubspec = fm / "pubspec.yaml"
    if not pubspec.exists():
        return

    pkg_config = fm / ".dart_tool" / "package_config.json"
    build_frame_aar = _config_needs_frame(cfg) if needs_frame is None else needs_frame

    # If an old cache references a previous directory layout, wipe it.
    if pkg_config.exists():
        try:
            s = pkg_config.read_text(encoding="utf-8", errors="ignore")
            if "../../Frame/frame_ble" in s or "../../Frame/frame_msg" in s:
                _wipe_flutter_caches(fm)
        except Exception:
            # If unreadable, just proceed.
            pass

    flutter: str | None = None

    if not pkg_config.exists():
        # Missing package_config: run flutter pub get.
        flutter = _ensure_flutter_cmd(fm)
        _run([flutter, "pub", "get"], cwd=fm)

        # flutter pub get downloads engine artifacts (impellerc, gen_snapshot, ...)
        # into bin/cache/artifacts/. On macOS these new files inherit the
        # com.apple.quarantine xattr and must be cleaned before Gradle can invoke them.
        if platform.system() != "Windows" and str(flutter).startswith(str(_MANAGED_FLUTTER_DIR)):
            _ensure_flutter_executables()

        if not pkg_config.exists():
            raise RuntimeError(
                "flutter pub get did not produce .dart_tool/package_config.json.\n"
                f"Please run `flutter pub get` manually in: {fm}"
            )

    if build_frame_aar:
        flutter = flutter or _ensure_flutter_cmd(fm)
        _ensure_frame_flutter_aar(fm, flutter)


def _ensure_flutter_cmd(fm: Path) -> str:
    flutter = _find_flutter_cmd()
    if flutter:
        return flutter
    if os.environ.get("XG_NO_FLUTTER_DOWNLOAD", "").strip() not in ("", "0"):
        raise RuntimeError(
            "Flutter module is present but not initialized, and `flutter` was not found on PATH.\n"
            f"Please install Flutter or run Flutter manually in: {fm}"
        )
    return _auto_download_flutter()


def _config_needs_frame(cfg: XgConfig) -> bool:
    raw = cfg.devices
    if raw is None:
        # Generated default projects keep the all-devices path, which includes Frame.
        return True
    text = raw.strip().strip("[]")
    if not text:
        return True
    devices = {part.strip().lower() for part in text.split(",") if part.strip()}
    return "all" in devices or "frame" in devices


def _ensure_frame_flutter_aar(fm: Path, flutter: str) -> None:
    aars = [
        fm
        / "build"
        / "host"
        / "outputs"
        / "repo"
        / "com"
        / "example"
        / "frame_module"
        / artifact
        / "1.0"
        / f"{artifact}-1.0.aar"
        for artifact in ("flutter_debug", "flutter_profile", "flutter_release")
    ]
    if all(aar.exists() for aar in aars):
        _remove_flutter_android_wrapper(fm)
        return

    _run([flutter, "build", "aar"], cwd=fm)
    missing = [str(aar) for aar in aars if not aar.exists()]
    if missing:
        raise RuntimeError("Flutter AAR build did not produce expected artifacts: " + ", ".join(missing))
    _remove_flutter_android_wrapper(fm)


def _remove_flutter_android_wrapper(fm: Path) -> None:
    # Flutter's generated .android wrapper currently carries AGP escape hatches.
    # The SDK consumes the built AAR repo instead, so the wrapper is just a cache.
    wrapper = fm / ".android"
    if wrapper.exists():
        shutil.rmtree(wrapper, ignore_errors=True)


def _wipe_flutter_caches(fm: Path) -> None:
    for rel in [
        ".dart_tool",
        ".android/Flutter/.dart_tool",
        ".android/.dart_tool",
    ]:
        p = fm / rel
        if p.exists():
            shutil.rmtree(p, ignore_errors=True)


def _managed_flutter_bin() -> Path:
    """Return the expected path to the managed Flutter binary."""
    name = "flutter.bat" if platform.system() == "Windows" else "flutter"
    return _MANAGED_FLUTTER_DIR / "flutter" / "bin" / name


def _ensure_flutter_executables() -> None:
    """
    Ensure the managed Flutter SDK is actually executable on macOS/Linux.

    Two separate issues are fixed:

    1. **Missing +x bits** – Python's ``zipfile.extractall()`` may drop POSIX
       execute bits, so we explicitly ``chmod +x`` key entrypoints.

    2. **macOS quarantine** – files downloaded from the internet (both the initial
       zip *and* artifacts that ``flutter pub get`` fetches later) carry the
       ``com.apple.quarantine`` extended attribute.  macOS Gatekeeper blocks
       execution of unsigned binaries that have this xattr, even when ``+x`` is
       set.  We strip quarantine from the **entire** managed Flutter tree so that
       ``dart``, ``flutter``, and engine binaries like ``impellerc`` can all run.

    This function is safe to call repeatedly (idempotent).
    """
    if platform.system() == "Windows":
        return
    root = _MANAGED_FLUTTER_DIR / "flutter"
    if not root.is_dir():
        return

    # ── macOS: remove quarantine xattr ──────────────────────────────────
    # We target the entire managed Flutter root so that engine artifacts
    # downloaded by `flutter pub get` (e.g. bin/cache/artifacts/engine/
    # darwin-x64/impellerc) are also cleaned.  `xattr -cr` only touches
    # metadata, so it is fast even for large trees.
    if platform.system() == "Darwin":
        try:
            subprocess.run(
                ["xattr", "-cr", str(root)],
                capture_output=True,
                timeout=300,
            )
        except Exception:
            pass

    # ── Ensure +x on core entrypoints ───────────────────────────────────
    for p in [
        root / "bin" / "flutter",
        root / "bin" / "dart",
        root / "bin" / "internal" / "update_engine_version.sh",
        root / "bin" / "internal" / "shared.sh",
    ]:
        _ensure_executable(p)

    # All .sh scripts under bin/internal/
    internal = root / "bin" / "internal"
    if internal.is_dir():
        for p in internal.glob("*.sh"):
            _ensure_executable(p)

    # Dart SDK binaries in cache (pre-packaged or downloaded on first run).
    dart_bin = root / "bin" / "cache" / "dart-sdk" / "bin"
    if dart_bin.is_dir():
        for p in dart_bin.iterdir():
            if p.is_file():
                _ensure_executable(p)

    # Engine artifacts downloaded by flutter (impellerc, gen_snapshot, etc.).
    artifacts = root / "bin" / "cache" / "artifacts" / "engine"
    if artifacts.is_dir():
        for p in artifacts.rglob("*"):
            if p.is_file() and not p.suffix:
                _ensure_executable(p)


def _auto_download_flutter() -> str:
    """
    Download the pinned Flutter SDK into ``~/.xg-glass/flutter/``
    and return the path to the ``flutter`` binary.
    """
    system = platform.system().lower()
    machine = platform.machine().lower()

    os_name = {"darwin": "macos", "windows": "windows"}.get(system, "linux")
    arch = "arm64" if machine in ("arm64", "aarch64") else "x64"

    print(f"Flutter SDK not found. Downloading Flutter SDK {_PINNED_FLUTTER_VERSION}...")
    print(f"  Install location: {_MANAGED_FLUTTER_DIR}")

    # Fetch the release manifest for this platform.
    releases_url = (
        "https://storage.googleapis.com/flutter_infra_release/releases/"
        f"releases_{os_name}.json"
    )
    try:
        data = _download_json(releases_url)
    except (urllib.error.URLError, OSError) as exc:
        raise RuntimeError(
            f"Failed to fetch Flutter release information: {exc}\n"
            "Please install Flutter manually: https://docs.flutter.dev/get-started/install"
        ) from exc

    base_url = data["base_url"]

    # Pick the pinned release that matches version + arch.
    candidates = [r for r in data["releases"] if r.get("version") == _PINNED_FLUTTER_VERSION]
    release = None
    for c in candidates:
        if c.get("dart_sdk_arch", "x64") == arch:
            release = c
            break
    if release is None and candidates:
        release = candidates[0]
    if release is None:
        raise RuntimeError(
            f"Could not find Flutter {_PINNED_FLUTTER_VERSION} for your platform.\n"
            "Please install Flutter manually: https://docs.flutter.dev/get-started/install"
        )

    archive_url = base_url + "/" + release["archive"]
    version = release["version"]
    archive_name = release["archive"].rsplit("/", 1)[-1]
    expected_sha256 = str(release.get("sha256") or "").strip()
    if not expected_sha256:
        raise RuntimeError("Flutter release manifest did not include a SHA-256 checksum.")

    _MANAGED_FLUTTER_DIR.mkdir(parents=True, exist_ok=True)
    archive_path = _MANAGED_FLUTTER_DIR / archive_name

    print(f"  Flutter version: {version}")
    print(f"  Downloading from: {archive_url}")

    try:
        _download_file(archive_url, archive_path)
        print()  # newline after progress bar
        _verify_sha256(archive_path, expected_sha256)
    except (urllib.error.URLError, OSError, RuntimeError) as exc:
        archive_path.unlink(missing_ok=True)
        raise RuntimeError(
            f"Failed to download Flutter SDK: {exc}\n"
            "Please install Flutter manually: https://docs.flutter.dev/get-started/install"
        ) from exc

    print("  Extracting...")
    try:
        _extract_archive(archive_path, _MANAGED_FLUTTER_DIR)
    finally:
        archive_path.unlink(missing_ok=True)

    _ensure_flutter_executables()

    flutter_bin = _managed_flutter_bin()
    if not flutter_bin.exists():
        raise RuntimeError(
            f"Flutter SDK extracted but binary not found at: {flutter_bin}\n"
            "Please install Flutter manually: https://docs.flutter.dev/get-started/install"
        )

    print(f"  Flutter SDK {version} installed successfully.")
    return str(flutter_bin)


def _find_flutter_cmd() -> str | None:
    """Find Flutter: FLUTTER env-var -> PATH -> managed install."""
    # Allow explicit override via env var
    env = os.environ.get("FLUTTER")
    if env:
        return env
    p = shutil.which("flutter")
    if p:
        return p
    # Check managed installation
    managed = _managed_flutter_bin()
    if managed.exists():
        _ensure_flutter_executables()
        return str(managed)
    return None
