package com.xgglass.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BaseGlassesClientEventDropTest {
    @Test
    fun suspendOverflowCountsRejectedEventsWithoutSubscriber() = runTest {
        val client = EventDropClient(BufferOverflow.SUSPEND)

        repeat(67) { client.log("event-$it") }

        assertEquals(3L, client.droppedEventCount)
    }

    @Test
    fun suspendOverflowCountsRejectedEventsAndReportsThemOnce() = runTest {
        val client = EventDropClient(BufferOverflow.SUSPEND)
        val gate = CompletableDeferred<Unit>()
        val blockedCollector = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { gate.await() }
        }

        client.log("blocks-collector")
        repeat(64) { client.log("buffer-$it") }
        repeat(3) { client.log("dropped-$it") }

        assertEquals(3L, client.droppedEventCount)

        blockedCollector.cancelAndJoin()
        runCurrent()

        val received = mutableListOf<GlassesEvent>()
        val reportCollector = launch(UnconfinedTestDispatcher(testScheduler)) {
            client.events.take(2).toList(received)
        }
        client.log("after-drop")
        advanceUntilIdle()

        reportCollector.cancelAndJoin()
        assertTrue(GlassesEvent.Log("after-drop") in received)
        assertTrue(GlassesEvent.Warning("3 event(s) were dropped because the event buffer was full") in received)
        assertEquals(3L, client.droppedEventCount)

        val next = async(UnconfinedTestDispatcher(testScheduler)) {
            client.events.take(1).toList()
        }
        client.log("after-reset")
        advanceUntilIdle()

        val afterReset = next.await()
        assertEquals(listOf(GlassesEvent.Log("after-reset")), afterReset)
        assertFalse(GlassesEvent.Warning("3 event(s) were dropped because the event buffer was full") in afterReset)
        assertEquals(3L, client.droppedEventCount)
    }

    @Test
    fun dropOldestOverflowDoesNotCountDiscardedOldestEvents() = runTest {
        val client = EventDropClient(BufferOverflow.DROP_OLDEST)

        repeat(128) { client.log("event-$it") }

        assertEquals(0L, client.droppedEventCount)
    }

    private class EventDropClient(
        eventBufferOverflow: BufferOverflow,
    ) : BaseGlassesClient(
        initialCapabilities = DeviceCapabilities(),
        eventBufferOverflow = eventBufferOverflow,
    ) {
        override val model: GlassesModel = GlassesModel.SIMULATOR

        fun log(message: String): Boolean = emitEvent(GlassesEvent.Log(message))

        override suspend fun doConnect() = Unit

        override suspend fun disconnect() = Unit

        override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> =
            unsupported()

        override suspend fun display(text: String, options: DisplayOptions): Result<Unit> =
            unsupported()

        override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> =
            unsupported()

        override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> =
            unsupported()

        private fun <T> unsupported(): Result<T> =
            Result.failure(GlassesError.Unsupported("test client"))
    }
}
