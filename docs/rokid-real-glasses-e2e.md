# Rokid real-glasses end-to-end (E2E) runbook

How to run a full end-to-end test of the Rokid device path on **real Rokid glasses**
(Rokid CXR-M v1.0.4 class devices) through a generated `xg.glass` app.

This is a hardware + credentials procedure: it needs physical Rokid glasses, the
Rokid developer authorization, and a person to observe the glasses. The steps
below were validated up to the hardware boundary (build → install → launch →
connect flow) on a phone with no glasses attached; the glasses-side capabilities
(capture / display / mic / audio) require the real device.

## 1. Prerequisites

- **Rokid glasses** (e.g. Rokid Max / Rokid Glasses, CXR-M v1.0.4).
- **Android phone** (Android 12+ recommended) reachable over `adb` from the Mac
  build host. Confirm: `adb devices` lists your phone's serial.
- **Rokid developer authorization** (both are secrets — never commit them):
  - an **SN license file** (`.lc`), issued for your specific glasses' serial, and
  - your developer **client secret**.
- Mac build host with the SDK toolchain (JDK 17/21, Android SDK, `xg-glass` CLI).
  The Rokid vendor SDK `com.rokid.cxr:client-m:1.0.4` resolves from the configured
  Maven repo (allow-listed in `settings.gradle.kts`).

## 2. Provide the Rokid authorization

The generated app supports two ways to supply the `.lc` + client secret. Pick one.

**Option A — at runtime in the app UI (recommended for testing):**
- On the app's ROKID screen, tap **"Select SN license (.lc)"** and pick your `.lc`.
- Enter your **Client secret** in the field above **Connect**.
- These are persisted in encrypted storage (SecureStore) for later runs.

**Option B — build-time via resources + `local.properties`:**
- Put the `.lc` under `app/src/main/res/raw/` (e.g. `res/raw/rokid_sn.lc`).
- In the project's `local.properties` (git-ignored), set:
  ```properties
  rokid.clientSecret=<your-client-secret>
  rokid.snRawName=<raw_resource_name_without_extension>   # e.g. rokid_sn
  ```

If neither is provided, the app logs `Rokid: SN auth missing.` with these exact
instructions and the connect/capture path will not authorize.

## 3. Pair the glasses (Bluetooth + Wi-Fi P2P)

Rokid CXR-M connects the phone to the glasses over **Bluetooth** (control) plus
**Wi-Fi P2P** (data). Power on the glasses and make them discoverable, then let
the app scan (step 5). The app requests these runtime permissions on connect;
grant them:
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`
- location (`ACCESS_FINE_LOCATION`) is also needed for BLE scanning on some ROMs.

## 4. Build, install, and launch

From the SDK build host, generate a full (non-`--sim`) project and run it on the phone:

```bash
xg-glass init /path/to/myrokidapp
cd /path/to/myrokidapp
xg-glass run --serial <phone-serial>
# or: ./gradlew :app:assembleDebug && xg-glass install --serial <phone-serial>
```

The app launches with **ROKID** preselected in the device dropdown.

## 5. Run the E2E and what to observe

1. **Connect** — supply credentials (step 2), tap **Connect**, grant the
   nearby-devices permission. Expect the log to show the BT scan → init → Wi-Fi P2P
   handshake, then **Status: Connected**. Commands become enabled.
   - Connect is bounded by `RokidOptions.connectTimeoutMs` (default 30 s). With no
     glasses in range it fails cleanly: `connect(ROKID) => false Timed out waiting
     for 30000 ms` and returns to **Status: Disconnected** (verified on-device).
2. **Capture photo** — trigger a capture command; a `CapturedImage` (JPEG) should
   return. Verify the image is non-empty and roughly the expected resolution.
3. **Display text** — trigger a display command; confirm the text appears on the
   **glasses display**.
4. **Microphone** — start the mic; confirm PCM frames flow (the app logs frames /
   you can route them to an app entry that transcribes or meters audio).
5. **Audio playback** — play raw/encoded bytes or TTS; confirm audio plays on the
   **glasses speaker** (Rokid supports on-device TTS: `canPlayTts = true`).
6. **Disconnect** — confirm a clean teardown (BT + Wi-Fi P2P released, no leaked
   scan/socket) and **Status: Disconnected**.

## 6. Troubleshooting

- **`Rokid: SN auth missing`** — provide the `.lc` + client secret (step 2). The
  `.lc` is bound to a specific glasses serial; a mismatched `.lc` will not authorize.
- **Connect times out after 30 s** — glasses not powered/in range/paired, or BLE
  scan blocked. Check the granted permissions and that Bluetooth + Wi-Fi are on.
  Tune `RokidOptions.connectTimeoutMs` if your environment is slow.
- **BT reconnect fails, falls back to scan** — expected after re-pair / reset /
  firmware change; the client clears stale reconnect info and rescans automatically.
- **Permissions re-prompt** — pre-grant for automated runs:
  `adb -s <serial> shell pm grant <appId> android.permission.BLUETOOTH_SCAN` (and
  `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`).

## 7. Driving it headless (for CI / scripted smoke)

- Target a single device with `ANDROID_SERIAL=<serial>` so multi-device hosts
  don't fan out.
- The app logs to an on-screen `Logs:` panel; capture UI state with
  `adb exec-out screencap -p` and watch `adb logcat` for `Rokid:` lines.
- A no-glasses run is a valid **hardware-boundary** smoke: it verifies build →
  install → launch → permission request → SN-auth guidance → bounded connect
  timeout, all without a device.
