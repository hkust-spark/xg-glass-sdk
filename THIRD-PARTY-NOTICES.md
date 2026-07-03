# Third-Party Notices

This file summarizes third-party SDKs and assets used by xg.glass.

## Meta Wearables Device Access Toolkit

Meta Wearables Device Access Toolkit is used as a dependency for Meta wearables support. Android artifacts are resolved from Meta's GitHub Packages repository, and iOS support uses the Swift package `facebook/meta-wearables-dat-ios`. These dependencies are not redistributed by this repository.

## Rokid CXR-M SDK

Rokid CXR-M SDK (`com.rokid.cxr:client-m` from `maven.rokid.com`) is used as a dependency for Rokid support. It is not redistributed by this repository.

## CitizenOneX Frame Dart Packages

The embedded Frame Flutter module depends on `frame_ble` and `frame_msg` by CitizenOneX (`https://github.com/CitizenOneX`). These packages are fetched at build time from Git. The pinned local package checkouts include the BSD 3-Clause License (Copyright 2025 CitizenOneX).

## Brilliant Labs Frame Lua Scripts

The Lua scripts under `third_party/frame/frame_module/assets/lua/` are redistributed as part of the embedded Frame module:

- `data.min.lua`, `camera.min.lua`, and `plain_text.min.lua` are minified copies of the upstream Lua assets in CitizenOneX `frame_msg` (`lib/lua/`) for the Brilliant Labs Frame ecosystem.
- `audio.min.lua` is a minified Frame-side support script for microphone streaming through Brilliant Labs Frame Lua runtime APIs. No separate license header or matching file was present in this repository or in the pinned local `frame_msg` / `frame_ble` checkouts.
- `xgglass_frame_app.lua` is this project's own Frame application script. It wires the Frame Lua support modules to the xg.glass Frame bridge.

For the Lua assets copied from `frame_msg`, the upstream package license is BSD 3-Clause (Copyright 2025 CitizenOneX). For `audio.min.lua`, license/provenance should be checked against the upstream source if a separate upstream repository later documents it.

## RayNeo Mercury / ARSDK and IPC SDK

RayNeo Mercury / ARSDK and IPC SDK are proprietary RayNeo SDKs. They are not
redistributed with this repository; developers download them from RayNeo's
official developer documentation (`https://rayneo.gitbook.io/rayneo-devdoc`) and
place them under `third_party/rayneo/aar/`.
