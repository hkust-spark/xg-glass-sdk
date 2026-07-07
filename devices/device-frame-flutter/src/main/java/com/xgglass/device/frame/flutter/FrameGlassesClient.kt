package com.xgglass.device.frame.flutter

import com.xgglass.core.AudioSource
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.CaptureOptions
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesEvent
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PlayAudioOptions
import com.xgglass.core.PushMicrophoneSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var activeMic: ActiveMicrophone? = null

    @Volatile
    private var hasCompletedConnect: Boolean = false

    @Volatile
    private var connectInFlight: Boolean = false

    @Volatile
    private var disconnectInFlight: Boolean = false

    init {
        stateScope.launch {
            bridge.state.collect { bridgeState ->
                val current = _state.value
                val next = resolveBridgeStateTransition(
                    current = current,
                    bridgeState = bridgeState,
                    hasCompletedConnect = hasCompletedConnect,
                    connectInFlight = connectInFlight,
                    disconnectInFlight = disconnectInFlight,
                ) ?: return@collect
                if (next is ConnectionState.Disconnected && current is ConnectionState.Connected) {
                    endActiveMicrophone()
                }
                if (next is ConnectionState.Connected && current is ConnectionState.Disconnected) {
                    _events.emit(GlassesEvent.Log("Frame runtime reported a spontaneous reconnect"))
                }
                _state.value = next
            }
        }
        stateScope.launch {
            bridge.events.collect { event ->
                _events.emit(event)
            }
        }
    }

    override suspend fun doConnect() {
        connectInFlight = true
        try {
            bridge.connect().getOrThrow()
            hasCompletedConnect = true
        } finally {
            connectInFlight = false
        }
    }

    override fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError) ?: GlassesError.Transport("Frame connect failed: ${error.message}", error)
    }

    override suspend fun disconnect() {
        disconnectInFlight = true
        try {
            bridge.disconnect()
        } finally {
            try {
                stopActiveMicrophone(notifyBridge = true)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
            } finally {
                _state.value = ConnectionState.Disconnected
                disconnectInFlight = false
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
            val sink = PushMicrophoneSession(format = fmt)
            val active = ActiveMicrophone(sink = sink)
            activeMic = active
            active.bridgeJob = stateScope.launch {
                bridge.microphone.collect { chunk ->
                    active.lastSequence = chunk.sequence
                    if (chunk.endOfStream) {
                        endActiveMicrophone(active, sequence = chunk.sequence)
                    } else {
                        active.sink.emit(bytes = chunk.bytes, sequence = chunk.sequence)
                    }
                }
            }
            val session = object : MicrophoneSession {
                override val format = fmt
                override val audio = sink.audio
                override suspend fun stop() {
                    stopActiveMicrophone(active, notifyBridge = true)
                }
            }
            session
        }
    }

    internal fun resolveBridgeStateTransition(
        current: ConnectionState,
        bridgeState: FrameFlutterState,
        hasCompletedConnect: Boolean,
        connectInFlight: Boolean,
        disconnectInFlight: Boolean,
    ): ConnectionState? {
        // Explicit connect()/disconnect() calls own their transient states. Bridge events only model
        // spontaneous runtime changes after the first successful connection.
        if (connectInFlight || disconnectInFlight) return null

        return when (bridgeState) {
            FrameFlutterState.Disconnected -> when (current) {
                is ConnectionState.Connected -> ConnectionState.Disconnected
                else -> null
            }
            FrameFlutterState.Connecting -> null
            FrameFlutterState.Connected -> when {
                hasCompletedConnect && current is ConnectionState.Disconnected -> ConnectionState.Connected
                else -> null
            }
            is FrameFlutterState.Error -> ConnectionState.Error(
                GlassesError.Transport("Frame error: ${bridgeState.message}")
            )
        }
    }

    private suspend fun stopActiveMicrophone(
        active: ActiveMicrophone? = activeMic,
        notifyBridge: Boolean,
    ) {
        val current = active ?: return
        if (activeMic !== current) {
            endActiveMicrophone(current)
            return
        }
        try {
            if (notifyBridge) {
                bridge.stopMicrophone()
            }
        } finally {
            endActiveMicrophone(current)
        }
    }

    private fun endActiveMicrophone(
        active: ActiveMicrophone? = activeMic,
        sequence: Long? = null,
    ) {
        val current = active ?: return
        current.sink.emitEndOfStream(sequence ?: current.lastSequence + 1)
        current.bridgeJob?.cancel()
        if (activeMic === current) {
            activeMic = null
        }
    }

    private class ActiveMicrophone(
        val sink: PushMicrophoneSession,
    ) {
        @Volatile
        var lastSequence: Long = -1L

        @Volatile
        var bridgeJob: Job? = null
    }
}
