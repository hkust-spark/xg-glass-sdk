# Frame Flutter Bridge

`device-frame-flutter` is the Android bridge API and contract for Brilliant Labs Frame support in xg.glass.

It defines the Kotlin-side `FrameGlassesClient`, `FrameFlutterBridge`, and channel payload contract used to talk to a host-provided Flutter runtime. The channel names and payloads live in [`FrameFlutterChannelContract.kt`](./src/main/java/com/xgglass/device/frame/flutter/FrameFlutterChannelContract.kt), and the Flutter implementation follows the same contract in [`third_party/frame/frame_module/lib/universal_frame_bridge.dart`](../../third_party/frame/frame_module/lib/universal_frame_bridge.dart).

## What This Is Not

This artifact is not a complete working Frame integration by itself. The embedded Flutter module at [`third_party/frame/frame_module`](../../third_party/frame/frame_module) is not published to Maven Central in 0.1.0, so Maven consumers only get the bridge API/contract. A Frame app still needs a host that embeds the Flutter module and provides a `FrameFlutterBridge` implementation.

## How To Use Frame Today

Use the source/CLI flow for Frame:

1. Clone this SDK repository.
2. Generate an app from the template with `xg-glass init --sdk /path/to/xg-glass-sdk ...`.
3. Build and run from that generated project.

The [`templates/kotlin-app`](../../templates/kotlin-app) source flow wires the source SDK into the generated app. In that source build, `device-frame-embedded` owns the Flutter engine and uses the module under `third_party/frame/frame_module`, while this `device-frame-flutter` module provides the stable bridge API between Kotlin and Flutter.

Use Maven Central for the portable Frame bridge contract, and use the source/CLI flow when you need actual Frame hardware behavior today.
