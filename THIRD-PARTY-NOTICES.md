# Third-Party Notices

This file summarizes third-party SDKs and assets used by xg.glass.

## Meta Wearables Device Access Toolkit

Meta Wearables Device Access Toolkit is used as a dependency for Meta wearables support. Android artifacts are resolved from Meta's GitHub Packages repository, and iOS support uses the Swift package `facebook/meta-wearables-dat-ios`. These dependencies are not redistributed by this repository.

## Rokid CXR-M SDK

Rokid CXR-M SDK (`com.rokid.cxr:client-m` from `maven.rokid.com`) is used as a dependency for Rokid support. It is not redistributed by this repository.

## CitizenOneX Frame Dart Packages

The embedded Frame Flutter module depends on `frame_ble` and `frame_msg` by CitizenOneX (`https://github.com/CitizenOneX`). These packages are fetched at build time; license: see upstream repository.

## Brilliant Labs Frame Lua Scripts

The Lua scripts under `third_party/frame/frame_module/assets/lua/` are adapted from the Brilliant Labs Frame ecosystem, including `frame_msg` reference scripts. They are redistributed within this repository as part of the embedded Frame module.

## RayNeo Mercury / ARSDK and IPC SDK

RayNeo Mercury / ARSDK and IPC SDK are proprietary RayNeo SDKs. They are not
redistributed with this repository; developers download them from RayNeo's
official developer documentation (`https://rayneo.gitbook.io/rayneo-devdoc`) and
place them under `third_party/rayneo/aar/`.
