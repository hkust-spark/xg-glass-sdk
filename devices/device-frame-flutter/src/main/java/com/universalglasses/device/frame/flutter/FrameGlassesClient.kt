package com.universalglasses.device.frame.flutter

import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.DeviceCapabilities
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.AudioSource
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.core.MicrophoneOptions
import com.universalglasses.core.MicrophoneSession
import com.universalglasses.core.PlayAudioOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Frame implementation of [GlassesClient] backed by a host-provided [FrameFlutterBridge].
 *
 * The bridge is expected to talk to a Flutter module that uses `frame_ble` + `frame_msg`.
 */
class FrameGlassesClient(
    private val bridge: FrameFlutterBridge,
) : GlassesClient {

    override val model: GlassesModel = GlassesModel.FRAME
    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        canRecordAudio = true,
        canPlayTts = false,
        canPlayAudioBytes = false,
        supportsTapEvents = true,
        supportsStreamingTextUpdates = true,
    )

    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    @Volatile
    private var activeMic: MicrophoneSession? = null

    init {
        stateScope.launch {
            bridge.state.collect { st ->
                _state.value = when (st) {
                    FrameFlutterState.Disconnected -> ConnectionState.Disconnected
                    FrameFlutterState.Connecting -> ConnectionState.Connecting
                    FrameFlutterState.Connected -> ConnectionState.Connected
                    is FrameFlutterState.Error -> ConnectionState.Error(
                        com.universalglasses.core.GlassesError.Transport("Frame error: ${st.message}")
                    )
                }
            }
        }
    }

    override val events: Flow<GlassesEvent> = bridge.events

    override suspend fun connect(): Result<Unit> = bridge.connect()

    override suspend fun disconnect() {
        bridge.disconnect()
        activeMic = null
        _state.value = ConnectionState.Disconnected
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
}
