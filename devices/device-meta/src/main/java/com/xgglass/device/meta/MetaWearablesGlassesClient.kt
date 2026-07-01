package com.xgglass.device.meta

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.removeStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.TextStyle
import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioSource
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayOptions
import com.xgglass.core.ExternalActivityBridge
import com.xgglass.core.ExternalActivityResult
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PcmFormat
import com.xgglass.core.PhotoQuality
import com.xgglass.core.PlayAudioOptions
import com.xgglass.core.android.openAndroidMicrophone
import com.xgglass.core.android.playEncodedViaMediaPlayer
import com.xgglass.core.android.playPcmViaAudioTrack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Meta AI glasses adapter for Android phone hosts.
 *
 * Notes:
 * - Camera/photo capture is routed through the DAT SDK.
 * - Display text is enabled only when the connected DAT device is Meta Ray-Ban Display.
 *   Camera-only Meta devices keep canDisplayText=false and may reject display attach at runtime.
 * - Mic/speaker audio uses Android's Bluetooth communication stack, following DAT docs.
 */
class MetaWearablesGlassesClient @JvmOverloads constructor(
    private val activity: AppCompatActivity,
    private val externalActivityBridge: ExternalActivityBridge? = null,
    private val options: MetaWearablesOptions = MetaWearablesOptions(),
) : BaseGlassesClient(
    initialCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = false,
        canRecordAudio = true,
        canPlayTts = false,
        canPlayAudioBytes = true,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = false,
    ),
) {

    override val model: GlassesModel = GlassesModel.META

    private val audioManager: AudioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val deviceSelector: DeviceSelector = options.deviceSelector ?: AutoDeviceSelector()

    @Volatile private var activeSession: DeviceSession? = null
    @Volatile private var activeStream: Stream? = null
    @Volatile private var activeDisplay: Display? = null
    @Volatile private var activeMic: MicrophoneSession? = null
    @Volatile private var activePlayer: MediaPlayer? = null

    private val displayLock = Mutex()

    private val audioRouteLock = Any()
    private var audioRouteRefCount = 0
    private var previousAudioMode: Int? = null

    override suspend fun doConnect() {
        ensureWearablesInitialized()
        ensureRegistered()
        val session = createDeviceSession()
        activeSession = session
        try {
            session.start()
            awaitSessionStarted(session)
            val deviceId = awaitActiveDevice()
            applyConnectedDeviceInfo(deviceId)
            emitLog("Meta: connected to device $deviceId")
        } catch (ce: CancellationException) {
            activeSession = null
            runCatching { session.stop() }
            throw ce
        } catch (e: Exception) {
            activeSession = null
            runCatching { session.stop() }
            throw e
        }
    }

    override fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError) ?: GlassesError.Transport("Meta connect failed: ${error.message}", error)
    }

    override suspend fun disconnect() {
        val session = activeSession
        detachDisplayQuietly(session)
        stopActiveStreamQuietly(session)
        activeSession = null
        try {
            session?.stop()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Best-effort disconnect.
        }
        try { activeMic?.stop() } catch (_: Exception) {}
        activeMic = null
        try { activePlayer?.release() } catch (_: Exception) {}
        activePlayer = null
        forceClearAudioRoute()
        resetCapabilities()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)

        return try {
            ensureCameraPermissionGranted()

            // DAT only exposes still capture while a stream session is active, so we start
            // a short-lived stream on the active device session, wait for STREAMING,
            // capture once, then detach it.
            val captured = withTimeout(options.timeoutMs) {
                val session = activeSession ?: throw GlassesError.NotConnected
                val stream = startPhotoStream(session, options)
                try {
                    awaitStreaming(stream, options.timeoutMs)
                    val photo = stream.capturePhoto().fold(
                        onSuccess = { it },
                        onFailure = { error, cause ->
                            throw GlassesError.Transport("Meta capturePhoto failed: ${error.description}", cause)
                        },
                    )
                    withContext(Dispatchers.Default) {
                        photo.toCapturedImage(
                            quality = options.photoQuality.toMetaJpegQuality(),
                            sourceModel = model,
                        )
                    }
                } finally {
                    stopPhotoStream(session, stream)
                }
            }

            Result.success(captured)
        } catch (e: TimeoutCancellationException) {
            Result.failure(GlassesError.Timeout("Meta capture"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Meta capture failed: ${e.message}", e))
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)

        return try {
            val display = ensureDisplayStarted()
            val displayText = text
            display.sendContent {
                flexBox(
                    direction = Direction.COLUMN,
                    padding = 24,
                    alignment = Alignment.CENTER,
                    crossAlignment = Alignment.CENTER,
                ) {
                    text(displayText, style = TextStyle.BODY)
                }
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { error, cause ->
                    Result.failure(GlassesError.Transport("Meta display sendContent failed: ${error.description}", cause))
                },
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(GlassesError.Timeout("Meta display"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Meta display failed: ${e.message}", e))
        }
    }

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)

        return when (source) {
            is AudioSource.Tts -> Result.failure(
                GlassesError.Unsupported(
                    "Meta AI glasses do not provide on-device TTS through DAT. Use AudioSource.RawBytes instead."
                )
            )
            is AudioSource.RawBytes -> playRawAudio(source, options)
        }
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        if (!hasRecordAudioPermission()) return Result.failure(GlassesError.PermissionDenied)
        if (activeMic != null) return Result.failure(GlassesError.Busy)
        if (options.preferredEncoding != AudioEncoding.PCM_S16_LE) {
            return Result.failure(
                GlassesError.Unsupported("Meta microphone currently supports PCM_S16_LE only.")
            )
        }

        return try {
            acquireAudioRoute()
            delay(this.options.audioRouteWarmupMs)

            val sampleRate = HFP_SAMPLE_RATE_HZ
            val micOptions = options.copy(
                preferredEncoding = AudioEncoding.PCM_S16_LE,
                preferredSampleRateHz = sampleRate,
                preferredChannelCount = 1,
            )
            val session = openAndroidMicrophone(
                options = micOptions,
                audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRateHz = sampleRate,
                channelCount = 1,
                minBufferErrorMessage = { "Meta AudioRecord.getMinBufferSize failed: $it" },
                uninitializedMessage = "Meta AudioRecord not initialized",
                sharedFlowOverflow = BufferOverflow.DROP_OLDEST,
                afterStop = {
                    activeMic = null
                    releaseAudioRoute()
                },
            ).getOrThrow()
            activeMic = session
            emitLog("Meta: microphone started over Bluetooth HFP")
            Result.success(session)
        } catch (ce: CancellationException) {
            releaseAudioRoute()
            throw ce
        } catch (e: Exception) {
            releaseAudioRoute()
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Meta microphone failed: ${e.message}", e))
        }
    }

    private suspend fun playRawAudio(source: AudioSource.RawBytes, options: PlayAudioOptions): Result<Unit> {
        val bytes = source.data
        if (bytes.isEmpty()) return Result.success(Unit)

        return try {
            if (options.interrupt) {
                try { activePlayer?.release() } catch (_: Exception) {}
                activePlayer = null
            }

            ensureMusicVolumeNotZero()
            val preferredOutput = resolvePreferredMediaOutputDevice()

            val pcm = source.pcmFormat
            if (pcm != null) {
                playPcm(bytes, pcm, preferredOutput)
            } else {
                playEncoded(bytes, preferredOutput)
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Meta playAudio failed: ${e.message}", e))
        }
    }

    private suspend fun playPcm(data: ByteArray, pcm: PcmFormat, preferredOutput: AudioDeviceInfo?) {
        playPcmViaAudioTrack(
            data = data,
            format = pcm,
            usageAttributes = AudioAttributes.USAGE_MEDIA,
            interrupt = false,
            legacyStreamType = AudioManager.STREAM_MUSIC,
            preferredDevice = preferredOutput,
            unsupportedOpusMessage = "Meta playAudio does not support OPUS PCM playback.",
            uninitializedMessage = "Meta AudioTrack not initialized",
        ).getOrThrow()
    }

    private suspend fun playEncoded(data: ByteArray, preferredOutput: AudioDeviceInfo?) {
        playEncodedViaMediaPlayer(
            data = data,
            usageAttributes = AudioAttributes.USAGE_MEDIA,
            interrupt = false,
            tempFileFactory = { File(activity.cacheDir, "meta_audio_${System.currentTimeMillis()}.tmp") },
            legacyStreamType = AudioManager.STREAM_MUSIC,
            preferredDevice = preferredOutput,
            currentPlayer = { activePlayer },
            setCurrentPlayer = { activePlayer = it },
            errorMessage = { what, extra -> "Meta MediaPlayer error: what=$what extra=$extra" },
        ).getOrThrow()
    }

    private suspend fun ensureWearablesInitialized() {
        synchronized(initLock) {
            if (wearablesInitialized) return
            try {
                Wearables.initialize(activity.applicationContext)
                    .onFailure { error, cause ->
                        wearablesInitialized = false
                        throw GlassesError.Transport("Meta Wearables initialize failed: ${error.description}", cause)
                    }
                wearablesInitialized = true
            } catch (e: Exception) {
                wearablesInitialized = false
                throw e
            }
        }
    }

    private suspend fun ensureRegistered() {
        when (val current = Wearables.registrationState.value) {
            RegistrationState.REGISTERED -> return
            RegistrationState.UNAVAILABLE -> emitWarn("Meta registration state is UNAVAILABLE; launching registration anyway.")
            else -> emitLog("Meta registration state: $current")
        }

        emitLog("Meta: starting registration flow in Meta AI app")
        withContext(Dispatchers.Main) {
            Wearables.startRegistration(activity)
        }

        val registered = withTimeoutOrNull(options.registrationTimeoutMs) {
            Wearables.registrationState.first { state ->
                emitLog("Meta registration state: $state")
                state == RegistrationState.REGISTERED
            }
        }
        if (registered != RegistrationState.REGISTERED) {
            throw GlassesError.Timeout("Meta registration")
        }
    }

    private suspend fun awaitActiveDevice(): DeviceIdentifier {
        val current = deviceSelector.activeDevice()
        if (current != null) return current

        return withTimeoutOrNull(options.deviceDiscoveryTimeoutMs) {
            val selected = deviceSelector.activeDeviceFlow().first { it != null }
            selected ?: throw GlassesError.Transport("Meta device selector emitted no active device")
        } ?: throw GlassesError.Timeout("Meta device discovery")
    }

    private fun createDeviceSession(): DeviceSession {
        return Wearables.createSession(deviceSelector).fold(
            onSuccess = { it },
            onFailure = { error, cause ->
                throw GlassesError.Transport("Meta createSession failed: ${error.description}", cause)
            },
        )
    }

    private suspend fun awaitSessionStarted(session: DeviceSession) {
        var lastState = session.state.value
        val state = withTimeoutOrNull(options.deviceDiscoveryTimeoutMs) {
            session.state.first { currentState ->
                lastState = currentState
                emitLog("Meta session state: $currentState")
                currentState == DeviceSessionState.STARTED ||
                    currentState == DeviceSessionState.STOPPED
            }
        } ?: throw GlassesError.Transport("Meta session timed out before STARTED (lastState=$lastState)")

        if (state != DeviceSessionState.STARTED) {
            throw GlassesError.Transport("Meta session stopped before STARTED: $state")
        }
    }

    private suspend fun ensureCameraPermissionGranted() {
        val statusResult = Wearables.checkPermissionStatus(Permission.CAMERA)
            .onFailure { error, cause ->
                throw GlassesError.Transport("Meta camera permission check failed: $error", cause)
            }
        if (statusResult.getOrNull() == PermissionStatus.Granted) {
            return
        }

        val bridge = externalActivityBridge ?: throw GlassesError.PermissionDenied
        val contract = Wearables.RequestPermissionContract()
        val activityResult: ExternalActivityResult = bridge.launch(
            contract.createIntent(activity, Permission.CAMERA)
        )
        val permissionResult = contract.parseResult(activityResult.resultCode, activityResult.data)
            .onFailure { error, cause ->
                throw GlassesError.Transport("Meta camera permission request failed: $error", cause)
            }

        if (permissionResult.getOrNull() != PermissionStatus.Granted) {
            throw GlassesError.PermissionDenied
        }
    }

    private suspend fun applyConnectedDeviceInfo(deviceId: DeviceIdentifier) {
        val metadata = Wearables.devicesMetadata[deviceId]?.first()
        val deviceType = metadata?.deviceType
        updateCapabilities { it.copy(canDisplayText = deviceType == DeviceType.META_RAYBAN_DISPLAY) }

        if (metadata == null) return
        if (metadata.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED) {
            emitWarn("Meta device '${metadata.name.ifEmpty { deviceId.toString() }}' requires a firmware update.")
        }
    }

    private fun startPhotoStream(session: DeviceSession, options: CaptureOptions): Stream {
        val videoQuality = when {
            (options.targetWidth ?: 0) >= 1280 || (options.targetHeight ?: 0) >= 720 -> VideoQuality.HIGH
            (options.targetWidth ?: 0) >= 896 || (options.targetHeight ?: 0) >= 504 -> VideoQuality.MEDIUM
            else -> VideoQuality.LOW
        }

        val stream = session.addStream(
            StreamConfiguration(
                videoQuality = videoQuality,
                frameRate = 15,
            ),
        ).fold(
            onSuccess = { it },
            onFailure = { error, cause ->
                throw GlassesError.Transport("Meta addStream failed: ${error.description}", cause)
            },
        )
        activeStream = stream
        stream.start().fold(
            onSuccess = { },
            onFailure = { error, cause ->
                // Clean up the just-added stream so it is not orphaned in the session.
                activeStream = null
                runCatching { stream.stop() }
                runCatching { session.removeStream() }
                throw GlassesError.Transport("Meta stream start failed: ${error.description}", cause)
            },
        )
        return stream
    }

    private suspend fun awaitStreaming(stream: Stream, timeoutMs: Long) {
        var lastState = stream.state.value
        val state = withTimeoutOrNull(timeoutMs) {
            stream.state.first { currentState ->
                lastState = currentState
                emitLog("Meta stream state: $currentState")
                currentState == StreamState.STREAMING ||
                    currentState == StreamState.CLOSED
            }
        } ?: throw GlassesError.Transport(
            "Meta stream timed out before reaching STREAMING (lastState=$lastState)"
        )

        if (state != StreamState.STREAMING) {
            throw GlassesError.Transport("Meta stream closed before reaching STREAMING: $state")
        }
    }

    private fun stopPhotoStream(session: DeviceSession, stream: Stream) {
        if (activeStream === stream) activeStream = null
        try {
            stream.stop()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Best-effort cleanup.
        }
        try {
            session.removeStream().onFailure { error, _ ->
                emitWarn("Meta: removeStream failed: ${error.description}")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Best-effort cleanup.
        }
    }

    private fun stopActiveStreamQuietly(session: DeviceSession?) {
        val stream = activeStream
        activeStream = null
        try {
            stream?.stop()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Best-effort disconnect.
        }
        if (session != null) {
            try {
                session.removeStream().onFailure { error, _ ->
                    emitWarn("Meta: removeStream failed: ${error.description}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Best-effort disconnect.
            }
        }
    }

    private suspend fun ensureDisplayStarted(): Display = displayLock.withLock {
        val existing = activeDisplay
        val reusable = if (existing == null) {
            null
        } else when (existing.state.value) {
            DisplayState.STARTED -> existing
            DisplayState.STARTING -> {
                awaitDisplayStarted(existing)
                existing
            }
            DisplayState.STOPPING,
            DisplayState.STOPPED,
            DisplayState.CLOSED,
            -> {
                // Terminal state: detach before re-attaching so the session can accept a new display.
                activeDisplay = null
                activeSession?.let { session -> runCatching { session.removeDisplay() } }
                null
            }
        }

        reusable ?: run {
            val session = activeSession ?: throw GlassesError.NotConnected
            val display = session.addDisplay().fold(
                onSuccess = { it },
                onFailure = { error, cause ->
                    throw mapDisplayAttachError(error, cause)
                },
            )
            activeDisplay = display
            awaitDisplayStarted(display)
            display
        }
    }

    private suspend fun awaitDisplayStarted(display: Display) {
        var lastState = display.state.value
        val state = withTimeoutOrNull(options.displayTimeoutMs) {
            display.state.first { currentState ->
                lastState = currentState
                emitLog("Meta display state: $currentState")
                currentState == DisplayState.STARTED ||
                    currentState == DisplayState.STOPPED ||
                    currentState == DisplayState.CLOSED
            }
        } ?: throw GlassesError.Transport("Meta display timed out before STARTED (lastState=$lastState)")

        if (state != DisplayState.STARTED) {
            activeDisplay = null
            throw GlassesError.Transport("Meta display stopped before STARTED: $state")
        }
    }

    private fun mapDisplayAttachError(error: DeviceSessionError, cause: Throwable?): GlassesError {
        return when (error) {
            DeviceSessionError.CAPABILITY_DENIED,
            DeviceSessionError.CAPABILITY_NOT_FOUND,
            -> GlassesError.Unsupported("Meta display unsupported or denied: ${error.description}")
            else -> GlassesError.Transport("Meta addDisplay failed: ${error.description}", cause)
        }
    }

    private fun detachDisplayQuietly(session: DeviceSession?) {
        val display = activeDisplay
        activeDisplay = null
        if (session != null) {
            try {
                session.removeDisplay().onFailure { error, _ ->
                    emitWarn("Meta: removeDisplay failed: ${error.description}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Best-effort disconnect.
            }
        }
        try {
            display?.stop()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Best-effort disconnect.
        }
    }

    private fun acquireAudioRoute() {
        synchronized(audioRouteLock) {
            if (audioRouteRefCount == 0) {
                previousAudioMode = audioManager.mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!routeAudioToBluetooth()) {
                    audioManager.mode = previousAudioMode ?: AudioManager.MODE_NORMAL
                    previousAudioMode = null
                    throw GlassesError.Transport("Meta Bluetooth communication device unavailable")
                }
            }
            audioRouteRefCount++
        }
    }

    private fun releaseAudioRoute() {
        synchronized(audioRouteLock) {
            if (audioRouteRefCount <= 0) return
            audioRouteRefCount--
            if (audioRouteRefCount == 0) {
                clearAudioRouteLocked()
            }
        }
    }

    private fun forceClearAudioRoute() {
        synchronized(audioRouteLock) {
            audioRouteRefCount = 0
            clearAudioRouteLocked()
        }
    }

    private fun clearAudioRouteLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }
        audioManager.mode = previousAudioMode ?: AudioManager.MODE_NORMAL
        previousAudioMode = null
    }

    private fun routeAudioToBluetooth(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val selected = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: return false
            audioManager.setCommunicationDevice(selected)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                true
            }.getOrElse { false }
        }
    }

    private fun resolvePreferredMediaOutputDevice(): AudioDeviceInfo? {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                outputDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                }
            } else {
                null
            }
    }

    private fun ensureMusicVolumeNotZero() {
        try {
            val stream = AudioManager.STREAM_MUSIC
            val current = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream)
            if (current <= 0 && max > 0) {
                audioManager.setStreamVolume(stream, (max / 2).coerceAtLeast(1), 0)
            }
        } catch (_: Exception) {
            // Best-effort only.
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    data class MetaWearablesOptions(
        val deviceSelector: DeviceSelector? = null,
        val registrationTimeoutMs: Long = 90_000,
        val deviceDiscoveryTimeoutMs: Long = 30_000,
        val displayTimeoutMs: Long = 30_000,
        val audioRouteWarmupMs: Long = 1_000,
    )

    private companion object {
        private const val HFP_SAMPLE_RATE_HZ = 8_000

        private val initLock = Any()
        @Volatile private var wearablesInitialized = false
    }
}

private fun PhotoData.toCapturedImage(quality: Int, sourceModel: GlassesModel): CapturedImage {
    val bitmap = when (this) {
        is PhotoData.Bitmap -> bitmap
        is PhotoData.HEIC -> decodeHeicToBitmap(data)
    }
    val bytes = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
    return CapturedImage(
        jpegBytes = bytes,
        width = bitmap.width,
        height = bitmap.height,
        rotationDegrees = null,
        sourceModel = sourceModel,
    )
}

private fun decodeHeicToBitmap(buffer: java.nio.ByteBuffer): Bitmap {
    val copy = buffer.duplicate()
    val bytes = ByteArray(copy.remaining())
    copy.get(bytes)
    val matrix = readExifTransform(bytes)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw GlassesError.Transport("Meta HEIC decode failed")
    return if (matrix.isIdentity) {
        decoded
    } else {
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed != decoded) {
            decoded.recycle()
        }
        transformed
    }
}

private fun PhotoQuality.toMetaJpegQuality(): Int = when (this) {
    PhotoQuality.LOWEST -> 25
    PhotoQuality.LOW -> 50
    PhotoQuality.MEDIUM -> 75
    PhotoQuality.HIGH -> 92
    PhotoQuality.HIGHEST -> 100
}

private fun readExifTransform(heicBytes: ByteArray): Matrix {
    val exif = try {
        ByteArrayInputStream(heicBytes).use { ExifInterface(it) }
    } catch (_: Exception) {
        return Matrix()
    }

    return Matrix().apply {
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(270f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        }
    }
}
