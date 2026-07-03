package com.xgglass.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PushMicrophoneSessionTest {

    private val format = AudioFormat(
        encoding = AudioEncoding.PCM_S16_LE,
        sampleRateHz = 16_000,
        channelCount = 1,
    )

    @Test
    fun emitsFramesThenEndOfStream() = runTest {
        val sink = PushMicrophoneSession(format = format)
        val received = mutableListOf<AudioChunk>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            sink.audio.collect { received.add(it) }
        }

        sink.emit(byteArrayOf(1, 2, 3), sequence = 0)
        sink.emit(byteArrayOf(4, 5), sequence = 1)
        sink.emitEndOfStream(sequence = 2)

        job.cancel()

        assertEquals(3, received.size)
        assertContentEquals(byteArrayOf(1, 2, 3), received[0].bytes)
        assertEquals(0L, received[0].sequence)
        assertFalse(received[0].endOfStream)
        assertEquals(format, received[0].format)
        assertContentEquals(byteArrayOf(4, 5), received[1].bytes)
        assertTrue(received[2].endOfStream)
        assertEquals(2L, received[2].sequence)
        assertEquals(0, received[2].bytes.size)
    }

    @Test
    fun ignoresEmitAfterEndOfStream() = runTest {
        val sink = PushMicrophoneSession(format = format)
        val received = mutableListOf<AudioChunk>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            sink.audio.collect { received.add(it) }
        }

        sink.emit(byteArrayOf(1), sequence = 0)
        sink.emitEndOfStream(sequence = 1)
        assertFalse(sink.emit(byteArrayOf(2), sequence = 2))

        job.cancel()

        assertEquals(2, received.size)
        assertTrue(received.last().endOfStream)
    }

    @Test
    fun stopInvokesOnStopOnceAndEmitsEndOfStream() = runTest {
        var onStopCount = 0
        val sink = PushMicrophoneSession(format = format, onStop = { onStopCount += 1 })
        val received = mutableListOf<AudioChunk>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            sink.audio.collect { received.add(it) }
        }

        sink.emit(byteArrayOf(9), sequence = 7)
        sink.stop()
        sink.stop()

        job.cancel()

        assertEquals(1, onStopCount)
        assertEquals(2, received.size)
        val eos = received.last()
        assertTrue(eos.endOfStream)
        assertEquals(8L, eos.sequence)
    }
}
