# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows semantic versioning.

## [Unreleased]

### Added

- Even Realities G1 adapter on Android and iOS, including dual-BLE left/right arm connection, paged display output, LC3 microphone passthrough, and tap events.
- INMO Air3 on-glasses Android runtime adapter, including camera, display, microphone, raw/encoded audio playback, and tap events through host Activity key forwarding.
- Meta iOS microphone capture over Bluetooth HFP, with actual sample-rate reporting and route validation so the phone microphone is not silently substituted.
- Core API support for `GlassesModel.EVEN`, `GlassesModel.INMO`, and `AudioEncoding.LC3`.
- CLI first-run SDK auto-download for `xg-glass init` and single-file `xg-glass run`, cached under `~/.xg-glass/sdk/` with `--sdk` still available as an explicit checkout override.
- iOS dependency-guard CI for Kotlin/Native tasks on dependency pull requests.
- App-size design spike documenting measured generated-APK deltas and the decision to prefer per-artifact opt-in over Play Feature Delivery or runtime dynamic loading as the default direction.

### Changed

- Frame Android client now uses the shared base-client lifecycle plumbing and maps bridge state changes, including spontaneous reconnect/disconnect transitions, into the unified `ConnectionState`.
- Gradle wrapper updated to 8.14.5 in the repository and Android app template.
- Dependency refresh: Android Gradle Plugin 8.13.2, Kotlin 2.1.21, coroutines 1.10.2, CameraX 1.6.1, Dokka 2.2.0, Gson 2.14.0, AndroidX Core 1.19.0, AppCompat 1.7.1, ExifInterface 1.4.2, Tink 1.22.0, AndroidX Test JUnit 1.3.0, AndroidX Test Core/Runner 1.7.0, and GitHub Actions major-version updates for checkout, setup-java, setup-python, setup-android, gradle/actions, deploy-pages, and upload-pages-artifact.

### Fixed

- Meta iOS HFP microphone teardown and routing hardening: disconnect/deinit now end streams, start is non-reentrant across permission prompts, route identity is pinned to the accepted HFP port, engine configuration changes are observed, teardown warnings are surfaced, and normal audio chunks cannot be emitted after end-of-stream.
- Coroutines dependency pinned to 1.10.2 after the 1.11.0 release introduced Kotlin 2.2-built klibs that were incompatible with the current toolchain.
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
