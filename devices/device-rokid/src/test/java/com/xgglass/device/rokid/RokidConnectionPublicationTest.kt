package com.xgglass.device.rokid

import com.xgglass.core.AudioSource
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PlayAudioOptions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drive the real BaseGlassesClient.connect() around the exact setup/publication race. */
class RokidConnectionPublicationTest {
    private class Client(private val useSessionGate: Boolean = true) : BaseGlassesClient(DeviceCapabilities()) {
        override val model = GlassesModel.ROKID
        private val session = RokidConnectionSession()
        var beforePublication: (() -> Unit)? = null
        var afterPublication: (() -> Unit)? = null

        override suspend fun doConnect() {
            session.markBluetoothReady()
            session.markWifiReady()
        }

        override fun publishConnectedState(): Boolean {
            if (!useSessionGate) return super.publishConnectedState()
            beforePublication?.invoke()
            val published = session.publishIfReady { _state.value = ConnectionState.Connected }
            afterPublication?.invoke()
            return published
        }

        fun loseTransport() {
            session.close(GlassesError.Transport("device disconnected"))
            _state.value = ConnectionState.Disconnected
        }

        override suspend fun disconnect() { loseTransport() }
        override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> = Result.failure(GlassesError.NotConnected)
        override suspend fun display(text: String, options: DisplayOptions): Result<Unit> = Result.failure(GlassesError.NotConnected)
        override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> = Result.failure(GlassesError.NotConnected)
        override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> = Result.failure(GlassesError.NotConnected)
    }

    @Test fun disconnectAfterSetupBeforePublicationCannotBecomeConnected() = runTest {
        val client = Client()
        client.beforePublication = client::loseTransport
        val result = client.connect()
        assertTrue(result.isFailure)
        assertEquals<Throwable?>(GlassesError.NotConnected, result.exceptionOrNull())
        assertEquals<ConnectionState>(ConnectionState.Disconnected, client.state.value)
    }

    @Test fun disconnectImmediatelyAfterPublicationIsNotOverwrittenByBase() = runTest {
        val client = Client()
        client.afterPublication = client::loseTransport
        // Setup succeeded before the loss. The loss must remain the newest state.
        assertTrue(client.connect().isSuccess)
        assertEquals<ConnectionState>(ConnectionState.Disconnected, client.state.value)
    }

    @Test fun liveSessionPublishesConnectedNormally() = runTest {
        val client = Client()
        assertTrue(client.connect().isSuccess)
        assertEquals<ConnectionState>(ConnectionState.Connected, client.state.value)
    }

    @Test fun defaultPublicationPreservesOtherAdaptersBehavior() = runTest {
        val client = Client(useSessionGate = false)
        assertTrue(client.connect().isSuccess)
        assertEquals<ConnectionState>(ConnectionState.Connected, client.state.value)
    }
}
