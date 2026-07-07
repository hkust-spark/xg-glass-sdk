# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows semantic versioning.

## [Unreleased]

### Added

### Fixed

## [0.2.1] - 2026-07-07

### Added

- Added `GlassesEvent.LongPress` and `DeviceCapabilities.supportsLongPressEvents`, with Even G1, INMO Air3, and Simulator event support plus generated-app simulator buttons for hardware-free tap and long-press testing.
- Added a portable `scripts/sim-e2e.sh` simulator smoke test and nightly KVM GitHub Actions workflow with logcat, screenshot, UI XML, and per-step evidence artifacts.
- Added `xg-glass run --devices` for single-file quick mode, so generated quick-run projects can use the same adapter selection as `xg-glass init`.
- Added generated `llms-full.txt` docs ingestion output with a stdlib generator and CI freshness guard.
- Added Omi DevKit button-service mapping on Android and iOS, with tap capability gated on discovered button services and legacy long-press events emitted when field firmware sends them.
- Added Play Feature Delivery guidance for app-owned dynamic feature modules that defer heavy Frame and Meta Android adapters.
- Added a deterministic JVM fake-BLE harness covering Omi Android scan, GATT callback, notification, and descriptor-write flows without hardware.
- Added a deterministic dual-arm JVM fake-BLE harness covering Even G1 Android scan, GATT callback, notification, command-response, and disconnect flows without hardware.

### Fixed

- Expanded the published API-docs site to a Dokka aggregate covering every Maven-published SDK module while preserving existing `core/` and `app-contract/` links.
- Added nightly simulator quick-run E2E coverage and verified managed Android command-line tools downloads with pinned SHA-256 checksums.
- Frame adapters now map spontaneous runtime reconnect/drop events consistently on Android and iOS, and end active microphone streams on disconnect/drop so audio collectors do not hang.
- Surfaced rejected SDK event emissions through `BaseGlassesClient.droppedEventCount` and a one-shot warning when `SUSPEND` event buffers overflow.

### Notes

- Patch-style release: all changes since 0.2.0 are additive or fixes, with no breaking API changes.

## [0.2.0] - 2026-07-07

### Added

- Even Realities G1 adapter on Android and iOS, including dual-BLE left/right arm connection, paged display output, LC3 microphone passthrough, and tap events.
- INMO Air3 on-glasses Android runtime adapter, including camera, display, microphone, raw/encoded audio playback, and tap events through host Activity key forwarding.
- Meta iOS microphone capture over Bluetooth HFP, with actual sample-rate reporting and route validation so the phone microphone is not silently substituted.
- Core API support for `GlassesModel.EVEN`, `GlassesModel.INMO`, and `AudioEncoding.LC3`.
- CLI generated-project device selection with `xg-glass init --devices`, including marker-based template filtering, explicit artifact sets for partial selections, and byte-identical default output when no device selection is requested.
- Generated Android app starter commands for capture, display, and microphone smoke tests, so fresh projects are end-to-end runnable out of the box.
- CLI first-run SDK auto-download for `xg-glass init` and single-file `xg-glass run`, cached under `~/.xg-glass/sdk/` with `--sdk` still available as an explicit checkout override.
- iOS dependency-guard CI for Kotlin/Native tasks on dependency pull requests.
- App-size design spike documenting measured generated-APK deltas and the decision to prefer per-artifact opt-in over Play Feature Delivery or runtime dynamic loading as the default direction.

### Changed

- Version catalog coverage now includes all versioned external dependency coordinates, including build-logic plugin classpath dependencies.
- Frame Android client now uses the shared base-client lifecycle plumbing and maps bridge state changes, including spontaneous reconnect/disconnect transitions, into the unified `ConnectionState`.
- `xg-glass run --sim` for generated projects now shares the single-file quick-mode path for simulator settings, emulator auto-boot, install, and launch.
- Toolchain upgraded to Gradle 9.3.1, Android Gradle Plugin 9.1.1, Kotlin 2.4.0, coroutines 1.11.0, `compileSdk` 36, AndroidX Core 1.17.0, CameraX 1.6.1, Dokka 2.2.0, and vanniktech Maven Publish 0.37.0.
- Kotlin Multiplatform Android modules migrated to `com.android.kotlin.multiplatform.library`, with build-logic convention updates for AGP built-in Kotlin.
- Embedded Frame Android support now consumes Flutter's `build aar` output for debug and release builds instead of a project dependency on the generated Flutter module.
- iOS CI jobs now run on `macos-26` with a pinned Xcode selection.

### Fixed

- Generated-app compatibility was restored after dependency refreshes, with CI coverage for generated simulator app assembly.
- Per-device Android modules now declare the runtime permissions their code paths use, so partial `--devices` selections get correct merged manifests instead of silently auto-denying undeclared requests such as `CAMERA` and `RECORD_AUDIO` in RayNeo-only apps.
- RayNeo-only generated app templates now compile by giving the filtered client factory an explicit `GlassesClient` type.
- Simulator `--local_video` and `--video_url` inputs are now wired through generated-project runs, stale simulator video paths are reset, and adb/device readiness checks fail loudly instead of silently continuing.
- Meta iOS HFP microphone teardown and routing hardening: disconnect/deinit now end streams, start is non-reentrant across permission prompts, route identity is pinned to the accepted HFP port, engine configuration changes are observed, teardown warnings are surfaced, and normal audio chunks cannot be emitted after end-of-stream.
- Dependabot ignore coverage expanded for blocked CXR-M and Android/Kotlin toolchain upgrades that require separate hardware or toolchain validation.

## [0.1.0] - 2026-07-02

### Added

- First public release of xg.glass.
- Unified `GlassesClient` API for camera, microphone, display, and audio across Rokid, RayNeo, Omi, Frame, Meta, and Simulator adapters on Android.
- Kotlin Multiplatform core and app-contract modules for shared SDK models and app entry contracts.
- iOS support through Simulator, Omi, and Meta adapters plus the Swift Package products `XgGlass` and `XgGlassMeta`.
- `xg-glass` CLI for generated-project workflows, build/install/run commands, and source-based simulator development.
- Maven Central distribution for Android artifacts, PyPI distribution for the CLI, and Swift Package distribution for iOS.

### Notes

- Hardware-validation status varies by device. RayNeo x2 has been validated; RayNeo x3 Pro is not yet validated on hardware.
- Frame support from Maven provides the bridge API only; the working Frame integration currently uses the source/CLI flow with the embedded Flutter module.
- Android XR remains a non-functional preview scaffold and is not published in 0.1.0.
