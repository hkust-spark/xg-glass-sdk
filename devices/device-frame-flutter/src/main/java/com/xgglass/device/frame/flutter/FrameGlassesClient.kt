package com.xgglass.device.frame.flutter

import com.xgglass.core.AudioSource
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.CaptureOptions
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PlayAudioOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Frame implementation backed by a host-provided [FrameFlutterBridge].
 *
 * The bridge is expected to talk to a Flutter module that uses `frame_ble` + `frame_msg`.
 */
class FrameGlassesClient(
    private val bridge: FrameFlutterBridge,
) : BaseGlassesClient(
    initialCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        canRecordAudio = true,
        canPlayTts = false,
        canPlayAudioBytes = false,
        supportsTapEvents = true,
        supportsStreamingTextUpdates = true,
    ),
    eventBufferOverflow = BufferOverflow.SUSPEND,
) {

    override val model: GlassesModel = GlassesModel.FRAME

    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var activeMic: MicrophoneSession? = null

    init {
        stateScope.launch {
            bridge.state.collect { bridgeState ->
                resolveBridgeStateTransition(current = _state.value, bridgeState = bridgeState)
                    ?.let { next -> _state.value = next }
            }
        }
        stateScope.launch {
            bridge.events.collect { event ->
                _events.emit(event)
            }
        }
    }

    override suspend fun doConnect() {
        bridge.connect().getOrThrow()
    }

    override fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError) ?: GlassesError.Transport("Frame connect failed: ${error.message}", error)
    }

    override suspend fun disconnect() {
        try {
            bridge.disconnect()
        } finally {
            try {
                activeMic?.stop()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
            } finally {
                activeMic = null
                _state.value = ConnectionState.Disconnected
            }
        }
    }

    override suspend fun capturePhoto(options: CaptureOptions) = bridge.capturePhoto(options)

    override suspend fun display(text: String, options: DisplayOptions) = bridge.displayText(text, options)

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> {
        return Result.failure(GlassesError.Unsupported("Frame does not have a speaker; audio playback is not supported"))
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (activeMic != null) return Result.failure(GlassesError.Busy)

        val fmtRes = bridge.startMicrophone(options)
        return fmtRes.map { fmt ->
            val session = object : MicrophoneSession {
                override val format = fmt
                override val audio = bridge.microphone
                override suspend fun stop() {
                    try {
                        bridge.stopMicrophone()
                    } finally {
                        if (activeMic === this) {
                            activeMic = null
                        }
                    }
                }
            }
            activeMic = session
            session
        }
    }

    private fun resolveBridgeStateTransition(
        current: ConnectionState,
        bridgeState: FrameFlutterState,
    ): ConnectionState? = when (bridgeState) {
        FrameFlutterState.Disconnected -> when (current) {
            is ConnectionState.Connected, ConnectionState.Disconnected -> ConnectionState.Disconnected
            else -> null
        }
        FrameFlutterState.Connecting -> null
        FrameFlutterState.Connected -> when (current) {
            ConnectionState.Disconnected, is ConnectionState.Error -> ConnectionState.Connected
            else -> null
        }
        is FrameFlutterState.Error -> ConnectionState.Error(
            GlassesError.Transport("Frame error: ${bridgeState.message}")
        )
    }
}
