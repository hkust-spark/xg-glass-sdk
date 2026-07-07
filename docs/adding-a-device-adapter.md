# Adding a Device Adapter

This is the shortest path for porting a new pair of glasses into xg.glass. It is distilled from the current Omi, Even Realities G1, and INMO Air3 adapters.

## 1. Choose the Integration Pattern

Start by answering two questions: does the vendor expose a public protocol or SDK, and do apps run on the glasses themselves?

| Pattern | Use when | Current references |
| --- | --- | --- |
| Phone-side BLE client | The glasses expose a plain BLE/GATT protocol that Android or iOS can implement directly. Put protocol bytes in testable Kotlin helpers and keep platform transport code thin. | `devices/device-even/` is one KMP module with `commonMain`, `androidMain`, and `iosMain`. `devices/device-omi/` is the Android BLE module; `devices/device-omi-ios/` keeps iOS protocol helpers in `commonMain` and the CoreBluetooth adapter in `iosMain`. |
| Phone-side vendor SDK | The vendor SDK owns connection, auth, or media transport. Keep the SDK behind a `GlassesClient` and report capability changes honestly. | `devices/device-rokid/` uses Rokid CXR-M plus BLE/Wi-Fi P2P. `devices/device-meta/` uses Meta DAT and makes display capability dynamic. |
| On-glasses Android runtime | The glasses are an Android device and the app runs on the glasses. Prefer stock Android APIs before adding a vendor dependency. | `devices/device-rayneo-runtime/` and `devices/device-inmo-runtime/` use Camera2, `AudioRecord`, `MediaPlayer`/`AudioTrack`, and an Activity-provided display sink. |

If the transport depends on Wi-Fi Direct, adb, USB host mode, or a closed licensed handshake, assume iOS is gated until a vendor iOS SDK exists. See `docs/ios-device-support.md`.

## 2. Source-Trace the Protocol

Do not invent bytes. Every UUID, command byte, bit flag, packet size, and status code needs a source and a cross-check when possible. The Even adapter is the reference style:

```kotlin
// Source: official Android constants; /tmp/even-ref/EvenDemoApp/android/.../BleManager.kt lines 35-37.
// Cross-check: iOS ServiceIdentifiers.swift lines 11-15 and docs/G1_BLE_CONNECTION.en.md lines 50-56.
const val NORDIC_UART_SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
```

Use official demos first, then community notes as a second source. Keep pure framing and packet logic in `commonMain` or another unit-testable file, as in `devices/device-even/src/commonMain/.../EvenProtocol.kt` and `devices/device-omi-ios/src/commonMain/.../OmiProtocol.kt`.

## 3. Build the Module Skeleton

Create the module under `devices/device-<name>/`. Use Kotlin Multiplatform when the same protocol should run on Android and iOS (`device-even`). Use a plain Android library when the adapter is Android-only (`device-omi`, `device-inmo-runtime`, `device-rokid`, `device-meta`).

Device clients should extend `BaseGlassesClient` unless there is a behavior reason not to. The base owns `state`, `events`, and the connect mutex. Implement `doConnect()`, keep `disconnect()` idempotent, and call `resetCapabilities()` on disconnect or link loss when capabilities can change after connection.

Set `DeviceCapabilities` conservatively. If a feature is missing or unverified, return `GlassesError.Unsupported` with a specific message. Current examples:

- Even G1: no camera, speaker, TTS, or raw audio playback; microphone emits LC3 frames through `PushMicrophoneSession`.
- Omi: BLE audio/photo paths exist; display and speaker playback return `Unsupported`.
- INMO Air3: stock Android camera, mic, display sink, and raw audio playback; TTS remains `Unsupported` until hardware verifies a usable engine.

For microphone transports that push frames from callbacks, use `PushMicrophoneSession`. Map physical input to `GlassesEvent.Tap(count)` and `GlassesEvent.LongPress` when the device exposes those gestures; set `supportsTapEvents` and `supportsLongPressEvents` accordingly.

## 4. BLE House Rules

Use `devices/device-even/src/androidMain/.../EvenGlassesClient.kt` as the Android BLE reference implementation.

- Guard every `suspendCancellableCoroutine` with a single-resume flag or an owned-continuation check.
- Wait for descriptor writes before treating notifications as enabled.
- Rethrow `CancellationException`; do not wrap cancellation as transport failure.
- Use the Android 13+ `writeDescriptor(descriptor, value)` and `writeCharacteristic(characteristic, value, writeType)` overloads, with pre-33 fallbacks.
- Clear pending continuations on cancellation, timeout, disconnect, and superseded commands.
- Rate-limit logs for malformed notifications or backpressure drops.

## 5. Wiring Checklist

Wire the new adapter in all of these places.

| Area | File |
| --- | --- |
| Gradle module include and stable project directory | `settings.gradle.kts` |
| Published Android aggregate | `kotlin/universal/build.gradle.kts` |
| Dev-only full aggregate, only if the generated app needs extra non-published pieces | `kotlin/universal-full/build.gradle.kts` |
| Per-module Maven POM description | `build-logic/src/main/kotlin/com/xgglass/buildlogic/publish/XgGlassMavenPublishPlugin.kt` |
| CI compile/test task list | `.github/workflows/ci.yml` |
| Public device table and channel matrix | `README.md` |
| Release coordinates and verification matrix | `docs/releasing.md` |
| Generated app dependencies and substitutions | `templates/kotlin-app/app/build.gradle.kts`, `templates/kotlin-app/settings.gradle.kts` |
| Generated app UI/client wiring | `templates/kotlin-app/app/src/main/java/com/example/xgglassapp/MainActivity.kt` |
| CLI `--devices` filtering | `tools/xg_glass_cli/devices.py` |
| Device README and hardware checklist | `devices/device-<name>/README.md` |

Keep template blocks inside `// xg:device:<name>:begin` and `// xg:device:<name>:end` markers. Add an explicit `GlassesModel.<NAME>` branch in `MainActivity.kt`; do not hide enum fallout behind a generic branch in the all-device path.

Permissions are self-contained: the module manifest declares what its code uses. Current examples are `devices/device-even/src/androidMain/AndroidManifest.xml` and `devices/device-omi/src/main/AndroidManifest.xml` for BLE, and `devices/device-inmo-runtime/src/main/AndroidManifest.xml` for camera/microphone. The generated app may request a superset at runtime, but the module should still declare its own needs.

## 6. Verify Without Hardware

Run the no-hardware matrix first. It catches protocol math, Gradle wiring, generated template fallout, and simulator regressions before hardware is available.

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

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew --console=plain \
    :core:iosSimulatorArm64Test \
    :device-even:iosSimulatorArm64Test \
    :device-omi-ios:iosSimulatorArm64Test \
    :app-contract:assembleXgGlassKitXCFramework
```

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew --console=plain publishToMavenLocal
```

```bash
rm -rf /tmp/xg-adapter-check
./xg-glass init /tmp/xg-adapter-check --devices even,simulator --sim --no-shell-setup
cd /tmp/xg-adapter-check
./gradlew --console=plain :app:assembleDebug
```

```bash
rm -rf /tmp/xg-cli-venv
python3 -m venv /tmp/xg-cli-venv
/tmp/xg-cli-venv/bin/python -m pip install -e "tools[dev]"
/tmp/xg-cli-venv/bin/python -m pytest tools/tests -q
./xg-glass --help
./xg-glass run --help
```

Run `scripts/sim-e2e.sh` when adapter or template changes should prove the generated Simulator app still connects, captures, displays, records microphone audio, emits synthetic gestures, and disconnects on an Android Emulator.

Keep protocol unit tests large enough to pin the wire format. The current no-hardware baselines are 29 Even protocol tests, 16 Omi iOS protocol/photo tests, and 10 INMO runtime-policy tests.

Until someone validates on hardware, document the posture as "adapter shipped, hardware validation pending." Link the call-for-testers issue when you need community coverage: https://github.com/hkust-spark/xg-glass-sdk/issues/63.
