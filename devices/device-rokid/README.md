# Rokid adapter

This module uses the Android **CXR-M 1.2.2** SDK (`com.rokid.cxr:client-m`) to
connect directly to Rokid Glasses with Bluetooth and Wi-Fi P2P. Supply the
device's SN license (`.lc`) and developer client secret through `RokidOptions`.
The host app owns Android runtime permission requests.

Rokid's **CXR-L** integration uses the official Rokid phone app as the connection
provider. It is a different integration route, not another version of this
adapter's CXR-M artifact. See the [official SDK overview](https://ar.rokid.com/sprite?lang=en).

## Migration and behavior

- CXR-M 1.2.2 changes the audio callback signatures and adds stream IDs and a
  finish callback. The adapter ignores retired connection callbacks and foreign
  audio stream IDs, and finishes each microphone session once.
- A Bluetooth or P2P loss invalidates the connection and pending operations.
  Call `connect()` again to reconnect. Explicit disconnect and connection timeout
  also tear down both transports and cancel pending display updates.
- Rapid `DisplayMode.APPEND` calls accumulate the complete desired document
  before transmission. Immediate vendor failures return a failed `Result`;
  failures of previously accepted, throttled updates emit a `GlassesEvent.Warning`.
  Display success indicates request acceptance, not a glasses-side rendering acknowledgment.
- 1.2.2's native libraries have 16 KB ELF alignment. Applications must still check
  their complete APK/AAB and test in a 16 KB environment; other dependencies can
  affect compatibility.

## Validation

Run the deterministic callback and display regressions with:

```sh
./gradlew :device-rokid:testDebugUnitTest
```

Tests cover queued APPEND, forced replacement, display teardown and rejection,
retired Bluetooth attempts, cancellation of pending operations, stream ID
correlation, invalid frame bounds, and microphone teardown. They use injected
display transport/scheduling and the SDK's actual callback interfaces. These are
JVM tests, not hardware integration tests.

Before claiming this migration is hardware validated, run these checks with real
glasses and record the glasses model, firmware, phone OS, and authorization setup:

1. Fresh scan and authenticated Bluetooth/P2P connection, then cached reconnect.
   Also check bad credentials and the `onInActiveConnected` callback. This callback
   remains diagnostic; the adapter requires the authorized `onConnected` event.
2. Capture a photo, verify the downloaded JPEG and dimensions, and capture again
   after reconnecting.
3. Display a complete document and a rapid stream of APPEND chunks. Disconnect
   while an update is queued, and confirm no display is reopened afterwards.
4. Record PCM and OPUS; verify the returned audio using the actual format reported
   by the device. The adapter uses recording mode **1** and denoise mode **2**.
   Denoise mode 2 is confirmed by the 1.2.2 bytecode's default overload; recording
   mode 1 and the opaque callback metadata still require device validation.
5. Stop recording twice, allow a device-initiated recording finish, then start a
   new session. Confirm that late callbacks cannot stop the new recording.
6. Exercise TTS and PCM playback on the glasses speakers, then disconnect during
   playback/recording.
7. Drop Bluetooth and Wi-Fi P2P independently, cancel during each setup phase,
   and let a connection time out. Verify state changes, resource cleanup, and a
   successful subsequent `connect()`.
8. Build the consuming APK/AAB, check its complete native-library and ZIP
   alignment, and launch on a 16 KB Android device or emulator.

Official artifact versions: [Rokid Maven metadata](https://maven.rokid.com/repository/maven-public/com/rokid/cxr/client-m/maven-metadata.xml).
Android packaging guidance: [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes).
