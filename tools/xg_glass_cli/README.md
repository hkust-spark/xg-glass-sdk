# xg-glass

This is a minimal command-line tool for driving an Android host project based on xg.glass:

- `xg-glass init <dir>`: generate a developer project from a template (default: `./templates/kotlin-app`, using `includeBuild`)
- `xg-glass build`: build the phone host APK (for RayNeo, it will auto-generate and package the glasses host APK into assets before building)
- `xg-glass install`: install onto the phone via `adb install`
- `xg-glass run`: launch the app via `adb shell monkey`
- `xg-glass doctor`: diagnose Java, Android SDK, adb, emulator, Flutter, SDK cache, and network setup

## Install from PyPI

```sh
pip install xg-glass
```

From PyPI, commands that operate inside an already-generated project work out of the box because they read `xg-glass.yaml`:

```sh
cd /path/to/generated-project
xg-glass build
xg-glass install
xg-glass run
```

For generated simulator projects, `xg-glass run --sim` now performs the simulator one-shot flow: it applies simulator build settings, builds, starts an Android Emulator when the selected serial is not already online, installs the APK, and launches it. `--local_video /path/to/video.mp4` and `--video_url <url>` use the same simulator video path as quick mode.

Set `XG_EMULATOR_ARGS` to append extra flags to the auto-started emulator command. For headless CI runners, use `XG_EMULATOR_ARGS="-no-window -gpu swiftshader_indirect -no-audio -no-boot-anim"`.

Commands that create or synthesize a project (`xg-glass init` and `xg-glass run <file.kt>`) download the matching `xg-glass-sdk` release on first use and cache it under `~/.xg-glass/sdk/`:

```sh
xg-glass init /path/to/myapp
xg-glass run /path/to/MyEntry.kt
```

Pass `--sdk /path/to/xg-glass-sdk` to use an existing checkout instead of the cached download. If the first-run download fails because you are offline or the matching tag is unavailable, retry when online or pass `--sdk` explicitly.

## Doctor

Run `xg-glass doctor` when setup, emulator boot, or build tooling fails. It prints the same Java, Android SDK, adb, emulator, Flutter, and SDK paths that the CLI commands would use, plus one-line hints.

```text
[ OK   ] python         Python 3.12.0 at /usr/bin/python3 Hint: Python >=3.9 satisfies the CLI requirement.
[ WARN ] android-sdk    /Users/me/Library/Android/sdk is partially provisioned; missing sdkmanager. Hint: Run sdkmanager for missing packages; xg-glass can auto-provision its managed SDK.
Summary: 0 FAIL, 1 WARN, 10 checks
```

Use `xg-glass doctor --offline` to skip the best-effort download-host checks.

`xg-glass init` defaults to all supported Android devices for demos and zero-config exploration. For production-sized generated apps, pass `--devices` with a comma-separated list:

```sh
xg-glass init /path/to/myapp --devices even,simulator
xg-glass init /path/to/myapp --devices rokid,rayneo
xg-glass init /path/to/myapp --devices frame,simulator
```

Valid values are `rokid`, `rayneo`, `meta`, `frame`, `omi`, `even`, `inmo`, `simulator`, and `all`. Device names are case-insensitive. `--sim` still enables emulator build settings and also adds `simulator` to any concrete `--devices` selection.

## From a repository checkout (contributors)

From the repository root (where `xg-glass` lives):

- `xg-glass init /path/to/myapp`
- `cd /path/to/myapp`
- `<path-to-sdk-repo>/xg-glass build`
- `<path-to-sdk-repo>/xg-glass install`
- `<path-to-sdk-repo>/xg-glass run`

## xg-glass.yaml (scalable)

`xg-glass init` generates `xg-glass.yaml` in the project root, and `xg-glass build/install/run` will read it automatically:

- `sdkPath`
- `entryClass`
- `rayneoMercuryAarDir`
- `variant`
- `module`
- `applicationId`
- `devices` (written when `--devices` is provided, for example `[even, simulator]`)

## Bare-file quick mode (Quick mode)

You can use a single `.kt` file to trigger "temporary init → build → install → run":

- `xg-glass run /path/to/MyEntry.kt`

Constraints:

- The `.kt` file must contain a `package ...` line
- The `.kt` file must contain a top-level `class`/`object` (used to infer the entry class)

Optional:

- `--entry-class <fqcn>`: skip inference and specify the entry class explicitly
- `--sdk <path/to/sdk-repo>`: specify the SDK path
- `--save ./myapp`: persist the temporary project as a real project (so you can continue development)
- `--keep-tmp`: keep the temporary directory for debugging
- `--sim`: build a simulator-compatible APK (x86_64) and enable simulator backend
- `--devices <list>`: quick mode only; include only selected device adapters in the generated project
- `--local_video <mp4>` / `--video_url <url>`: in `--sim` mode, feed simulator `capturePhoto()` from a local/downloaded video

Example:

```bash
xg-glass run --sdk /path/to/xg-glass-sdk --sim --devices simulator /path/to/MyEntry.kt
```

### Options

- `--project`: specify the project root (default: current directory)
- `--variant`: default `debug`
- `--serial`: specify the adb device serial (optional)
