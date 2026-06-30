package com.universalglasses.device.meta

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
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
import com.universalglasses.core.AudioChunk
import com.universalglasses.core.AudioEncoding
import com.universalglasses.core.AudioFormat
import com.universalglasses.core.AudioSource
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.DeviceCapabilities
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.ExternalActivityBridge
import com.universalglasses.core.ExternalActivityResult
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.core.MicrophoneOptions
import com.universalglasses.core.MicrophoneSession
import com.universalglasses.core.PcmFormat
import com.universalglasses.core.PlayAudioOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) : GlassesClient {

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

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<GlassesEvent> = _events

    @Volatile private var activeDeviceId: DeviceIdentifier? = null
    @Volatile private var activeMic: MicrophoneSession? = null
    @Volatile private var activePlayer: MediaPlayer? = null
    private val connectMutex = Mutex()

    private val audioRouteLock = Any()
    private var audioRouteRefCount = 0
    private var previousAudioMode: Int? = null

    override suspend fun connect(): Result<Unit> = connectMutex.withLock {
        if (_state.value is ConnectionState.Connected || _state.value is ConnectionState.Connecting) {
            return Result.success(Unit)
        }

        _state.value = ConnectionState.Connecting
        return try {
            ensureWearablesInitialized()
            ensureRegistered()
            val deviceId = awaitActiveDevice()
            activeDeviceId = deviceId
            emitCompatibilityWarningIfNeeded(deviceId)
            emitLog("Meta: connected to device $deviceId")
            _state.value = ConnectionState.Connected
            Result.success(Unit)
        } catch (ce: CancellationException) {
            _state.value = ConnectionState.Disconnected
            Result.failure(ce)
        } catch (e: Exception) {
            val err = (e as? GlassesError) ?: GlassesError.Transport("Meta connect failed: ${e.message}", e)
            _state.value = ConnectionState.Error(err)
            Result.failure(err)
        }
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
                            quality = (options.quality ?: 92).coerceIn(1, 100),
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
            val channelConfig = AndroidAudioFormat.CHANNEL_IN_MONO
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                releaseAudioRoute()
                return Result.failure(GlassesError.Transport("Meta AudioRecord.getMinBufferSize failed: $minBuffer"))
            }

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                releaseAudioRoute()
                record.release()
                return Result.failure(GlassesError.Transport("Meta AudioRecord not initialized"))
            }

            val format = AudioFormat(
                encoding = AudioEncoding.PCM_S16_LE,
                sampleRateHz = sampleRate,
                channelCount = 1,
            )
            val shared = MutableSharedFlow<AudioChunk>(
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val running = AtomicBoolean(true)
            val seq = AtomicLong(0)
            val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val session = object : MicrophoneSession {
                override val format: AudioFormat = format
                override val audio: Flow<AudioChunk> = shared

                override suspend fun stop() {
                    if (!running.compareAndSet(true, false)) return
                    try { record.stop() } catch (_: Exception) {}
                    try { record.release() } catch (_: Exception) {}
                    readScope.cancel()
                    activeMic = null
                    releaseAudioRoute()
                    shared.tryEmit(
                        AudioChunk(
                            bytes = ByteArray(0),
                            format = format,
                            sequence = seq.incrementAndGet(),
                            endOfStream = true,
                        )
                    )
                }
            }

            record.startRecording()
            readScope.launch {
                val buffer = ByteArray(minBuffer)
                while (running.get()) {
                    val read = try {
                        record.read(buffer, 0, buffer.size)
                    } catch (_: Exception) {
                        break
                    }
                    if (read > 0) {
                        shared.tryEmit(
                            AudioChunk(
                                bytes = buffer.copyOfRange(0, read),
                                format = format,
                                sequence = seq.incrementAndGet(),
                            )
                        )
                    } else if (read < 0) {
                        break
                    }
                }
            }

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
        val channelMask = if (pcm.channelCount <= 1) {
            AndroidAudioFormat.CHANNEL_OUT_MONO
        } else {
            AndroidAudioFormat.CHANNEL_OUT_STEREO
        }
        val encoding = when (pcm.encoding) {
            AudioEncoding.PCM_S16_LE -> AndroidAudioFormat.ENCODING_PCM_16BIT
            AudioEncoding.PCM_S8 -> AndroidAudioFormat.ENCODING_PCM_8BIT
            AudioEncoding.OPUS -> throw GlassesError.Unsupported("Meta playAudio does not support OPUS PCM playback.")
        }

        val minBuffer = AudioTrack.getMinBufferSize(pcm.sampleRateHz, channelMask, encoding).coerceAtLeast(1024)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setSampleRate(pcm.sampleRateHz)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw GlassesError.Transport("Meta AudioTrack not initialized")
        }

        try {
            if (preferredOutput != null) {
                runCatching { track.preferredDevice = preferredOutput }
            }
            track.play()
            var written = 0
            while (written < data.size) {
                val n = track.write(data, written, minOf(4096, data.size - written))
                if (n <= 0) break
                written += n
            }
            val bytesPerSecond = pcm.sampleRateHz.toLong() * pcm.channelCount * if (encoding == AndroidAudioFormat.ENCODING_PCM_16BIT) 2 else 1
            if (bytesPerSecond > 0) {
                delay(data.size * 1000L / bytesPerSecond + 200L)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private suspend fun playEncoded(data: ByteArray, preferredOutput: AudioDeviceInfo?) {
        val tmpFile = File(activity.cacheDir, "meta_audio_${System.currentTimeMillis()}.tmp")
        try {
            tmpFile.writeBytes(data)
            withContext(Dispatchers.Main) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    val player = MediaPlayer()
                    activePlayer = player
                    player.setDataSource(tmpFile.absolutePath)
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                            .build()
                    )
                    if (preferredOutput != null) {
                        runCatching { player.preferredDevice = preferredOutput }
                    }
                    player.setOnCompletionListener {
                        player.release()
                        activePlayer = null
                        tmpFile.delete()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    player.setOnErrorListener { _, what, extra ->
                        player.release()
                        activePlayer = null
                        tmpFile.delete()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                GlassesError.Transport("Meta MediaPlayer error: what=$what extra=$extra")
                            )
                        }
                        true
                    }
                    player.prepare()
                    player.start()
                    cont.invokeOnCancellation {
                        player.release()
                        activePlayer = null
                        tmpFile.delete()
                    }
                }
            }
        } finally {
            tmpFile.delete()
        }
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

    private fun emitLog(message: String) {
        _events.tryEmit(GlassesEvent.Log(message))
    }

    private fun emitWarn(message: String) {
        _events.tryEmit(GlassesEvent.Warning(message))
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
