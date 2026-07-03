# Contributing to xg.glass

Thanks for helping improve xg.glass. This SDK spans Android, iOS, Python CLI tooling, and vendor device adapters, so focused pull requests with clear validation notes are easiest to review.

## Development Environment

### Android and shared Kotlin

- JDK 17 or newer. The Android Studio bundled JBR is known to work.
- Android SDK and platform tools.
- Use the root Gradle wrapper. The SDK publishes Maven artifacts under `io.github.hkust-spark:xgglass-*:0.1.0`.

### iOS

- macOS.
- Xcode 15.4 or newer.
- JDK 17+ and the Android SDK, because the shared XCFramework is produced from the Kotlin/Gradle build.
- The Swift Package products are `XgGlass`, `XgGlassMeta`, and `XgGlassMetaTesting`, and target iOS 16+.

The iOS sample workflow is documented in `samples/ios/README.md`; open the workspace, not the project file.

### Python CLI

```bash
pip install -e tools/
./xg-glass --help
```

The published CLI package is `xg-glass` on PyPI.

## Optional Device Gates

- Meta: the Android module depends on Meta DAT artifacts hosted on GitHub Packages. If you have access, put a GitHub token with `read:packages` in `~/.gradle/gradle.properties`:

```properties
github_token=ghp_xxxxxxxxxxxxx
```

Without the token, the build auto-excludes `:device-meta`.

- Frame: the embedded Frame integration needs the Flutter SDK. If Flutter is unavailable, Frame embedded work is auto-gated. Maven consumers get the Frame bridge API, while the working Frame integration currently uses the source/CLI flow.

## Build and Test

Build Android artifacts:

```bash
./gradlew :universal:assembleRelease
```

Run Android/shared JVM unit tests:

```bash
./gradlew :app-contract:testDebugUnitTest :core-android:testDebugUnitTest
```

Build the iOS XCFramework on macOS:

```bash
scripts/build-xcframework.sh
```

Run iOS shared tests on macOS:

```bash
./gradlew :core:iosSimulatorArm64Test :device-omi-ios:iosSimulatorArm64Test
```

Check the CLI entry point:

```bash
pip install -e tools/
xg-glass --help
```

iOS sample tests use the workspace flow documented in `samples/ios/README.md`. Run the smallest relevant test set for your change, and include the command output or a short summary in the PR.

## Code Style

- Use Kotlin official style.
- Match surrounding code and module conventions.
- Keep docs, comments, commit messages, and user-facing artifacts in English.
- Keep vendor-specific logic inside the matching `devices/device-<vendor>` module.

## Commit Messages

Use Conventional Commits. The existing history uses this format consistently, for example:

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
- Ensure the relevant CI jobs and local tests are green.
- Use a Conventional Commit style PR title.

## Adding a New Device Adapter

Device adapters live under `devices/device-<vendor>`. Android adapters implement `GlassesClient` in their device module; iOS adapters are added where the transport is feasible.

Before starting an iOS adapter, read `docs/ios-device-support.md`. It explains the feasibility framework: open BLE or public vendor SDKs are candidates; Wi-Fi Direct, adb-only flows, or closed licensed transports may be platform-gated.

Community device contributions are welcome. Please include:

- Vendor and model.
- Transport details: BLE, Wi-Fi, vendor SDK, on-glasses Android, or other.
- Links to public SDKs or protocol docs.
- Which capabilities are exposed: camera, microphone, display, speaker.
- Whether you can test on hardware.

Maintainers will run hardware validation where possible, because many contributors will not have every device in hand.
