# iOS device support: feasibility matrix and policy

xg.glass's shared layer (`:core` + `:app-contract`) is Kotlin Multiplatform and
compiles for iOS. Whether a *given glasses model* works on iOS is a separate
question, decided **per device** by its transport — not by whether the vendor
ships an iOS SDK for the shared layer.

## Principle

- The **common contract** (`GlassesClient`, models, app-contract) is platform-neutral.
- Each device's **transport implementation** is platform-specific. Android has one;
  iOS needs its own.
- So "does device X work on iOS?" == "can device X's transport be implemented with
  iOS public APIs (or a vendor iOS SDK)?"

## Decision framework — "the vendor has no iOS SDK, do we adapt it ourselves?"

| Case | Transport | Can we self-adapt on iOS? |
|------|-----------|---------------------------|
| **Open / standard** | BLE GATT, standard Bluetooth audio (HFP/A2DP) with a documented/plain protocol | **Yes** — reimplement with `CoreBluetooth` / `AVFoundation`. Moderate effort. |
| **iOS-incompatible** | Wi-Fi Direct / Wi-Fi P2P, USB host, `adb` | **No** — iOS has no public API for these. Not an SDK problem; a platform limit. |
| **Closed / licensed proprietary** | Vendor SDK does encryption / handshake / attestation / licensing over the wire, no iOS variant | **Generally no** — self-adapting means reverse-engineering a closed/licensed protocol: impractical and legally risky. Realistically **gated on the vendor** shipping an iOS SDK. |

Caveat: "uses BLE" only means self-adaptable when the BLE protocol is open/plain.
If the vendor layers proprietary crypto/handshake on top of BLE, it slides toward
the "closed/licensed" row.

## Per-device matrix (current devices)

| Device | Transport | iOS feasibility | Approach |
|--------|-----------|-----------------|----------|
| **Simulator** | Device's own camera + screen (dev/test only) | ✅ Yes | Reimplement with `AVFoundation` (camera) + a UI display sink. No vendor dep. |
| **Omi** | Open BLE GATT characteristics | ✅ Yes | Self-adapt with `CoreBluetooth`. |
| **Frame** | BLE + on-glasses Lua | ✅ Yes | BLE self-adapt; Brilliant Labs also has cross-platform SDKs. |
| **Meta** | DAT (closed SDK, talks to the Meta app) | ✅ Yes (vendor) | Meta ships an **official iOS DAT SDK** (`facebook/meta-wearables-dat-ios`) — use it; do not self-adapt. |
| **Rokid** | BLE + **Wi-Fi P2P** + CXR **licensed** SDK (`.lc`) | ❌ Not now | Wi-Fi P2P is unavailable on iOS **and** CXR is a closed/licensed Android SDK. Gated on a Rokid iOS SDK. |
| **RayNeo** | On-glasses **Android** app; phone side installs/controls via **adb** | ❌ Not now | The on-glasses app is Android regardless of phone OS; the install/control path uses adb (no iOS equivalent). Needs a non-adb control channel. |
| **Android XR** | Jetpack XR (Android) | ❌ N/A | Android-only by definition (preview scaffold). |

## Policy

1. **iOS covers the subset of devices whose transport is iOS-feasible.** Implement
   an iOS transport per device where feasible; the common contract and app code are
   unchanged, so app developers write once and only the *available device set*
   differs per platform.
2. **Label per-device iOS support** (supported / vendor-gated / not feasible) in the
   SDK docs and, where useful, at runtime — never promise iOS for a device that
   cannot work.
3. **Self-adapt only for open/standard transports.** Do **not** reverse-engineer
   closed or licensed vendor protocols (e.g. Rokid CXR); wait for the vendor.

## Recommended iOS adapter order

1. **Simulator** — no vendor SDK / no hardware; proves the whole iOS path (framework
   consumption + a working `GlassesClient` on iOS) end-to-end.
2. **Meta** — vendor iOS DAT SDK already exists.
3. **Omi / Frame** — open BLE, self-adaptable with `CoreBluetooth`.
4. **Rokid / RayNeo** — deferred (vendor-gated / no iOS transport today).
