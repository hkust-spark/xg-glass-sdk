# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows semantic versioning.

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
