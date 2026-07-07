# Play Feature Delivery for Heavy Android Adapters

This guide is for Play-distributed Android apps that want Frame and/or Meta support without putting those heavy adapters in the base install. It builds on the measured [app size spike](app-size-spike.md) and the selected-adapter workflow in the [AI Assistant Guide](ai-assistant-guide.md).

## When To Use It

Use Play Feature Delivery only when the app is distributed through Google Play and the user may never need the heavy adapter. The measured arm64 release APK numbers are the reason: the default generated `universal-full` app was 49.62 MiB, removing embedded Frame/Flutter saved 14.56 MiB, Meta added 15.88 MiB relative to the 17.66 MiB lean floor, and small adapters such as Even, Omi, and RayNeo runtime were cheap enough to keep in the base module.

For sideloaded APKs, enterprise installs, non-Play stores, or apps that target only one glasses family, prefer per-device artifacts or the CLI's selected-adapter generation:

```sh
xg-glass init /path/to/myapp --devices even,simulator
```

That path is simpler because normal Gradle dependencies own versioning, resources, native libraries, and permissions. PFD is an advanced consuming-app layout; the SDK cannot impose it from a library artifact.

## Module Layout

Keep the base `:app` small and put each heavy optional adapter in one dynamic feature module. The base module depends only on always-on SDK pieces:

```kotlin
// app/build.gradle.kts
android {
    dynamicFeatures += setOf(":feature:frame", ":feature:meta")
}

dependencies {
    implementation("io.github.hkust-spark:xgglass-core:0.2.0")
    implementation("io.github.hkust-spark:xgglass-core-android:0.2.0")
    implementation("io.github.hkust-spark:xgglass-app-contract:0.2.0")
    implementation("io.github.hkust-spark:xgglass-device-even:0.2.0")
    implementation("io.github.hkust-spark:xgglass-device-simulator:0.2.0")
    implementation("com.google.android.play:feature-delivery-ktx:2.1.0")
}
```

Wire the feature modules in settings:

```kotlin
// settings.gradle.kts
include(":app")
include(":feature:frame")
include(":feature:meta")
```

Each feature applies the dynamic-feature plugin and depends on the base app plus the heavy adapter it owns:

```kotlin
// feature/meta/build.gradle.kts
plugins {
    id("com.android.dynamic-feature")
    kotlin("android")
}

android {
    namespace = "com.example.xgglass.feature.meta"
}

dependencies {
    implementation(project(":app"))
    implementation("io.github.hkust-spark:xgglass-device-meta:0.2.0")
}
```

```kotlin
// feature/frame/build.gradle.kts
plugins {
    id("com.android.dynamic-feature")
    kotlin("android")
}

android {
    namespace = "com.example.xgglass.feature.frame"
}

dependencies {
    implementation(project(":app"))
    implementation("io.github.hkust-spark:xgglass-device-frame-embedded:0.2.0")
    // Not on public Maven in 0.2.0; resolves only through the SDK composite build in source/CLI flow.
}
```

Meta uses the published optional artifact `io.github.hkust-spark:xgglass-device-meta:0.2.0` and still needs Meta's GitHub Packages repository/token. Frame is different in 0.2.0: public Maven publishes `io.github.hkust-spark:xgglass-device-frame-flutter:0.2.0`, which is the bridge API, while the working embedded runtime is the source module `:device-frame-embedded` plus the app-hosted Flutter module.

Each feature manifest declares on-demand delivery:

```xml
<manifest
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:dist="http://schemas.android.com/apk/distribution">

    <dist:module
        dist:instant="false"
        dist:title="@string/feature_meta_name">
        <dist:delivery>
            <dist:on-demand />
        </dist:delivery>
        <dist:fusing dist:include="true" />
    </dist:module>
</manifest>
```

## Runtime Loading

The base module should know module names and class names, but not import feature classes. Install the split first, call `SplitCompat.install(...)` if you need same-process access immediately, then instantiate through reflection:

```kotlin
enum class OptionalAdapter(val moduleName: String, val className: String) {
    FRAME(
        moduleName = "frame",
        className = "com.xgglass.device.frame.embedded.EmbeddedFrameGlassesClient",
    ),
    META(
        moduleName = "meta",
        className = "com.xgglass.device.meta.MetaWearablesGlassesClient",
    ),
}

fun installAdapter(manager: SplitInstallManager, adapter: OptionalAdapter) {
    val request = SplitInstallRequest.newBuilder()
        .addModule(adapter.moduleName)
        .build()
    manager.startInstall(request)
}

fun createInstalledAdapter(activity: AppCompatActivity, adapter: OptionalAdapter): GlassesClient {
    val clazz = Class.forName(adapter.className)
    return when (adapter) {
        OptionalAdapter.FRAME -> {
            clazz.getConstructor(Context::class.java)
                .newInstance(activity.applicationContext) as GlassesClient
        }
        OptionalAdapter.META -> {
            clazz.getConstructor(AppCompatActivity::class.java)
                .newInstance(activity) as GlassesClient
        }
    }
}
```

The entry classes above exist in the SDK sources as `EmbeddedFrameGlassesClient` for Frame and `MetaWearablesGlassesClient` for Meta.

## Limitations And Verification

PFD is Play-only. Sideloaded APKs do not get the same on-demand behavior unless you test an app bundle with `bundletool build-apks --local-testing`, and non-Play stores require a different delivery mechanism. SplitCompat is required when you need feature code available in the same process immediately after install.

This repository has not shipped a production Play app with this layout. Treat it as a constraint-checked recipe, then validate your real app bundle and real hardware. Frame and Meta behavior still needs hardware validation for your product; see the active tester call in [issue #63](https://github.com/hkust-spark/xg-glass-sdk/issues/63).

Recommended local checks:

```sh
./gradlew :app:bundleRelease
bundletool build-apks \
    --bundle app/build/outputs/bundle/release/app-release.aab \
    --local-testing \
    --output /tmp/xgglass.apks
bundletool install-apks --apks /tmp/xgglass.apks
```

After install, exercise both paths: launch without the feature installed to confirm graceful degradation, request the feature, then instantiate the adapter and run the same `GlassesClient` smoke tests you use for the base app.
