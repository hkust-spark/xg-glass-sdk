package com.xgglass.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PushVideoStreamSessionTest {
    private val format = VideoFormat(
        encoding = VideoFrameEncoding.JPEG,
        width = 640,
        height = 480,
        framesPerSecond = 3,
    )

    @Test
    fun emitsFramesThenEndOfStreamInOrder() = runTest {
        val session = PushVideoStreamSession(format = format)
        val received = mutableListOf<VideoFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            session.frames.collect { received.add(it) }
        }

        session.emit(frame(sequence = 0, payload = 1))
        session.emit(frame(sequence = 1, payload = 2))
        session.emitEndOfStream(sequence = 2)

        job.join()

        assertEquals(3, received.size)
        assertContentEquals(byteArrayOf(1), received[0].bytes)
        assertEquals(0L, received[0].sequence)
        assertFalse(received[0].endOfStream)
        assertEquals(format, received[0].format)
        assertContentEquals(byteArrayOf(2), received[1].bytes)
        assertTrue(received[2].endOfStream)
        assertEquals(2L, received[2].sequence)
        assertEquals(0, received[2].bytes.size)
    }

    @Test
    fun smokeEmitsOneFrame() = runTest {
        val session = PushVideoStreamSession(format = format)

        session.emit(frame(sequence = 7, payload = 9))
        session.emitEndOfStream(sequence = 8)
        val received = mutableListOf<VideoFrame>()
        session.frames.collect { received += it }

        assertEquals(listOf(7L, 8L), received.map { it.sequence })
        assertContentEquals(byteArrayOf(9), received.first().bytes)
        assertTrue(received.last().endOfStream)
    }

    @Test
    fun dropOldestCountsRealDropsAndNewestFrameWins() = runTest {
        val session = PushVideoStreamSession(format = format, bufferCapacity = 1)

        assertTrue(session.emit(frame(sequence = 0, payload = 0)))
        assertTrue(session.emit(frame(sequence = 1, payload = 1)))
        assertTrue(session.emit(frame(sequence = 2, payload = 2)))

        val newest = session.frames.first()

        assertEquals(2L, session.droppedFrameCount)
        assertEquals(2L, newest.sequence)
        assertContentEquals(byteArrayOf(2), newest.bytes)
        assertFalse(newest.endOfStream)
    }

    @Test
    fun ignoresEmitAfterEndOfStream() = runTest {
        val session = PushVideoStreamSession(format = format)

        session.emit(frame(sequence = 0, payload = 1))
        session.emitEndOfStream(sequence = 1)
        assertFalse(session.emit(frame(sequence = 2, payload = 2)))

        val received = mutableListOf<VideoFrame>()
        session.frames.collect { received += it }

        assertEquals(listOf(0L, 1L), received.map { it.sequence })
        assertTrue(received.last().endOfStream)
    }

    @Test
    fun stopInvokesOnStopOnceAndEmitsEndOfStream() = runTest {
        var onStopCount = 0
        val session = PushVideoStreamSession(format = format, onStop = { onStopCount += 1 })
        val received = mutableListOf<VideoFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            session.frames.collect { received.add(it) }
        }

        session.emit(frame(sequence = 4, payload = 4))
        session.stop()
        session.stop()

        job.join()

        assertEquals(1, onStopCount)
        assertEquals(listOf(4L, 5L), received.map { it.sequence })
        assertTrue(received.last().endOfStream)
    }

    @Test
    fun concurrentEmitAndStopEmitsEndOfStreamLast() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                coroutineScope {
                    repeat(50) { round ->
                        val session = PushVideoStreamSession(format = format, bufferCapacity = 256)
                        val received = mutableListOf<VideoFrame>()
                        val collector = launch(Dispatchers.Default) {
                            session.frames.collect { received.add(it) }
                        }
                        val start = CompletableDeferred<Unit>()
                        val emitters = (0 until 64).map { index ->
                            launch(Dispatchers.Default) {
                                start.await()
                                session.emit(
                                    frame(
                                        sequence = round * 1_000L + index,
                                        payload = (index % 127).toByte(),
                                    )
                                )
                            }
                        }
                        val stopper = launch(Dispatchers.Default) {
                            start.await()
                            session.stop()
                        }

                        start.complete(Unit)
                        (emitters + stopper).joinAll()
                        collector.join()

                        assertTrue(received.isNotEmpty(), "round $round did not emit EOS")
                        val eosIndex = received.indexOfFirst { it.endOfStream }
                        assertTrue(eosIndex >= 0, "round $round did not emit EOS")
                        assertEquals(received.lastIndex, eosIndex, "round $round emitted frames after EOS")
                        assertEquals(0L, session.droppedFrameCount, "round $round should not drop with spare capacity")
                        assertFalse(session.emit(frame(sequence = 1_000_000L + round, payload = 1)))
                    }
                }
            }
        }
    }

    @Test
    fun liveCollectorDoesNotCountDropsWhenBufferHasRoom() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                coroutineScope {
                    val frameCount = 200
                    val session = PushVideoStreamSession(format = format, bufferCapacity = 1)
                    val observed = List(frameCount) { CompletableDeferred<Unit>() }
                    val received = mutableListOf<Long>()
                    val collector = launch(Dispatchers.Default) {
                        session.frames.collect { frame ->
                            if (!frame.endOfStream) {
                                received += frame.sequence
                                observed[frame.sequence.toInt()].complete(Unit)
                            }
                        }
                    }
                    val producer = launch(Dispatchers.Default) {
                        repeat(frameCount) { index ->
                            assertTrue(session.emit(frame(sequence = index.toLong(), payload = (index % 127).toByte())))
                            observed[index].await()
                            assertEquals(0L, session.droppedFrameCount)
                        }
                        session.emitEndOfStream(sequence = frameCount.toLong())
                    }

                    producer.join()
                    collector.join()

                    assertEquals((0 until frameCount).map { it.toLong() }, received)
                    assertEquals(0L, session.droppedFrameCount)
                }
            }
        }
    }

    private fun frame(sequence: Long, payload: Byte): VideoFrame =
        VideoFrame(
            bytes = byteArrayOf(payload),
            format = format,
            sequence = sequence,
            timestampMs = 1_000 + sequence,
            rotationDegrees = 0,
        )
}
