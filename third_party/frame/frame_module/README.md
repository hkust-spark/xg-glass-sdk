# Frame Flutter Module (no-UI) for xgglass

This folder contains a **Flutter-module template** that exposes Frame capabilities to Android via:

- MethodChannel name: `xgglass/frame/methods`
- (Bidirectional) MethodChannel callback: Flutter calls `onEvent` to push events to Android

The channel names and payload formats follow:

- `xgglass/devices/device-frame-flutter/.../FrameFlutterChannelContract.kt`

## Why this exists

App developers should not have to embed Flutter themselves.

- During **in-repo development**, `xgglass` includes the generated module (`third_party/frame/frame_module/.android/...`) and owns a `FlutterEngine` internally (`device-frame-embedded`).
- For **real distribution**, the goal is to publish a **prebuilt Flutter AAR** from this module and ship it as part of the universal SDK, so app developers only depend on the SDK.

## Local dependencies

This module depends on the Frame Flutter packages:

- `frame_ble`
- `frame_msg`

They are pulled from upstream Git (see `pubspec.yaml`).

## Prerequisite

You need Flutter installed locally to generate the module scaffolding (`.android/`, Gradle glue, etc.).

## Create / regenerate the Flutter module locally

From repo root:

```bash
flutter create -t module xg-glass-sdk/third_party/frame/frame_module
```

Then **merge** the files from this repo version back in (because `flutter create` may overwrite some):

- `pubspec.yaml` (merge deps/assets)
- `lib/main.dart`
- `lib/universal_frame_bridge.dart`
- `assets/lua/*`

## What it provides

- `connect`: scans + connects via `frame_ble`, uploads Lua libs + `xgglass_frame_app.lua`, starts frameside loop
- `capturePhoto`: sends `TxCaptureSettings` via `sendMessage` and returns a full JPEG (`Uint8List`)
- `displayText`: sends `TxPlainText` via `sendMessage`
- events: state/log/tap (sent to Android via `onEvent`)
