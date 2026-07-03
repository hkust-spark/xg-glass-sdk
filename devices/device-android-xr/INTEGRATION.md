# Android XR Scaffold Integration

This module is an unvalidated proof-of-concept scaffold for Google Android XR AI glasses through
the Jetpack XR projected-context model. It is included in the source build so the scaffold stays
visible during development, but its developer-preview dependencies and API surface still need
validation against the current Android XR SDK before it can be treated as functional.

> **Release note:** This is a non-functional preview scaffold. It is **not** published to Maven
> Central in 0.1.0, and applications should not depend on it.

## Dependency Pinning

`build.gradle.kts` currently includes the likely Jetpack XR artifacts below, each marked
`TODO(android-xr)`:

- `androidx.xr.runtime:runtime:1.0.0-alpha15`
- `androidx.xr.projected:projected:1.0.0-alpha09`
- `androidx.xr.glimmer:glimmer:1.0.0-alpha13`
- `androidx.xr.glimmer:glimmer-google-fonts:1.0.0-alpha13`
- `androidx.xr.arcore:arcore:1.0.0-alpha14`
- Optional test-only candidate: `androidx.xr.projected:projected-testing:1.0.0-alpha09`

Before treating this module as a supported adapter, verify every coordinate against the current Android
XR release notes and setup guide. Google's AI glasses setup guide has shown `projected` examples
with earlier alpha versions than the latest release notes, so do not assume the scaffold versions
are correct for a given emulator/device image.

## Projected Context Model

The intended model is:

- The phone app creates or receives a projected context for the connected AI glasses.
- Camera APIs created from that projected context capture from the glasses camera, not the phone.
- Audio recording created from that projected context reads the glasses microphone, not the phone.
- Audio playback created from that projected context routes to the glasses speakers.
- Display rendering happens inside the projected glasses display/activity surface.

The scaffold maps the `GlassesClient` operations this way:

- `connect()`: resolve and hold the projected context.
- `capturePhoto()`: use that projected context to capture a JPEG from the glasses camera.
- `startMicrophone()`: create a hot `MicrophoneSession` from the glasses microphone.
- `display(text)`: render text on the glasses display.
- `playAudio()`: play audio through the glasses speaker.

Exact API calls are stubbed behind `AndroidXrOptions` bridge lambdas and TODO comments in
`AndroidXrGlassesClient.kt`.

## Runtime Permissions

Host apps must still declare and request normal Android runtime permissions:

- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO`

Projected devices also require glasses-scoped permission grants for the hardware reached through
the projected context. Request and verify those permissions using the current Jetpack XR projected
permission APIs before calling capture or microphone operations. Do not treat a phone permission
grant as proof that glasses camera/microphone access is granted.

## GlassesModel

This scaffold adds `GlassesModel.ANDROID_XR` to `core/src/main/java/com/xgglass/core/Models.kt`.
That is an additive enum value so the scaffold can identify captured images and client model state
without using a misleading placeholder like `SIMULATOR`.

## Source Build Only

The module is already wired into the source build for preview work. Do not publish it, depend on it
from applications, or advertise it as supported until dependency coordinates and API calls are
validated. The source-build wiring is:

```kotlin
include(":device-android-xr")
project(":device-android-xr").projectDir = file("devices/device-android-xr")
```

Only after validation should an application dependency be considered:

```kotlin
implementation(project(":device-android-xr"))
```

## Validation Plan

Use a current Android XR AI glasses emulator image or hardware device with a projected app:

1. Pin the current Jetpack XR versions.
2. Replace `projectedContextProvider` with the verified projected-context API.
3. Request phone and glasses-scoped camera/microphone permissions.
4. Confirm `connect()` only succeeds with a valid projected context.
5. Capture a JPEG and verify it is from the glasses camera.
6. Start a microphone session and verify chunks come from the glasses microphone.
7. Render text and verify it appears on the glasses display.
8. Play audio and verify output routes to the glasses speakers.
9. Run disconnect/stop flows and confirm resources are released.

## Stubbed TODOs

- `connect()`: projected context resolution is not implemented by direct Jetpack XR calls.
- `capturePhoto()`: glasses camera capture is bridge-only.
- `startMicrophone()`: glasses microphone capture is bridge-only.
- `display(text)`: projected display rendering is bridge-only.
- `playAudio()`: glasses speaker playback is bridge-only.
- Dependency coordinates are developer-preview guesses and must be verified.

These stubs are intentional. The scaffold is structured for integration work without claiming that
unvalidated preview APIs compile or behave correctly in this environment.
