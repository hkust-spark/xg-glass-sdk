package com.xgglass.core.android

import android.media.AudioRecord
import com.xgglass.core.AudioChunk
import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioFormat
import com.xgglass.core.GlassesError
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

fun openAndroidMicrophone(
    options: MicrophoneOptions,
    audioSource: Int,
    sampleRateHz: Int = options.preferredSampleRateHz ?: 16_000,
    channelCount: Int = options.preferredChannelCount ?: 1,
    unsupportedOpusMessage: String = "Android microphone: OPUS not supported",
    unsupportedChannelMessage: (Int) -> String = { "Android microphone: channelCount=$it" },
    minBufferErrorMessage: (Int) -> String = { "AudioRecord.getMinBufferSize failed: $it" },
    uninitializedMessage: String = "AudioRecord not initialized",
    sharedFlowExtraBufferCapacity: Int = 64,
    sharedFlowOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    breakOnNegativeRead: Boolean = true,
    beforeStart: () -> Unit = {},
    beforeStop: () -> Unit = {},
    afterStop: () -> Unit = {},
): Result<MicrophoneSession> {
    val encoding = when (options.preferredEncoding) {
        AudioEncoding.PCM_S16_LE -> AudioEncoding.PCM_S16_LE
        AudioEncoding.PCM_S8 -> AudioEncoding.PCM_S8
        AudioEncoding.OPUS -> return Result.failure(GlassesError.Unsupported(unsupportedOpusMessage))
    }
    val channelConfig = when (channelCount) {
        1 -> android.media.AudioFormat.CHANNEL_IN_MONO
        2 -> android.media.AudioFormat.CHANNEL_IN_STEREO
        else -> return Result.failure(GlassesError.Unsupported(unsupportedChannelMessage(channelCount)))
    }
    val audioFormat = when (encoding) {
        AudioEncoding.PCM_S16_LE -> android.media.AudioFormat.ENCODING_PCM_16BIT
        AudioEncoding.PCM_S8 -> android.media.AudioFormat.ENCODING_PCM_8BIT
        AudioEncoding.OPUS -> android.media.AudioFormat.ENCODING_PCM_16BIT
    }

    return try {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, audioFormat)
        if (minBuffer <= 0) {
            return Result.failure(GlassesError.Transport(minBufferErrorMessage(minBuffer)))
        }

        val record = AudioRecord(
            audioSource,
            sampleRateHz,
            channelConfig,
            audioFormat,
            minBuffer * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release()
            } catch (_: Exception) {}
            return Result.failure(GlassesError.Transport(uninitializedMessage))
        }

        val format = AudioFormat(
            encoding = encoding,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
        )
        val shared = MutableSharedFlow<AudioChunk>(
            extraBufferCapacity = sharedFlowExtraBufferCapacity,
            onBufferOverflow = sharedFlowOverflow,
        )
        val running = AtomicBoolean(true)
        val seq = AtomicLong(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val session = object : MicrophoneSession {
            override val format: AudioFormat = format
            override val audio: Flow<AudioChunk> = shared

            override suspend fun stop() {
                if (!running.compareAndSet(true, false)) return
                try {
                    beforeStop()
                } catch (_: Exception) {}
                try {
                    record.stop()
                } catch (_: Exception) {}
                try {
                    record.release()
                } catch (_: Exception) {}

                scope.cancel()
                afterStop()
                shared.tryEmit(
                    AudioChunk(
                        bytes = ByteArray(0),
                        format = format,
                        sequence = seq.incrementAndGet(),
                        endOfStream = true,
                    )
                )
            }
        }

        beforeStart()
        record.startRecording()
        scope.launch {
            val buffer = ByteArray(minBuffer)
            while (running.get()) {
                val read = try {
                    record.read(buffer, 0, buffer.size)
                } catch (_: Exception) {
                    break
                }
                if (read > 0) {
                    shared.tryEmit(
                        AudioChunk(
                            bytes = buffer.copyOfRange(0, read),
                            format = format,
                            sequence = seq.incrementAndGet(),
                        )
                    )
                } else if (read < 0 && breakOnNegativeRead) {
                    break
                }
            }
        }

        Result.success(session)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
