# XgGlass Swift Package

The package supports iOS 17.2 and newer.

The unreleased source branch uses Meta DAT 0.9.0 and raises the minimum from
iOS 16 to 17.2. The published 0.3.0 release still uses DAT 0.8.0; these changes
will ship in the next minor release. DAT 0.9 always enables the Device Access
Toolkit App Model (DAM); `MWDAT.DAMEnabled` is ignored, so no opt-in key or
additional registration call is needed for this migration. Existing application
registration and permissions still apply. See the
[vendor migration notes](https://github.com/facebook/meta-wearables-dat-ios/blob/0.9.0/CHANGELOG.md).

The root `Package.swift` publishes these iOS products:

- `XgGlass`: the Kotlin Multiplatform `XgGlassKit` XCFramework re-exported for Swift clients.
- `XgGlassMeta`: the Meta iOS adapter plus its Meta Wearables DAT dependencies.
- `XgGlassMetaTesting`: mock-device test rig for the Meta adapter (MockDeviceKit); link it from tests or dev tools only.

Frame iOS remains local to the sample because it is backed by the Flutter add-to-app CocoaPods integration.

## Consuming the package

```swift
dependencies: [
    .package(url: "https://github.com/hkust-spark/xg-glass-sdk", from: "0.3.0")
]
```

This version-based dependency uses published releases; it does not include the
unreleased Meta migration or adapter fixes. To try those changes before release,
use this checkout as a local package dependency.

The `XgGlassKit` binary target is downloaded and checksum-verified automatically
by SwiftPM. The current source manifest still points at the `0.3.0` release asset,
so using the local Swift sources alone does not include the Kotlin adapter fixes.
Use the local binary instructions below when testing those fixes.

## Quick start

The `XgGlass` product includes the core API plus the Simulator and Omi adapters. Use `XgGlassMeta` only when you need the Meta adapter and Meta Wearables DAT dependencies.

After your target depends on `.product(name: "XgGlass", package: "xg-glass-sdk")`, add a small client wrapper like this:

```swift
import Foundation
import XgGlass

public final class XgQuickstart {
    private let client = SimulatorIosGlassesClient(displaySink: { text in print("display: \(text)") })

    public init() {}

    public func run() {
        client.connect { _, error in
            if let error {
                print("connect failed: \(error.localizedDescription)")
                return
            }

            let options = CaptureOptions(photoQuality: .high, targetWidth: nil, targetHeight: nil, timeoutMs: 30_000)
            self.client.capturePhoto(options: options) { result, error in
                if let error {
                    print("capture failed: \(error.localizedDescription)")
                    return
                }
                guard let captured = result as? CapturedImage else {
                    print("capture returned an unexpected result")
                    return
                }
                print("captured \(captured.jpegBytes.size) bytes")
            }
        }
    }
}
```

## Developing the SDK itself

After building the current Kotlin binary, verify both callback lifecycle behavior
and the complete Swift adapter product without editing the release manifest:

```sh
./gradlew :app-contract:assembleXgGlassKitXCFramework
bash scripts/test-swift-operations.sh
bash scripts/check-swift-package.sh
```

To test local Kotlin changes, build the XCFramework and temporarily point the binary target back at the local path (do not commit that change):

```sh
scripts/build-xcframework.sh
```

The script runs `:app-contract:assembleXgGlassKitXCFramework` and copies the result to `artifacts/XgGlassKit.xcframework` (the `artifacts/` directory is git-ignored). Then, in `Package.swift`, temporarily replace the `url:`/`checksum:` binary target with:

```swift
.binaryTarget(
    name: "XgGlassKit",
    path: "artifacts/XgGlassKit.xcframework"
)
```

Building the XCFramework locally requires macOS with a JDK and Android toolchain available; Gradle itself is provided by the repository wrapper.

## Release Packaging

For each release, archive the XCFramework as a zip and compute the SwiftPM checksum:

```sh
scripts/package-xcframework-release.sh
```

Then update the `url:` (release tag) and `checksum:` in `Package.swift`, commit, tag that commit with the version, create the GitHub Release on the tag, and upload `artifacts/XgGlassKit.xcframework.zip` as a release asset. The tag must contain the `url:`/`checksum:` form of `Package.swift`, otherwise consumers pinning that version cannot resolve the package.
