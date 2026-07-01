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
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Meta AI glasses adapter for Android phone hosts.
 *
 * Notes:
 * - Camera/photo capture is routed through the DAT SDK.
 * - Meta glasses do not expose a text display, so `display()` is unsupported.
 * - Mic/speaker audio uses Android's Bluetooth communication stack, following DAT docs.
 */
class MetaWearablesGlassesClient @JvmOverloads constructor(
    private val activity: AppCompatActivity,
    private val externalActivityBridge: ExternalActivityBridge? = null,
    private val options: MetaWearablesOptions = MetaWearablesOptions(),
) : BaseGlassesClient() {

    override val model: GlassesModel = GlassesModel.META

    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = false,
        canRecordAudio = true,
        canPlayTts = false,
        canPlayAudioBytes = true,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = false,
    )

    private val audioManager: AudioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val deviceSelector: DeviceSelector = options.deviceSelector ?: AutoDeviceSelector()

    @Volatile private var activeDeviceId: DeviceIdentifier? = null
    @Volatile private var activeMic: MicrophoneSession? = null
    @Volatile private var activePlayer: MediaPlayer? = null

    private val audioRouteLock = Any()
    private var audioRouteRefCount = 0
    private var previousAudioMode: Int? = null

    override suspend fun doConnect() {
        ensureWearablesInitialized()
        ensureRegistered()
        val deviceId = awaitActiveDevice()
        activeDeviceId = deviceId
        emitCompatibilityWarningIfNeeded(deviceId)
        emitLog("Meta: connected to device $deviceId")
    }

    override fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError) ?: GlassesError.Transport("Meta connect failed: ${error.message}", error)
    }

    override suspend fun disconnect() {
        try { activeMic?.stop() } catch (_: Exception) {}
        activeMic = null
        try { activePlayer?.release() } catch (_: Exception) {}
        activePlayer = null
        forceClearAudioRoute()
        activeDeviceId = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)

        return try {
            ensureCameraPermissionGranted()

            // DAT only exposes still capture while a stream session is active, so we start
            // a short-lived stream, wait for STREAMING, capture once, then close it.
            val captured = withTimeout(options.timeoutMs) {
                val session = startPhotoSession(options)
                try {
                    awaitStreaming(session, options.timeoutMs)
                    val photo = session.capturePhoto().getOrElse { cause ->
                        throw GlassesError.Transport("Meta capturePhoto failed: ${cause.message}", cause)
                    }
                    withContext(Dispatchers.Default) {
                        photo.toCapturedImage(
                            quality = options.photoQuality.toMetaJpegQuality(),
                            sourceModel = model,
                        )
                    }
                } finally {
                    session.close()
                }
            }

            Result.success(captured)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Meta capture failed: ${e.message}", e))
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        return Result.failure(
            GlassesError.Unsupported(
                "Meta AI glasses do not expose a text display surface through DAT."
            )
        )
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
                        throw GlassesError.Transport("Meta Wearables initialize failed: $error", cause)
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
            is RegistrationState.Registered -> return
            is RegistrationState.Unavailable -> {
                throw GlassesError.Transport("Meta registration unavailable: ${current.error ?: "unknown"}")
            }
            else -> Unit
        }

        emitLog("Meta: starting registration flow in Meta AI app")
        withContext(Dispatchers.Main) {
            Wearables.startRegistration(activity)
        }

        withTimeout(options.registrationTimeoutMs) {
            Wearables.registrationState.first { it is RegistrationState.Registered }
        }
    }

    private suspend fun awaitActiveDevice(): DeviceIdentifier {
        return withTimeout(options.deviceDiscoveryTimeoutMs) {
            deviceSelector.activeDevice(Wearables.devices)
                .filterNotNull()
                .first()
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

    private suspend fun emitCompatibilityWarningIfNeeded(deviceId: DeviceIdentifier) {
        val metadata = Wearables.devicesMetadata[deviceId]?.first() ?: return
        if (metadata.compatibility == com.meta.wearable.dat.core.types.DeviceCompatibility.DEVICE_UPDATE_REQUIRED) {
            emitWarn("Meta device '${metadata.name.ifEmpty { deviceId.toString() }}' requires a firmware update.")
        }
    }

    private fun startPhotoSession(options: CaptureOptions): StreamSession {
        val videoQuality = when {
            (options.targetWidth ?: 0) >= 1280 || (options.targetHeight ?: 0) >= 720 -> VideoQuality.HIGH
            (options.targetWidth ?: 0) >= 896 || (options.targetHeight ?: 0) >= 504 -> VideoQuality.MEDIUM
            else -> VideoQuality.LOW
        }
        return Wearables.startStreamSession(
            context = activity.applicationContext,
            deviceSelector = deviceSelector,
            streamConfiguration = StreamConfiguration(
                videoQuality = videoQuality,
                frameRate = 15,
            ),
        )
    }

    private suspend fun awaitStreaming(session: StreamSession, timeoutMs: Long) {
        var lastState = session.state.value
        val state = try {
            withTimeout(timeoutMs) {
                session.state.first { currentState ->
                    lastState = currentState
                    emitLog("Meta stream state: $currentState")
                    currentState == StreamSessionState.STREAMING ||
                        currentState == StreamSessionState.CLOSED
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw GlassesError.Transport(
                "Meta stream timed out before reaching STREAMING (lastState=$lastState)"
            )
        }
        if (state != StreamSessionState.STREAMING) {
            throw GlassesError.Transport("Meta stream closed before reaching STREAMING: $state")
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
