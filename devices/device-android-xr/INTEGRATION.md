# Android XR DP4 Integration Spike

`device-android-xr` is an unpublished preview scaffold for Android XR AI glasses
using the Jetpack Projected model introduced in Android XR SDK Developer Preview
4. It is kept in the source build so preview work keeps compiling, but it is not
a functional adapter and must not be published or referenced from generated app
templates.

Sources used for this spike:

- Android XR DP4 announcement:
  <https://android-developers.googleblog.com/2026/05/android-xr-sdk-developer-preview-4-updates.html>
- AI glasses first projected activity:
  <https://developer.android.com/develop/xr/jetpack-xr-sdk/ai-glasses/first-activity>
- XR glasses first projected activity:
  <https://developer.android.com/develop/xr/jetpack-xr-sdk/glasses/first-activity>
- Hardware access from a projected context:
  <https://developer.android.com/develop/xr/jetpack-xr-sdk/access-hardware-projected-context>
- Hardware permissions on projected devices:
  <https://developer.android.com/develop/xr/jetpack-xr-sdk/request-hardware-permissions>
- Google Maven metadata under
  <https://dl.google.com/dl/android/maven2/androidx/xr/>

## Outcome

This spike stops at T1 documentation. The projected artifacts can be inspected
and some current latest coordinates resolve with compileSdk 36, but the DP4
guide-pinned AI glasses dependency set does not pass this repository's
compileSdk 36 AAR metadata gate. A T2 implementation would either need a
compileSdk 37 upgrade or a narrower dependency set that omits the guide's
Glimmer UI path.

No production Kotlin was changed in this spike. `AndroidXrGlassesClient.kt`
continues to expose bridge lambdas and `GlassesError.Unsupported` paths until
the SDK toolchain and projected device test environment are ready.

## Artifact Coordinates

The AI glasses first-activity guide currently shows this DP4-style dependency
set:

| Purpose | Coordinate |
| --- | --- |
| XR runtime | `androidx.xr.runtime:runtime:1.0.0-alpha15` |
| Glimmer UI | `androidx.xr.glimmer:glimmer:1.0.0-alpha13` |
| Glimmer Google fonts | `androidx.xr.glimmer:glimmer-google-fonts:1.0.0-alpha13` |
| Projected context/device APIs | `androidx.xr.projected:projected:1.0.0-alpha08` |
| XR ARCore | `androidx.xr.arcore:arcore:1.0.0-alpha14` |

The ProjectedTestRule artifact exists on Google Maven as:

| Purpose | Coordinate |
| --- | --- |
| Projected test fakes/rule | `androidx.xr.projected:projected-testing:1.0.0-alpha08` |

Google Maven currently exposes newer versions for some artifacts:

| Artifact | Latest observed on Google Maven | AAR metadata observed locally |
| --- | ---: | --- |
| `androidx.xr.projected:projected` | `1.0.0-alpha09` | `minCompileSdk=36`, `minAndroidGradlePluginVersion=8.9.1` |
| `androidx.xr.projected:projected-testing` | `1.0.0-alpha09` | `minCompileSdk=36`, `minAndroidGradlePluginVersion=8.9.1` |
| `androidx.xr.runtime:runtime` | `1.0.0-alpha15` | `minCompileSdk=34`, `minAndroidGradlePluginVersion=8.1.1` |
| `androidx.xr.glimmer:glimmer` | `1.0.0-alpha15` | `minCompileSdk=35`, `minAndroidGradlePluginVersion=8.6.0` |
| `androidx.xr.glimmer:glimmer-google-fonts` | `1.0.0-alpha15` | latest metadata exists; the full latest set pulls compileSdk 37 transitive dependencies |
| `androidx.xr.arcore:arcore` | `1.0.0-alpha15` | `minCompileSdk=34`, `minAndroidGradlePluginVersion=8.1.1` |

Compatibility result in this repo:

- Branch toolchain: AGP `9.1.1`, compileSdk `36`.
- `projected:1.0.0-alpha09` and `projected-testing:1.0.0-alpha09`
  pass their own AAR metadata with compileSdk 36.
- The guide-pinned DP4 set fails `:device-android-xr:checkDebugAarMetadata`
  because `projected:1.0.0-alpha08`, `projected-testing:1.0.0-alpha08`,
  `glimmer:1.0.0-alpha13`, and `glimmer-google-fonts:1.0.0-alpha13` declare
  `minCompileSdk=37`.
- The latest full set also fails against compileSdk 36 through Glimmer/Compose
  transitives such as Compose UI `1.12.0-alpha03` and AndroidX Core
  `1.19.0-alpha02`, which require compileSdk 37.

## Projected Context Acquisition

DP4's Projected API exposes the core context handoff as
`ProjectedContext.createProjectedDeviceContext(context)`. Android APIs created
from that projected context target the projected device instead of the host
phone where supported by the platform. The same artifact exposes:

- `ProjectedContext.createHostDeviceContext(context)` for the inverse host
  context.
- `ProjectedContext.isProjectedDeviceContext(context)` to detect whether a
  context is already projected.
- `ProjectedContext.getProjectedDeviceName(context)` for the projected device
  name.
- `ProjectedContext.createProjectedActivityOptions(context)` for launching an
  Activity onto the projected display.
- `ProjectedContext.isProjectedDeviceConnected(context, coroutineContext)` as a
  `Flow<Boolean>` for device connection availability.

The existing scaffold's `AndroidXrOptions.projectedContextProvider` should be
replaced by this acquisition path only after the dependency/toolchain conflict is
cleared. A real `connect()` should:

1. Check availability using the Projected connection flow, or `XrDevice` where
   a broader XR device lifecycle is needed.
2. Create and store the projected device context.
3. Verify projected-device permissions before enabling hardware operations.
4. Move to `ConnectionState.Connected` only after projected context creation and
   permission checks succeed.

## Device Availability and ConnectionState

DP4 adds device-availability hooks so apps can react when the projected device is
connected or disconnected. The projected artifact provides
`ProjectedContext.isProjectedDeviceConnected(...)` as a connection flow, and the
runtime artifact exposes `XrDevice.getCurrentDevice(...)` plus an Android
`Lifecycle` for XR-device state.

Recommended mapping:

| DP4 state/signal | `GlassesClient` mapping |
| --- | --- |
| No projected device connected | `ConnectionState.Disconnected`; `connect()` should fail with `GlassesError.NotConnected` or `Unsupported` while preview-gated |
| Device flow emits connected and projected context is created | `ConnectionState.Connecting` during setup, then `ConnectionState.Connected` |
| Projected device lifecycle drops below active or flow emits disconnected | stop mic/display resources, close projected controllers, then `ConnectionState.Disconnected` |
| Projected service unavailable on this host/device | `GlassesError.Unsupported` with an Android XR DP4 message |

This mapping should be unit-tested without hardware by isolating the boolean
availability-to-state logic from any AndroidX classes.

## Hardware Mapping to GlassesClient

DP4 does not introduce a separate camera/microphone/speaker API for glasses.
The hardware-access guide maps those operations back to standard Android APIs
created from the projected device context.

| `GlassesClient` surface | DP4 API path | Current scaffold behavior |
| --- | --- | --- |
| `connect()` | `ProjectedContext.isProjectedDeviceConnected(...)`, then `ProjectedContext.createProjectedDeviceContext(...)`; optionally `XrDevice.getCurrentDevice(...)` for lifecycle/capability context | Bridge-only projected context provider; otherwise `GlassesError.Unsupported` |
| `capturePhoto()` | Use the projected context with Android camera APIs, for example Camera2/CameraX created from that context; request projected `CAMERA` permission first | Bridge-only; unsupported without integrator-provided capture lambda |
| `display(text)` | Launch/render an Activity on the projected display using `ProjectedContext.createProjectedActivityOptions(...)`; `ProjectedDisplayController` can observe/control projected presentation flags | Bridge-only; unsupported without integrator-provided display lambda |
| `startMicrophone()` | Use projected context with normal microphone APIs such as `AudioRecord`; request projected `RECORD_AUDIO` permission first | Bridge-only; unsupported without integrator-provided microphone lambda |
| `playAudio(AudioSource.Tts)` | Use projected context with Android TTS/audio output routing; projected output support is exposed through device/controller audio devices | Bridge-only; `canPlayTts=false` in assumed capabilities |
| `playAudio(AudioSource.RawBytes)` | Use projected context and Android audio APIs such as `AudioTrack`; verify output device routing | Bridge-only; unsupported without integrator-provided audio lambda |
| Events | `ProjectedActivityCompat.projectedInputEvents` emits projected input events; DP4 currently exposes actions such as `TOGGLE_APP_CAMERA` | Not wired; `supportsTapEvents=false` |

The capability model should stay conservative. DP4's
`ProjectedDeviceController.Capability` currently exposes `CAPABILITY_VISUAL_UI`,
not a complete stable SDK capability matrix for still camera, microphone, TTS,
or speaker playback. Do not infer support solely from a connected projected
context.

## Projected Permissions

Projected hardware access is not covered by a host-phone permission grant alone.
The DP4 permissions APIs include `ProjectedPermissionsResultContract`,
`ProjectedPermissionsRequestParams`, and host/projected helper activities. The
adapter must request and verify projected-device permission grants before
starting camera or microphone paths.

Required host manifest/runtime permissions remain:

- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO`

The adapter should fail loudly with `GlassesError.PermissionDenied` when the
projected-device permission result denies a required hardware permission.

## ProjectedTestRule Scope

`androidx.xr.projected:projected-testing` contains `ProjectedTestRule`. The
alpha09 class surface can fake:

- projected-device connection state with `isDeviceConnected` /
  `setDeviceConnected`;
- projected device capabilities;
- projected audio devices;
- projected display presentation mode and layout flags;
- projected lifecycle state;
- projected input events;
- projected battery state;
- launching a test projected-device Activity.

This is useful for Android instrumented or Robolectric-style tests of connection
state mapping, controller setup, display flag logic, input events, and projected
audio-device discovery. It does not prove real glasses camera pixels,
microphone samples, speaker routing, or physical display output. Those paths
still need an Android XR glasses emulator/device rig and manual or device-lab
evidence.

Do not wire ProjectedTestRule tests into CI until the repo has a stable Android
XR test target and compileSdk/toolchain path for the selected preview artifacts.

## What DP4 Provides vs. What Is Still Missing

DP4 provides:

- projected-context creation and detection;
- projected-device connection availability;
- projected Activity launch/display options;
- projected display controller flags and presentation mode callbacks;
- projected input events;
- projected device controller capability/audio/battery surfaces;
- projected-device permission result plumbing;
- ProjectedTestRule fakes.

Still missing or not yet proven in this repository:

- compileSdk 36 compatibility for the full guide-pinned AI glasses dependency
  set;
- a stable API contract for camera/microphone/speaker capability discovery;
- end-to-end proof that Camera2/CameraX captures from glasses hardware in this
  module;
- end-to-end proof that `AudioRecord`, TTS, or `AudioTrack` route to the
  projected glasses hardware;
- a text-display host Activity owned by this SDK module;
- CI-safe Android XR emulator/device coverage;
- publication readiness.

## Beta-Time Upgrade Checklist

When the core XR libraries move to Beta, revisit this module in this order:

1. Re-check Google Maven latest versions and release notes for `runtime`,
   `projected`, `projected-testing`, `glimmer`, `glimmer-google-fonts`, and
   `arcore`.
2. Confirm the selected set passes AAR metadata with the repo compileSdk and
   AGP, or raise the repo compileSdk deliberately.
3. Add catalog entries only for the artifacts actually used by code.
4. Replace `AndroidXrOptions.projectedContextProvider` with
   `ProjectedContext.createProjectedDeviceContext(...)` plus availability
   observation.
5. Add a small pure mapping layer for projected availability/lifecycle to
   `ConnectionState`, with JVM tests.
6. Add projected permission request/result handling before camera and
   microphone operations.
7. Implement camera capture using standard Android camera APIs from the
   projected context, then validate with real projected-device evidence.
8. Implement microphone capture and audio playback with explicit projected
   routing checks.
9. Decide whether display belongs in this adapter or in an app-owned projected
   Activity surface, then wire `display(text)` accordingly.
10. Add ProjectedTestRule tests for controller/display/input/lifecycle behavior
    only after the selected artifacts compile in this repo.
11. Keep the module unpublished until real-device or XR-emulator evidence covers
    connect, capture, display, mic, and audio.

## Source Build Only

The module remains source-build-only:

```kotlin
include(":device-android-xr")
project(":device-android-xr").projectDir = file("devices/device-android-xr")
```

Applications should not add:

```kotlin
implementation(project(":device-android-xr"))
```

until the preview dependency set, runtime hardware behavior, permissions, and
tests are validated.
