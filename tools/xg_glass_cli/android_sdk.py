from __future__ import annotations

import hashlib
import os
import platform
import subprocess
import sys
import urllib.error
from pathlib import Path

from .constants import _ANDROID_SDK_PACKAGES, _MANAGED_ANDROID_SDK_DIR
from .downloads import _download_file, _extract_archive, _run_quiet
from .java import _ensure_java_runtime
from .paths import _ensure_executable

_ANDROID_STUDIO_MANUAL_INSTALL_URL = "https://developer.android.com/studio"
_ANDROID_COMMANDLINE_TOOLS_BUILD = "11076708"
_ANDROID_COMMANDLINE_TOOLS_SHA256 = {
    # Source: official Google dl.google.com pinned command-line tools archives.
    # The Android Studio page currently lists newer command-line tools and no longer exposes build 11076708.
    "mac": "7bc5c72ba0275c80a8f19684fb92793b83a6b5c94d4d179fc5988930282d7e64",
    "win": "4d6931209eebb1bfb7c7e8b240a6a3cb3ab24479ea294f3539429574b1eec862",
    "linux": "2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258",
}


def _android_sdk_has_platform_tools(sdk: str | Path | None) -> bool:
    if not sdk:
        return False
    root = Path(str(sdk)).expanduser()
    return root.is_dir() and (root / "platform-tools").is_dir()


def _find_env_android_sdk(env: dict[str, str] | None = None) -> str | None:
    env = os.environ if env is None else env
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = (env.get(name) or "").strip()
        if _android_sdk_has_platform_tools(sdk):
            return sdk
    return None


def _find_android_sdk() -> str | None:
    """Locate the Android SDK directory from environment or common default paths."""
    sdk = _find_env_android_sdk(os.environ)
    if sdk:
        return sdk
    # Check common default locations
    system = platform.system()
    candidates: list[Path] = []
    if system == "Windows":
        local_app = os.environ.get("LOCALAPPDATA", "")
        if local_app:
            candidates.append(Path(local_app) / "Android" / "Sdk")
        home = Path.home()
        candidates.append(home / "AppData" / "Local" / "Android" / "Sdk")
    elif system == "Darwin":
        candidates.append(Path.home() / "Library" / "Android" / "sdk")
    else:
        candidates.append(Path.home() / "Android" / "Sdk")
    # Also check managed install
    candidates.append(_MANAGED_ANDROID_SDK_DIR)
    for c in candidates:
        if _android_sdk_has_platform_tools(c):
            return str(c)
    return None


def _resolve_android_sdk() -> str | None:
    """Find an existing Android SDK or download one.  Respects ``XG_NO_ANDROID_DOWNLOAD``."""
    sdk = _find_android_sdk()
    if sdk:
        return sdk
    if os.environ.get("XG_NO_ANDROID_DOWNLOAD", "").strip() not in ("", "0"):
        return None  # user opted out
    return _auto_download_android_sdk()


def _auto_download_android_sdk() -> str:
    """
    Download the Android SDK command-line tools into ``~/.xg-glass/android-sdk/``
    and install the minimum required packages.  Returns the SDK root path.
    """
    system = platform.system().lower()
    os_tag = {"darwin": "mac", "windows": "win"}.get(system, "linux")

    print("Android SDK not found. Downloading Android SDK command-line tools...")
    print(f"  Install location: {_MANAGED_ANDROID_SDK_DIR}")

    url = f"https://dl.google.com/android/repository/commandlinetools-{os_tag}-{_ANDROID_COMMANDLINE_TOOLS_BUILD}_latest.zip"

    _MANAGED_ANDROID_SDK_DIR.mkdir(parents=True, exist_ok=True)
    archive_path = _MANAGED_ANDROID_SDK_DIR / "cmdline-tools.zip"

    print(f"  Downloading from: {url}")
    try:
        _download_file(url, archive_path)
        print()  # newline after progress
    except (urllib.error.URLError, OSError) as exc:
        archive_path.unlink(missing_ok=True)
        raise RuntimeError(
            f"Failed to download Android SDK command-line tools: {exc}\n"
            f"Please install the Android SDK manually: {_ANDROID_STUDIO_MANUAL_INSTALL_URL}"
        ) from exc

    _verify_commandline_tools_archive(os_tag, archive_path)

    print("  Extracting...")
    try:
        _extract_archive(archive_path, _MANAGED_ANDROID_SDK_DIR)
    finally:
        archive_path.unlink(missing_ok=True)

    # The zip extracts to cmdline-tools/ – sdkmanager expects the layout:
    #   <sdk>/cmdline-tools/latest/bin/sdkmanager
    extracted = _MANAGED_ANDROID_SDK_DIR / "cmdline-tools"
    dest = _MANAGED_ANDROID_SDK_DIR / "cmdline-tools" / "latest"
    if extracted.is_dir() and not dest.exists():
        # The archive puts files directly under cmdline-tools/ (with bin/, lib/).
        # Move them into cmdline-tools/latest/.
        tmp = _MANAGED_ANDROID_SDK_DIR / "_cmdline_tmp"
        extracted.rename(tmp)
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp.rename(dest)

    if system == "windows":
        sdkmanager = dest / "bin" / "sdkmanager.bat"
    else:
        sdkmanager = dest / "bin" / "sdkmanager"
        _ensure_executable(sdkmanager)

    if not sdkmanager.exists():
        raise RuntimeError(
            f"sdkmanager not found at: {sdkmanager}\n"
            f"Please install the Android SDK manually: {_ANDROID_STUDIO_MANUAL_INSTALL_URL}"
        )

    # Accept licenses and install required packages.
    sdk_root = str(_MANAGED_ANDROID_SDK_DIR)
    env = {**os.environ, "ANDROID_HOME": sdk_root, "ANDROID_SDK_ROOT": sdk_root}
    _ensure_java_runtime(env)

    print("  Accepting licenses...")
    try:
        # Pipe "y" answers to accept all licenses.
        _run_quiet(
            [str(sdkmanager), f"--sdk_root={sdk_root}", "--licenses"],
            env=env,
            timeout=120,
            input_text="y\n" * 20,
            check=False,
            verbose_env="XG_VERBOSE_SDKMANAGER",
        )
    except Exception:
        pass  # best-effort – install will also prompt

    print(f"  Installing SDK packages: {', '.join(_ANDROID_SDK_PACKAGES)}")
    try:
        _run_quiet(
            [str(sdkmanager), f"--sdk_root={sdk_root}"] + _ANDROID_SDK_PACKAGES,
            env=env,
            check=True,
            timeout=600,
            input_text="y\n" * 20,
            verbose_env="XG_VERBOSE_SDKMANAGER",
        )
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            f"Failed to install Android SDK packages: {exc}\n"
            f"Please install the Android SDK manually: {_ANDROID_STUDIO_MANUAL_INSTALL_URL}"
        ) from exc

    print("  Android SDK installed successfully.")
    return sdk_root


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _verify_commandline_tools_archive(os_tag: str, archive_path: Path) -> None:
    expected = _ANDROID_COMMANDLINE_TOOLS_SHA256.get(os_tag)
    if not expected:
        print(
            f"  Warning: no SHA-256 checksum pinned for Android command-line tools os_tag={os_tag}; "
            "proceeding without archive verification.",
            file=sys.stderr,
        )
        return

    actual = _sha256_file(archive_path)
    if actual != expected:
        archive_path.unlink(missing_ok=True)
        raise RuntimeError(
            f"Android SDK command-line tools SHA-256 mismatch for {archive_path.name}: "
            f"expected {expected}, got {actual}.\n"
            f"Please install the Android SDK manually: {_ANDROID_STUDIO_MANUAL_INSTALL_URL}"
        )


def _ensure_sdk_local_properties(sdk_root: Path) -> None:
    """
    Ensure ``local.properties`` exists in the SDK root with a valid ``sdk.dir``.

    The Flutter module Gradle plugin resolves inside the included SDK build
    and requires the Android SDK location.  Without ``local.properties`` at
    the SDK root Gradle fails with "SDK location not found".
    """
    lp = sdk_root / "local.properties"
    if lp.exists():
        # Check if it actually has sdk.dir set to a valid path.
        try:
            text = lp.read_text(encoding="utf-8", errors="ignore")
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith("sdk.dir=") and not stripped.startswith("sdk.dir=#"):
                    val = stripped.split("=", 1)[1].strip()
                    if val and Path(val.replace("/", os.sep)).is_dir():
                        return  # valid sdk.dir already set
        except Exception:
            pass
    sdk_dir = _resolve_android_sdk()
    if not sdk_dir:
        return
    # Normalise to forward slashes (Gradle properties file convention).
    sdk_dir_escaped = sdk_dir.replace("\\", "/")
    lp.write_text(
        "## Auto-generated by xg-glass CLI – do NOT commit.\n"
        f"sdk.dir={sdk_dir_escaped}\n",
        encoding="utf-8",
    )


def _ensure_project_sdk_dir(project: Path, sdk_dir: str) -> None:
    """Ensure project ``local.properties`` contains a valid ``sdk.dir``.

    If the file already has a valid ``sdk.dir``, this is a no-op.
    If the file exists but ``sdk.dir`` is missing/invalid, append it.
    If the file doesn't exist, create it.
    """
    lp = project / "local.properties"
    sdk_dir_escaped = sdk_dir.replace("\\", "/")
    if lp.exists():
        try:
            text = lp.read_text(encoding="utf-8", errors="ignore")
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith("sdk.dir=") and not stripped.startswith("sdk.dir=#"):
                    val = stripped.split("=", 1)[1].strip()
                    if val and Path(val.replace("/", os.sep)).is_dir():
                        return  # already valid
            # File exists but no valid sdk.dir – append.
            with lp.open("a", encoding="utf-8") as f:
                f.write(f"\nsdk.dir={sdk_dir_escaped}\n")
        except Exception:
            pass
        return
    # File does not exist – create a minimal one.
    lp.write_text(
        "## Auto-generated by xg-glass CLI – do NOT commit.\n"
        f"sdk.dir={sdk_dir_escaped}\n",
        encoding="utf-8",
    )


def _write_local_properties(project: Path, sdk_dir: str | None = None) -> None:
    if sdk_dir is None:
        sdk_dir = _resolve_android_sdk()
    lp = project / "local.properties"
    if sdk_dir:
        sdk_dir_escaped = sdk_dir.replace("\\", "/")
        lp.write_text(
            "## Auto-generated by xg-glass init\n"
            "## This file must *NOT* be checked into VCS.\n"
            f"sdk.dir={sdk_dir_escaped}\n"
            "\n"
            "## Rokid (optional): CXR-M v1.0.4 SN authorization\n"
            "## - Put sn_*.lc under app/src/main/res/raw/\n"
            "## - Set snRawName to the raw resource entry name (file name without extension)\n"
            "rokid.clientSecret=\n"
            "rokid.snRawName=\n",
            encoding="utf-8",
        )
    else:
        lp.write_text(
            "## Created by xg-glass init\n"
            "## Please set sdk.dir to your Android SDK location, e.g.:\n"
            "## sdk.dir=/Users/<you>/Library/Android/sdk\n"
            "\n"
            "## Rokid (optional): CXR-M v1.0.4 SN authorization\n"
            "## - Put sn_*.lc under app/src/main/res/raw/\n"
            "## - Set snRawName to the raw resource entry name (file name without extension)\n"
            "rokid.clientSecret=\n"
            "rokid.snRawName=\n",
            encoding="utf-8",
        )
