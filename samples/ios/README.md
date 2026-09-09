# iOS Sample

The iOS sample demonstrates the Swift Package products, the Meta mock runtime path, and the Frame Flutter add-to-app adapter.

It requires iOS 17.2 or later and uses Meta DAT 0.9.0. Simulator builds require Apple Silicon, matching the arm64 simulator slice in `XgGlassKit`.

## Setup

The sample uses this checkout's Swift adapter sources and Meta DAT 0.9.0.
The root package manifest still downloads the published `0.3.0` Kotlin binary.
To include the current Kotlin adapter fixes, first build the shared XCFramework:

```sh
scripts/build-xcframework.sh
```

Then temporarily point the root `Package.swift` binary target at
`artifacts/XgGlassKit.xcframework`, as described in
[local binary setup](../../docs/swift-package.md#developing-the-sdk-itself).
Restore the release URL and checksum before committing.

Prepare the Frame Flutter module and CocoaPods integration:

```sh
cd third_party/frame/frame_module
flutter pub get

cd ../../../samples/ios/XgGlassSample
pod install
open XgGlassSample.xcworkspace
```

Open `samples/ios/XgGlassSample/XgGlassSample.xcworkspace`, not the `.xcodeproj`. The workspace is required because the sample uses CocoaPods for Flutter add-to-app.

## Running

Use iOS simulator targets for the sample UI and tests. The Meta path includes a mock runtime test in `XgGlassSampleTests/MetaMockRuntimeTests.swift`.

DAT 0.9 always enables App Model; no `MWDAT.DAMEnabled` opt-in is needed. For
registered applications on real glasses, set the sample's `META_APP_ID`,
`CLIENT_TOKEN`, and `DEVELOPMENT_TEAM` build settings and configure the
`xgglasssample://` callback scheme in Meta's application settings. The checked-in
zero IDs are development placeholders. See the
[Meta migration notes](../../docs/sdk-maintenance-2026-09.md#vendor-migrations).

Frame is included to exercise the add-to-app bridge and error mapping, but real Frame BLE behavior does not work on the iOS simulator. The simulator tests assert that Frame operations fail honestly when BLE hardware is unavailable.

Run the callback cancellation and timeout regression tests without launching a simulator:

```sh
bash scripts/test-swift-operations.sh
```

After building the XCFramework, check the Swift products against the current Kotlin API:

```sh
bash scripts/check-swift-package.sh
```
