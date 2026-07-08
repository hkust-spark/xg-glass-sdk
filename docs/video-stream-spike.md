# Video Stream API Spike

Design-only spike for the 0.3 camera streaming surface. This document is
internal planning material and is deliberately not linked from `llms.txt`.

## Problem

The SDK currently exposes camera access as single-shot `capturePhoto`.
That is enough for snapshots, but AI assistant products need continuous
frames for low-latency perception loops. The new API should rhyme with the
existing microphone API instead of inventing a separate streaming model:
`AudioChunk` carries bytes, format, sequence, timestamp, and EOS metadata
(`kotlin/core/src/commonMain/kotlin/com/xgglass/core/Audio.kt:88-95`);
`MicrophoneSession` exposes a hot `Flow` plus `stop()`
(`kotlin/core/src/commonMain/kotlin/com/xgglass/core/Audio.kt:140-149`);
`PushMicrophoneSession` centralizes `MutableSharedFlow` production with
`DROP_OLDEST` overflow and sequence-gap detection
(`kotlin/core/src/commonMain/kotlin/com/xgglass/core/PushMicrophoneSession.kt:25-55`).

The current still-image type already carries optional dimensions and
`rotationDegrees`, which should carry forward to streaming frames
(`kotlin/core/src/commonMain/kotlin/com/xgglass/core/Models.kt:98-115`).

## Use Cases

- Continuous vision for AI assistants: send a low-rate frame stream to a VLM
  while the assistant listens or reads user context.
- Viewfinder or preview: give the app a live image to help users aim or confirm
  what the glasses camera sees.
- QR and visual code scanning: provide enough temporal density for scan
  libraries without making apps poll `capturePhoto`.

## Requirements

### Frame Rate Tiers

The API should expose intent-based tiers rather than requiring every adapter to
honor an arbitrary integer FPS:

<!-- markdownlint-disable MD013 -->
| Tier | Target | Primary use | Notes |
| --- | ---: | --- | --- |
| `SLOW` | 0.5 to 1 fps | battery-safe VLM context refresh | Acceptable for background scene awareness. |
| `LOW` | 2 to 3 fps | VLM loop with user-visible latency | Good default for first release. |
| `MEDIUM` | 5 to 10 fps | QR scanning, coarse preview | Requires real streaming transport. |
| `HIGH` | 15 fps | responsive preview | Matches current Meta Android photo stream config in repo. |
| `NATIVE` | adapter-selected | demos and device-specific apps | Adapter must report actual format/FPS. |
<!-- markdownlint-enable MD013 -->

Meta Android currently creates DAT streams with `frameRate = 15` for still
capture setup (`devices/device-meta/src/main/java/com/xgglass/device/meta/MetaWearablesGlassesClient.kt:470-474`).
Meta iOS currently configures a raw stream at `frameRate: 24` before photo
capture (`Sources/XgGlassMeta/MetaGlassesClient.swift:385-389`). Those are
evidence points, not a cross-device default.

### Resolution Negotiation

`VideoStreamOptions` should use preferences, not guarantees. Each session
returns the actual `VideoFormat`, and each frame repeats format metadata so a
device can renegotiate after rotation, thermal throttling, or camera restart.
The Android runtime clients already negotiate still capture size against the
camera's supported output list: INMO chooses the closest supported JPEG size
(`devices/device-inmo-runtime/src/main/java/com/xgglass/device/inmo/runtime/InmoRuntimeGlassesClient.kt:397-418`)
and RayNeo does the same (`devices/device-rayneo-runtime/src/main/java/com/xgglass/device/rayneo/runtime/RayNeoRuntimeGlassesClient.kt:378-408`).

### Formats

MVP should support JPEG frames first:

- Existing `CapturedImage` is JPEG-only
  (`kotlin/core/src/commonMain/kotlin/com/xgglass/core/Models.kt:98-100`).
- Simulator video mode already extracts a bitmap and compresses it to JPEG
  (`devices/device-simulator/src/main/java/com/xgglass/device/sim/SimulatorGlassesClient.kt:492-514`).
- BLE devices that can return photos already return chunked JPEG or photo bytes
  rather than raw camera buffers (`devices/device-omi-ios/src/commonMain/kotlin/com/xgglass/device/omi/ios/OmiPhotoAssembler.kt:18-25`).

The type system should leave room for `YUV_420_888`, `NV21`, `RGBA_8888`, and
Meta-specific raw formats. Android Camera2 supports repeating preview-style
requests through `CameraCaptureSession.setRepeatingRequest` with
`CameraDevice.TEMPLATE_PREVIEW` ([android-camera2]).

### Backpressure

Video is higher-volume than events or microphone audio. The default policy
should be low-latency:

- Default overflow: drop oldest frame.
- Session-local dropped count: `VideoStreamSession.droppedFrameCount`.
- Per-frame sequence: consumers can detect gaps like audio consumers can detect
  lost chunks (`kotlin/core/src/commonMain/kotlin/com/xgglass/core/PushMicrophoneSession.kt:45-55`).
- No use of `GlassesClient.events` for video bytes. Events already have a
  bounded buffer and a dropped-event precedent
  (`kotlin/core/src/commonMain/kotlin/com/xgglass/core/BaseGlassesClient.kt:33-58`).

Camera callback threads should not suspend on slow collectors. `SUSPEND` can be
available for tests, but product adapters should default to drop-oldest and
record drops.

### Battery and Thermal

Streaming should be explicit, short-lived, and one-session-at-a-time per client.
The default tier should be `LOW` or lower. Adapters may downshift FPS or stop
with a transport error if the platform reports camera, thermal, or battery
pressure. Display or microphone sessions should not implicitly start video; apps
must opt in with `startVideoStream`.

## API Proposal

The API should be KMP-first and mirror the microphone surface:

```kotlin
enum class VideoFrameEncoding {
    JPEG,
    YUV_420_888,
    NV21,
    RGBA_8888,
    META_RAW,
}

enum class VideoFrameRateTier {
    SLOW,
    LOW,
    MEDIUM,
    HIGH,
    NATIVE,
}

data class VideoFormat(
    val encoding: VideoFrameEncoding,
    val width: Int? = null,
    val height: Int? = null,
    val framesPerSecond: Int? = null,
)

data class VideoStreamOptions(
    val preferredEncoding: VideoFrameEncoding = VideoFrameEncoding.JPEG,
    val preferredWidth: Int? = 640,
    val preferredHeight: Int? = 480,
    val frameRateTier: VideoFrameRateTier = VideoFrameRateTier.LOW,
    val timeoutMs: Long = 30_000,
)

data class VideoFrame(
    val bytes: ByteArray,
    val format: VideoFormat,
    val sequence: Long,
    val timestampMs: Long = nowMillis(),
    val rotationDegrees: Int? = null,
    val endOfStream: Boolean = false,
)

interface VideoStreamSession {
    val format: VideoFormat
    val frames: Flow<VideoFrame>
    val droppedFrameCount: Long
    suspend fun stop()
}

interface GlassesClient {
    suspend fun startVideoStream(options: VideoStreamOptions = VideoStreamOptions()):
        Result<VideoStreamSession>
}
```

`DeviceCapabilities` should add:

```kotlin
val canStreamVideo: Boolean = false
val supportedVideoFormats: Set<VideoFrameEncoding> = emptySet()
```

If Kotlin/Native source-jar stability matters, consider using a list rather
than a set. The capability should mean "the adapter exposes a streaming
session", not "the hardware has any camera". Current capability fields already
separate still capture, display, microphone, playback, tap events, and streaming
text updates (`kotlin/core/src/commonMain/kotlin/com/xgglass/core/Models.kt:33-57`).

### Relationship to `capturePhoto`

`capturePhoto` stays as the still-image API. It may internally consume one
frame from `startVideoStream` on devices where that preserves behavior, but the
interface should not require this. Some devices have a higher-quality still path
or a special SDK request for photos:

- Meta Android starts a short-lived DAT stream, waits for `STREAMING`, calls
  `stream.capturePhoto()`, then removes the stream
  (`devices/device-meta/src/main/java/com/xgglass/device/meta/MetaWearablesGlassesClient.kt:170-199`).
- Rokid uses `takeGlassPhoto`, then `syncSingleFile`, then reads the local file
  (`devices/device-rokid/src/main/java/com/xgglass/device/rokid/RokidGlassesClient.kt:140-168`).
- Omi writes command `0x05` to request a single photo after enabling photo data
  notifications (`devices/device-omi/src/main/java/com/xgglass/device/omi/OmiGlassesClient.kt:186-243`).

Those should remain allowed even after streaming exists.

## Feasibility Matrix

<!-- markdownlint-disable MD013 -->
| Adapter | Verdict | Evidence | Implementation notes |
| --- | --- | --- | --- |
| Simulator Android | Phase 1 reference implementation | `capturePhoto` already supports a local video source and loops it at original frame rate (`devices/device-simulator/src/main/java/com/xgglass/device/sim/SimulatorGlassesClient.kt:50-63`); the CLI can push `--local_video` or `--video_url` to `/data/local/tmp/xg_glass_sim_video.mp4` (`tools/xg_glass_cli/cli.py:84-96`, `tools/xg_glass_cli/adb.py:106-113`); `capturePhotoFromVideo` extracts the frame at the current virtual playback head and JPEG-encodes it (`devices/device-simulator/src/main/java/com/xgglass/device/sim/SimulatorGlassesClient.kt:475-523`). | Convert the existing playback head into a coroutine ticker that emits frames. This is the hardware-free E2E path. |
| INMO Air3 runtime | Phase 1 yes | The adapter runs on standalone Android 14 and uses stock Android APIs (`devices/device-inmo-runtime/src/main/java/com/xgglass/device/inmo/runtime/InmoRuntimeGlassesClient.kt:47-53`); still capture opens Camera2, creates a JPEG `ImageReader`, and uses `TEMPLATE_STILL_CAPTURE` (`devices/device-inmo-runtime/src/main/java/com/xgglass/device/inmo/runtime/InmoRuntimeGlassesClient.kt:278-349`). | Replace one-shot capture with a long-lived session using preview or image-analysis output. Android Camera2 has repeating requests for continuous preview ([android-camera2]). |
| RayNeo runtime | Phase 1 yes | The adapter is vendor-SDK-free and says `capturePhoto()` uses Camera2 single JPEG capture (`devices/device-rayneo-runtime/src/main/java/com/xgglass/device/rayneo/runtime/RayNeoRuntimeGlassesClient.kt:47-55`); the implementation opens Camera2, chooses a supported JPEG size, listens for `ImageReader` output, and fires `TEMPLATE_STILL_CAPTURE` (`devices/device-rayneo-runtime/src/main/java/com/xgglass/device/rayneo/runtime/RayNeoRuntimeGlassesClient.kt:271-337`). | Same implementation shape as INMO. Use one camera session, repeating requests, and the same permission/busy policy. |
| Meta Android | Phase 2 likely, needs runtime validation | The adapter depends on DAT core/camera/display artifacts (`devices/device-meta/build.gradle.kts:19-21`) and already imports `Stream`, `StreamConfiguration`, `StreamState`, and `VideoQuality` (`devices/device-meta/src/main/java/com/xgglass/device/meta/MetaWearablesGlassesClient.kt:17-23`); it creates a DAT stream at 15 fps for photo capture (`devices/device-meta/src/main/java/com/xgglass/device/meta/MetaWearablesGlassesClient.kt:463-492`). Meta's public Android DAT reference describes the camera package as video streaming plus photo capture ([meta-android-camera]). | Existing code proves stream lifecycle, but the adapter currently consumes still `PhotoData`, not frame flow (`devices/device-meta/src/main/java/com/xgglass/device/meta/MetaWearablesGlassesClient.kt:749-764`). Validate DAT `Stream.videoStream` frame format, permissions, and lifecycle before promising 0.3 support. |
| Meta iOS | Phase 2 likely, parallel to Android | The Swift adapter creates an `MWDATCamera.Stream` with raw codec, low resolution, and 24 fps (`Sources/XgGlassMeta/MetaGlassesClient.swift:385-389`), waits for `.streaming` (`Sources/XgGlassMeta/MetaGlassesClient.swift:428-449`), and listens to `photoDataPublisher` for still capture (`Sources/XgGlassMeta/MetaGlassesClient.swift:453-469`). Meta's public iOS DAT package is the vendor source for this adapter ([meta-ios-dat]). | Possible only if DAT iOS exposes frame publisher data with stable format and lifecycle. Do not block phase 1 on it. |
| Rokid CXR-M | Unknown, not phase 1 | Current adapter requires Bluetooth plus Wi-Fi P2P before media sync (`devices/device-rokid/src/main/java/com/xgglass/device/rokid/RokidGlassesClient.kt:59-70`); photo capture calls `CxrApi.takeGlassPhoto(...)`, then `CxrApi.syncSingleFile(...)` for the remote picture (`devices/device-rokid/src/main/java/com/xgglass/device/rokid/RokidGlassesClient.kt:430-484`). Public CXR-M docs found online describe CXR-M as phone-to-glasses over BLE plus Wi-Fi Direct and include file/media APIs, but I did not find a public CXR-M continuous camera stream method ([rokid-public-docs]). | Treat as still-photo/file-transfer until vendor docs or hardware prove a streaming camera API. Do not emulate streaming by repeatedly triggering photos. |
| Frame | Out of scope for continuous video | The Kotlin Frame client delegates `capturePhoto` to a Flutter bridge (`devices/device-frame-flutter/src/main/java/com/xgglass/device/frame/flutter/FrameGlassesClient.kt:119-121`); the bridge contract lists `capturePhoto`, display, and microphone methods, but no video stream method (`devices/device-frame-flutter/src/main/java/com/xgglass/device/frame/flutter/FrameFlutterChannelContract.kt:13-31`); the embedded bridge returns a single `ByteArray` as `CapturedImage` (`devices/device-frame-embedded/src/main/java/com/xgglass/device/frame/embedded/EmbeddedFrameFlutterBridge.kt:162-179`). | BLE bandwidth makes polling unsuitable. See the BLE math below. At most, expose low-rate photo polling in an app-specific layer, not the flagship stream API. |
| Omi Android | Out of scope for continuous video | Baseline capability starts as `canCapturePhoto = false` until photo characteristics are discovered (`devices/device-omi/src/main/java/com/xgglass/device/omi/OmiGlassesClient.kt:77-88`, `devices/device-omi/src/main/java/com/xgglass/device/omi/OmiGlassesClient.kt:338-348`); capture enables photo notifications, writes command `0x05`, and assembles chunked photo data until an EOF marker (`devices/device-omi/src/main/java/com/xgglass/device/omi/OmiGlassesClient.kt:186-243`, `devices/device-omi/src/main/java/com/xgglass/device/omi/OmiGlassesClient.kt:400-423`). | This is a single-photo BLE protocol, not a stream protocol. Continuous video would be bounded by BLE throughput and firmware behavior. |
| Omi iOS | Out of scope for continuous video | The iOS adapter mirrors the BLE photo path: photo control/data characteristics gate capture capability (`devices/device-omi-ios/src/iosMain/kotlin/com/xgglass/device/omi/ios/OmiIosGlassesClient.kt:568-586`), notification packets are routed to `OmiPhotoAssembler` (`devices/device-omi-ios/src/iosMain/kotlin/com/xgglass/device/omi/ios/OmiIosGlassesClient.kt:624-628`), and the assembler documents chunked JPEG packet IDs plus an EOF marker (`devices/device-omi-ios/src/commonMain/kotlin/com/xgglass/device/omi/ios/OmiPhotoAssembler.kt:18-25`). | Same BLE conclusion as Android. |
| Even G1 | N/A | Even starts with `canCapturePhoto = false` (`devices/device-even/src/androidMain/kotlin/com/xgglass/device/even/EvenGlassesClient.kt:76-86`) and `capturePhoto` always returns unsupported because the adapter has no camera path (`devices/device-even/src/androidMain/kotlin/com/xgglass/device/even/EvenGlassesClient.kt:225-226`). | No camera API to stream. |
<!-- markdownlint-enable MD013 -->

## BLE Bandwidth Ceiling

LE 2M PHY is a 2 Mbps raw physical layer ([bluetooth-le-2m]). Real application
throughput is lower after inter-frame spacing, connection interval, ATT/L2CAP
headers, retransmits, and mobile OS scheduling; Nordic's throughput material
uses about 1.3 Mbps as a practical 2M PHY example ([nordic-throughput]).

Using the optimistic 1.3 Mbps number:

- 1.3 Mbps / 8 = 162.5 kB/s before protocol and app overhead.
- Raw YUV 4:2:0 at 640 x 480 is `640 x 480 x 1.5 = 460,800` bytes, so even
  the optimistic transport time is about 2.8 seconds per frame, below 0.4 fps.
- JPEG at 50 kB costs about 0.31 seconds on the link, or about 3.2 fps before
  BLE overhead, GATT chunking, camera exposure, device processing, and ACKs.
- JPEG at 100 kB costs about 0.62 seconds, or about 1.6 fps before overhead.

That ceiling is below the `MEDIUM` and `HIGH` tiers and fragile even for
`LOW`. Frame and Omi should therefore remain outside the continuous video
stream API unless their vendors expose a non-BLE transport or a device-side
streaming primitive.

## 0.3 Phase Plan

### Phase 1: API, Simulator, Android Runtime Devices

- Add `VideoFrame`, `VideoFormat`, `VideoStreamOptions`,
  `VideoStreamSession`, and `GlassesClient.startVideoStream`.
- Add `DeviceCapabilities.canStreamVideo` and supported format reporting.
- Implement a `PushVideoStreamSession` patterned after `PushMicrophoneSession`,
  with drop-oldest default and `droppedFrameCount`.
- Implement simulator streaming from the existing local-video playback state.
- Implement INMO Air3 and RayNeo runtime streaming with stock Android Camera2
  repeating requests or CameraX `ImageAnalysis`.
- Keep `capturePhoto` behavior unchanged.

### Phase 1 Verification

Use the existing CLI simulator video path as the nightly E2E story:

1. Generate or include a short MP4 with deterministic visual markers, for
   example frame number, color block, and QR code phases.
2. Run the generated simulator app with `xg-glass run --sim --local_video`.
   The CLI already validates `--local_video`/`--video_url` with `--sim`
   (`tools/xg_glass_cli/cli.py:84-96`) and pushes the file through ADB
   (`tools/xg_glass_cli/adb.py:106-113`).
3. Collect a fixed number of `VideoFrame` emissions and assert monotonic
   sequence, timestamps, non-empty bytes, expected dimensions, and at least one
   decoded marker.
4. Run the same flow in CI without physical glasses.

### Phase 2: Meta DAT

Validate Meta Android and Meta iOS on real devices and mocks if available:

- Android: confirm DAT `Stream.videoStream` frame type, encoding, timestamp,
  and backpressure behavior against the public camera package ([meta-android-camera]).
- iOS: confirm whether `MWDATCamera.Stream` exposes continuous frames beyond
  `photoDataPublisher`; the current Swift adapter only consumes still photo data
  (`Sources/XgGlassMeta/MetaGlassesClient.swift:453-469`).
- Decide whether Meta should emit raw frames, compressed frames, or both.

### Explicitly Out of Scope

- Frame and Omi continuous video over BLE. The bandwidth math above makes this
  unsuitable for the flagship streaming API.
- Rokid streaming until a vendor-supported stream API is documented or proven
  on hardware.
- Even G1 camera support, because the adapter exposes no camera path.

### iOS Story

The iOS simulator client currently renders placeholder JPEG stills rather than
reading a video source (`devices/device-simulator-ios/src/iosMain/kotlin/com/xgglass/device/sim/ios/SimulatorIosGlassesClient.kt:68-84`).
For 0.3, iOS streaming can follow after the Android reference path. The only
near-term real iOS candidate is Meta because the Swift adapter already uses
DAT stream lifecycle objects (`Sources/XgGlassMeta/MetaGlassesClient.swift:71-83`).
Omi iOS is BLE chunked-photo only, and Frame iOS stays behind the Flutter
method channel with no streaming contract.

## Open Questions

- Which encodings ship in 0.3: JPEG only, or JPEG plus one raw Android format?
- Should the API allow exactly one active video stream per client?
- What should happen when `capturePhoto` is called while video streaming?
- Should `capturePhoto` be permitted to use the active stream for a lower-quality
  still, or must it always use the existing still-capture path?
- Should options expose a hard numeric FPS or only tiered intent?
- How should apps request camera permission in KMP samples without hiding
  platform-specific policy?
- Should thermal/battery downshift be observable as an event, a new frame format
  value, or a terminal stream error?
- Should `VideoFrame` include an optional monotonic timestamp in addition to
  epoch `timestampMs`?
- Should `supportedVideoFormats` include resolution/FPS tuples instead of only
  encodings?

## Appendix: Draft GitHub Issue Body

```markdown
## Goal

Add a flagship 0.3 video streaming API for continuous glasses camera frames.
The current camera API is single-shot `capturePhoto`; assistant use cases need
low-latency frame streams for VLM context, preview/viewfinder, and QR scanning.

## Proposed API

- `GlassesClient.startVideoStream(options): Result<VideoStreamSession>`
- `VideoStreamSession.frames: Flow<VideoFrame>` plus `stop()`
- `VideoFrame`: bytes, format, sequence, timestamp, rotation, EOS
- `DeviceCapabilities.canStreamVideo` and supported video formats
- Drop-oldest default backpressure with a session-local dropped-frame counter

The design should mirror the existing microphone API and keep `capturePhoto`
as the still-image API.

## Phase 1

- Core API types and `PushVideoStreamSession`
- Android simulator streaming from the existing `--local_video`/`--video_url`
  video feed
- INMO Air3 runtime streaming with stock Android camera APIs
- RayNeo runtime streaming with stock Android camera APIs
- Nightly no-hardware E2E using a deterministic simulator MP4

## Phase 2

- Meta Android/iOS DAT validation. The repo already creates DAT streams for
  still capture, but continuous frame format and lifecycle need real validation.

## Out of Scope

- Frame and Omi continuous video over BLE. LE 2M practical throughput is too low
  for preview/AI frame streaming.
- Rokid streaming until a vendor-supported continuous camera API is documented
  or proven on hardware.
- Even G1 because the adapter exposes no camera path.

## Open Questions

- JPEG-only MVP or raw frame support in 0.3?
- One stream per client?
- `capturePhoto` behavior during active stream?
- Numeric FPS vs tiered FPS?
- Permission and thermal/battery reporting model?
```

[android-camera2]: https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession#setRepeatingRequest(android.hardware.camera2.CaptureRequest,%20android.hardware.camera2.CameraCaptureSession.CaptureCallback,%20android.os.Handler)
[bluetooth-le-2m]: https://www.bluetooth.com/learn-about-bluetooth/tech-overview/
[meta-android-camera]: https://wearables.developer.meta.com/docs/reference/android/dat/0.8/com_meta_wearable_dat_camera/
[meta-ios-dat]: https://github.com/facebookincubator/MetaWearablesDATSwift
[nordic-throughput]: https://devzone.nordicsemi.com/nordic/nordic-blog/b/blog/posts/throughput-and-long-range-demo
[rokid-public-docs]: https://github.com/buildwithfenna/rokid-docs
