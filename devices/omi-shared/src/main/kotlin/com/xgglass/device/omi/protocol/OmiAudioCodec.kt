package com.xgglass.device.omi.protocol

import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioFormat
import com.xgglass.core.GlassesError

/** Codec byte from the read-only 19B10002 characteristic; no host-side transcoding.
 * Source: https://github.com/BasedHardware/omi/blob/5d992f1fe92b8521283b4cfa4ba89844717203bf/sdks/device/PROTOCOL.md
 */
internal object OmiAudioCodec {
    fun format(value: ByteArray): AudioFormat {
        val id = value.firstOrNull()?.toInt()?.and(0xFF)
            ?: throw GlassesError.Transport("Omi audio codec response is empty")
        val encoding = when (id) {
            0 -> AudioEncoding.PCM_S16_LE
            1 -> AudioEncoding.PCM_S8
            // Opus 20 uses 10 ms frames; Opus FS320 (21) uses 20 ms frames.
            // Forward both without assuming a fixed frame duration or decoding them.
            20, 21 -> AudioEncoding.OPUS
            else -> throw GlassesError.Unsupported("Omi audio codec $id is not supported")
        }
        return AudioFormat(encoding = encoding, sampleRateHz = 16_000, channelCount = 1)
    }
}
