# September 2026 SDK maintenance

This source update fixes the issues identified by the September 9 audit. It has
not been published as a new Maven, PyPI, or Swift Package release. A minor release
is required because the Swift Package minimum deployment target changes.

## Vendor migrations

- **Rokid:** CXR-M 1.2.2 replaces 1.0.4. Audio callbacks, stream identifiers,
  end-of-stream and the recording overload are adapted. The vendor's AAR now
  contains 16 KB-aligned native libraries. CXR-L is a separate integration model,
  not a replacement version number for this CXR-M adapter.
- **Meta:** Android and iOS use DAT 0.9.0 camera capabilities instead of the
  removed stream-creation API. The Swift Package and sample require iOS 17.2.
  DAT now always enables the Device Access Toolkit App Model (DAM); the old
  Android `com.meta.wearable.mwdat.DAM_ENABLED` and iOS `MWDAT.DAMEnabled` settings
  are ignored. No additional opt-in or registration call is required by this
  change; existing application registration and permissions still apply. See the
  [Android](https://github.com/facebook/meta-wearables-dat-android/blob/main/CHANGELOG.md)
  and [iOS](https://github.com/facebook/meta-wearables-dat-ios/blob/0.9.0/CHANGELOG.md)
  migration notes.
- **Omi:** both platforms read the device's codec characteristic before audio
  subscription. Codec IDs 0/1 map to PCM16/PCM8; 20/21 map to Opus without
  assuming a fixed packet duration. Unknown or unreadable codecs return an error.
- **RayNeo:** the host uses one Mercury and one IPC AAR. SDK files are synced
  into a managed directory before host compilation, and old versions disappear
  from the classpath on upgrade. An optional `rayneo-sdk.sha256` pins the exact
  developer-supplied files; see the [AAR setup](../third_party/rayneo/aar/README.md).
- **Frame:** the already locked vendor commits are now also explicit in
  `pubspec.yaml`. Migration to the new `brilliant_*` Dart/Lua SDK is separate work;
  changing names alone would break the current Lua protocol.

## Behavior corrections

Rokid rejects callbacks from retired connections and Bluetooth attempts, clears
public state on link loss, and coordinates connection-state publication with
teardown. Its display queue preserves every accepted APPEND and cancels queued
work on disconnect. Synchronous display failures are returned; delayed failures
emit a warning event.

Meta iOS photo timeouts and cancellation now stop the request and release its
listeners/camera. Concurrent captures return Busy until cleanup finishes. Swift
capability constructors and copies include the image/video/battery fields added
in 0.3.0; the sample declares its microphone privacy usage.

Android encoded playback now completes an interrupted/disconnected caller and
releases resources after preparation errors or cancellation. A noninterrupting
encoded call returns Busy while another session is active. SDK signed PCM8 is
converted at the boundary to Android's unsigned PCM8 format in both directions.

Omi serializes characteristic and descriptor operations, waits for their actual
completion, and rejects stale callbacks. Starting/stopping a microphone cannot
silently succeed when notification configuration fails.

Omi and Even iOS BLE writes now create a real Foundation `NSData` copy instead
of using an invalid native pointer cast. Native tests check empty and binary
payloads and verify that later mutations cannot change the copied data.

The CLI uses a pinned Ruff version and explicit lint rules. Tar extraction on
older Python rejects escaping paths, unsafe links (including repeated symlink
members), and special files. Internal links required by toolchain archives are
preserved.

## Deliberate dependency boundaries

CameraX is 1.6.2, AppCompat 1.8.0, Tink Android 1.23.0, and MockK 1.14.11.
AndroidX Core stays at 1.18.0 because 1.19.0 requires compileSdk 37. The coordinated
Gradle 9.6.1 / AGP 9.2.1 / Kotlin 2.4.0 baseline remains; upgrading that toolchain
or the Frame SDK is not required for the fixes above. Even G1 and INMO do not
have vendor package upgrades applicable to their current direct transports.

## Validation before release

CI now runs the new JVM regressions and checks the Swift product against the
current Kotlin XCFramework, including relevant pull requests. Meta Android PR
compilation runs when package credentials are available and explicitly reports
when it was skipped.

Local validation on September 9:

| Check | Result |
| --- | --- |
| Android host tests | 160 passed, including 21 Rokid and 26 Omi tests |
| Gradle plugin tests | 5 passed, including actual RayNeo host dependency synchronization |
| iOS Kotlin tests | 91 passed, including six Foundation data-copy regressions |
| Python CLI | Python 3.12: 130 passed; Python 3.9: 122 passed, 8 modern-filter cases skipped; Ruff passed |
| Meta Android | DAT 0.9 adapter and mock-device instrumentation sources compile |
| Swift | Meta product builds against the current Kotlin XCFramework; eight cancellation/timeout regression groups pass, including 100 cancellation races |
| iOS sample | 24 full-suite tests passed against the published Kotlin 0.3.0 binary; the added concurrent-photo regression and Meta smoke test also passed |
| Generated Android application | Debug APKs build for arm64-v8a, armeabi-v7a, and x86_64 with Rokid, Meta, Omi, Even, INMO, and Simulator enabled |
| Native packaging | All 55 64-bit libraries have 16 KB PT_LOAD alignment; all three APKs pass `zipalign -c -P 16`; all 85 native libraries have GNU_RELRO |
| Frame dependency pins | `flutter pub get --offline` succeeds with the unchanged vendor commits |

The ELF check follows Android's [64-bit ABI alignment guidance](https://developer.android.com/guide/practices/page-sizes#elf-alignment).
Some 32-bit Meta libraries retain 4 KB alignment. These checks do not include a
16 KB device run, a release AAB, a full Frame build, or RayNeo's proprietary AARs.

Physical glasses validation is still required: Rokid microphone mode=1,
inactive-connected behavior and BT/Wi-Fi recovery; Meta App Model registration
and HFP routing; Omi firmware codec/subscription behavior; and RayNeo's actual
proprietary AARs on the glasses. Simulator/mock-device tests and ELF alignment
checks do not establish those hardware results. Use the
[Rokid checklist](../devices/device-rokid/README.md) and `xg-glass validate` before
publishing a release.
