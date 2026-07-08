package com.xgglass.device.omi.ios

/**
 * Platform-independent Omi BLE protocol constants and framing helpers.
 *
 * These mirror the Android `OmiGlassesClient` behavior so the iOS (CoreBluetooth)
 * adapter and the Android (GATT) adapter interpret the same on-wire format
 * identically. Everything here is pure Kotlin and lives in `commonMain` so it can
 * be unit-tested without a BLE stack.
 */
internal object OmiBleUuids {
    /** Omi Audio Service; also used as the BLE scan filter service UUID. */
    const val AUDIO_SERVICE = "19B10000-E8F2-537E-4F6C-D104768A1214"

    /** Audio data characteristic (notify): streams codec frames. */
    const val AUDIO_DATA = "19B10001-E8F2-537E-4F6C-D104768A1214"

    /** Audio codec characteristic (read): reports the on-device codec. */
    const val AUDIO_CODEC = "19B10002-E8F2-537E-4F6C-D104768A1214"

    // Source: Bluetooth SIG Battery Service 1.1, https://www.bluetooth.com/specifications/specs/battery-service/
    /** Standard BLE Battery Service. */
    const val BATTERY_SERVICE = "0000180F-0000-1000-8000-00805F9B34FB"

    // Source: Bluetooth SIG Battery Service 1.1 Battery Level characteristic, same BAS spec.
    /** Standard BLE Battery Level characteristic (read, notify). */
    const val BATTERY_LEVEL = "00002A19-0000-1000-8000-00805F9B34FB"

    /** Photo data characteristic (notify): streams chunked JPEG. */
    const val PHOTO_DATA = "19B10005-E8F2-537E-4F6C-D104768A1214"

    /** Photo control characteristic (write): 0x05 triggers a single photo. */
    const val PHOTO_CONTROL = "19B10006-E8F2-537E-4F6C-D104768A1214"

    /** Time-sync service. */
    const val TIME_SYNC_SERVICE = "19B10030-E8F2-537E-4F6C-D104768A1214"

    /** Time-sync write characteristic: 4-byte little-endian epoch seconds. */
    const val TIME_SYNC_WRITE = "19B10031-E8F2-537E-4F6C-D104768A1214"

    // Source: https://github.com/BasedHardware/omi/blob/51db883/firmware/devkit/src/button.c#L30-L44
    /** Omi DevKit button service. Stock DevKit1 firmware and OMI Glass may not expose it. */
    const val BUTTON_SERVICE = "23BA7924-0000-1000-7450-346EAC492E92"

    // Source: https://github.com/BasedHardware/omi/blob/51db883/firmware/devkit/src/button.c#L30-L44
    /** Omi DevKit button data characteristic (read/notify). */
    const val BUTTON_TRIGGER = "23BA7925-0000-1000-7450-346EAC492E92"

    /** Advertised device names accepted by the scan, in addition to the service UUID. */
    val DEVICE_NAMES = listOf("Omi", "OMI Glass")
}

/** Command byte written to [OmiBleUuids.PHOTO_CONTROL] to request a single photo. */
internal const val OMI_PHOTO_CAPTURE_COMMAND: Byte = 0x05

/**
 * Omi audio packet framing.
 *
 * Each audio-data notification carries a 3-byte header (2-byte little-endian
 * packet index + 1-byte sub-index) followed by the codec payload. The transport
 * forwards only the payload downstream; host apps decode Opus/PCM themselves.
 */
internal object OmiAudioFraming {
    const val HEADER_SIZE = 3

    /**
     * Strip the 3-byte Omi header and return the codec payload, or `null` when the
     * packet is too short to contain any payload (header-only / malformed).
     */
    fun payload(packet: ByteArray): ByteArray? =
        if (packet.size > HEADER_SIZE) packet.copyOfRange(HEADER_SIZE, packet.size) else null
}

/** Standard BLE Battery Level parsing. */
internal object OmiBatteryProtocol {
    fun percent(packet: ByteArray): Int? = packet.firstOrNull()?.toInt()?.and(0xFF)?.coerceIn(0, 100)
}

/** Time-sync payload encoding. */
internal object OmiTimeSync {
    /** Encode epoch seconds as 4 little-endian bytes (matches the Android time-sync write). */
    fun epochSecondsLE(epochSeconds: Int): ByteArray = byteArrayOf(
        (epochSeconds and 0xFF).toByte(),
        ((epochSeconds ushr 8) and 0xFF).toByte(),
        ((epochSeconds ushr 16) and 0xFF).toByte(),
        ((epochSeconds ushr 24) and 0xFF).toByte(),
    )
}

/**
 * Find the index of the JPEG SOI marker (0xFFD8) in [data], or -1 if absent.
 *
 * Omi hardware may prepend a small orientation/header prefix before the JPEG
 * stream, so the assembler strips everything before the first SOI.
 */
internal fun findJpegStart(data: ByteArray): Int {
    var i = 0
    while (i < data.size - 1) {
        if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) return i
        i++
    }
    return -1
}
