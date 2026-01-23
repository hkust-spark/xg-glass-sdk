# xg-glass

This is a minimal command-line tool for driving an Android host project based on `universal_glasses`:

- `xg-glass init <dir>`: generate a developer project from a template (default: `./templates/kotlin-app`, using `includeBuild`)
- `xg-glass build`: build the phone host APK (for RayNeo, it will auto-generate and package the glasses host APK into assets before building)
- `xg-glass install`: install onto the phone via `adb install`
- `xg-glass run`: launch the app via `adb shell monkey`

## Typical usage (same repo)

From the repository root (where `./xg-glass` lives):

- `./xg-glass init /path/to/myapp`
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
- `--sim`: build an emulator-compatible APK (x86_64) and enable simulator backend

### Options

- `--project`: specify the project root (default: current directory)
- `--variant`: default `debug`
- `--serial`: specify the adb device serial (optional)


