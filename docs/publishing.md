# Publishing Android Artifacts

This repository publishes the Android/Kotlin SDK artifacts under:

- `io.github.hkust-spark:xgglass-universal:0.1.0`
- `io.github.hkust-spark:xgglass-core:0.1.0`
- `io.github.hkust-spark:xgglass-core-android:0.1.0`
- `io.github.hkust-spark:xgglass-app-contract:0.1.0`
- `io.github.hkust-spark:xgglass-device-rokid:0.1.0`
- `io.github.hkust-spark:xgglass-device-rayneo-installer:0.1.0`
- `io.github.hkust-spark:xgglass-device-rayneo-runtime:0.1.0`
- `io.github.hkust-spark:xgglass-device-simulator:0.1.0`
- `io.github.hkust-spark:xgglass-device-omi:0.1.0`
- `io.github.hkust-spark:xgglass-device-meta:0.1.0`
- `io.github.hkust-spark:xgglass-device-frame-flutter:0.1.0`

`xgglass-device-meta` is intentionally published as an optional artifact. The aggregate
`universal` artifact does not depend on it, so consumers can resolve
`io.github.hkust-spark:xgglass-universal:0.1.0` without access to the Meta GitHub
Packages repository. Consumers that explicitly add `xgglass-device-meta` must
also add Meta's GitHub Packages repository and provide a `read:packages` token.

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

## One-Time Release Setup

1. Create or access the Maven Central Portal account at
   `https://central.sonatype.com`.
2. Ensure the `io.github.hkust-spark` namespace is verified for the
   `hkust-spark` GitHub organization.
3. Generate a GPG signing key and publish the public key to the usual public
   keyservers.
4. Add the Central Portal credentials and signing material to
   `~/.gradle/gradle.properties`. Do not commit these values.

Example property names:

```properties
mavenCentralUsername=...
mavenCentralPassword=...
signingInMemoryKey=...
signingInMemoryKeyPassword=...
xgGlassSignPublications=true
```

The local verification path does not sign artifacts. The
`xgGlassSignPublications=true` property is only for an actual release run where
the signing key properties are present.

## Commands

Local verification:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  templates/kotlin-app/gradlew -p /Users/spark/jiayang/xg-glass-sdk publishToMavenLocal
```

Release upload, after credentials, signing, and license metadata are finalized:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  templates/kotlin-app/gradlew -p /Users/spark/jiayang/xg-glass-sdk publishAndReleaseToMavenCentral
```

If using the lower-level Vanniktech task names directly, publish to the Central
Portal staging repository first with `publishAllPublicationsToMavenCentralRepository`
and then release from the portal workflow.
