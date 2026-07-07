// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "xg-glass-sdk",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(name: "XgGlass", targets: ["XgGlass"]),
        .library(name: "XgGlassMeta", targets: ["XgGlassMeta"]),
        .library(name: "XgGlassMetaTesting", targets: ["XgGlassMetaTesting"])
    ],
    dependencies: [
        .package(url: "https://github.com/facebook/meta-wearables-dat-ios", exact: "0.8.0")
    ],
    targets: [
        .binaryTarget(
            name: "XgGlassKit",
            url: "https://github.com/hkust-spark/xg-glass-sdk/releases/download/0.2.0/XgGlassKit.xcframework.zip",
            checksum: "5548d26c723ac737217f55cc1ecc4420b46ae851ca3dcc6bb3ee4ece38195337"
        ),
        .target(
            name: "XgGlass",
            dependencies: ["XgGlassKit"],
            path: "Sources/XgGlass"
        ),
        .target(
            name: "XgGlassMeta",
            dependencies: [
                "XgGlass",
                .product(name: "MWDATCore", package: "meta-wearables-dat-ios"),
                .product(name: "MWDATCamera", package: "meta-wearables-dat-ios"),
                .product(name: "MWDATDisplay", package: "meta-wearables-dat-ios")
            ],
            path: "Sources/XgGlassMeta"
        ),
        .target(
            name: "XgGlassMetaTesting",
            dependencies: [
                "XgGlassMeta",
                .product(name: "MWDATCore", package: "meta-wearables-dat-ios"),
                .product(name: "MWDATMockDevice", package: "meta-wearables-dat-ios")
            ],
            path: "Sources/XgGlassMetaTesting"
        )
    ]
)
