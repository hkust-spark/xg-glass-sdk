package com.xgglass.device.omi.protocol

/**
 * Omi DevKit button protocol parser.
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
    const val INITIAL_READ: Long = 0
    const val SINGLE_TAP: Long = 1
    const val DOUBLE_TAP: Long = 2
    const val LONG_TAP: Long = 3
    const val DEAD_CODE: Long = 4
    const val BUTTON_RELEASE: Long = 5

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
