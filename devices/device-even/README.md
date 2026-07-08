# Even Realities G1 Adapter

Android-first adapter for Even Realities G1 glasses.

## Status

- Connects to the G1 as two BLE peripherals, one left arm and one right arm.
- Uses the Nordic UART service exposed by both arms.
- Sends display text with the G1 text paging frame.
- Starts microphone capture on the right arm and forwards raw LC3 frames through `PushMicrophoneSession`.
- Maps simple TouchBar events to `GlassesEvent.Tap` and the Even AI begin packet (`0x17`) to `GlassesEvent.LongPress`.
- Battery events are not surfaced yet. The local community protocol notes mention `Get Battery State (0x2C 01)` and case-battery `F5` events, but the response layout, per-arm meaning, and unified aggregation rule are not source-traced clearly enough to emit `GlassesEvent.BatteryLevel`.
- Does not support camera capture. G1 has no camera path in this adapter.
- Does not support `displayImage` today. The G1 1-bit BMP display protocol is
  a future candidate, gated on hardware validation of the existing text display
  path and a device-appropriate image conversion policy.
- Does not support speaker playback, TTS, or raw audio playback.

This is a no-hardware implementation and needs validation on a real G1 before marking the adapter fully hardware-verified.

## Dual-Arm Model

G1 advertises paired peripherals whose names include a shared channel plus `_L_` or `_R_`, for example `G1_45_L_92333` and `G1_45_R_92334`. The adapter scans until it finds both arms for the same channel, connects both, discovers the UART service, enables notifications on both RX characteristics, then starts the heartbeat.

Most commands are sent to the left arm first and then to the right arm after the left acknowledgment. Microphone enable is sent to the right arm only.

## Audio

The microphone stream is LC3. The adapter does not decode it. Apps receive `AudioChunk` values with `format.encoding = AudioEncoding.LC3`, `sampleRateHz = 16000`, and one channel. Host-side decoding or ASR integration is the app's responsibility.

## Protocol Sources

- Official demo app: [even-realities/EvenDemoApp](https://github.com/even-realities/EvenDemoApp), local reference `/tmp/even-ref/EvenDemoApp` at commit `3899aac`.
- Community protocol notes: [AGiXT/mobile Even Realities G1 BLE Protocol.txt](https://github.com/AGiXT/mobile/blob/main/Even%20Realities%20G1%20BLE%20Protocol.txt), local reference `/tmp/even-ref/g1-ble-protocol.txt`.

Every byte and UUID constant in `EvenProtocol.kt` has a source-line citation back to those references.
