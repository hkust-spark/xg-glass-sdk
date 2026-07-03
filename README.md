<h3 align="center">
xg.glass
</h3>

<h3 align="center">
Easy, fast, glasses application development for everyone
</h3>

<p align="center">
<a href="https://github.com/hkust-spark/xg-glass-sdk/actions/workflows/ci.yml"><img src="https://github.com/hkust-spark/xg-glass-sdk/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
<a href="https://central.sonatype.com/artifact/io.github.hkust-spark/xgglass-universal"><img src="https://img.shields.io/maven-central/v/io.github.hkust-spark/xgglass-universal" alt="Maven Central"></a>
<a href="https://pypi.org/project/xg-glass/"><img src="https://img.shields.io/pypi/v/xg-glass" alt="PyPI"></a>
<a href="./docs/swift-package.md"><img src="https://img.shields.io/badge/Swift_Package-iOS_16%2B-F05138?logo=swift" alt="Swift Package"></a>
<a href="./LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License"></a>
</p>

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

Use Maven Central for normal Android apps. SDK artifacts use group `io.github.hkust-spark`, version `0.1.0`, and prefixed artifact IDs such as `xgglass-universal`, `xgglass-core`, and `xgglass-device-meta`. Note that the Maven group is only the distribution namespace — Kotlin packages in code are `com.xgglass.*` (for example `import com.xgglass.core.GlassesClient`).

New to the SDK? Follow [Your First App (Android)](./docs/getting-started-android.md).

The main Android artifact is `xgglass-universal`. It includes Rokid, RayNeo, Simulator, Omi, and the Frame bridge API. The SDK minSdk is 28; Meta support and the RayNeo glasses host require minSdk 29.
RayNeo support additionally requires the vendor AARs downloaded from RayNeo's developer docs; see [third_party/rayneo/aar/README.md](./third_party/rayneo/aar/README.md).

Besides `google()` and `mavenCentral()`, one extra repository is required: Rokid support pulls `com.rokid.cxr:client-m` from Rokid's Maven repository. Published SDK artifacts other than Rokid's vendor dependency resolve from Maven Central alone.

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

The `XgGlassKit` binary is downloaded automatically from the GitHub Release for the version you depend on — no local build step is needed. See [Swift Package setup](./docs/swift-package.md) and [iOS device support](./docs/ios-device-support.md).
Start with the [Swift Package quick start](./docs/swift-package.md#quick-start).

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
- [Your First App (Android)](./docs/getting-started-android.md)
- [API reference (ai-assistant-guide)](./docs/ai-assistant-guide.md)
- [Swift Package setup](./docs/swift-package.md)
- [iOS device support](./docs/ios-device-support.md)
- [Changelog](./CHANGELOG.md)
- [Contributing](./CONTRIBUTING.md)
- [Security policy](./SECURITY.md)

#### AI-assisted development

We also provide [`docs/ai-assistant-guide.md`](./docs/ai-assistant-guide.md), a comprehensive reference specifically prepared for AI coding assistants such as ChatGPT, Claude, Cursor, and Copilot.

Developers can give this document directly to their AI assistant so it can reference the xg.glass SDK APIs, patterns, and examples when helping build applications.

## Versioning and support

xg.glass follows Semantic Versioning. While the major version is `0`, minor releases (`0.x` to `0.y`) may include breaking API changes; those changes are always listed in the [CHANGELOG](./CHANGELOG.md). Patch releases will not break public APIs.

Sealed hierarchies such as `ConnectionState` and `GlassesEvent` may gain new subtypes in minor releases, so keep an `else` branch in Kotlin `when` expressions. Deprecated APIs are kept with `@Deprecated` for at least one minor release before removal.

Supported toolchains:

- Android minSdk 28; minSdk 29 for Meta and the RayNeo glasses host.
- Android compileSdk 35.
- JDK 17+.
- Building from source needs Kotlin 2.1+ and Android Gradle Plugin 8.x.
- iOS 16+ with Xcode 15.4+.
- CLI Python 3.9+ on macOS/Linux.

Per-device hardware-validation status is listed in the device table above.

## Repository layout

- `core/`, `core-android/`, `app-contract/`, `universal/` — published SDK modules.
- `universal-full/` and `app/` — internal build scaffolding for the CLI composite flow; not published. Maven consumers use `xgglass-universal`.
- `devices/` — per-device adapters.
- `Sources/` and `Package.swift` — Swift Package products.
- `tools/` — the `xg-glass` CLI.
- `templates/` — app template used by `xg-glass init`.
- `samples/` — iOS sample.
- `third_party/` — Flutter Frame module and the bring-your-own RayNeo AAR directory.
- `scripts/` — build and release helpers.
- `docs/` — documentation and design notes.

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
