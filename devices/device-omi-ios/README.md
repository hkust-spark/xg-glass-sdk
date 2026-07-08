## Omi iOS device module

This module integrates Omi over CoreBluetooth into the unified `xg-glass` API surface.

- **Host platform**: iOS (Kotlin/Native).
- **Model**: `GlassesModel.OMI`.
- **Capabilities**:
  - Audio input over BLE via `startMicrophone`.
  - Photo capture over BLE via `capturePhoto`.
  - Tap events when the connected device exposes the Omi DevKit button service.
  - Battery-level events when the connected device exposes the standard BLE Battery Service.
  - No display or audio playback primitives are exposed by the Omi BLE services.

### Notes

- Button events are gated on service discovery, not model/name matching. `supportsTapEvents`
  flips to `true` only when the connected peripheral exposes service
  `23BA7924-0000-1000-7450-346EAC492E92` and characteristic
  `23BA7925-0000-1000-7450-346EAC492E92`.
- Battery events are gated on service discovery, not model/name matching. `supportsBatteryEvents`
  flips to `true` only when the connected peripheral exposes the standard BLE Battery Service
  `0000180F-0000-1000-8000-00805F9B34FB` and Battery Level characteristic
  `00002A19-0000-1000-8000-00805F9B34FB`. The CoreBluetooth adapter rolls capability back if
  notification enablement fails, reads the initial level after notifications are enabled, and emits
  later updates as `GlassesEvent.BatteryLevel(percent)` with percent clamped to `0..100`.
- `supportsLongPressEvents` intentionally remains `false`: only legacy Omi firmware emits
  button code `3`, while post-2026-01 firmware powers off silently on a hold of at least
  three seconds. The adapter still maps code `3` to `GlassesEvent.LongPress` for field
  devices that send it.
- Legacy firmware caveats: a single tap can be followed by power-off/disconnect, and
  `Tap(1)` has at least 300 ms firmware-side latency for double-tap disambiguation.

### Hardware verification checklist

- Connect to a device whose GATT services include the Omi button service.
- Confirm `client.capabilities.supportsTapEvents == true` only after that service is discovered.
- Confirm button code `1` emits `GlassesEvent.Tap(1)` and code `2` emits `GlassesEvent.Tap(2)`.
- On legacy firmware only, confirm button code `3` emits `GlassesEvent.LongPress` while
  `client.capabilities.supportsLongPressEvents` remains `false`.
- Confirm button release code `5` is ignored except for rate-limited diagnostic logs.
- Confirm `client.capabilities.supportsBatteryEvents == true` only after the standard BLE Battery Service is discovered.
- Confirm `client.capabilities.supportsBatteryEvents` returns to `false` if Battery Level notification enablement fails.
- Confirm the initial Battery Level read and later notifications emit `GlassesEvent.BatteryLevel(percent)`.
