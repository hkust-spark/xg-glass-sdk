package com.xgglass.core.android

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import com.xgglass.core.AudioEncoding
import com.xgglass.core.GlassesError
import com.xgglass.core.PcmFormat
import kotlinx.coroutines.delay
import kotlin.math.max

suspend fun playPcmViaAudioTrack(
    data: ByteArray,
    format: PcmFormat,
    usageAttributes: Int,
    interrupt: Boolean,
    legacyStreamType: Int? = null,
    preferredDevice: AudioDeviceInfo? = null,
    unsupportedOpusMessage: String = "playAudio: OPUS not supported",
    uninitializedMessage: String = "AudioTrack not initialized",
    bufferSizeInBytes: (Int) -> Int = { it.coerceAtLeast(1024) },
    fallbackToLegacyStream: Boolean = false,
    checkInitialized: Boolean = true,
): Result<Unit> {
    val sampleRate = format.sampleRateHz
    val channels = format.channelCount
    val channelMask = if (channels <= 1) {
        android.media.AudioFormat.CHANNEL_OUT_MONO
    } else {
        android.media.AudioFormat.CHANNEL_OUT_STEREO
    }
    val encoding = when (format.encoding) {
        AudioEncoding.PCM_S16_LE -> android.media.AudioFormat.ENCODING_PCM_16BIT
        AudioEncoding.PCM_S8 -> android.media.AudioFormat.ENCODING_PCM_8BIT
        AudioEncoding.OPUS -> return Result.failure(GlassesError.Unsupported(unsupportedOpusMessage))
        AudioEncoding.LC3 -> return Result.failure(GlassesError.Unsupported("playAudio: LC3 not supported"))
    }

    @Suppress("UNUSED_VARIABLE")
    val shouldInterrupt = interrupt
    val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
    val bufferSize = bufferSizeInBytes(minBuffer)

    return try {
        val track = buildAudioTrack(
            sampleRate = sampleRate,
            channelMask = channelMask,
            encoding = encoding,
            usageAttributes = usageAttributes,
            legacyStreamType = legacyStreamType,
            bufferSize = bufferSize,
            fallbackToLegacyStream = fallbackToLegacyStream,
        )

        if (checkInitialized && track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return Result.failure(GlassesError.Transport(uninitializedMessage))
        }

        try {
            if (preferredDevice != null) {
                runCatching { track.preferredDevice = preferredDevice }
            }
            track.play()
            var written = 0
            while (written < data.size) {
                val n = track.write(data, written, minOf(4096, data.size - written))
                if (n <= 0) break
                written += n
            }
            val bytesPerSample = if (encoding == android.media.AudioFormat.ENCODING_PCM_16BIT) 2 else 1
            val bytesPerSecond = sampleRate.toLong() * channels * bytesPerSample
            if (bytesPerSecond > 0) {
                delay(data.size * 1000L / bytesPerSecond + 200L)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }

        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

fun rayNeoPcmBufferSize(minBuffer: Int): Int {
    return max(minBuffer.coerceAtLeast(0), 8 * 1024)
}

private fun buildAudioTrack(
    sampleRate: Int,
    channelMask: Int,
    encoding: Int,
    usageAttributes: Int,
    legacyStreamType: Int?,
    bufferSize: Int,
    fallbackToLegacyStream: Boolean,
): AudioTrack {
    val builder = {
        val attrs = AudioAttributes.Builder()
            .setUsage(usageAttributes)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        if (legacyStreamType != null) {
            attrs.setLegacyStreamType(legacyStreamType)
        }
        AudioTrack.Builder()
            .setAudioAttributes(attrs.build())
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    if (!fallbackToLegacyStream) {
        return builder()
    }

    return runCatching { builder() }.getOrElse {
        @Suppress("DEPRECATION")
        AudioTrack(
            legacyStreamType ?: AudioManager.STREAM_MUSIC,
            sampleRate,
            channelMask,
            encoding,
            bufferSize,
            AudioTrack.MODE_STREAM,
        )
    }
}
