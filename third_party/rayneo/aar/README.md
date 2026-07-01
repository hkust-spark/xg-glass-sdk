# RayNeo / Mercury vendor AARs

Place the vendor AARs required by the RayNeo glasses-side Host in this directory (e.g. `MercuryAndroidSDK*.aar`, `RayNeoIPCSDK*.aar`).

The SDK’s RayNeo Host generation plugin (`com.xgglass.rayneo.app`) will copy any `*.aar` files from this directory into the generated glasses-side project at `:xgglass_rayneo_glass_host/libs/` and include them in the build.

Recommended location (fixed path within the SDK):

- `./third_party/rayneo/aar/`
