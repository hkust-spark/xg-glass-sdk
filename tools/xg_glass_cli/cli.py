from __future__ import annotations

import argparse
import os
import subprocess
import sys

from . import commands as _commands
from .constants import DEFAULT_CONFIG_FILE, CliUsageError
from .doctor import run_doctor


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="xg-glass",
        description="xg-glass: build/install/run xg.glass-based Android host apps (MVP).",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_init = sub.add_parser("init", help="Create a new dev project from template (includeBuild-based).")
    p_init.add_argument("dir", help="Target directory for the new project.")
    p_init.add_argument(
        "--template",
        help="Template project directory (default: <resolved-sdk>/templates/kotlin-app).",
    )
    p_init.add_argument(
        "--sdk",
        help="Path to the SDK repo root (default: this repo when invoked from a checkout).",
    )
    p_init.add_argument(
        "--entry-class",
        default="com.example.xgglassapp.logic.ExampleAppEntry",
        help="Fully-qualified UniversalAppEntry class name (default: com.example.xgglassapp.logic.ExampleAppEntry).",
    )
    p_init.add_argument(
        "--sim",
        action="store_true",
        help="Initialize the project in simulator mode (enables x86_64 + simulator backend).",
    )
    p_init.add_argument(
        "--devices",
        help=(
            "Comma-separated devices to include: rokid, rayneo, meta, frame, omi, even, "
            "inmo, simulator, or all (default). --sim adds simulator."
        ),
    )
    p_init.add_argument(
        "--no-shell-setup",
        action="store_true",
        help="Do not update shell startup files; print export lines to add manually instead.",
    )

    p_build = sub.add_parser("build", help="Build the phone-side APK.")
    _add_common_project_args(p_build)
    p_build.add_argument("--config", default=DEFAULT_CONFIG_FILE, help="Config file name/path (default: xg-glass.yaml).")
    p_build.add_argument("--entry-class", help="Override entry class (optional).")
    p_build.add_argument("--sdk", help="Override sdkPath (optional).")
    p_build.add_argument("--rayneo-aar-dir", help="Override RayNeo mercuryAarDir (optional).")

    p_install = sub.add_parser("install", help="Install the phone-side APK via adb.")
    _add_common_project_args(p_install)
    p_install.add_argument("--config", default=DEFAULT_CONFIG_FILE, help="Config file name/path (default: xg-glass.yaml).")
    p_install.add_argument("--serial", help="adb device serial (optional).")
    p_install.add_argument("--apk", help="Explicit APK path (optional).")

    p_run = sub.add_parser("run", help="Launch the phone-side app via adb.")
    _add_common_project_args(p_run)
    p_run.add_argument("--config", default=DEFAULT_CONFIG_FILE, help="Config file name/path (default: xg-glass.yaml).")
    p_run.add_argument("--serial", help="adb device serial (optional).")
    p_run.add_argument("--package", help="Override applicationId/package (optional).")
    p_run.add_argument("kt_file", nargs="?", help="Quick mode: a Kotlin entry file (.kt).")
    p_run.add_argument("--save", help="Quick mode: save the generated project to this directory.")
    p_run.add_argument("--keep-tmp", action="store_true", help="Quick mode: keep the temporary project directory.")
    p_run.add_argument("--entry-class", help="Quick mode: override inferred entry class (optional).")
    p_run.add_argument("--sdk", help="Quick mode: override sdkPath (optional).")
    p_run.add_argument("--sim", action="store_true", help="Build for Android Emulator (x86_64) and enable simulator backend.")
    p_run.add_argument("--local_video", help="(sim mode) Local video file path to use as capturePhoto source.")
    p_run.add_argument("--video_url", help="(sim mode) Video URL (YouTube/Bilibili) to download and use as capturePhoto source.")

    p_doctor = sub.add_parser("doctor", help="Diagnose the local xg-glass CLI environment.")
    p_doctor.add_argument("--offline", action="store_true", help="Skip best-effort network checks.")

    args = parser.parse_args(argv)

    # Validate: --local_video and --video_url require --sim.
    if args.cmd == "run":
        has_video = getattr(args, "local_video", None) or getattr(args, "video_url", None)
        if has_video and not getattr(args, "sim", False):
            parser.error("--local_video and --video_url require --sim mode.")

    try:
        if args.cmd == "init":
            return cmd_init(args)
        if args.cmd == "build":
            return cmd_build(args)
        if args.cmd == "install":
            return cmd_install(args)
        if args.cmd == "run":
            return cmd_run(args)
        if args.cmd == "doctor":
            return cmd_doctor(args)
        raise RuntimeError(f"Unknown command: {args.cmd}")
    except subprocess.CalledProcessError as e:
        print(e, file=sys.stderr)
        return e.returncode or 1
    except CliUsageError as e:
        print(e, file=sys.stderr)
        return 2
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1


def _add_common_project_args(p: argparse.ArgumentParser) -> None:
    p.add_argument(
        "--project",
        default=os.getcwd(),
        help="Android project root (default: current directory).",
    )
    p.add_argument("--variant", default="debug", help="Build variant (default: debug).")
    p.add_argument("--module", default="app", help="Android application module name (default: app).")


def cmd_init(args: argparse.Namespace) -> int:
    return _commands.cmd_init(args)


def cmd_build(args: argparse.Namespace) -> int:
    return _commands.cmd_build(args)


def cmd_install(args: argparse.Namespace) -> int:
    return _commands.cmd_install(args)


def cmd_run(args: argparse.Namespace) -> int:
    return _commands.cmd_run(args)


def cmd_doctor(args: argparse.Namespace) -> int:
    return run_doctor(offline=getattr(args, "offline", False))


if __name__ == "__main__":
    raise SystemExit(main())
