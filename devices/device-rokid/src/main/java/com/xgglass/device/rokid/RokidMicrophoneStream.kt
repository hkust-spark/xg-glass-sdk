package com.xgglass.device.rokid

import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.xgglass.core.AudioChunk
import com.xgglass.core.AudioFormat
import com.xgglass.core.MicrophoneSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Correlates the CXR-M 1.2.x callbacks and owns exactly one recorder lifetime. */
internal class RokidMicrophoneStream(
    override val format: AudioFormat,
    private val lock: Any = Any(),
    private val isCurrentConnection: () -> Boolean = { true },
    private val closeRecorder: () -> Unit,
    private val onFinished: (RokidMicrophoneStream) -> Unit,
    private val warn: (String) -> Unit = {},
) : MicrophoneSession, AudioStreamListener {
    private val chunks = MutableSharedFlow<AudioChunk>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val audio: Flow<AudioChunk> = chunks
    private var running = true
    private var streamId: Int? = null
    private var sequence = 0L
    private var dropped = 0L
    val isRunning: Boolean get() = synchronized(lock) { running }

    override fun onStartAudioStream(id: Int, codecType: Int, modeOrChannels: Int, streamType: String?) = synchronized(lock) {
        if (!running || !isCurrentConnection()) return@synchronized
        if (streamId == null) streamId = id
        else if (streamId != id) dropped("unexpected stream start id=$id expected=$streamId")
    }

    override fun onAudioStream(id: Int, data: ByteArray?, offset: Int, length: Int) = synchronized(lock) {
        if (!running || !isCurrentConnection()) return@synchronized
        if (streamId != id) {
            dropped("unexpected audio stream id=$id expected=$streamId")
            return@synchronized
        }
        if (data == null || offset < 0 || length <= 0 || length > data.size || offset > data.size - length) {
            dropped("invalid audio frame offset=$offset length=$length")
            return@synchronized
        }
        chunks.tryEmit(AudioChunk(data.copyOfRange(offset, offset + length), format, ++sequence))
        Unit
    }

    override fun onAudioStreamFinish(id: Int) = synchronized(lock) {
        if (!running || !isCurrentConnection()) return@synchronized
        if (streamId != id) {
            dropped("unexpected stream finish id=$id expected=$streamId")
            return@synchronized
        }
        finish(close = false)
    }

    override suspend fun stop() { stopNow() }

    fun stopNow() = synchronized(lock) { finish(close = true) }

    fun cancelStart() = synchronized(lock) { finish(close = false) }

    private fun finish(close: Boolean) {
        if (!running) return
        running = false
        try {
            if (close) closeRecorder()
        } finally {
            onFinished(this)
            chunks.tryEmit(AudioChunk(ByteArray(0), format, ++sequence, endOfStream = true))
        }
    }

    private fun dropped(reason: String) {
        dropped++
        if (dropped == 1L || dropped % 50L == 0L) warn("Rokid: ignored $reason; count=$dropped")
    }
}
