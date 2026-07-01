# KMP Migration Feasibility for xg-glass-sdk

## Executive Summary

Recommendation: use Kotlin Multiplatform for the SDK's shared API, app contract, and host/device orchestration logic, but keep actual glasses transport implementations platform-specific.

KMP is the best cross-platform path for this codebase because the core abstraction is already Kotlin-first and small. `GlassesClient` is a pure interface over connection state, events, photo capture, text display, audio playback, and microphone sessions in `core/src/main/java/com/xgglass/core/GlassesClient.kt:14-57`. The app developer surface in `app-contract/src/main/java/com/xgglass/appcontract/UniversalAppEntry.kt:173-198` is also mostly plain Kotlin.

KMP is not a magic "one implementation runs everywhere" path for hardware support. The current device modules are Android libraries in `settings.gradle.kts:57-69`, and their transport code is dominated by Android SDKs, Android Bluetooth, Camera2, Android media, Flutter embedding, and vendor AARs. For example, Rokid uses Android Bluetooth plus Rokid CXR-M AAR APIs in `devices/device-rokid/src/main/java/com/xgglass/device/rokid/RokidGlassesClient.kt:3-21`, Meta uses Meta DAT Android APIs in `devices/device-meta/src/main/java/com/xgglass/device/meta/MetaWearablesGlassesClient.kt:17-29`, and RayNeo runtime code runs inside an Android glasses app with Camera2/AudioManager/Toast in `devices/device-rayneo-runtime/src/main/java/com/xgglass/device/rayneo/runtime/RayNeoRuntimeGlassesClient.kt:3-18`.

KMP should therefore be scoped to:

- Stable common model/API types.
- Shared business rules and command definitions.
- Platform-neutral bridge contracts.
- Optional common protocol helpers for transports that exist on both platforms.

KMP should not try to share:

- Android vendor SDK calls.
- Android Bluetooth/Camera2/AudioRecord/AudioTrack implementations.
- Android on-glasses runtimes such as RayNeo.
- CLI run/install tooling, which is Android-only today.

React Native or Flutter would be the wrong primary SDK migration here. They are app UI/runtime choices, not the natural home for the existing Kotlin API and Gradle library modules. Flutter is already used narrowly for Brilliant/Frame integration, through a bridge contract in `devices/device-frame-flutter/src/main/java/com/xgglass/device/frame/flutter/FrameFlutterBridge.kt:13-20`; making the whole SDK Flutter-first would make Android vendor AARs and future native iOS SDKs harder to expose cleanly. Native Swift alone would support iOS clients, but would duplicate the shared API and app contract already expressed in Kotlin.

The practical answer is: yes, share business logic across Android and iOS with KMP, but only above the hardware/vendor boundary. iOS only matters for phone-companion glasses and BLE transports. On-glasses Android runtimes remain Android-only regardless of KMP.

## Current Architecture Grounding

The SDK is organized around a common client interface:

- `GlassesClient` exposes `model`, `capabilities`, `state`, `events`, `connect`, `disconnect`, `capturePhoto`, `display`, `playAudio`, and `startMicrophone` in `core/src/main/java/com/xgglass/core/GlassesClient.kt:14-57`.
- Models and capture/display options are in `core/src/main/java/com/xgglass/core/Models.kt:3-79`.
- Audio playback and microphone abstractions are in `core/src/main/java/com/xgglass/core/Audio.kt:14-141`.
- State and events are in `core/src/main/java/com/xgglass/core/StateAndEvents.kt:3-14`.
- Errors are in `core/src/main/java/com/xgglass/core/Errors.kt:3-10`.
- App-level command contracts live in `app-contract/src/main/java/com/xgglass/appcontract/UniversalAppEntry.kt:18-237`.

The build is Android-first:

- `core/build.gradle.kts:1-12` applies `com.xgglass.android.library`.
- `app-contract/build.gradle.kts:1-11` also applies `com.xgglass.android.library`.
- `build-logic/src/main/kotlin/com/xgglass/buildlogic/android/XgGlassAndroidLibraryPlugin.kt:8-20` applies `com.android.library` and `org.jetbrains.kotlin.android`.
- `build-logic/src/main/kotlin/com/xgglass/buildlogic/android/AndroidConventions.kt:6-18` sets Android compile/min SDK and Java compatibility.
- `settings.gradle.kts:57-69` includes one aggregate module, core modules, and Android device modules.

The recent `:core-android` module is already the right direction:

- `core-android/build.gradle.kts:1-11` is an Android-only module depending on `:core`.
- It holds shared Android audio helpers using `AudioRecord`, `AudioTrack`, and `MediaPlayer` in `core-android/src/main/java/com/xgglass/core/android/AndroidMicrophoneSession.kt:3-36`, `core-android/src/main/java/com/xgglass/core/android/AudioTrackPlayer.kt:3-25`, and `core-android/src/main/java/com/xgglass/core/android/MediaPlayerPlayer.kt:3-26`.
- Device modules consume it through `implementation(project(":core-android"))`, for example `devices/device-simulator/build.gradle.kts:9-12`, `devices/device-rayneo-runtime/build.gradle.kts:9-12`, `devices/device-meta/build.gradle.kts:13-15`, and `devices/device-rokid/build.gradle.kts:9-12`.

## Current Android Couplings in the Shared Layer

This section separates true shared-layer blockers from Android-only device implementation blockers.

### `core` blockers

| File | Coupling | Why it blocks `commonMain` | Fix |
|---|---|---|---|
| `core/src/main/java/com/xgglass/core/ExternalActivityBridge.kt:3-16` | Imports and exposes `android.content.Intent` in `ExternalActivityResult.data` and `ExternalActivityBridge.launch`. | `android.content.Intent` does not exist in common Kotlin or iOS. This is the strongest shared-layer blocker. | Move this to `core:androidMain`, or replace it with a common opaque request/result model plus Android actual adapters. Example: `data class ExternalActivityRequest(val action: String, val uri: String?, val extras: Map<String, String>)`. |
| `core/src/main/java/com/xgglass/core/Models.kt:50-56` | `CapturedImage.timestampMs` defaults to `System.currentTimeMillis()`. | `java.lang.System.currentTimeMillis()` is not common Kotlin API. | Use `expect fun nowMillis(): Long`, inject a `Clock`, or require callers to pass timestamps from platform code. |
| `core/src/main/java/com/xgglass/core/Audio.kt:86-92` | `AudioChunk.timestampMs` defaults to `System.currentTimeMillis()`. | Same JVM/Android dependency as `CapturedImage`. | Same `expect fun nowMillis(): Long` or injected clock. |
| `core/build.gradle.kts:1-12` | Android library plugin plus `kotlinx-coroutines-android`. | A KMP common module cannot apply only `com.android.library`, and commonMain should not depend on Android dispatcher artifacts. | Convert to `kotlin("multiplatform")` with `androidTarget()` and `iosArm64`/`iosSimulatorArm64`. Put `kotlinx-coroutines-core` in `commonMain`; put `kotlinx-coroutines-android` in `androidMain` only. |
| `gradle/libs.versions.toml:13-14` | Both `kotlinx-coroutines-core` and `kotlinx-coroutines-android` exist, but `core/build.gradle.kts:10-11` exposes both as `api`. | Coroutines are portable, but the Android artifact is not. | Keep `kotlinx-coroutines-core` in common. Move `kotlinx-coroutines-android` to Android implementations or Android source set. |

Not blockers:

- `ByteArray` is portable. It appears in `AudioSource.RawBytes.data` at `core/src/main/java/com/xgglass/core/Audio.kt:40-43`, `AudioChunk.bytes` at `core/src/main/java/com/xgglass/core/Audio.kt:86-88`, and `CapturedImage.jpegBytes` at `core/src/main/java/com/xgglass/core/Models.kt:50-52`.
- `Result<T>` is common Kotlin and is already a good cross-platform return convention in `GlassesClient` at `core/src/main/java/com/xgglass/core/GlassesClient.kt:24-57`.
- `Flow` and `StateFlow` are available from multiplatform coroutines. `GlassesClient.state` and `GlassesClient.events` use them in `core/src/main/java/com/xgglass/core/GlassesClient.kt:18-22`.
- `GlassesError` extends `Exception` in `core/src/main/java/com/xgglass/core/Errors.kt:3-10`; this is common Kotlin-capable, though iOS-facing Swift wrappers may prefer domain-specific error mapping.

### `app-contract` blockers

| File | Coupling | Why it matters | Fix |
|---|---|---|---|
| `app-contract/build.gradle.kts:1-11` | Android library plugin. | The source is mostly common Kotlin, but the module is built only as an Android library. | Convert to KMP and depend on `:core` common metadata. |
| `app-contract/src/main/java/com/xgglass/appcontract/UniversalAppEntry.kt:6` | `CoroutineScope` import. | This is not an Android blocker if it comes from `kotlinx-coroutines-core`; it is portable. | Keep it in commonMain after `core` moves coroutines-core to common. |
| `app-contract/src/main/java/com/xgglass/appcontract/UniversalAppEntry.kt:139-157` | `UniversalAppContext` carries `GlassesClient`, `CoroutineScope?`, callback lambdas, and `Map<String, String>`. | These are portable. The only risk is host lifecycle semantics, not source compatibility. | Keep as common. Add platform host adapters for Android and iOS lifecycle/UI behavior. |
| `app-contract/src/main/java/com/xgglass/appcontract/UniversalAppEntry.kt:218-237` | Command filtering policy extension point. | Portable code can keep it; current behavior is passthrough. | Keep as a shared extension point until a real cross-host default is needed. |

### Public device constructor and implementation couplings

These are not blockers for making `core` and `app-contract` common, but they define the platform split.

| Device | Android/public coupling | Evidence | Migration action |
|---|---|---|---|
| Rokid | `AppCompatActivity`, Android Bluetooth, Android storage, CXR-M AAR, Android audio route. | Imports at `devices/device-rokid/src/main/java/com/xgglass/device/rokid/RokidGlassesClient.kt:3-22`, constructor at `:77-80`, Bluetooth/Wi-Fi comments at `:65-75`, CXR calls at `:149-153`, `:223-239`, and `:335-338`. | Keep Android implementation in `device-rokid-android`; write a separate `device-rokid-ios` around Rokid's iOS SDK if product scope requires iOS. |
| Meta | `AppCompatActivity`, Meta DAT Android packages, Android media/Bluetooth route. | Imports at `devices/device-meta/src/main/java/com/xgglass/device/meta/MetaWearablesGlassesClient.kt:3-29`, constructor at `:78-82`, DAT init at `:301-329`, capture at `:154-183`, audio at `:207-299`. | Keep Android implementation; build `device-meta-ios` around Meta DAT iOS if available and licensing permits. |
| Frame | Pure Kotlin bridge contract plus Android embedded Flutter implementation. | Contract avoids Flutter dependency in `devices/device-frame-flutter/src/main/java/com/xgglass/device/frame/flutter/FrameFlutterBridge.kt:13-20`; Android embedded bridge imports Android/Flutter in `devices/device-frame-embedded/src/main/java/com/xgglass/device/frame/embedded/EmbeddedFrameFlutterBridge.kt:3-22`. | Move bridge contract to common or keep as KMP module; implement Android through embedded Flutter and iOS through Flutter/iOS SDK bridge. |
| RayNeo device manager | Android `Context`, `Uri`, assets/content resolver, ADB-over-TCP. | Imports at `devices/device-rayneo-installer/src/main/java/com/xgglass/device/rayneo/installer/RayNeoDeviceManager.kt:3-8`; manager install behavior at `:60-104`; APK open paths at `:165-184`. | Keep Android-only installer path; iOS cannot use Android ADB/install flow for on-glasses Android APKs. |
| RayNeo runtime | Android on-glasses process with Camera2, AudioManager, MediaPlayer, AudioRecord helper, Toast. | Imports at `devices/device-rayneo-runtime/src/main/java/com/xgglass/device/rayneo/runtime/RayNeoRuntimeGlassesClient.kt:3-18`; class docs at `:52-61`; camera capture at `:105-130`; audio/mic at `:142-259`. | Keep Android-only. Do not block KMP common work on this. |
| Omi | Android BLE/GATT APIs and Android permission checks. | Imports at `devices/device-omi/src/main/java/com/xgglass/device/omi/OmiGlassesClient.kt:3-22`; BLE behavior docs at `:59-70`; GATT connection at `:286-330`; permission checks at `:273-283`. | Most feasible to reimplement for iOS using CoreBluetooth if Omi BLE services are stable/documented. |
| Simulator | Android emulator, AppCompat, CameraX, Android media/TTS, `core-android` helpers. | Imports at `devices/device-simulator/src/main/java/com/xgglass/device/sim/SimulatorGlassesClient.kt:3-18`; emulator description at `:57-69`; Android camera/media/mic code at `:167-419`. | Keep Android-only dev tooling. Build a separate iOS simulator later only if an iOS app template needs it. |

## Target Module Layout

Target layout:

```text
:core                         KMP
  commonMain                  Pure API: GlassesClient, models, audio types, errors, events
  androidMain                 Android actuals for clock, optional Android-only bridge aliases
  iosMain                     iOS actuals for clock

:app-contract                 KMP
  commonMain                  UniversalAppEntry, UniversalCommand, settings, command policy
  androidMain                 Android host helpers only if needed
  iosMain                     iOS host helpers only if needed

:core-android                 Android-only, or folded into :core androidMain
  main/androidMain            AudioRecord, AudioTrack, MediaPlayer helpers

:device-rokid-android         Android-only
:device-meta-android          Android-only
:device-frame-common          KMP bridge contract if useful
:device-frame-android         Android Flutter embedding
:device-frame-ios             iOS Flutter/native bridge
:device-rayneo-installer-android
:device-rayneo-runtime-android
:device-omi-android
:device-omi-ios
:device-simulator-android
:device-simulator-ios         Optional future developer tool

:universal-android            Android aggregate artifact
:universal-ios                Optional SwiftPM/XCFramework packaging facade
```

Minimum viable KMP shape for `core`:

```kotlin
kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:<version>")
        }
        androidMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-android:<version>")
        }
    }
}
```

Clock extraction sketch:

```kotlin
// commonMain
internal expect fun nowMillis(): Long

data class CapturedImage(
    val jpegBytes: ByteArray,
    val timestampMs: Long = nowMillis(),
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    val sourceModel: GlassesModel,
)
```

```kotlin
// androidMain
internal actual fun nowMillis(): Long = System.currentTimeMillis()

// iosMain
internal actual fun nowMillis(): Long = platform.Foundation.NSDate().timeIntervalSince1970.times(1000).toLong()
```

External activity bridge extraction:

```kotlin
// commonMain
data class ExternalLaunchRequest(
    val action: String,
    val uri: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

data class ExternalLaunchResult(
    val resultCode: Int,
    val dataUri: String? = null,
)

fun interface ExternalLaunchBridge {
    suspend fun launch(request: ExternalLaunchRequest): ExternalLaunchResult
}
```

```kotlin
// androidMain adapter
fun interface AndroidExternalActivityBridge {
    suspend fun launch(intent: android.content.Intent): ExternalActivityResult
}
```

`core-android` should remain the Android home for shared media mechanics. It currently centralizes `AudioRecord` in `core-android/src/main/java/com/xgglass/core/android/AndroidMicrophoneSession.kt:21-143`, `AudioTrack` in `core-android/src/main/java/com/xgglass/core/android/AudioTrackPlayer.kt:13-136`, and `MediaPlayer` in `core-android/src/main/java/com/xgglass/core/android/MediaPlayerPlayer.kt:14-82`. In a KMP setup, either keep it as a separate Android-only module consumed by Android device modules, or move those files into `core/androidMain` if the team wants fewer modules. Keeping it separate is lower risk because it does not pollute common API metadata.

## Per-Device iOS Reachability Table

The vendor iOS SDK availability column is a migration assumption to verify during vendor due diligence before implementation.

| Device | Current module(s) | Transport and runtime in this repo | Vendor iOS SDK availability | iOS feasibility | Recommended action |
|---|---|---|---|---|---|
| Rokid | `devices/device-rokid` | Phone companion over Bluetooth plus Wi-Fi P2P; CXR-M AAR controls display, TTS, photo sync, mic stream. Evidence: docs in `RokidGlassesClient.kt:65-75`, connect flow at `:117-141`, capture path at `:166-194`, display/TTS/mic via CXR at `:196-239` and `:286-378`. | Rokid CXR-M has iOS. | Medium-high effort. The common API maps well, but vendor API calls, auth files, Bluetooth/session lifecycle, file sync, and audio callbacks must be rewritten for iOS. | Pilot candidate only if Rokid iOS SDK access is available. Do not share Android CXR code. |
| Meta | `devices/device-meta` | Phone companion through Meta Wearables DAT Android SDK plus Android Bluetooth audio route. Evidence: Meta DAT imports in `MetaWearablesGlassesClient.kt:17-29`, registration init at `:301-329`, capture stream at `:154-183`, HFP mic/audio at `:207-299`. | Meta DAT has iOS. | Medium effort if DAT iOS exposes comparable camera/audio APIs; high if audio routing differs. Text display remains unsupported. | Good second pilot after core KMP because the public `GlassesClient` operations map cleanly. |
| Frame | `devices/device-frame-flutter`, `devices/device-frame-embedded` | Bridge contract to Flutter module using `frame_ble`/`frame_msg`; Android embedded module owns FlutterEngine/MethodChannel. Evidence: bridge purpose at `FrameFlutterBridge.kt:13-20`, frame client delegation at `FrameGlassesClient.kt:24-31` and `:68-105`, Android Flutter embedding at `EmbeddedFrameFlutterBridge.kt:17-23` and `:61-76`. | Brilliant/Frame has Flutter plus iOS SDK path. | Medium. The existing bridge contract is closest to platform-neutral already. Android embedded Flutter is not portable, but the contract can be KMP and iOS can provide a Swift/Flutter bridge. | Strong KMP candidate. Promote bridge contract/common client first. |
| RayNeo device manager | `devices/device-rayneo-installer` | Phone-side Android `DeviceManager` that pushes/installs a glasses APK via ADB-over-TCP and pushes settings to the on-glasses app. Evidence: class docs at `RayNeoDeviceManager.kt:36-45`, ADB dependency at `devices/device-rayneo-installer/build.gradle.kts:12-13`, install flow at `RayNeoDeviceManager.kt:60-104`, settings push to `/data/local/tmp` at `:126-162`. | No useful iOS equivalent for installing an Android on-glasses APK. | Low feasibility. iOS cannot be expected to run Android ADB workflows or install APKs. | Keep Android-only. Do not block KMP common layers on this. |
| RayNeo runtime | `devices/device-rayneo-runtime` | Android app running on the glasses. Uses Camera2, Android media, Android permissions, and display sink/Toast. Evidence: class docs at `RayNeoRuntimeGlassesClient.kt:52-61`, Camera2 imports at `:3-10`, audio imports at `:11-18`, capture at `:105-130`, display at `:132-140`, audio/mic at `:142-259`. | On-glasses runtime is Android-only. | Not an iOS target. It can consume shared KMP app logic compiled to Android, but no iOS device client is needed. | Keep as `device-rayneo-runtime-android`. Shared app logic may still run in common modules. |
| Omi | `devices/device-omi` | BLE/GATT audio-focused device client. Evidence: Bluetooth imports at `OmiGlassesClient.kt:3-22`, behavior docs at `:59-70`, service discovery and GATT connection at `:286-330`, unsupported display/playback at `:223-236`. | BLE device; iOS reimplementation likely possible with CoreBluetooth. | Medium. No vendor AAR lock-in in the repo, but CoreBluetooth has different scanning, permission, backgrounding, and MTU behavior. | Best low-dependency iOS proof after `core`/`app-contract`; reimplement transport with shared packet parsing where possible. |
| Simulator | `devices/device-simulator` | Android emulator/dev tool using AppCompat, CameraX, Android TTS/media, and `core-android` audio helpers. Evidence: emulator design at `SimulatorGlassesClient.kt:57-69`, constructor at `:71-75`, CameraX capture at `:167-231`, Android TTS/audio at `:250-350`, Android microphone at `:380-419`. | Not a vendor device. | Android-only today. iOS simulator could be built, but it is a separate app/tooling project. | Keep Android simulator. Add iOS simulator only after real iOS device work starts. |

## Tooling Impact

The current Python CLI is Android-only.

Evidence:

- CLI description says it builds/installs/runs Android host apps at `tools/xg_glass_cli/cli.py:50`.
- `init` copies Android Gradle project files, wrapper files, and Gradle settings at `tools/xg_glass_cli/cli.py:140-178`.
- `build` runs Gradle through `gradlew` and assembles an APK at `tools/xg_glass_cli/cli.py:258-299`.
- `install` uses `adb install` at `tools/xg_glass_cli/cli.py:303-318`.
- `run` launches via ADB and supports quick-mode Android project generation at `tools/xg_glass_cli/cli.py:323-424`.
- Simulator mode bootstraps Android emulator/system images and waits for `adb` devices at `tools/xg_glass_cli/cli.py:546-699`.
- The CLI patches generated Android Gradle files for simulator mode at `tools/xg_glass_cli/cli.py:703-764`.
- It bootstraps Java, Android SDK, and Flutter, including environment persistence, at `tools/xg_glass_cli/cli.py:1206-1349`, `:1389-1490`, and `:1998-2151`.

KMP will not give this CLI an iOS run loop. An iOS developer experience needs a parallel path:

- SwiftPM or CocoaPods/XCFramework packaging for KMP outputs.
- Xcode project or template generation.
- Code signing/provisioning guidance.
- iOS device install/run through Xcode, `xcodebuild`, or `ios-deploy`.
- iOS-specific simulator/device support if any glasses SDK supports it.
- A separate replacement for Android-only ADB workflows. RayNeo's APK install path cannot be ported directly.

KMP shares code artifacts and contracts. It does not share Gradle Android app assembly, ADB install, emulator boot, or Android vendor AAR packaging.

## Phased Migration Plan

### Phase 0: Freeze the shared API boundary

Goal: avoid mixing behavior changes with build-system migration.

Actions:

1. Mark `GlassesClient`, `Models`, `Audio`, `StateAndEvents`, and `Errors` as the KMP target surface.
2. Keep current Android modules and aggregate behavior unchanged.
3. Add tests or compile probes around generated Android template behavior where possible.
4. Decide whether the recently added `GlassesModel.ANDROID_XR` in `core/src/main/java/com/xgglass/core/Models.kt:3-10` is part of the stable public enum before publishing KMP metadata.

Risks:

- Enum additions already affect exhaustive `when` users, as seen in the template fix needed around `templates/kotlin-app/app/src/main/java/com/example/xgglassapp/MainActivity.kt:250-283`.
- KMP metadata publication makes source/binary compatibility more visible to iOS and Android consumers.

### Phase 1: Convert `core` and `app-contract` to KMP with Android behavior preserved

Goal: commonMain builds with no production behavior change.

Actions:

1. Convert `core/build.gradle.kts:1-12` from Android-only library to KMP.
2. Move pure types from `core/src/main/java` to `core/src/commonMain/kotlin`.
3. Move `ExternalActivityBridge` from `core/src/main/java/com/xgglass/core/ExternalActivityBridge.kt:1-16` to `androidMain`, or replace it with common request/result types plus Android adapter.
4. Replace `System.currentTimeMillis()` defaults in `Models.kt:50-56` and `Audio.kt:86-92` with `expect fun nowMillis(): Long`.
5. Keep `kotlinx-coroutines-core` in common and move `kotlinx-coroutines-android` out of common, fixing the current `core/build.gradle.kts:9-12` dependency exposure.
6. Convert `app-contract/build.gradle.kts:1-11` to KMP and move `UniversalAppEntry.kt:1-237` to commonMain.
7. Keep Android aggregate modules depending on the Android target output.

Exit criteria:

- Existing Android generated-project build still passes.
- No public behavior changes in Android clients.
- Common metadata can be consumed from a minimal KMP sample.

### Phase 2: Split Android device implementations from common contracts

Goal: device modules remain working while naming and source sets make platform boundaries explicit.

Actions:

1. Leave current device modules Android-only at first.
2. Rename or alias them as `device-*-android` only after publication strategy is agreed.
3. Keep `core-android` as Android helper home for `AudioRecord`, `AudioTrack`, and `MediaPlayer`.
4. For Frame, move `FrameFlutterBridge.kt:13-38`, `FrameFlutterChannelContract.kt:10-76`, and possibly `FrameGlassesClient.kt:24-106` into a KMP module because they are already platform-neutral except for coroutine dispatcher choices.
5. Do not move RayNeo runtime out of Android. It is specifically an Android glasses app per `RayNeoRuntimeGlassesClient.kt:52-61`.

Exit criteria:

- Android aggregate artifact still exposes the same devices through `universal/build.gradle.kts:9-36`.
- No iOS device work is blocking Android releases.

### Phase 3: Pilot one iOS device with a vendor iOS SDK or BLE

Goal: validate the KMP boundary with a real iOS client.

Recommended pilot order:

1. Frame, because the existing bridge contract is already abstraction-first.
2. Omi, because BLE/GATT is reimplementable with CoreBluetooth and has narrow capabilities.
3. Meta or Rokid, if vendor iOS SDK access and licensing are available.

Pilot deliverables:

- `device-frame-ios` or `device-omi-ios`.
- Swift-facing factory/wrapper that returns a common `GlassesClient` or a Swift-friendly facade.
- iOS permission handling and lifecycle mapping.
- Minimal sample app.

Risks:

- Kotlin/Native interop with Swift async/Flow requires deliberate wrappers.
- Vendor SDK callback threading may not match Kotlin coroutine expectations.
- Bluetooth background behavior and permissions are meaningfully different on iOS.

### Phase 4: Build iOS packaging and tooling

Goal: make the shared KMP output usable by iOS developers.

Actions:

1. Publish `core` and `app-contract` as an XCFramework or SwiftPM binary target.
2. Provide Swift examples for `GlassesClient` state/events.
3. Add a parallel CLI path or documented scripts around Xcode/SwiftPM.
4. Keep Android `xg-glass` commands intact.

Exit criteria:

- Android developers still run `xg-glass init/build/install/run`.
- iOS developers can open/build/run an Xcode sample without touching Android Gradle.

## Effort and Risk Callouts

High confidence:

- `core` and `app-contract` can become KMP with limited source changes.
- Most of `GlassesClient` is already common-shaped.
- `ByteArray`, `Result`, data classes, sealed classes, enums, `Flow`, and `StateFlow` are compatible with a common model.
- `core-android` provides a clean place for shared Android media code.

Medium risk:

- `ExternalActivityBridge` needs an explicit common/Android split.
- Time defaults need `expect/actual` or clock injection.
- `Dispatchers.Main` and Android lifecycle assumptions in device modules must not leak into common code.
- The aggregate `:universal` module currently exports Android implementations directly in `universal/build.gradle.kts:9-36`; KMP publication likely needs a new Android aggregate and separate iOS packaging.

High risk:

- Vendor SDK parity between Android and iOS is not guaranteed, even when both SDKs exist.
- Meta and Rokid auth/registration flows may differ materially across platforms.
- RayNeo's app install/run model is Android-only.
- CLI parity is a separate project, not a KMP side effect.

## What We Are Not Doing and Why

We are not rewriting the SDK in React Native. The codebase already has a Kotlin API surface and multiple Android vendor integrations. React Native would add a JavaScript bridge without solving Android AAR and native iOS SDK divergence.

We are not making the whole SDK Flutter-first. Frame already uses Flutter where it is useful, but Rokid, Meta, RayNeo, Omi, and the Android CLI are not naturally Flutter libraries. A Flutter-first SDK would still need native Android/iOS plugins underneath.

We are not translating Android device implementations into common Kotlin. Android Bluetooth, Camera2, AudioRecord, AudioTrack, MediaPlayer, AppCompat, FlutterEngine, ADB, and vendor AARs are platform code by definition.

We are not blocking Android-only devices on iOS. RayNeo runtime and simulator should stay Android-only while common app logic moves forward.

We are not promising iOS support for every current model in one pass. The right first milestone is shared API/contract metadata plus one iOS pilot with either Frame or Omi.

## Concrete Next Step

Start with a branch that converts only `:core` and `:app-contract` to KMP and keeps every Android device module unchanged. That validates the highest-leverage common layer without entangling vendor SDKs, ADB, Flutter embedding, or on-glasses Android runtimes.
