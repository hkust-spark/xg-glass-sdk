# Contributing to xg.glass

Thanks for helping improve xg.glass. This SDK spans Android, iOS, Python CLI tooling, and vendor device adapters, so focused pull requests with clear validation notes are easiest to review.

## Start Without Glasses

You can do a full app-development loop with zero glasses by using the simulator backend. The CLI can generate a simulator-only host app, and the `run` command exposes `--sim`, `--local_video`, and `--video_url` for emulator-based runs.

```bash
rm -rf /tmp/xg-sim-loop
./xg-glass init /tmp/xg-sim-loop --devices simulator --sim --no-shell-setup
cd /tmp/xg-sim-loop
./gradlew --console=plain :app:assembleDebug
```

```bash
./xg-glass run --help
```

When an Android Emulator is available, use the `run` command's `--sim` flag and optionally `--local_video` to feed `capturePhoto()` from a video file instead of camera hardware.

## Development Environment

Use the current checked-in toolchain:

- Gradle wrapper: `9.3.1`.
- Android Gradle Plugin: `9.1.1`.
- Kotlin Gradle plugin: `2.4.0`.
- Android compileSdk: `36`; minSdk: `28`, except Meta and the RayNeo glasses host require minSdk `29`.
- JDK 17 or newer. The Android Studio bundled JBR is known to work.
- Python 3.9+ for the CLI.
- macOS plus Xcode 15.4+ for iOS/Kotlin and Swift Package work.

The Android Studio JBR path used by maintainers on this Mac is `/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

## Optional Device Gates

- Meta Android depends on Meta DAT artifacts hosted on GitHub Packages. If you have access, put a GitHub token with `read:packages` in `~/.gradle/gradle.properties` as `github_token=...`. Without the token, the Gradle settings auto-exclude `:device-meta`.
- Frame embedded Android work needs Flutter because the runtime uses Flutter add-to-app. Maven consumers get the Frame bridge API; the current working Frame integration uses the source/CLI flow.
- RayNeo host builds need the vendor AARs described in `third_party/rayneo/aar/README.md`.

## Build and Test

Android/shared Kotlin compile and unit-test matrix:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew --console=plain \
    :core:assembleAndroidMain \
    :core-android:compileDebugKotlin \
    :app-contract:assembleAndroidMain \
    :universal:compileDebugKotlin \
    :universal-full:compileDebugKotlin \
    :device-rokid:compileDebugKotlin \
    :device-even:assembleAndroidMain \
    :device-omi:compileDebugKotlin \
    :device-simulator:compileDebugKotlin \
    :device-rayneo-installer:compileDebugKotlin \
    :device-rayneo-runtime:compileDebugKotlin \
    :device-inmo-runtime:compileDebugKotlin \
    :device-android-xr:compileDebugKotlin \
    :device-frame-flutter:compileDebugKotlin \
    :device-inmo-runtime:testDebugUnitTest \
    :device-even:testAndroidHostTest \
    :app-contract:testAndroidHostTest \
    :core-android:testDebugUnitTest \
    :core:testAndroidHostTest
```

iOS/Kotlin tests and XCFramework assembly:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew --console=plain \
    :core:iosSimulatorArm64Test \
    :device-even:iosSimulatorArm64Test \
    :device-omi-ios:iosSimulatorArm64Test \
    :app-contract:assembleXgGlassKitXCFramework
```

CLI install, tests, and smoke checks:

```bash
rm -rf /tmp/xg-cli-venv
python3 -m venv /tmp/xg-cli-venv
/tmp/xg-cli-venv/bin/python -m pip install -e "tools[dev]"
/tmp/xg-cli-venv/bin/python -m pytest tools/tests -q
./xg-glass --help
./xg-glass run --help
```

Generated-app guard:

```bash
rm -rf /tmp/xg-generated-check
./xg-glass init /tmp/xg-generated-check --sim --no-shell-setup
cd /tmp/xg-generated-check
./gradlew --console=plain :app:assembleDebug
```

Run the smallest relevant subset while developing, then include the exact command output or a short summary in the PR. iOS sample tests use the workspace flow documented in `samples/ios/README.md`.

## Adding a Device Adapter

Start with the porting guide: [Adding a Device Adapter](docs/adding-a-device-adapter.md).

Before starting an iOS adapter, also read `docs/ios-device-support.md`. Open BLE or public vendor SDKs are candidates; Wi-Fi Direct, adb-only flows, or closed licensed transports may be platform-gated.

Community device contributions are welcome. Good starter tasks are tagged with the [good first issue](https://github.com/hkust-spark/xg-glass-sdk/labels/good%20first%20issue) label. If you can validate hardware, join the call-for-testers thread: https://github.com/hkust-spark/xg-glass-sdk/issues/63.

For a new device proposal, include:

- Vendor and model.
- Transport details: BLE, Wi-Fi, vendor SDK, on-glasses Android, or other.
- Links to public SDKs, protocol docs, official demos, or source-traced notes.
- Which capabilities are exposed: camera, microphone, display, speaker, tap, long-press.
- Whether you can test on hardware.

## Code Style

- Use Kotlin official style.
- Match surrounding code and module conventions.
- Keep docs, comments, commit messages, and user-facing artifacts in English.
- Keep vendor-specific logic inside the matching `devices/device-<vendor>` module.
- Keep permissions self-contained: a device module manifest should declare the permissions its code uses.

## Commit Messages

Use Conventional Commits. Existing examples include:

- `feat(omi-ios): ...`
- `build(publish)!: ...`
- `release(ios): ...`
- `docs(api): ...`

Use `!` for breaking changes and explain the migration in the commit body or PR description.

## Pull Request Expectations

- Explain what changed and why.
- Link the relevant issue when one exists.
- Keep PRs focused; split unrelated device, docs, and tooling work.
- Add or update tests for new behavior.
- Update documentation for user-facing changes.
- Use a Conventional Commit style PR title.
- Make sure the relevant CI jobs are green: Android, generated-app, CLI/Python 3.9 and 3.12, iOS Kotlin on main, Meta Android on main when the token is configured, and Docs on release tags.
- Respect both guards: the generated-app guard catches template fallout, and the iOS deps guard (`.github/workflows/ci-ios-deps.yml`) catches Kotlin/Native dependency regressions on relevant PRs.
