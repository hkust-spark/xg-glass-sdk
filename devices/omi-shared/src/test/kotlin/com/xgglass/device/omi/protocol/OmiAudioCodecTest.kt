package com.xgglass.device.omi.protocol

import com.xgglass.core.AudioEncoding
import com.xgglass.core.GlassesError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OmiAudioCodecTest {
    @Test
    fun reportsTheDeviceEncodingWithoutTranscoding() {
        for ((id, encoding) in listOf(0 to AudioEncoding.PCM_S16_LE, 1 to AudioEncoding.PCM_S8,
            20 to AudioEncoding.OPUS, 21 to AudioEncoding.OPUS)) {
            val format = OmiAudioCodec.format(byteArrayOf(id.toByte()))
            assertEquals(encoding, format.encoding)
            assertEquals(16_000, format.sampleRateHz)
            assertEquals(1, format.channelCount)
        }
    }

    @Test
    fun unknownAndMissingCodecNeverBecomeOpus() {
        assertFailsWith<GlassesError.Unsupported> { OmiAudioCodec.format(byteArrayOf(0xFF.toByte())) }
        assertFailsWith<GlassesError.Transport> { OmiAudioCodec.format(byteArrayOf()) }
    }
}
