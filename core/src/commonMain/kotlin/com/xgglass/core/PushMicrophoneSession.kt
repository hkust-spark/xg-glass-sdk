package com.xgglass.core

import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A [MicrophoneSession] whose audio is *pushed in* by a platform transport — BLE notifications,
 * a Flutter event channel, an OS microphone callback, etc.
 *
 * This centralizes Kotlin [Flow] production so platform adapters that cannot easily build a
 * [MutableSharedFlow] themselves (notably Swift / Kotlin-Native adapters bridging a vendor SDK)
 * only need to call plain methods: [emit] for each audio frame, [emitEndOfStream] when the vendor
 * signals end-of-stream, and the inherited [stop] when the app tears the session down.
 *
 * Threading: [audio] is a hot [MutableSharedFlow] whose `tryEmit` is thread-safe, so the transport
 * may call [emit] from whatever callback thread it uses. The transport is expected to supply a
 * monotonically increasing [emit] `sequence` (vendors like Frame already number their frames).
 *
 * @param onStop invoked once when the session stops, so the adapter can tell the vendor/transport
 *   to stop streaming (e.g. a "stopMicrophone" channel call). Kept non-suspending so it is trivial
 *   to pass from Swift.
 */
class PushMicrophoneSession(
    override val format: AudioFormat,
    private val onStop: () -> Unit = {},
    extraBufferCapacity: Int = 128,
) : MicrophoneSession {

    private val _audio = MutableSharedFlow<AudioChunk>(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val audio: Flow<AudioChunk> = _audio

    @Volatile
    private var finished = false

    @Volatile
    private var lastSequence = -1L

    /**
     * Push one audio frame. No-op (returns false) once the stream has ended. Returns false when the
     * buffer was full and the frame was dropped (DROP_OLDEST); the [sequence] gap lets a downstream
     * consumer detect the loss.
     */
    fun emit(bytes: ByteArray, sequence: Long): Boolean {
        if (finished) return false
        lastSequence = sequence
        return _audio.tryEmit(
            AudioChunk(bytes = bytes, format = format, sequence = sequence),
        )
    }

    /**
     * Terminate the stream with an end-of-stream marker (empty bytes). Idempotent — subsequent
     * calls (and any late [emit]) are ignored.
     */
    fun emitEndOfStream(sequence: Long) {
        if (finished) return
        finished = true
        _audio.tryEmit(
            AudioChunk(bytes = ByteArray(0), format = format, sequence = sequence, endOfStream = true),
        )
    }

    override suspend fun stop() {
        if (finished) return
        onStop()
        emitEndOfStream(lastSequence + 1)
    }
}
