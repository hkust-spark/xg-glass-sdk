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
 * Omi DevKit button event parser.
 *
 * Source: https://github.com/BasedHardware/omi/blob/51db883/app/lib/services/capture/capture_controller.dart#L765-L771
 * mirrors the official app parser: require at least 4 bytes and decode bytes 0..3
 * as a little-endian unsigned event code. Firmware sends an 8-byte payload (two
 * little-endian int32 values); the event code is the first int32.
 *
 * Firmware caveats from BasedHardware/omi @ 51db883:
 * - Source: https://github.com/BasedHardware/omi/blob/51db883/firmware/devkit/src/button.c#L30-L44
 * - Legacy firmware may emit code 3 (long tap); post-2026-01 firmware powers off
 *   silently on >=3s hold instead, so adapters must not advertise long-press support.
 * - On legacy firmware a single tap is followed by device power-off/disconnect;
 *   consumers must not assume the BLE link survives.
 * - Tap(1) has >=300ms firmware-side latency for double-tap disambiguation; this
 *   is not an adapter bug.
 */
internal object OmiButtonEvents {
    const val INITIAL_READ = 0L
    const val SINGLE_TAP = 1L
    const val DOUBLE_TAP = 2L
    const val LONG_TAP = 3L
    const val DEAD_CODE = 4L
    const val BUTTON_RELEASE = 5L

    fun parse(packet: ByteArray): OmiButtonEvent? {
        if (packet.size < 4) return null
        val code = (packet[0].toLong() and 0xFF) or
            ((packet[1].toLong() and 0xFF) shl 8) or
            ((packet[2].toLong() and 0xFF) shl 16) or
            ((packet[3].toLong() and 0xFF) shl 24)
        return when (code) {
            SINGLE_TAP -> OmiButtonEvent.Tap(1)
            DOUBLE_TAP -> OmiButtonEvent.Tap(2)
            LONG_TAP -> OmiButtonEvent.LongPress
            else -> OmiButtonEvent.Ignored(code)
        }
    }
}

internal sealed interface OmiButtonEvent {
    data class Tap(val count: Int) : OmiButtonEvent
    data object LongPress : OmiButtonEvent
    data class Ignored(val code: Long) : OmiButtonEvent
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
