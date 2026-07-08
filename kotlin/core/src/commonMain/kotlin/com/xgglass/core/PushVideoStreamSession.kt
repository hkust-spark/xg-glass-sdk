package com.xgglass.core

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow

/**
 * A [VideoStreamSession] whose frames are pushed by a platform transport.
 *
 * The backing queue keeps low-latency drop-oldest semantics and records real drops. Unlike
 * [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST], this implementation increments
 * [droppedFrameCount] only when it evicts an old frame to make room for the newest one.
 */
@OptIn(ExperimentalAtomicApi::class)
class PushVideoStreamSession(
    override val format: VideoFormat,
    private val onStop: () -> Unit = {},
    bufferCapacity: Int = 128,
) : VideoStreamSession {

    private val capacity = bufferCapacity.coerceAtLeast(1)
    private val lock = NonSuspendingLock()
    private val queue = ArrayDeque<VideoFrame>()
    private val frameSignals = Channel<Unit>(capacity = Channel.CONFLATED)

    override val frames: Flow<VideoFrame> = flow {
        while (true) {
            val frame = lock.withLock { removeFirstOrNullLocked() }
            if (frame != null) {
                emit(frame)
                if (frame.endOfStream) return@flow
                continue
            }

            val signal = frameSignals.receiveCatching()
            if (signal.isClosed) {
                val finalFrame = lock.withLock { removeFirstOrNullLocked() } ?: return@flow
                emit(finalFrame)
                if (finalFrame.endOfStream) return@flow
            }
        }
    }

    private val finished = AtomicBoolean(false)
    private val lastSequence = AtomicLong(-1)
    private val droppedFrames = AtomicLong(0)

    override val droppedFrameCount: Long
        get() = droppedFrames.load()

    fun emit(frame: VideoFrame): Boolean {
        val emitted = lock.withLock {
            if (frame.endOfStream) {
                return@withLock emitEndFrameLocked(frame)
            }
            if (finished.load()) return@withLock false
            lastSequence.store(frame.sequence)
            offerNewestLocked(frame)
            true
        }
        if (emitted) {
            if (frame.endOfStream) closeFrameSignals() else notifyFrameAvailable()
        }
        return emitted
    }

    fun emitEndOfStream(sequence: Long) {
        val emitted = lock.withLock {
            emitEndFrameLocked(
                VideoFrame(
                    bytes = ByteArray(0),
                    format = format,
                    sequence = sequence,
                    endOfStream = true,
                )
            )
        }
        if (emitted) closeFrameSignals()
    }

    override suspend fun stop() {
        val emitted = lock.withLock {
            if (!finished.compareAndSet(false, true)) return
            onStop()
            val sequence = lastSequence.load() + 1
            offerNewestLocked(
                VideoFrame(
                    bytes = ByteArray(0),
                    format = format,
                    sequence = sequence,
                    endOfStream = true,
                )
            )
            true
        }
        if (emitted) closeFrameSignals()
    }

    private fun emitEndFrameLocked(frame: VideoFrame): Boolean {
        if (!finished.compareAndSet(false, true)) return false
        lastSequence.store(frame.sequence)
        offerNewestLocked(frame)
        return true
    }

    private fun offerNewestLocked(frame: VideoFrame) {
        if (queue.size >= capacity) {
            queue.removeFirst()
            droppedFrames.fetchAndAdd(1)
        }
        queue.addLast(frame)
    }

    private fun removeFirstOrNullLocked(): VideoFrame? {
        return if (queue.isEmpty()) null else queue.removeFirst()
    }

    private fun notifyFrameAvailable() {
        frameSignals.trySend(Unit)
    }

    private fun closeFrameSignals() {
        frameSignals.trySend(Unit)
        frameSignals.close()
    }
}
