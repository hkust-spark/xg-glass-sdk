package com.xgglass.device.omi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OmiButtonProtocolTest {

    @Test
    fun mapsKnownButtonCodes() {
        assertEquals(OmiButtonEvent.Tap(1), OmiButtonEvents.parse(buttonPacket(1)))
        assertEquals(OmiButtonEvent.Tap(2), OmiButtonEvents.parse(buttonPacket(2)))
        assertEquals(OmiButtonEvent.LongPress, OmiButtonEvents.parse(buttonPacket(3)))
    }

    @Test
    fun dropsShortButtonPackets() {
        assertNull(OmiButtonEvents.parse(byteArrayOf(0x01, 0x00, 0x00)))
        assertNull(OmiButtonEvents.parse(ByteArray(0)))
    }

    @Test
    fun ignoresInitialReadDeadCodeReleaseAndUnknownCodes() {
        assertEquals(OmiButtonEvent.Ignored(0L), OmiButtonEvents.parse(buttonPacket(0)))
        assertEquals(OmiButtonEvent.Ignored(4L), OmiButtonEvents.parse(buttonPacket(4)))
        assertEquals(OmiButtonEvent.Ignored(5L), OmiButtonEvents.parse(buttonPacket(5)))
        assertEquals(OmiButtonEvent.Ignored(99L), OmiButtonEvents.parse(buttonPacket(99)))
    }

    @Test
    fun decodesButtonCodeFromFirstFourBytesLittleEndian() {
        assertEquals(
            OmiButtonEvent.Ignored(0x01000005L),
            OmiButtonEvents.parse(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00, 0x00)),
        )
    }

    private fun buttonPacket(code: Int): ByteArray = byteArrayOf(
        (code and 0xFF).toByte(),
        ((code ushr 8) and 0xFF).toByte(),
        ((code ushr 16) and 0xFF).toByte(),
        ((code ushr 24) and 0xFF).toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
    )
}
