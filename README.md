<h3 align="center">
xg.glass
</h3>

<h3 align="center">
Easy, fast, glasses application development for everyone
</h3>


<p align="center">
| <a href="https://xg.glass/developer-guide/"><b>Documentation</b></a> | <a href="https://xg.glass/blog/"><b>Blog</b></a> | <a href="https://github.com/hkust-spark/xg-glass-sample/"><b>Sample Applications</b></a>
</p>

🔥 We have built a xg.glass website to help you get started with xg.glass. Please visit [xg.glass](https://xg.glass) to learn more.

---

## About

xg.glass is a fast and easy-to-use library for smart glasses application development.

Smart glasses development is supposed to be easy. If you want to build an application, all you need is the following four interfaces:

- Video input from the camera
- Audio input from the microphone
- Display output
- Audio output

This is what xg.glass has extracted for you from tens of smart glasses SDKs. We hide all details of communicating with difference glasses' SDKs and make sure that the code that you develop based on xg.glass can smoothly run on multiple glasses or a simulator without any single line of additional effort.

Currently we support:

| Category | Products |
| --- | --- |
| Rokid | Rokid Glasses |
| Meta | Meta Wearables |
| Brilliant Labs | Frame |
| RayNeo | x2 Glasses (validated), x3 Pro Glasses (untested) |
| Omi | Omi Glass |
| *Simulation* | — |

We're working on and will support soon:

- **INMO**

Welcome the contributions from the community on more glasses!

## Getting Started

### App developers (build apps with the SDK)

Choose one installation channel depending on the platform you are targeting.

#### Android (Maven Central)

Use Maven Central for normal Android apps. SDK artifacts use group `io.github.hkust-spark`, version `0.1.0`, and prefixed artifact IDs such as `xgglass-universal`, `xgglass-core`, and `xgglass-device-meta`.

The main Android artifact is `xgglass-universal`. It includes Rokid, RayNeo, Simulator, Omi, and the Frame bridge API. The SDK minSdk is 28; Meta support and the RayNeo glasses host require minSdk 29.

Besides `google()` and `mavenCentral()`, one extra repository is required: Rokid support pulls `com.rokid.cxr:client-m` from Rokid's Maven repository. Everything else resolves from Maven Central alone.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
            }
            filter {
                includeGroupByRegex("com\\.rokid(\\..+)?")
            }
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.github.hkust-spark:xgglass-universal:0.1.0")
}
```

#### Meta opt-in for Android

Meta is intentionally separate from `xgglass-universal`. Add `xgglass-device-meta` and Meta's GitHub Packages repository, then provide a GitHub username and token with `read:packages` scope.

```properties
# ~/.gradle/gradle.properties
github_user=YOUR_GITHUB_USERNAME
github_token=ghp_xxxxxxxxxxxxx
```

```kotlin
// settings.gradle.kts
val metaGithubUser = providers.gradleProperty("github_user").orNull
    ?: providers.environmentVariable("GITHUB_ACTOR").orNull
    ?: ""
val metaGithubToken = providers.gradleProperty("github_token").orNull
    ?: providers.environmentVariable("GITHUB_TOKEN").orNull
    ?: ""

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = metaGithubUser
                password = metaGithubToken
            }
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.github.hkust-spark:xgglass-device-meta:0.1.0")
}
```

#### iOS (Swift Package)

The Swift Package is hosted from this repository and requires iOS 16+. It publishes `XgGlass` for the core API plus Simulator/Omi adapters, and `XgGlassMeta` for the Meta adapter with Meta DAT 0.8.0.

```swift
// Package.swift
dependencies: [
    .package(url: "https://github.com/hkust-spark/xg-glass-sdk", from: "0.1.0")
],
targets: [
    .target(
        name: "YourApp",
        dependencies: [
            .product(name: "XgGlass", package: "xg-glass-sdk"),
            .product(name: "XgGlassMeta", package: "xg-glass-sdk")
        ]
    )
]
```

Until the 0.1.0 GitHub Release hosts `XgGlassKit.xcframework.zip`, build the binary locally with `scripts/build-xcframework.sh` on macOS. See [Swift Package setup](./docs/swift-package.md) and [iOS device support](./docs/ios-device-support.md).

#### CLI (PyPI)

Use the CLI for generated projects and source-based workflows, especially when you need Frame support today.

```bash
pip install xg-glass
xg-glass --help
```

Out of the box, the PyPI CLI can build, install, and run inside an already-generated project that contains `xg-glass.yaml`. Commands that create a project from this SDK, such as `xg-glass init` and `xg-glass run <file.kt>`, need an SDK checkout passed with `--sdk` and, where needed, `--template`.

```bash
xg-glass build
xg-glass install
xg-glass run
```

#### Device matrix by channel

| Channel | Works from the channel | Notes |
| --- | --- | --- |
| Android Maven `xgglass-universal` | Rokid, RayNeo, Simulator, Omi, Frame bridge API | Frame is only the bridge API from Maven; a working Frame integration needs the source/CLI flow because the embedded Flutter module is not published. Hardware validation varies by device. |
| Android Maven `xgglass-device-meta` | Meta | Opt-in artifact. Requires Meta's GitHub Packages repository and a GitHub token with `read:packages`. |
| iOS Swift Package | Simulator, Omi, Meta | Frame is sample-only through Flutter add-to-app. Rokid and RayNeo are not available on iOS today; see [iOS device support](./docs/ios-device-support.md). |
| CLI/source flow | Android generated projects, Simulator, and current Frame integration | Use this path for Frame today. The template wires `device-frame-embedded` and the Flutter module from the SDK checkout. |

#### Simulator

If you don't have glasses right now, the simulator supports development and testing. In source/CLI workflows, add `--sim` and your computer camera or a video dataset can act as the glasses camera.

```bash
xg-glass run --sdk /path/to/xg-glass-sdk --sim /path/to/MyEntry.kt
xg-glass run --sdk /path/to/xg-glass-sdk --sim --local_video /path/to/video.mp4 /path/to/MyEntry.kt
xg-glass run --sdk /path/to/xg-glass-sdk --sim --video_url <youtube-or-bilibili-url> /path/to/MyEntry.kt
```

The launch of Android Emulator may take several minutes. You can keep it on to save time for the next run.

For more details, see the following documentation:

- [Developer Guide](https://xg.glass/developer-guide/)
- [AI assistant guide](./docs/ai-assistant-guide.md)
- [Swift Package setup](./docs/swift-package.md)
- [iOS device support](./docs/ios-device-support.md)
- [Changelog](./CHANGELOG.md)

#### AI-assisted development

We also provide [`docs/ai-assistant-guide.md`](./docs/ai-assistant-guide.md), a comprehensive reference specifically prepared for AI coding assistants such as ChatGPT, Claude, Cursor, and Copilot.

Developers can give this document directly to their AI assistant so it can reference the xg.glass SDK APIs, patterns, and examples when helping build applications.

### Contributors (extend the SDK)

If you want to **extend xg.glass itself** (new devices, new APIs, build tooling), start with [Contributor Guide](https://xg.glass/contributor-guide/).

For local development, clone the repository and use the editable CLI package under `tools/`, or invoke the root launcher directly:

```bash
git clone https://github.com/hkust-spark/xg-glass-sdk.git
cd xg-glass-sdk
pip install -e tools/
./xg-glass --help
```

## License

xg.glass is licensed under the [Apache License 2.0](./LICENSE).
Third-party dependency and redistributed asset notices are listed in [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md).
