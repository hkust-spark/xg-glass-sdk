# RayNeo vendor AARs

This directory is where you, the developer, place the proprietary RayNeo vendor
AARs required by the RayNeo glasses-side host. These AARs are not redistributed
with this repository.

## Download

1. Download `MercuryAndroidSDK*.aar` from RayNeo's ARDK download page:
   https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa/ardk-xia-zai
2. Download the RayNeo IPC SDK AAR from the IPC SDK page:
   https://rayneo.gitbook.io/rayneo-devdoc/x-xi-lie/android-kai-fa/ipc-sdk
3. Drop exactly one `MercuryAndroidSDK*.aar` and one `RayNeoIPCSDK*.aar` into this
   directory. Keep the lowercase `.aar` extension and remove older versions.
4. Re-run the build.

Historical minimums (not a lock on the actual files supplied):

- Mercury / RayNeo ARSDK: `MercuryAndroidSDK` v0.2.3 or newer
- RayNeo IPC SDK: `RayNeoIPCSDK-For-Android` V0.1.0 or newer

The official ARDK download page listed 0.2.6 on September 9, 2026. Select the
version supported by your glasses and firmware; the latest IPC version was not
independently confirmed. Proprietary files are not bundled here or automatically
upgraded by xg.glass.

The host generation plugin (`com.xgglass.rayneo.app`) synchronizes the AARs into
`:xgglass_rayneo_glass_host/build/xgglass/rayneo-libs/` **before** host compilation.
Only that managed directory is included as the vendor classpath. Obsolete
versions are removed from it; files in the old host `libs/` directory are left
untouched and no longer loaded as vendor SDKs.

## Pin the supplied binaries

After verifying the downloads against RayNeo's release information, record their
filenames and SHA-256 hashes in this directory:

```sh
shasum -a 256 *.aar > rayneo-sdk.sha256
```

Commit the checksum manifest with your application (the proprietary AARs can
remain outside Git). When the manifest exists, the plugin requires it to cover
every supplied AAR exactly and rejects changed bytes, missing files, duplicate
entries, and invalid filenames. Update it deliberately when updating the vendor
SDK. No manifest or hash is fabricated by this repository.

Recommended location within the SDK checkout:

- `./third_party/rayneo/aar/`
