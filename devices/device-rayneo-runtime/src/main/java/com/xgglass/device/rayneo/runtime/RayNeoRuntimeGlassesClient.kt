package com.xgglass.device.rayneo.runtime

import android.annotation.SuppressLint
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.widget.ImageView
import android.widget.Toast
import com.xgglass.core.AudioCaptureHint
import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioSource
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayImage
import com.xgglass.core.DisplayImageOptions
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesEvent
import com.xgglass.core.GlassesModel
import com.xgglass.core.ImageScaleMode
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PcmFormat
import com.xgglass.core.PlayAudioOptions
import com.xgglass.core.PushVideoStreamSession
import com.xgglass.core.VideoFormat
import com.xgglass.core.VideoFrame
import com.xgglass.core.VideoFrameEncoding
import com.xgglass.core.VideoStreamOptions
import com.xgglass.core.VideoStreamSession
import com.xgglass.core.android.openAndroidMicrophone
import com.xgglass.core.android.playEncodedViaMediaPlayer
import com.xgglass.core.android.playPcmViaAudioTrack
import com.xgglass.core.android.rayNeoPcmBufferSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
) : BaseGlassesClient(
    initialCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        canDisplayImages = true,
        canRecordAudio = true,
        canStreamVideo = true,
        supportedVideoFormats = listOf(VideoFrameEncoding.JPEG),
        canPlayTts = false,
        canPlayAudioBytes = true,
        supportsTapEvents = false,
        supportsBatteryEvents = true,
        supportsStreamingTextUpdates = false,
    ),
    eventBufferOverflow = BufferOverflow.SUSPEND,
) {

    override val model: GlassesModel = GlassesModel.RAYNEO

    @Volatile private var activeMic: MicrophoneSession? = null
    @Volatile private var activeVideoSession: PushVideoStreamSession? = null
    @Volatile private var activeVideoFrameCache: RayNeoVideoFrameCaptureCache? = null
    @Volatile private var activeVideoStream: RayNeoVideoStreamHandle? = null
    @Volatile private var activePlayer: MediaPlayer? = null
    @Volatile private var batteryReceiver: BroadcastReceiver? = null
    @Volatile private var lastBatteryPercent: Int? = null
    private val videoStreamGate = RayNeoSingleVideoStreamGate()

    override val markConnectingOnConnect: Boolean = false

    override suspend fun doConnect() {
        registerBatteryEvents()
    }

    override suspend fun disconnect() {
        unregisterBatteryEvents()
        try { activeVideoSession?.stop() } catch (_: Exception) {}
        try { activeMic?.stop() } catch (_: Exception) {}
        activeMic = null
        try { activePlayer?.release() } catch (_: Exception) {}
        activePlayer = null
        _state.value = ConnectionState.Disconnected
    }

    private fun registerBatteryEvents() {
        unregisterBatteryEvents()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handleBatteryIntent(intent)
            }
        }
        batteryReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        handleBatteryIntent(sticky)
        if (lastBatteryPercent == null) {
            handleBatteryManager(context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
        }
    }

    private fun unregisterBatteryEvents() {
        val receiver = batteryReceiver ?: return
        batteryReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun handleBatteryIntent(intent: Intent?) {
        RayNeoRuntimeBatteryPolicy.percentFromIntent(intent)?.let(::emitBatteryIfChanged)
    }

    private fun handleBatteryManager(manager: BatteryManager?) {
        RayNeoRuntimeBatteryPolicy.percentFromManager(manager)?.let(::emitBatteryIfChanged)
    }

    private fun emitBatteryIfChanged(percent: Int) {
        val previous = lastBatteryPercent
        if (!RayNeoRuntimeBatteryPolicy.shouldEmit(previous, percent)) return
        lastBatteryPercent = percent
        emitEvent(GlassesEvent.BatteryLevel(percent))
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        activeVideoFrameCache?.let { cache ->
            return cache.capture(timeoutMs = options.timeoutMs, sourceModel = GlassesModel.RAYNEO)
        }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(GlassesError.Transport("RayNeo capture failed: ${e.message ?: e::class.java.simpleName}", e))
        }
    }

    override suspend fun startVideoStream(options: VideoStreamOptions): Result<VideoStreamSession> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        if (!hasCameraPermission()) return Result.failure(GlassesError.PermissionDenied)
        if (options.preferredEncoding != VideoFrameEncoding.JPEG) {
            return Result.failure(
                GlassesError.Unsupported(
                    "RayNeo runtime video stream: ${options.preferredEncoding} not supported; only JPEG is available"
                )
            )
        }
        if (!videoStreamGate.tryAcquire()) return Result.failure(GlassesError.Busy)

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = chooseCameraId(cameraManager)
                ?: throw GlassesError.Transport("No camera available")
            val targetWidth = (options.preferredWidth ?: 640).coerceIn(320, 3840)
            val targetHeight = (options.preferredHeight ?: 480).coerceIn(240, 2160)
            val (actualWidth, actualHeight) = chooseBestSize(cameraManager, cameraId, targetWidth, targetHeight)
            val fps = RayNeoVideoStreamPolicy.framesPerSecond(options.frameRateTier)
            val intervalMs = RayNeoVideoStreamPolicy.frameIntervalMs(options.frameRateTier)

            lateinit var session: PushVideoStreamSession
            val frameCache = RayNeoVideoFrameCaptureCache()
            session = PushVideoStreamSession(
                format = VideoFormat(
                    encoding = VideoFrameEncoding.JPEG,
                    width = actualWidth,
                    height = actualHeight,
                    framesPerSecond = fps,
                ),
                onStop = { clearActiveVideoStream(session = session) },
            )
            activeVideoFrameCache = frameCache
            activeVideoSession = session
            startRepeatingJpegVideoStream(
                cameraManager = cameraManager,
                cameraId = cameraId,
                width = actualWidth,
                height = actualHeight,
                framesPerSecond = fps,
                frameIntervalMs = intervalMs,
                session = session,
                frameCache = frameCache,
            )
            Result.success(session)
        } catch (e: CancellationException) {
            clearActiveVideoStream(session = null)
            throw e
        } catch (e: Exception) {
            clearActiveVideoStream(session = null)
            Result.failure(
                (e as? GlassesError)
                    ?: GlassesError.Transport("RayNeo startVideoStream failed: ${e.message ?: e::class.java.simpleName}", e)
            )
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

    override suspend fun displayImage(image: DisplayImage, options: DisplayImageOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        return try {
            displaySink.displayImage(context, image, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                (e as? GlassesError)
                    ?: GlassesError.Transport("RayNeo displayImage failed: ${e.message ?: e::class.java.simpleName}", e)
            )
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
        playPcmViaAudioTrack(
            data = data,
            format = pcm,
            usageAttributes = AudioAttributes.USAGE_MEDIA,
            interrupt = false,
            legacyStreamType = AudioManager.STREAM_MUSIC,
            unsupportedOpusMessage = "RayNeo playAudio: OPUS PCM not supported",
            uninitializedMessage = "RayNeo AudioTrack not initialized",
            bufferSizeInBytes = ::rayNeoPcmBufferSize,
            fallbackToLegacyStream = true,
        ).getOrThrow()
    }

    private suspend fun playEncoded(data: ByteArray) {
        playEncodedViaMediaPlayer(
            data = data,
            usageAttributes = AudioAttributes.USAGE_MEDIA,
            interrupt = false,
            tempFileFactory = { File.createTempFile("xgglass_audio_", ".tmp", context.cacheDir) },
            currentPlayer = { activePlayer },
            setCurrentPlayer = { activePlayer = it },
            errorMessage = { what, extra -> "RayNeo MediaPlayer error: what=$what extra=$extra" },
        ).getOrThrow()
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
            AudioEncoding.LC3 -> return Result.failure(GlassesError.Unsupported("RayNeo runtime microphone: LC3 not supported (use PCM + app-side encoder)"))
        }

        val sampleRate = options.preferredSampleRateHz ?: 16_000
        val channels = options.preferredChannelCount ?: 1
        when (channels) {
            1, 2 -> Unit
            else -> return Result.failure(GlassesError.Unsupported("RayNeo runtime microphone: channelCount=$channels"))
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val rayNeoAudioMode = options.audioHint.toRayNeoVendorMode()

            val session = openAndroidMicrophone(
                options = options,
                audioSource = MediaRecorder.AudioSource.MIC,
                sampleRateHz = sampleRate,
                channelCount = channels,
                unsupportedOpusMessage = "RayNeo runtime microphone: OPUS not supported (use PCM + app-side encoder)",
                unsupportedChannelMessage = { "RayNeo runtime microphone: channelCount=$it" },
                minBufferErrorMessage = { "AudioRecord.getMinBufferSize failed: $it" },
                uninitializedMessage = "AudioRecord not initialized",
                breakOnNegativeRead = false,
                beforeStart = {
                    // Apply vendor mode before starting.
                    try {
                        if (rayNeoAudioMode != null) am.setParameters("audio_source_record=$rayNeoAudioMode")
                    } catch (_: Exception) {
                        // ignore; still try default MIC path
                    }
                },
                beforeStop = {
                    // Best-effort: inform RayNeo audio HAL that we're done.
                    if (rayNeoAudioMode != null) am.setParameters("audio_source_record=off")
                },
                afterStop = { activeMic = null },
            ).getOrThrow()
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

    @SuppressLint("MissingPermission")
    private suspend fun startRepeatingJpegVideoStream(
        cameraManager: CameraManager,
        cameraId: String,
        width: Int,
        height: Int,
        framesPerSecond: Int,
        frameIntervalMs: Long,
        session: PushVideoStreamSession,
        frameCache: RayNeoVideoFrameCaptureCache,
    ): RayNeoVideoStreamHandle {
        val thread = HandlerThread("rayneo-video-stream").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.JPEG, 2)
        val stopped = AtomicBoolean(false)
        val sequence = AtomicLong(0)
        val nextFrameAtMs = AtomicLong(0)

        var device: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null

        fun cleanup() {
            if (!stopped.compareAndSet(false, true)) return
            try {
                captureSession?.stopRepeating()
            } catch (_: Exception) {}
            try {
                captureSession?.close()
            } catch (_: Exception) {}
            try {
                device?.close()
            } catch (_: Exception) {}
            try {
                reader.close()
            } catch (_: Exception) {}
            try {
                thread.quitSafely()
            } catch (_: Exception) {}
        }

        val handle = RayNeoVideoStreamHandle { cleanup() }

        return suspendCancellableCoroutine { cont ->
            fun fail(error: GlassesError) {
                if (stopped.get()) return
                if (cont.isActive) {
                    cont.resumeWithException(error)
                    cleanup()
                } else {
                    emitWarn("RayNeo video stream ended: ${error.message}")
                    session.emitEndOfStream(sequence.get())
                    cleanup()
                    clearActiveVideoStream(session)
                }
            }

            cont.invokeOnCancellation { cleanup() }

            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (stopped.get()) return@setOnImageAvailableListener
                    val now = System.currentTimeMillis()
                    if (nextFrameAtMs.get() > now) return@setOnImageAvailableListener
                    nextFrameAtMs.set(now + frameIntervalMs)

                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val frameSequence = sequence.getAndIncrement()
                    val frame = VideoFrame(
                        bytes = bytes,
                        format = VideoFormat(
                            encoding = VideoFrameEncoding.JPEG,
                            width = width,
                            height = height,
                            framesPerSecond = framesPerSecond,
                        ),
                        sequence = frameSequence,
                        timestampMs = now,
                        rotationDegrees = null,
                    )
                    if (session.emit(frame)) {
                        frameCache.update(frame)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(GlassesError.Transport("RayNeo video frame failed: ${e.message}", e))
                } finally {
                    image.close()
                }
            }, handler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (stopped.get()) {
                        camera.close()
                        return
                    }
                    device = camera
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (stopped.get()) {
                                    s.close()
                                    return
                                }
                                captureSession = s
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }
                                    s.setRepeatingRequest(req.build(), null, handler)
                                    if (cont.isActive) {
                                        activeVideoStream = handle
                                        cont.resume(handle)
                                    }
                                } catch (e: Exception) {
                                    fail(GlassesError.Transport("RayNeo video repeating request failed: ${e.message}", e))
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                fail(GlassesError.Transport("RayNeo video camera session configure failed"))
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    fail(GlassesError.Transport("RayNeo video camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    fail(GlassesError.Transport("RayNeo video camera error: $error"))
                }
            }, handler)
        }
    }

    private fun clearActiveVideoStream(session: PushVideoStreamSession?) {
        if (session != null && activeVideoSession !== session) return
        val stream = activeVideoStream
        activeVideoStream = null
        activeVideoSession = null
        activeVideoFrameCache = null
        try {
            stream?.stop()
        } catch (_: Exception) {}
        videoStreamGate.release()
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

private fun interface RayNeoVideoStreamHandle {
    fun stop()
}

fun interface RayNeoDisplaySink {
    suspend fun display(context: Context, text: String, options: DisplayOptions)

    suspend fun displayImage(context: Context, image: DisplayImage, options: DisplayImageOptions) {
        showImageToast(context, image, options, "RayNeo")
    }
}

internal class ToastDisplaySink : RayNeoDisplaySink {
    override suspend fun display(context: Context, text: String, options: DisplayOptions) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}

private fun AudioCaptureHint.toRayNeoVendorMode(): String? = when (this) {
    AudioCaptureHint.DEFAULT -> null
    AudioCaptureHint.VOICE_ASSISTANT -> "voiceassistant"
    AudioCaptureHint.TRANSLATION -> "translation"
    AudioCaptureHint.CAMCORDER -> "camcorder"
}

private suspend fun showImageToast(
    context: Context,
    image: DisplayImage,
    options: DisplayImageOptions,
    label: String,
) {
    val bitmap = withContext(Dispatchers.Default) {
        decodeAndroidDisplayBitmap(label, image)
    }
    withContext(Dispatchers.Main) {
        val imageView = ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            maxWidth = 720
            maxHeight = 720
            scaleType = options.scaleMode.toImageViewScaleType()
        }
        @Suppress("DEPRECATION")
        Toast(context).apply {
            duration = Toast.LENGTH_LONG
            view = imageView
            show()
        }
    }
}

private fun decodeAndroidDisplayBitmap(label: String, image: DisplayImage): Bitmap {
    if (image.bytes.isEmpty()) {
        throw GlassesError.Transport("$label displayImage failed: empty ${image.encoding} payload")
    }
    return BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        ?: throw GlassesError.Transport("$label displayImage failed: invalid ${image.encoding} bytes")
}

private fun ImageScaleMode.toImageViewScaleType(): ImageView.ScaleType = when (this) {
    ImageScaleMode.FIT -> ImageView.ScaleType.FIT_CENTER
    ImageScaleMode.FILL -> ImageView.ScaleType.CENTER_CROP
    ImageScaleMode.CENTER -> ImageView.ScaleType.CENTER
}
