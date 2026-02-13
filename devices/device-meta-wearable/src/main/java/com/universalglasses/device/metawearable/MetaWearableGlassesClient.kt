package com.universalglasses.device.metawearable

import android.app.Activity
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
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MetaWearableGlassesClient(private val context: Context) : GlassesClient {

    companion object {
        private const val TAG = "MetaGlassesClient"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var streamSession: StreamSession? = null

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
                        .collect { connState -> _state.emit(connState) }
            }

            if (context is Activity) {
                Wearables.startRegistration(context)
            } else {
                Log.w(
                        TAG,
                        "Context is not an Activity. If app is not registered, connection may fail."
                )
            }

            // We report success to the caller immediately; state flow updates asynchronously
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
        if (streamSession != null) return streamSession!!

        val session =
                Wearables.startStreamSession(
                        context = context,
                        deviceSelector = AutoDeviceSelector(),
                        streamConfiguration =
                                StreamConfiguration(
                                        videoQuality = VideoQuality.MEDIUM,
                                        frameRate = 24
                                )
                )
        streamSession = session

        scope.launch {
            session.state.collect { s ->
                if (s == StreamSessionState.STOPPED || s == StreamSessionState.CLOSED) {
                    if (streamSession == session) {
                        streamSession = null
                    }
                }
            }
        }
        return session
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (_state.value !is ConnectionState.Connected) {
            return Result.failure(
                    IllegalStateException(
                            "Device disconnected. Please ensure glasses are connected and authorized (State: ${_state.value})"
                    )
            )
        }
        return try {
            val session = getOrCreateSession()
            var resultImage: CapturedImage? = null

            val result = session.capturePhoto()

            result
                    .onSuccess { photoData ->
                        emitLog("Photo data received: $photoData")
                        val jpegBytes =
                                when (photoData) {
                                    is PhotoData.Bitmap -> {
                                        val stream = ByteArrayOutputStream()
                                        photoData.bitmap.compress(
                                                Bitmap.CompressFormat.JPEG,
                                                90,
                                                stream
                                        )
                                        stream.toByteArray()
                                    }
                                    is PhotoData.HEIC -> {
                                        val buffer = photoData.data
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        bytes
                                    }
                                }

                        resultImage =
                                CapturedImage(
                                        jpegBytes = jpegBytes,
                                        sourceModel = model,
                                        width = options.targetWidth,
                                        height = options.targetHeight
                                )
                    }
                    .onFailure { e -> throw RuntimeException("Photo capture failed: $e") }

            if (resultImage != null) {
                Result.success(resultImage!!)
            } else {
                Result.failure(RuntimeException("Photo capture returned no data"))
            }
        } catch (e: Exception) {
            emitWarn("Capture exception: ${e.message}")
            if (e is IllegalStateException && e.message?.contains("disconnected", true) == true) {
                // Try to force state update if SDK thinks it's disconnected
                scope.launch { _state.emit(ConnectionState.Disconnected) }
            }
            Result.failure(e)
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Display not supported on this device"))
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        return Result.failure(NotImplementedError("Microphone not implemented yet"))
    }
}
