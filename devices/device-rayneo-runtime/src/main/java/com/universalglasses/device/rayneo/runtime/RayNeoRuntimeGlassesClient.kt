package com.universalglasses.device.rayneo.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.widget.Toast
import com.universalglasses.core.AudioChunk
import com.universalglasses.core.AudioEncoding
import com.universalglasses.core.AudioFormat
import com.universalglasses.core.AudioSource
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.DeviceCapabilities
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.core.MicrophoneOptions
import com.universalglasses.core.MicrophoneSession
import com.universalglasses.core.PcmFormat
import com.universalglasses.core.PlayAudioOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * RayNeo (on-glasses) client.
 *
 * This runs inside the RayNeo glasses app process.
 * - `capturePhoto()` uses Camera2 to capture a single JPEG (no preview UI).
 * - `display()` uses a pluggable sink; default is a Toast.
 *
 * Note: This module is intentionally vendor-SDK-free. If you want to integrate with RayNeo Mercury
 * SDK UI components, implement a custom [RayNeoDisplaySink] and/or your own capture pipeline.
 */
class RayNeoRuntimeGlassesClient(
    private val context: Context,
    private val displaySink: RayNeoDisplaySink = ToastDisplaySink(),
) : GlassesClient {

    override val model: GlassesModel = GlassesModel.RAYNEO

    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        canRecordAudio = true,
        canPlayTts = false,
        canPlayAudioBytes = true,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = false,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 64)
    override val events: Flow<GlassesEvent> = _events

    @Volatile private var activeMic: MicrophoneSession? = null
    @Volatile private var activePlayer: MediaPlayer? = null

    override suspend fun connect(): Result<Unit> {
        _state.value = ConnectionState.Connected
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        try { activeMic?.stop() } catch (_: Exception) {}
        activeMic = null
        try { activePlayer?.release() } catch (_: Exception) {}
        activePlayer = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        if (!hasCameraPermission()) return Result.failure(GlassesError.PermissionDenied)

        return try {
            val timeoutMs = options.timeoutMs
            val width = (options.targetWidth ?: 1920).coerceIn(320, 3840)
            val height = (options.targetHeight ?: 1080).coerceIn(240, 2160)

            val jpeg = withTimeoutOrNull(timeoutMs) {
                captureJpegOnce(width, height)
            } ?: return Result.failure(GlassesError.Timeout("capturePhoto"))

            return Result.success(
                CapturedImage(
                    jpegBytes = jpeg,
                    width = width,
                    height = height,
                    rotationDegrees = null,
                    sourceModel = GlassesModel.RAYNEO,
                )
            )
        } catch (e: Exception) {
            Result.failure(GlassesError.Transport("RayNeo capture failed: ${e.message ?: e::class.java.simpleName}", e))
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        return try {
            displaySink.display(context, text, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(GlassesError.Transport("RayNeo display failed: ${e.message ?: e::class.java.simpleName}", e))
        }
    }

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)

        return when (source) {
            is AudioSource.Tts -> Result.failure(
                GlassesError.Unsupported(
                    "RayNeo does not have a built-in TTS engine. " +
                        "Convert text to audio bytes externally and use AudioSource.RawBytes instead."
                )
            )
            is AudioSource.RawBytes -> playRawBytes(source, options)
        }
    }

    private suspend fun playRawBytes(source: AudioSource.RawBytes, options: PlayAudioOptions): Result<Unit> {
        val data = source.data
        if (data.isEmpty()) return Result.success(Unit)

        return try {
            if (options.interrupt) {
                try { activePlayer?.release() } catch (_: Exception) {}
                activePlayer = null
            }
            ensureMusicVolumeNotZero()

            val pcm = source.pcmFormat
            if (pcm != null) {
                playPcm(data, pcm)
            } else {
                playEncoded(data)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                (e as? GlassesError)
                    ?: GlassesError.Transport("RayNeo playAudio failed: ${e.message}", e)
            )
        }
    }

    private suspend fun playPcm(data: ByteArray, pcm: PcmFormat) {
        val sampleRate = pcm.sampleRateHz
        val channels = pcm.channelCount
        val channelMask = if (channels <= 1) {
            android.media.AudioFormat.CHANNEL_OUT_MONO
        } else {
            android.media.AudioFormat.CHANNEL_OUT_STEREO
        }
        val enc = when (pcm.encoding) {
            AudioEncoding.PCM_S16_LE -> android.media.AudioFormat.ENCODING_PCM_16BIT
            AudioEncoding.PCM_S8 -> android.media.AudioFormat.ENCODING_PCM_8BIT
            AudioEncoding.OPUS -> throw GlassesError.Unsupported("RayNeo playAudio: OPUS PCM not supported")
        }

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, enc).coerceAtLeast(0)
        val bufSize = max(minBuf, 8 * 1024)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(enc)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufSize)
                .build()
        }.getOrElse {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate, channelMask, enc,
                bufSize, AudioTrack.MODE_STREAM,
            )
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw GlassesError.Transport("RayNeo AudioTrack not initialized")
        }

        try {
            track.play()
            var written = 0
            while (written < data.size) {
                val n = track.write(data, written, minOf(4096, data.size - written))
                if (n <= 0) break
                written += n
            }
            val bytesPerSecond = sampleRate.toLong() * channels * (if (enc == android.media.AudioFormat.ENCODING_PCM_16BIT) 2 else 1)
            if (bytesPerSecond > 0) {
                delay(data.size * 1000L / bytesPerSecond + 200L)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private suspend fun playEncoded(data: ByteArray) {
        val tmpFile = File.createTempFile("ug_audio_", ".tmp", context.cacheDir)
        try {
            tmpFile.writeBytes(data)
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val mp = MediaPlayer()
                    activePlayer = mp
                    mp.setDataSource(tmpFile.absolutePath)
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setOnCompletionListener {
                        mp.release()
                        activePlayer = null
                        tmpFile.delete()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        mp.release()
                        activePlayer = null
                        tmpFile.delete()
                        if (cont.isActive) cont.resumeWithException(
                            GlassesError.Transport("RayNeo MediaPlayer error: what=$what extra=$extra")
                        )
                        true
                    }
                    mp.prepare()
                    mp.start()
                    cont.invokeOnCancellation {
                        mp.release()
                        activePlayer = null
                        tmpFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        if (!hasRecordAudioPermission()) return Result.failure(GlassesError.PermissionDenied)
        if (activeMic != null) return Result.failure(GlassesError.Busy)

        // RayNeo runtime implementation provides raw PCM only. (Apps can encode to AAC/Opus if desired.)
        val encoding = when (options.preferredEncoding) {
            AudioEncoding.PCM_S16_LE -> AudioEncoding.PCM_S16_LE
            AudioEncoding.PCM_S8 -> AudioEncoding.PCM_S8
            AudioEncoding.OPUS -> return Result.failure(GlassesError.Unsupported("RayNeo runtime microphone: OPUS not supported (use PCM + app-side encoder)"))
        }

        val sampleRate = options.preferredSampleRateHz ?: 16_000
        val channels = options.preferredChannelCount ?: 1
        val channelConfig = when (channels) {
            1 -> android.media.AudioFormat.CHANNEL_IN_MONO
            2 -> android.media.AudioFormat.CHANNEL_IN_STEREO
            else -> return Result.failure(GlassesError.Unsupported("RayNeo runtime microphone: channelCount=$channels"))
        }
        val audioFormat = when (encoding) {
            AudioEncoding.PCM_S16_LE -> android.media.AudioFormat.ENCODING_PCM_16BIT
            AudioEncoding.PCM_S8 -> android.media.AudioFormat.ENCODING_PCM_8BIT
            AudioEncoding.OPUS -> android.media.AudioFormat.ENCODING_PCM_16BIT // unreachable
        }

        return try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf <= 0) {
                return Result.failure(GlassesError.Transport("AudioRecord.getMinBufferSize failed: $minBuf"))
            }

            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val vendorMode = options.vendorMode?.trim()?.takeIf { it.isNotEmpty() }

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBuf * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                try {
                    record.release()
                } catch (_: Exception) {}
                return Result.failure(GlassesError.Transport("AudioRecord not initialized"))
            }

            val fmt = AudioFormat(
                encoding = encoding,
                sampleRateHz = sampleRate,
                channelCount = channels,
            )

            val shared = MutableSharedFlow<AudioChunk>(
                extraBufferCapacity = 64,
            )
            val running = AtomicBoolean(true)
            val seq = AtomicLong(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val session = object : MicrophoneSession {
                override val format: AudioFormat = fmt
                override val audio: Flow<AudioChunk> = shared

                override suspend fun stop() {
                    if (!running.compareAndSet(true, false)) return
                    try {
                        // Best-effort: inform RayNeo audio HAL that we're done.
                        if (vendorMode != null) am.setParameters("audio_source_record=off")
                    } catch (_: Exception) {}

                    try {
                        record.stop()
                    } catch (_: Exception) {}
                    try {
                        record.release()
                    } catch (_: Exception) {}

                    scope.cancel()
                    activeMic = null
                    // Emit EOS marker (best-effort).
                    shared.tryEmit(
                        AudioChunk(
                            bytes = ByteArray(0),
                            format = fmt,
                            sequence = seq.incrementAndGet(),
                            endOfStream = true,
                        )
                    )
                }
            }

            // Apply vendor mode before starting.
            try {
                if (vendorMode != null) am.setParameters("audio_source_record=$vendorMode")
            } catch (_: Exception) {
                // ignore; still try default MIC path
            }

            record.startRecording()
            scope.launch {
                val buf = ByteArray(minBuf)
                while (running.get()) {
                    val n = try {
                        record.read(buf, 0, buf.size)
                    } catch (_: Exception) {
                        break
                    }
                    if (n > 0) {
                        val out = buf.copyOfRange(0, n)
                        shared.tryEmit(
                            AudioChunk(
                                bytes = out,
                                format = fmt,
                                sequence = seq.incrementAndGet(),
                            )
                        )
                    }
                }
            }

            activeMic = session
            Result.success(session)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("RayNeo startMicrophone failed: ${e.message}", e))
        }
    }

    private fun hasCameraPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecordAudioPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureMusicVolumeNotZero() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = AudioManager.STREAM_MUSIC
            val cur = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            if (cur <= 0 && max > 0) {
                // Avoid blasting; set to a reasonable audible level.
                val target = (max / 2).coerceAtLeast(1)
                am.setStreamVolume(stream, target, 0)
            }
        } catch (_: Exception) {
            // ignore (may fail without MODIFY_AUDIO_SETTINGS on some ROMs)
        }
    }

    private suspend fun captureJpegOnce(width: Int, height: Int): ByteArray {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = chooseCameraId(cameraManager)
            ?: throw GlassesError.Transport("No camera available")

        // Camera2 requires the output size to be one of the supported sizes.
        // Pick the supported JPEG size closest to the requested resolution.
        val (actualWidth, actualHeight) = chooseBestSize(cameraManager, cameraId, width, height)

        val thread = HandlerThread("rayneo-camera").apply { start() }
        val handler = Handler(thread.looper)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null

        reader = ImageReader.newInstance(actualWidth, actualHeight, android.graphics.ImageFormat.JPEG, 2)

        return suspendCancellableCoroutine { cont ->
            fun cleanup() {
                try {
                    session?.close()
                } catch (_: Exception) {}
                try {
                    device?.close()
                } catch (_: Exception) {}
                try {
                    reader?.close()
                } catch (_: Exception) {}
                try {
                    thread.quitSafely()
                } catch (_: Exception) {}
            }

            cont.invokeOnCancellation { cleanup() }

            reader.setOnImageAvailableListener({ r ->
                if (!cont.isActive) return@setOnImageAvailableListener
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    cont.resume(bytes)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                } finally {
                    image.close()
                    cleanup()
                }
            }, handler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }
                                    s.capture(req.build(), null, handler)
                                } catch (e: Exception) {
                                    if (cont.isActive) cont.resumeWithException(e)
                                    cleanup()
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                if (cont.isActive) cont.resumeWithException(GlassesError.Transport("Camera session configure failed"))
                                cleanup()
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (cont.isActive) cont.resumeWithException(GlassesError.Transport("Camera disconnected"))
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (cont.isActive) cont.resumeWithException(GlassesError.Transport("Camera error: $error"))
                    cleanup()
                }
            }, handler)
        }
    }

    private fun chooseCameraId(cameraManager: CameraManager): String? {
        val ids = cameraManager.cameraIdList
        if (ids.isEmpty()) return null

        // Prefer a back-facing camera if available.
        for (id in ids) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return ids.firstOrNull()
    }

    /**
     * Pick the supported JPEG output size closest to the requested [targetW]×[targetH].
     *
     * Camera2 requires ImageReader dimensions to match one of the sizes listed in
     * [CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]. Using an unsupported
     * size will cause `createCaptureSession` → `onConfigureFailed`.
     */
    private fun chooseBestSize(
        cameraManager: CameraManager,
        cameraId: String,
        targetW: Int,
        targetH: Int,
    ): Pair<Int, Int> {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)

        if (sizes.isNullOrEmpty()) {
            // Fallback: try a safe default that most cameras support.
            return 1920 to 1080
        }

        val targetPixels = targetW.toLong() * targetH
        // Pick the size whose total pixel count is closest to the target.
        val best = sizes.minByOrNull {
            val px = it.width.toLong() * it.height
            kotlin.math.abs(px - targetPixels)
        }!!

        return best.width to best.height
    }
}

fun interface RayNeoDisplaySink {
    suspend fun display(context: Context, text: String, options: DisplayOptions)
}

class ToastDisplaySink : RayNeoDisplaySink {
    override suspend fun display(context: Context, text: String, options: DisplayOptions) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
