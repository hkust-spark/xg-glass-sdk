# Releasing xg.glass

This runbook covers publishing the xg.glass Android/Kotlin artifacts, Python CLI,
and iOS Swift Package assets.

## Published Coordinates

This repository publishes the Android/Kotlin SDK artifacts under:

- `io.github.hkust-spark:xgglass-universal:0.3.0`
- `io.github.hkust-spark:xgglass-core:0.3.0`
- `io.github.hkust-spark:xgglass-core-android:0.3.0`
- `io.github.hkust-spark:xgglass-app-contract:0.3.0`
- `io.github.hkust-spark:xgglass-device-rokid:0.3.0`
- `io.github.hkust-spark:xgglass-device-rayneo-installer:0.3.0`
- `io.github.hkust-spark:xgglass-device-rayneo-runtime:0.3.0`
- `io.github.hkust-spark:xgglass-device-inmo-runtime:0.3.0`
- `io.github.hkust-spark:xgglass-device-simulator:0.3.0`
- `io.github.hkust-spark:xgglass-device-omi:0.3.0`
- `io.github.hkust-spark:xgglass-device-even:0.3.0`
- `io.github.hkust-spark:xgglass-device-meta:0.3.0`
- `io.github.hkust-spark:xgglass-device-frame-flutter:0.3.0`

`xgglass-device-meta` is intentionally published as an optional artifact. The
aggregate `universal` artifact does not depend on it, so consumers can resolve
`io.github.hkust-spark:xgglass-universal:0.3.0` without access to the Meta
GitHub Packages repository. Consumers that explicitly add `xgglass-device-meta`
must also add Meta's GitHub Packages repository and provide a `read:packages`
token.

`device-android-xr` is a non-functional preview scaffold and is not published.

`universal-full` is a dev-only aggregate used by the CLI template through the
composite build. It depends on the published-shape `universal` module and, when
available in the SDK checkout, conditionally adds `device-meta` and the
embedded-Flutter Frame adapter. It is not published to Maven Central.

The `core` Kotlin Multiplatform publication also emits platform artifacts such
as `core-kmp-android`, `core-iosarm64`, and `core-iossimulatorarm64` as Gradle
metadata targets. Android consumers should depend on the public coordinates
above instead of adding those auxiliary artifacts directly. The iOS device
adapters continue to ship through the Swift package, not Maven Central.
The Even G1 adapter is also a Kotlin Multiplatform publication; publishing it
emits auxiliary Gradle metadata variants such as `xgglass-device-even-android`,
`xgglass-device-even-iosarm64`, and `xgglass-device-even-iossimulatorarm64`.
Consumers should still use `xgglass-device-even` on Android and the Swift
package/XgGlassKit umbrella on iOS.

The Swift package dependency form is:

```swift
.package(url: "https://github.com/hkust-spark/xg-glass-sdk", from: "0.3.0")
```

## 0. Prerequisites

Configure Maven Central Portal credentials and signing material in
`~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=...
mavenCentralPassword=...
signingInMemoryKey=...
signingInMemoryKeyPassword=...
xgGlassSignPublications=true
```

Also prepare:

- PyPI token for `twine upload`.
- `gh` authenticated for the `hkust-spark/xg-glass-sdk` repository.
- A GPG key whose public key has been published to public keyservers.
- Optional Meta GitHub Packages token (`github_token` Gradle property or
  `GITHUB_TOKEN`) for local Meta adapter verification.

## 1. Bump Version

```bash
scripts/bump-version.sh <version>
```

Then add the manual CHANGELOG entry for the release.

- Update `docs/ai-assistant-guide.md` and `CHANGELOG.md` for any new devices or APIs in the release.

Regenerate the AI assistant source bundle after version or docs updates; CI
checks that this file is current:

```bash
python3 scripts/gen_llms_full.py
```

## 2. Full Verification

Run the Android/Kotlin compile and unit-test matrix:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew --console=plain \
    :core:assembleAndroidMain \
    :app-contract:assembleAndroidMain \
    :core-android:compileDebugKotlin \
    :universal:compileDebugKotlin \
    :universal-full:compileDebugKotlin \
    :device-android-xr:compileDebugKotlin \
    :device-even:assembleAndroidMain \
    :device-frame-embedded:compileDebugKotlin \
    :device-frame-flutter:compileDebugKotlin \
    :device-inmo-runtime:compileDebugKotlin \
    :device-omi:compileDebugKotlin \
    :device-rayneo-installer:compileDebugKotlin \
    :device-rayneo-runtime:compileDebugKotlin \
    :device-rokid:compileDebugKotlin \
    :device-simulator:compileDebugKotlin \
    :device-even:testAndroidHostTest \
    :device-inmo-runtime:testDebugUnitTest \
    :device-rayneo-runtime:testDebugUnitTest \
    :device-simulator:testDebugUnitTest \
    :app-contract:testAndroidHostTest \
    :core:testAndroidHostTest \
    :core-android:testDebugUnitTest
```

Run the iOS/Kotlin tests and framework assembly:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew --console=plain \
    :core:iosSimulatorArm64Test \
    :device-even:iosSimulatorArm64Test \
    :device-omi-ios:iosSimulatorArm64Test \
    :app-contract:assembleXgGlassKitXCFramework
```

Run the CLI pytest suite, then build and check the CLI wheel:

```bash
cd tools
PYTHONPATH=. python -m pytest -q tests
python -m build
python -m twine check dist/*
cd ..
```

If Homebrew Python or another system Python does not have the `build` module
available, use the equivalent isolated `uvx` invocations instead:

```bash
cd tools
PYTHONPATH=. uvx pytest -q tests
uvx --from build pyproject-build
uvx twine check dist/*
cd ..
```

## 3. Maven Central

Publish to the Central Portal staging repository:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew publishToMavenCentral
```

Review the staged deployment in the Central Portal, then publish it. Maven
Central artifacts are immutable after publishing.

## 4. PyPI

Upload the checked CLI distributions from `tools/`:

```bash
cd tools
python -m twine upload dist/*
cd ..
```

## 5. iOS Swift Package

Build the XCFramework release archive:

```bash
scripts/package-xcframework-release.sh
```

Edit `Package.swift` with the release tag URL and the generated checksum. Commit
the release changes, tag that exact commit with the plain semver version, push
the commit and tag, then create the GitHub Release and upload the XCFramework
zip:

```bash
git tag <version>
git push origin main <version>
gh release create <version> artifacts/XgGlassKit.xcframework.zip --title <version> --notes-file CHANGELOG.md
```

After the release is live, verify from a clean directory:

```bash
swift package init --type executable
swift package resolve
```

On this build host, `swift package resolve` can fail over non-interactive SSH
with keychain `status -25308` when the login keychain is locked. In that case,
the equivalent verification is to download the `XgGlassKit.xcframework.zip`
release asset and compare its SHA-256 against the checksum committed in
`Package.swift`.

## 6. Post-Release Verification

Maven Central sync to `repo1.maven.org` is delayed, usually by about 15-60
minutes. After sync, verify:

```bash
python -m pip install --upgrade xg-glass
xg-glass --help
```

Also verify the released coordinates on Central Search and the artifacts under
`https://repo1.maven.org/maven2/io/github/hkust-spark/`.

## 7. Tag Convention

Use a plain semver tag such as `0.2.0`. Do not prefix tags with `v`.
