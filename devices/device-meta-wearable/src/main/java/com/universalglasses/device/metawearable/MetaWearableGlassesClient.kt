package com.universalglasses.device.metawearable

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.RegistrationState
import com.universalglasses.core.AudioSource
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.DeviceCapabilities
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.core.MicrophoneOptions
import com.universalglasses.core.MicrophoneSession
import com.universalglasses.core.PlayAudioOptions
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class MetaWearableGlassesClient(private val context: Context) : GlassesClient {

    companion object {
        private const val TAG = "MetaGlassesClient"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var streamSession: StreamSession? = null
    private var videoCollectionJob: Job? = null

    override val model: GlassesModel = GlassesModel.META

    override val capabilities: DeviceCapabilities =
            DeviceCapabilities(
                    canCapturePhoto = true,
                    canDisplayText =
                            false, // Ray-Ban Meta likely doesn't have a display usable by 3rd party
                    canRecordAudio =
                            false, // Audio stream requires video stream in current SDK, not exposed
                    // as standalone
                    supportsTapEvents = false,
                    supportsStreamingTextUpdates = false
            )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GlassesEvent>()
    override val events: Flow<GlassesEvent> = _events.asSharedFlow()

    override suspend fun connect(): Result<Unit> {
        return try {
            try {
                // Initialize SDK. May throw if already initialized.
                Wearables.initialize(context)
            } catch (e: Exception) {
                Log.d(TAG, "Wearables initialization note: ${e.message}")
            }

            scope.launch {
                combine(Wearables.registrationState, Wearables.devices) { reg, devices ->
                    val isRegistered = reg is RegistrationState.Registered
                    val hasDevices = devices.isNotEmpty()
                    Log.d(TAG, "Status update: Registered=$isRegistered, Devices=${devices.size}")

                    if (isRegistered && hasDevices) {
                        ConnectionState.Connected
                    } else {
                        ConnectionState.Disconnected
                    }
                }
                        .collect { connState ->
                            Log.d(TAG, "Connection state → $connState")
                            _state.emit(connState)

                            when (connState) {
                                is ConnectionState.Connected -> {
                                    if (streamSession == null) {
                                        scope.launch {
                                            try {
                                                getOrCreateSession()
                                            } catch (e: Exception) {
                                                emitWarn("Meta: session start failed: ${e.message}")
                                            }
                                        }
                                    }
                                }
                                is ConnectionState.Disconnected -> {
                                    Log.d(TAG, "Disconnected — stopping stream session.")
                                    stopStreamSession()
                                }
                                else -> {}
                            }
                        }
            }

            // Registration is verified upstream (MainActivity.ensurePermissionsThenConnect)
            // before connect() is ever called. Calling startRegistration() here would
            // re-open the Meta AI app unnecessarily on every connect — intentionally omitted.
            // Success is reported immediately; state updates asynchronously as devices appear.
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {

        stopStreamSession()
        scope.cancel()
        _state.emit(ConnectionState.Disconnected)
    }

    private fun stopStreamSession() {
        videoCollectionJob?.cancel()
        videoCollectionJob = null
        streamSession?.close()
        streamSession = null
    }

    private fun emitLog(message: String) {
        scope.launch { _events.emit(GlassesEvent.Log(message)) }
    }

    private fun emitWarn(message: String) {
        scope.launch { _events.emit(GlassesEvent.Warning(message)) }
    }

    private suspend fun getOrCreateSession(): StreamSession {
        if (streamSession != null) {
            emitLog(
                    "Meta: reusing existing stream session (state: ${streamSession!!.state.value})."
            )
            return streamSession!!
        }

        emitLog("Meta: starting new stream session (LOW / 15 fps)...")
        val session =
                Wearables.startStreamSession(
                        context = context,
                        deviceSelector = AutoDeviceSelector(),
                        streamConfiguration =
                                StreamConfiguration(
                                        videoQuality = VideoQuality.LOW,
                                        frameRate = 15,
                                )
                )
        streamSession = session

        // Per Step 6 of the DAT integration guide, an active consumer of `videoStream` is
        // required to drive the session from STARTED → STREAMING. Without this collector
        // the session stalls indefinitely at STARTED and capturePhoto() never becomes valid.
        videoCollectionJob?.cancel()
        videoCollectionJob =
                scope.launch {
                    try {
                        session.videoStream
                                .collect { /* frames drive STREAMING state; not displayed here */}
                    } catch (_: Exception) {
                        // Stream closed or cancelled — no action needed.
                    }
                }

        // Log every state transition and clean up on terminal states.
        scope.launch {
            session.state.collect { s ->
                emitLog("Meta: stream session state → $s")
                when (s) {
                    StreamSessionState.STOPPED, StreamSessionState.CLOSED -> {
                        if (streamSession == session) {
                            streamSession = null
                            videoCollectionJob?.cancel()
                            videoCollectionJob = null
                        }
                    }
                    else -> {}
                }
            }
        }
        return session
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (_state.value !is ConnectionState.Connected) {
            return Result.failure(
                    IllegalStateException(
                            "Not connected. Ensure glasses are registered and authorized (state: ${_state.value})"
                    )
            )
        }

        return try {
            // Step 6 (DAT docs): get or create a StreamSession backed by AutoDeviceSelector.
            val session = getOrCreateSession()

            // Step 7 (DAT docs): capturePhoto() is only valid on an active (STREAMING) session.
            // Wait for STREAMING, logging intermediate states so the user can see progress.
            // Common reasons this is slow:
            //  - First-time session start: camera initialisation takes several seconds.
            //  - Known DAT issue (knownissues doc): streams started while glasses are doffed
            //    are paused at the OS level when the glasses are donned. The session stalls at
            //    STARTED/STOPPING. Workaround: have the user tap the side of the glasses.
            if (session.state.value != StreamSessionState.STREAMING) {
                emitLog(
                        "Meta: waiting for STREAMING state " +
                                "(current: ${session.state.value}) \u2014 make sure glasses are worn..."
                )
                try {
                    withTimeout(45_000L) {
                        session.state.first { state ->
                            when (state) {
                                StreamSessionState.STREAMING -> true
                                // Terminal states \u2014 no point waiting further.
                                StreamSessionState.STOPPED,
                                StreamSessionState.CLOSED -> true
                                else -> false
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    val cur = session.state.value
                    val hint =
                            when (cur) {
                                StreamSessionState.STARTED ->
                                        "Session reached STARTED but stalled before STREAMING. " +
                                                "Glasses may have been doffed — tap the side of your " +
                                                "Ray-Ban glasses, then try again."
                                StreamSessionState.STOPPING ->
                                        "Session began stopping before reaching STREAMING. " +
                                                "Disconnect and reconnect."
                                else ->
                                        "Session is stuck at $cur after 45 s. " +
                                                "Ensure glasses are worn and camera permission is granted."
                            }
                    return Result.failure(IllegalStateException("capturePhoto: $hint"))
                }

                val finalState = session.state.value
                if (finalState != StreamSessionState.STREAMING) {
                    // Reached a terminal non-STREAMING state.
                    return Result.failure(
                            IllegalStateException(
                                    "Stream session ended before reaching STREAMING " +
                                            "(final: $finalState). Disconnect and reconnect."
                            )
                    )
                }
            }

            emitLog("Capturing photo from glasses...")
            var capturedImage: CapturedImage? = null

            // Official DAT pattern (StreamViewModel.capturePhoto, sample app):
            //   session.capturePhoto()
            //       .onSuccess { photoData -> ... }
            //       .onFailure { err -> ... }
            session.capturePhoto()
                    .onSuccess { photoData ->
                        emitLog("Photo data received (type: ${photoData::class.simpleName}).")
                        val jpegBytes =
                                when (photoData) {
                                    is PhotoData.Bitmap -> {
                                        // Bitmap variant — compress to JPEG in-memory.
                                        ByteArrayOutputStream().use { out ->
                                            photoData.bitmap.compress(
                                                    Bitmap.CompressFormat.JPEG,
                                                    90,
                                                    out
                                            )
                                            out.toByteArray()
                                        }
                                    }
                                    is PhotoData.HEIC -> {
                                        // HEIC variant — return raw bytes; caller can decode as
                                        // needed.
                                        val buffer = photoData.data
                                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                                    }
                                }
                        capturedImage =
                                CapturedImage(
                                        jpegBytes = jpegBytes,
                                        sourceModel = model,
                                        width = options.targetWidth,
                                        height = options.targetHeight,
                                )
                    }
                    .onFailure { err -> throw RuntimeException("capturePhoto failed: $err") }

            capturedImage?.let { Result.success(it) }
                    ?: Result.failure(RuntimeException("capturePhoto returned no image data"))
        } catch (e: Exception) {
            emitWarn("Photo capture exception: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Display not supported on this device"))
    }

    override suspend fun playAudio(
            source: AudioSource,
            options: PlayAudioOptions,
    ): Result<Unit> {
        return Result.failure(
                UnsupportedOperationException("Audio playback not supported on Meta wearable")
        )
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        return Result.failure(NotImplementedError("Microphone not implemented yet"))
    }
}
