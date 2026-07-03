# iOS Sample

The iOS sample demonstrates the Swift Package products, the Meta mock runtime path, and the Frame Flutter add-to-app adapter.

## Setup

Build the shared XCFramework first:

```sh
scripts/build-xcframework.sh
```

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

Frame is included to exercise the add-to-app bridge and error mapping, but real Frame BLE behavior does not work on the iOS simulator. The simulator tests assert that Frame operations fail honestly when BLE hardware is unavailable.
