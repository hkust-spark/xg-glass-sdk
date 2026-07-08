# RayNeo Runtime Adapter

`device-rayneo-runtime` is the on-glasses Android runtime adapter for RayNeo
glasses. It runs inside the glasses app process and uses stock Android APIs for
camera, display, microphone, playback, and battery state.

## What Works

- Camera capture through Camera2.
- JPEG video streaming through Camera2 repeating requests.
- Text display through a pluggable `RayNeoDisplaySink`; the default sink shows a
  Toast.
- Microphone capture through the shared Android `AudioRecord` helper.
- Raw/encoded audio playback through the shared Android audio helpers.
- Battery-level events from Android's on-device battery APIs.

## Battery Events

`supportsBatteryEvents` is always `true` because the runtime runs on the glasses.
On connect, the adapter registers `ACTION_BATTERY_CHANGED`, consumes the sticky
initial broadcast, falls back to `BatteryManager.BATTERY_PROPERTY_CAPACITY` if
needed, and emits `GlassesEvent.BatteryLevel(percent)` only when the integer
percentage changes by at least 1%. Percent values are clamped to `0..100`.

## Video Streaming Behavior

Phase 1 streaming is JPEG-only. Requests for `YUV_420_888`, `NV21`,
`RGBA_8888`, or `META_RAW` fail fast with `GlassesError.Unsupported`.

Only one video stream may be active per client. A second `startVideoStream()`
returns `GlassesError.Busy`. `stop()` and `disconnect()` end the stream with an
end-of-stream frame.

`capturePhoto()` returns `GlassesError.Busy` while a video stream is active
because the Camera2 repeating session owns the camera. The stream uses the same
supported-JPEG-size negotiation as still capture and reports the selected
resolution, JPEG encoding, fps tier, sequence, and timestamp in stream/frame
metadata. Rotation remains `null`, matching RayNeo still capture behavior.

## Hardware Verification Checklist

- Confirm the runtime receives the sticky battery broadcast on the glasses.
- Confirm battery changes emit `GlassesEvent.BatteryLevel(percent)` and duplicate
  unchanged percentages are ignored.
- Confirm Camera2 capture and repeating JPEG stream behavior, microphone
  capture, and playback behavior on the target RayNeo hardware.

## Extract-vs-Clone Decision

This module intentionally keeps its Camera2 video-stream helper local instead of
sharing source with INMO. Both runtime artifacts ship together in
`universal-full`; sharing via a `srcDir` trick would create duplicate classes.
A mechanical shared base for Camera2/audio/display/video helpers is a reasonable
0.3 refactor once both runtimes have stable hardware coverage.
