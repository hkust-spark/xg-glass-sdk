from __future__ import annotations

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SDK = REPO_ROOT
DEFAULT_TEMPLATE = REPO_ROOT / "templates" / "kotlin-app"
DEFAULT_CONFIG_FILE = "xg-glass.yaml"

# Managed SDK locations
_XG_GLASS_HOME = Path.home() / ".xg-glass"
_MANAGED_FLUTTER_DIR = _XG_GLASS_HOME / "flutter"
_MANAGED_JDK_DIR = _XG_GLASS_HOME / "jdk"
_MANAGED_ANDROID_SDK_DIR = _XG_GLASS_HOME / "android-sdk"

# Highest JDK major version known to work with the project's AGP / Gradle toolchain.
# JDK 25 (LTS, Sep 2025) is too new for AGP 8.13.1 / Gradle 8.13 and causes a bare
# "25.0.2" build error.  Bump this constant when upgrading AGP to a version that supports it.
_MAX_AGP_JDK_MAJOR = 21

# Default Android SDK packages required for building.
_ANDROID_SDK_PACKAGES = [
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0",
]

# Well-known path on the emulator/device where the CLI pushes the video file.
_DEVICE_VIDEO_PATH = "/data/local/tmp/xg_glass_sim_video.mp4"


class CliUsageError(RuntimeError):
    """User-facing CLI error that should exit without a traceback."""


def missing_sdk_checkout_message(subcommand: str) -> str:
    if subcommand == "init":
        command = (
            "xg-glass init ... --sdk /path/to/xg-glass-sdk "
            "--template /path/to/xg-glass-sdk/templates/kotlin-app"
        )
    elif subcommand == "run":
        command = "xg-glass run /path/to/MyEntry.kt --sdk /path/to/xg-glass-sdk"
    else:
        command = f"xg-glass {subcommand} ... --sdk /path/to/xg-glass-sdk"
    return (
        "xg-glass was installed without an SDK checkout (pip install). "
        f"The '{subcommand}' command needs the xg-glass-sdk repository:\n"
        "  git clone https://github.com/hkust-spark/xg-glass-sdk\n"
        f"  {command}\n"
        "Commands that read xg-glass.yaml inside an already-generated project "
        "(build/install/run) work without --sdk."
    )
