package com.xgglass.device.frame.flutter

import com.xgglass.core.AudioChunk
import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioFormat
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesEvent
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameGlassesClientTest {
    @Test
    fun spontaneousConnectedAfterDropRestoresConnectedAndLogs(): Unit = runBlocking {
        val bridge = FakeFrameBridge()
        val client = FrameGlassesClient(bridge)
        assertTrue(client.connect().isSuccess)
        bridge.state.value = FrameFlutterState.Connected
        delay(50)

        bridge.state.value = FrameFlutterState.Disconnected
        waitForState(client) { it is ConnectionState.Disconnected }

        val log = async {
            withTimeout(TIMEOUT_MS) {
                client.events.first { it is GlassesEvent.Log && it.message.contains("reconnect") }
            }
        }
        delay(50)
        bridge.state.value = FrameFlutterState.Connected

        waitForState(client) { it is ConnectionState.Connected }
        assertTrue(log.await() is GlassesEvent.Log)
    }

    @Test
    fun spontaneousDropDisconnectsAndEmitsMicrophoneEos(): Unit = runBlocking {
        val bridge = FakeFrameBridge()
        val client = FrameGlassesClient(bridge)
        assertTrue(client.connect().isSuccess)
        bridge.state.value = FrameFlutterState.Connected
        delay(50)
        val session = client.startMicrophone(MicrophoneOptions()).getOrThrow()

        val eos = async {
            withTimeout(TIMEOUT_MS) {
                session.audio.first { it.endOfStream }
            }
        }
        delay(50)
        bridge.state.value = FrameFlutterState.Disconnected

        assertTrue(eos.await().endOfStream)
        waitForState(client) { it is ConnectionState.Disconnected }
    }

    @Test
    fun disconnectEmitsMicrophoneEosBeforeClearingSession(): Unit = runBlocking {
        val bridge = FakeFrameBridge()
        val client = FrameGlassesClient(bridge)
        assertTrue(client.connect().isSuccess)
        val session = client.startMicrophone(MicrophoneOptions()).getOrThrow()

        val eos = async {
            withTimeout(TIMEOUT_MS) {
                session.audio.first { it.endOfStream }
            }
        }
        delay(50)

        client.disconnect()

        assertTrue(eos.await().endOfStream)
        assertEquals(1, bridge.disconnectCalls)
        assertEquals(1, bridge.stopMicrophoneCalls)
    }

    @Test
    fun microphoneEosIsIdempotentAcrossDisconnectAndLateStop(): Unit = runBlocking {
        val bridge = FakeFrameBridge()
        val client = FrameGlassesClient(bridge)
        assertTrue(client.connect().isSuccess)
        val session = client.startMicrophone(MicrophoneOptions()).getOrThrow()

        val received = mutableListOf<AudioChunk>()
        val collector = async {
            withTimeout(300) {
                session.audio.collect { chunk -> received += chunk }
            }
        }
        delay(50)

        client.disconnect()
        waitForState(client) { it is ConnectionState.Disconnected }
        session.stop()
        delay(100)
        collector.cancel()

        assertEquals(1, received.count { it.endOfStream })
        assertEquals(1, bridge.stopMicrophoneCalls)
    }

    private suspend fun waitForState(
        client: FrameGlassesClient,
        predicate: (ConnectionState) -> Boolean,
    ): ConnectionState = withTimeout(TIMEOUT_MS) {
        while (true) {
            val state = client.state.value
            if (predicate(state)) return@withTimeout state
            delay(10)
        }
        error("unreachable")
    }

    private class FakeFrameBridge : FrameFlutterBridge {
        override val state = MutableStateFlow<FrameFlutterState>(FrameFlutterState.Disconnected)
        override val events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 16)
        override val microphone = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 16)

        var disconnectCalls = 0
            private set
        var stopMicrophoneCalls = 0
            private set

        override suspend fun connect(): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() {
            disconnectCalls += 1
        }

        override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
            return Result.success(CapturedImage(byteArrayOf(1), sourceModel = GlassesModel.FRAME))
        }

        override suspend fun displayText(text: String, options: DisplayOptions): Result<Unit> = Result.success(Unit)

        override suspend fun startMicrophone(options: MicrophoneOptions): Result<AudioFormat> {
            return Result.success(AudioFormat(AudioEncoding.PCM_S16_LE, sampleRateHz = 16_000, channelCount = 1))
        }

        override suspend fun stopMicrophone(): Result<Unit> {
            stopMicrophoneCalls += 1
            return Result.success(Unit)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
