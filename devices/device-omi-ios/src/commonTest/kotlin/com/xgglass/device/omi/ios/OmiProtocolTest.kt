package com.xgglass.device.omi.ios

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class OmiAudioFramingTest {

    @Test
    fun stripsThreeByteHeaderAndReturnsPayload() {
        val packet = byteArrayOf(0x01, 0x00, 0x00, 0xAA.toByte(), 0xBB.toByte())
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), OmiAudioFraming.payload(packet))
    }

    @Test
    fun returnsNullWhenPacketHasNoPayload() {
        // Header-only (3 bytes) or shorter carries no codec payload.
        assertNull(OmiAudioFraming.payload(byteArrayOf(0x00, 0x00, 0x00)))
        assertNull(OmiAudioFraming.payload(byteArrayOf(0x00)))
        assertNull(OmiAudioFraming.payload(ByteArray(0)))
    }
}

class OmiTimeSyncTest {

    @Test
    fun encodesEpochSecondsLittleEndian() {
        assertContentEquals(
            byteArrayOf(0x04, 0x03, 0x02, 0x01),
            OmiTimeSync.epochSecondsLE(0x01020304),
        )
    }

    @Test
    fun encodesZero() {
        assertContentEquals(byteArrayOf(0, 0, 0, 0), OmiTimeSync.epochSecondsLE(0))
    }

    @Test
    fun encodesHighBitValueWithoutSignExtension() {
        // 0xFF000000 as an Int is negative; ushr must keep the byte layout clean.
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0xFF.toByte()),
            OmiTimeSync.epochSecondsLE(-0x1000000),
        )
    }
}

class JpegStartTest {

    @Test
    fun findsSoiAtStart() {
        assertEquals(0, findJpegStart(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)))
    }

    @Test
    fun findsSoiAfterPrefix() {
        assertEquals(2, findJpegStart(byteArrayOf(0x07, 0x09, 0xFF.toByte(), 0xD8.toByte(), 0x01)))
    }

    @Test
    fun returnsMinusOneWhenAbsent() {
        assertEquals(-1, findJpegStart(byteArrayOf(0x01, 0x02, 0x03)))
        assertEquals(-1, findJpegStart(ByteArray(0)))
    }
}

class OmiPhotoAssemblerTest {

    @Test
    fun assemblesSingleChunkImage() {
        val assembler = OmiPhotoAssembler()
        assertIs<OmiPhotoResult.Incomplete>(
            assembler.addPacket(packet(id = 0, payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x2A))),
        )
        val result = assembler.addPacket(eof())
        assertIs<OmiPhotoResult.Complete>(result)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x2A), result.jpegBytes)
    }

    @Test
    fun assemblesMultiChunkImageInOrder() {
        val assembler = OmiPhotoAssembler()
        assembler.addPacket(packet(id = 0, payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assembler.addPacket(packet(id = 1, payload = byteArrayOf(0x11, 0x22)))
        assembler.addPacket(packet(id = 2, payload = byteArrayOf(0x33)))
        val result = assembler.addPacket(eof())
        assertIs<OmiPhotoResult.Complete>(result)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33), result.jpegBytes)
    }

    @Test
    fun stripsBytesBeforeJpegStart() {
        val assembler = OmiPhotoAssembler()
        // Leading orientation byte (0x07) before the SOI marker must be dropped.
        assembler.addPacket(packet(id = 0, payload = byteArrayOf(0x07, 0xFF.toByte(), 0xD8.toByte(), 0x2A)))
        val result = assembler.addPacket(eof())
        assertIs<OmiPhotoResult.Complete>(result)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x2A), result.jpegBytes)
    }

    @Test
    fun keepsRawBytesWhenNoJpegMarkerPresent() {
        val assembler = OmiPhotoAssembler()
        assembler.addPacket(packet(id = 0, payload = byteArrayOf(0x01, 0x02, 0x03)))
        val result = assembler.addPacket(eof())
        assertIs<OmiPhotoResult.Complete>(result)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), result.jpegBytes)
    }

    @Test
    fun reportsDroppedChunkButKeepsBuffering() {
        val assembler = OmiPhotoAssembler()
        assembler.addPacket(packet(id = 0, payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        // Skip id 1 -> expected 1, got 2.
        val dropped = assembler.addPacket(packet(id = 2, payload = byteArrayOf(0x33)))
        assertIs<OmiPhotoResult.DroppedChunk>(dropped)
        assertEquals(1, dropped.expected)
        assertEquals(2, dropped.got)
        val result = assembler.addPacket(eof())
        assertIs<OmiPhotoResult.Complete>(result)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33), result.jpegBytes)
    }

    @Test
    fun ignoresTooShortPacket() {
        val assembler = OmiPhotoAssembler()
        assertIs<OmiPhotoResult.Incomplete>(assembler.addPacket(byteArrayOf(0x00)))
    }

    @Test
    fun ignoresEofWithEmptyBuffer() {
        val assembler = OmiPhotoAssembler()
        // EOF before any data packet must not produce a bogus zero-byte "Complete".
        assertIs<OmiPhotoResult.Incomplete>(assembler.addPacket(eof()))
    }

    @Test
    fun resetDiscardsPartialImage() {
        val assembler = OmiPhotoAssembler()
        assembler.addPacket(packet(id = 0, payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x2A)))
        assembler.reset()
        // After reset a fresh id-0 chunk starts cleanly; the stale 0x2A is gone.
        assembler.addPacket(packet(id = 0, payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        val result = assembler.addPacket(eof())
        assertIs<OmiPhotoResult.Complete>(result)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), result.jpegBytes)
    }

    private fun packet(id: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf((id and 0xFF).toByte(), ((id shr 8) and 0xFF).toByte())
        return header + payload
    }

    private fun eof(): ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
}
