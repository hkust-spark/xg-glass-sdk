# Simulator Adapter

`device-simulator` is the Android Emulator adapter for local development
without glasses hardware. It can use the emulator camera or a video file passed
through the CLI with `--local_video` / `--video_url`.

## What Works

- Photo capture through CameraX `ImageCapture`, or from the current playback
  position of the configured simulator video file.
- JPEG video streaming through `startVideoStream()`, using the same camera or
  video-file source as `capturePhoto()`.
- Text display through the generated host app display sink.
- PNG/JPEG image display through `displayImage`.
- Microphone capture through the shared Android `AudioRecord` helper.
- Text-to-speech and raw/encoded audio playback through Android media APIs.
- Synthetic tap, long-press, and battery events for hardware-free tests.

## Video Streaming Behavior

Phase 1 streaming is JPEG-only. Requests for `YUV_420_888`, `NV21`,
`RGBA_8888`, or `META_RAW` fail fast with `GlassesError.Unsupported`.

Only one video stream may be active per client. A second `startVideoStream()`
returns `GlassesError.Busy`. `stop()` and `disconnect()` end the stream with an
end-of-stream frame.

During an active stream, `capturePhoto()` does not touch the camera or video
source directly. It returns the latest JPEG frame already produced by the
stream, or waits up to `CaptureOptions.timeoutMs` for the first frame and then
returns `GlassesError.Timeout("capturePhoto")`.

`VideoFrameRateTier` maps to tiered frame intervals: `SLOW` 1 fps, `LOW` 3 fps,
`MEDIUM` 8 fps, `HIGH` 15 fps, and `NATIVE` the video metadata fps when
available, otherwise 15 fps. The session reports the actual JPEG format and fps
in `VideoStreamSession.format`.
