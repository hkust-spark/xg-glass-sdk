# RayNeo Runtime Adapter

`device-rayneo-runtime` is the on-glasses Android runtime adapter for RayNeo
glasses. It runs inside the glasses app process and uses stock Android APIs for
camera, display, microphone, playback, and battery state.

## What Works

- Camera capture through Camera2.
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

## Hardware Verification Checklist

- Confirm the runtime receives the sticky battery broadcast on the glasses.
- Confirm battery changes emit `GlassesEvent.BatteryLevel(percent)` and duplicate
  unchanged percentages are ignored.
- Confirm Camera2 capture, microphone capture, and playback behavior on the
  target RayNeo hardware.
