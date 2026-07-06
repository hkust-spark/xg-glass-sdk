# App Size Design Spike

## Problem

Issue 8 asks how SDK consumers avoid shipping unused Android adapters. The generated Android app currently defaults to `xgglass-universal-full`, which is good for zero-config exploration but expensive for production apps that only need one glasses family. The largest known concern is Frame because `universal-full` pulls in the embedded Flutter wrapper.

This spike measured release APKs from real CLI-generated consumer apps and compares three implementation strategies: per-artifact opt-in, Play Feature Delivery, and runtime dynamic loading. No product code was changed.

## Methodology

All builds were generated under `/tmp/xg-u-*` with `./xg-glass init ... --no-shell-setup` and built with:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease
```

The measured artifact is the generated release split APK for `arm64-v8a`: `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`. The template ABI split was active and also emitted `armeabi-v7a`; the tables below use arm64 because it is the target device slice. The template release build has `isMinifyEnabled = false`; no minification or resource shrinking is represented in these numbers.

The environment had Meta DAT GitHub Packages access, so Meta was included where configured and was measured. The RayNeo generated host APK was not packaged because the local RayNeo/Mercury vendor AAR directory was empty; the default build printed that the RayNeo glasses host was skipped. The `rayneo-runtime` row intentionally measures only `xgglass-device-rayneo-runtime`, not the installer or generated host APK.

The lean/per-device rows use a generated app with the same Android app shell and `xgglass_app_logic` module, but with the RayNeo settings/app plugins removed and a tiny generated `MainActivity` so a narrow dependency graph compiles. That keeps the consumer app overhead fixed while changing one SDK dependency at a time.

## Build Configurations

All rows used composite-build substitutions to the local SDK checkout. Direct SDK dependencies were:

| Row | Directory | SDK dependency configuration | Generated-app source/scaffold changes |
| --- | --- | --- | --- |
| Default generated app | `/tmp/xg-u-baseline` | `implementation("io.github.hkust-spark:xgglass-universal-full:0.1.0")` | Default generated app; `com.xgglass.rayneo.settings`, `com.xgglass.rayneo.app`, and `xgRayneo { ... }` present |
| Default minus embedded Frame/Flutter | `/tmp/xg-u-no-frame` | `implementation("io.github.hkust-spark:xgglass-universal:0.1.0")` plus `implementation("io.github.hkust-spark:xgglass-device-meta:0.1.0")` | Same generated app, but the `GlassesModel.FRAME` branch was replaced with `error("Frame is not available in this measurement build")` so the app compiles without `device-frame-embedded` |
| Lean floor | `/tmp/xg-u-lean` | `xgglass-core`, `xgglass-core-android`, `xgglass-app-contract`, `xgglass-device-simulator` | RayNeo settings/app plugins and `xgRayneo { ... }` removed; `MainActivity` reduced to a tiny activity referencing `SimulatorGlassesClient` |
| Lean plus Rokid | `/tmp/xg-u-rokid` | Lean floor plus `implementation("io.github.hkust-spark:xgglass-device-rokid:0.1.0")` | Same as lean floor |
| Lean plus RayNeo runtime | `/tmp/xg-u-rayneo-runtime` | Lean floor plus `implementation("io.github.hkust-spark:xgglass-device-rayneo-runtime:0.1.0")` | Same as lean floor |
| Lean plus OMI | `/tmp/xg-u-omi` | Lean floor plus `implementation("io.github.hkust-spark:xgglass-device-omi:0.1.0")` | Same as lean floor |
| Lean plus Even | `/tmp/xg-u-even` | Lean floor plus `implementation("io.github.hkust-spark:xgglass-device-even:0.1.0")` | Same as lean floor |
| Lean plus Meta | `/tmp/xg-u-meta` | Lean floor plus `implementation("io.github.hkust-spark:xgglass-device-meta:0.1.0")` | Same as lean floor |

## Measurements

| Build | arm64 APK bytes | MiB | Delta bytes | Delta MiB |
| --- | ---: | ---: | ---: | ---: |
| Default generated app (`universal-full`, Meta present) | 52,034,895 | 49.62 | - | - |
| Default minus embedded Frame/Flutter (`universal` + Meta) | 36,765,644 | 35.06 | -15,269,251 | -14.56 |
| Lean floor (`core` + `core-android` + `app-contract` + `device-simulator`) | 18,521,034 | 17.66 | -33,513,861 vs default | -31.96 |

Frame/Flutter is the single largest optional piece in the default path. APK contents confirm `lib/arm64-v8a/libflutter.so` at 11,580,816 bytes and `lib/arm64-v8a/libapp.so` at 2,032,528 bytes in the default build; both are absent from the no-Frame build. The no-Frame row keeps Meta so the 15,269,251-byte delta isolates the embedded Frame/Flutter removal.

Per-device increments from the lean floor:

| Added SDK artifact | arm64 APK bytes | Delta bytes vs lean | Delta KiB | Delta MiB |
| --- | ---: | ---: | ---: | ---: |
| none, lean floor | 18,521,034 | - | - | - |
| `xgglass-device-rokid` | 19,860,592 | 1,339,558 | 1,308.2 | 1.28 |
| `xgglass-device-rayneo-runtime` | 18,553,802 | 32,768 | 32.0 | 0.03 |
| `xgglass-device-omi` | 18,553,802 | 32,768 | 32.0 | 0.03 |
| `xgglass-device-even` | 18,603,054 | 82,020 | 80.1 | 0.08 |
| `xgglass-device-meta` | 35,175,370 | 16,654,336 | 16,264.0 | 15.88 |

Raw `stat` and `du` evidence:

```text
52034895 /tmp/xg-u-baseline/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
45398945 /tmp/xg-u-baseline/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
36765644 /tmp/xg-u-no-frame/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
32980506 /tmp/xg-u-no-frame/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
50816    /tmp/xg-u-baseline/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
44336    /tmp/xg-u-baseline/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
35904    /tmp/xg-u-no-frame/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
32208    /tmp/xg-u-no-frame/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk

18513784 /tmp/xg-u-lean/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
18521034 /tmp/xg-u-lean/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
18088    /tmp/xg-u-lean/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
18080    /tmp/xg-u-lean/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk

19419522 /tmp/xg-u-rokid/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
19860592 /tmp/xg-u-rokid/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
19396    /tmp/xg-u-rokid/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
18968    /tmp/xg-u-rokid/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk

18546552 /tmp/xg-u-rayneo-runtime/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
18553802 /tmp/xg-u-rayneo-runtime/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
18120    /tmp/xg-u-rayneo-runtime/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
18112    /tmp/xg-u-rayneo-runtime/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk

18546552 /tmp/xg-u-omi/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
18553802 /tmp/xg-u-omi/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
18120    /tmp/xg-u-omi/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
18112    /tmp/xg-u-omi/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk

18595804 /tmp/xg-u-even/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
18603054 /tmp/xg-u-even/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
18168    /tmp/xg-u-even/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
18160    /tmp/xg-u-even/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk

31832588 /tmp/xg-u-meta/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
35175370 /tmp/xg-u-meta/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
34352    /tmp/xg-u-meta/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
31088    /tmp/xg-u-meta/app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
```

## Strategy Comparison

### A. Per-artifact opt-in

Per-artifact opt-in is the lowest-risk answer. It works for APKs, AABs, sideloading, Chinese Android stores, enterprise distribution, and local debug builds. The measured savings are immediate: avoiding embedded Frame/Flutter saves 15,269,251 bytes on arm64 in this environment, and avoiding Meta saves 16,654,336 bytes relative to the lean floor. Small adapters such as RayNeo runtime, OMI, and Even are cheap enough that they do not need special delivery mechanics.

The cost is UX and documentation. The generated app currently imports all concrete clients and defaults to `universal-full`; production templates need either explicit adapter choices or conditional source generation so a consumer can choose only the artifacts they ship. This is still much simpler than dynamic delivery because normal Gradle dependency resolution owns versioning, transitive dependencies, native libraries, and resources.

### B. Play Feature Delivery

Play Feature Delivery can help Play-only Android apps defer large optional adapters, especially Frame/Flutter or Meta. The key limitation is ownership: `xg.glass` is a library. Play Feature Delivery operates on the consuming app's dynamic-feature modules, so the SDK cannot impose PFD from a library artifact. The SDK can provide documentation, sample module layouts, and possibly CLI scaffolding that creates app-owned feature modules, but the final module graph, install-time policy, and Play publishing setup belong to the app.

PFD also excludes sideload APKs, non-Play stores, many China-market flows, and some enterprise deployments. It is therefore an advanced app-distribution option, not the base SDK answer. It should layer on top of per-artifact opt-in rather than replace it.

### C. Runtime dynamic loading

Runtime dynamic loading would make the SDK or app download/load adapter code after install. It is the most flexible distribution story in theory, but it is also the highest maintenance cost: the app must own signature verification, version compatibility, resource loading, native library extraction, classloader boundaries, startup failure modes, offline behavior, and security review. The large adapters here include native libraries and resources, so this is not just loading a small Dex file.

This strategy is not justified for 0.2 or 0.3. It should be reconsidered only if a real product must support non-Play deferred delivery and cannot accept per-artifact APK variants.

## iOS Analog

The iOS package already follows the per-artifact pattern more closely: SwiftPM splits `XgGlass` and `XgGlassMeta`, and Frame is sample-only rather than a default SDK dependency. Android should converge on that model for production app generation while keeping an explicit all-devices path for demos.

## Recommendation

For 0.2, keep this as a design spike and do not introduce Play Feature Delivery or runtime dynamic loading. The evidence supports per-artifact opt-in as the default implementation direction: keep `universal-full` as an explicit zero-config/demo aggregate, but make production app generation and docs steer consumers toward selected artifacts. For 0.3, update the CLI/template path so generated apps can choose devices up front and emit source that only references selected adapters; reserve embedded Frame/Flutter and Meta for explicit opt-in because each costs roughly 15 to 16 MiB on arm64.

Do not make Play Feature Delivery the SDK default. Add PFD scaffolding/docs only after per-artifact opt-in exists, and only as an advanced consuming-app layout for Play-distributed apps. Do not pursue runtime dynamic loading unless a partner requires non-Play deferred adapter delivery and accepts the security and maintenance burden.

Revisit this decision if one of these triggers happens:

- A production consumer needs Play-only deferred delivery for Frame/Flutter or Meta and cannot tolerate separate APK/AAB variants.
- A large adapter grows past 20 MiB arm64 compressed size or starts dominating startup/install constraints.
- The generated app moves to minification/resource shrinking by default and new measurements show materially different adapter deltas.
- RayNeo vendor AARs become part of the default generated host asset and need separate measurement.
- Android and iOS packaging goals diverge enough that the current SwiftPM-style artifact split no longer maps to consumer expectations.

## CLI Follow-Up

The CLI now supports `xg-glass init --devices <list>` for generated Android apps. The absent flag keeps the zero-config all-device demo path on `xgglass-universal-full`; explicit selections emit core/app-contract plus only the requested device artifacts and record the selection in `xg-glass.yaml`.

The size guidance above is still the practical rule: include Frame/Flutter only when the app needs Frame support because it adds about 14.6 MiB to the arm64 APK, and include Meta only when the app needs Meta DAT support because it adds about 15.9 MiB relative to the lean floor.
