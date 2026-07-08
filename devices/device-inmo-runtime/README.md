# INMO Air3 Runtime Adapter

`device-inmo-runtime` is the on-glasses Android runtime adapter for INMO Air3.
It is intended to run inside a foreground Activity installed directly on the
glasses.

## What Works

- Camera capture through Camera2.
- Text display through a pluggable `InmoDisplaySink`; the default sink shows a
  Toast.
- PNG/JPEG image display through `displayImage`; the default sink decodes the
  encoded bytes and shows them in an Android `ImageView`-backed Toast.
- Microphone capture through the shared Android `AudioRecord` helper.
- Raw/encoded audio playback through the shared Android audio helpers.
- Single-tap and long-press events when the host Activity forwards key events.
- Battery-level events from Android's on-device battery APIs.

## No Vendor SDK

Air3 is a standalone Android 14 device. The known `com.inmo:inmo_arsdk:0.0.1`
artifact is a small 6DOF-pose AAR and is unnecessary for camera, microphone,
display, audio, or touch input. This module intentionally has no INMO vendor
dependency and no `third_party/inmo` assets.

## Host Key Forwarding

Forward `Activity.onKeyDown` to the client:

```kotlin
private val inmoClient = InmoRuntimeGlassesClient(this)

override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (inmoClient.onHostKeyEvent(keyCode)) return true
    return super.onKeyDown(keyCode, event)
}
```

The current SDK maps `KEYCODE_ENTER` (`66`) to `GlassesEvent.Tap(1)`. Air2
documentation maps DPAD `19/20/21/22` to swipes and `289/290` to long-press
gestures; `289/290` are emitted as `GlassesEvent.LongPress` while swipes remain
unhandled until the cross-device input API lands.

## Battery Events

`supportsBatteryEvents` is always `true` because the runtime runs on the glasses.
On connect, the adapter registers `ACTION_BATTERY_CHANGED`, consumes the sticky
initial broadcast, falls back to `BatteryManager.BATTERY_PROPERTY_CAPACITY` if
needed, and emits `GlassesEvent.BatteryLevel(percent)` only when the integer
percentage changes by at least 1%. Percent values are clamped to `0..100`.

## Hardware Verification Checklist

- Confirm Air3 keycodes match the Air2 table: ENTER=66, DPAD=19/20/21/22,
  BACK=4, long-press=289/290.
- Check whether launcher/system gestures consume double-tap BACK before the app
  receives it.
- Confirm Camera2 support level, supported JPEG sizes, and reported
  `SENSOR_ORIENTATION` on production Air3 hardware.
- Confirm `displayImage` renders PNG and JPEG payloads legibly on production
  Air3 display hardware for `FIT`, `FILL`, and `CENTER` scale modes.
- Confirm whether a usable on-device TTS engine is present; until then
  `AudioSource.Tts` is reported unsupported.
- Confirm battery changes emit `GlassesEvent.BatteryLevel(percent)` and duplicate
  unchanged percentages are ignored.

## Extract-vs-Clone Decision

This module intentionally clones the small RayNeo on-glasses runtime pattern
instead of extracting a shared base class. That keeps RayNeo behavior unchanged
while INMO-specific deltas are still being hardware validated. A mechanical
shared base for Camera2/audio/display helpers is a reasonable 0.3 refactor once
both runtimes have stable device coverage.
