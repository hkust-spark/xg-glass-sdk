# XgGlass Swift Package

The root `Package.swift` publishes two iOS products:

- `XgGlass`: the Kotlin Multiplatform `XgGlassKit` XCFramework re-exported for Swift clients.
- `XgGlassMeta`: the Meta iOS adapter plus its Meta Wearables DAT dependencies.

Frame iOS remains local to the sample because it is backed by the Flutter add-to-app CocoaPods integration.

## Local Development

Build the binary artifact before opening or building the Swift package:

```sh
scripts/build-xcframework.sh
```

The script runs `:app-contract:assembleXgGlassKitXCFramework` and copies the result to `artifacts/XgGlassKit.xcframework`. The `artifacts/` directory is ignored because it contains generated binary output.

Add this repository as a local Swift package in Xcode, then link `XgGlass` for the core API and `XgGlassMeta` when the Meta adapter is needed.

## Release Packaging

For a public binary release, archive `XgGlassKit.xcframework` as a zip, publish it at a stable URL, then update `Package.swift` from the local binary target:

```swift
.binaryTarget(
    name: "XgGlassKit",
    url: "https://example.com/XgGlassKit.xcframework.zip",
    checksum: "<swift package compute-checksum output>"
)
```

Generate the checksum with:

```sh
scripts/package-xcframework-release.sh
```
