# RayNeo vendor AARs

This directory is where you, the developer, place the proprietary RayNeo vendor
AARs required by the RayNeo glasses-side host. These AARs are not redistributed
with this repository.

## Download

1. Download `MercuryAndroidSDK*.aar` from RayNeo's ARDK download page:
   https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa/ardk-xia-zai
2. Download the RayNeo IPC SDK AAR from the IPC SDK page:
   https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa/ipc-sdk
3. Drop both `.aar` files into this directory.
4. Re-run the build.

Known-good minimums:

- Mercury / RayNeo ARSDK: `MercuryAndroidSDK` v0.2.3 or newer
- RayNeo IPC SDK: `RayNeoIPCSDK-For-Android` V0.1.0 or newer

The SDK's RayNeo host generation plugin (`com.xgglass.rayneo.app`) copies any
`*.aar` files from this directory into the generated glasses-side project at
`:xgglass_rayneo_glass_host/libs/` and includes them in the build.

Recommended location within the SDK checkout:

- `./third_party/rayneo/aar/`
