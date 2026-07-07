package com.xgglass.device.inmo.runtime

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
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.widget.Toast
import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioSource
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesEvent
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PcmFormat
import com.xgglass.core.PlayAudioOptions
import com.xgglass.core.android.openAndroidMicrophone
import com.xgglass.core.android.playEncodedViaMediaPlayer
import com.xgglass.core.android.playPcmViaAudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * INMO Air3 on-glasses runtime client.
 *
 * This runs inside a foreground Activity on the Air3 glasses. Air3 is a standalone Android 14
 * device, so camera, microphone, display text, audio playback, and touchpad key events are handled
 * through stock Android APIs with no vendor SDK dependency.
 */
class InmoRuntimeGlassesClient(
    private val context: Context,
    private val displaySink: InmoDisplaySink = ToastDisplaySink(),
) : BaseGlassesClient(
    initialCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        canRecordAudio = true,
        // Keep RayNeo's honest policy: built-in TTS-engine presence is hardware-unverified.
        canPlayTts = false,
        canPlayAudioBytes = true,
        supportsTapEvents = true,
        supportsLongPressEvents = true,
        supportsStreamingTextUpdates = false,
    ),
    eventBufferOverflow = BufferOverflow.SUSPEND,
) {

    override val model: GlassesModel = GlassesModel.INMO

    @Volatile private var activeMic: MicrophoneSession? = null
    @Volatile private var activePlayer: MediaPlayer? = null

    override val markConnectingOnConnect: Boolean = false

    override suspend fun doConnect() = Unit

    /**
     * Forward host Activity key-down events here.
     *
     * ENTER (66) is emitted as [GlassesEvent.Tap]. INMO Air2 documentation maps
     * DPAD 19/20/21/22 to touchpad swipes and 289/290 to long-press gestures; the
     * long-press keycodes are emitted as [GlassesEvent.LongPress].
     */
    fun onHostKeyEvent(keyCode: Int): Boolean {
        return when (val action = InmoAir3RuntimePolicy.hostKeyAction(keyCode)) {
            is InmoAir3RuntimePolicy.HostKeyAction.Tap -> {
                emitEvent(GlassesEvent.Tap(action.count))
                true
            }
            InmoAir3RuntimePolicy.HostKeyAction.LongPress -> {
                emitEvent(GlassesEvent.LongPress)
                true
            }
            InmoAir3RuntimePolicy.HostKeyAction.Unhandled -> false
        }
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

            val capture = withTimeoutOrNull(timeoutMs) {
                captureJpegOnce(width, height)
            } ?: return Result.failure(GlassesError.Timeout("capturePhoto"))

            Result.success(
                CapturedImage(
                    jpegBytes = capture.bytes,
                    width = capture.width,
                    height = capture.height,
                    rotationDegrees = capture.rotationDegrees,
                    sourceModel = GlassesModel.INMO,
                )
            )
        } catch (e: Exception) {
            Result.failure(GlassesError.Transport("INMO capture failed: ${e.message ?: e::class.java.simpleName}", e))
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        return try {
            displaySink.display(context, text, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(GlassesError.Transport("INMO display failed: ${e.message ?: e::class.java.simpleName}", e))
        }
    }

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)

        return when (source) {
            is AudioSource.Tts -> Result.failure(
                GlassesError.Unsupported(
                    "INMO Air3 on-device TTS engine presence is not hardware-verified. " +
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
                    ?: GlassesError.Transport("INMO playAudio failed: ${e.message}", e)
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
            unsupportedOpusMessage = "INMO playAudio: OPUS PCM not supported",
            uninitializedMessage = "INMO AudioTrack not initialized",
            bufferSizeInBytes = ::inmoPcmBufferSize,
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
            errorMessage = { what, extra -> "INMO MediaPlayer error: what=$what extra=$extra" },
        ).getOrThrow()
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        if (!hasRecordAudioPermission()) return Result.failure(GlassesError.PermissionDenied)
        if (activeMic != null) return Result.failure(GlassesError.Busy)

        when (options.preferredEncoding) {
            AudioEncoding.PCM_S16_LE,
            AudioEncoding.PCM_S8 -> Unit
            AudioEncoding.OPUS -> return Result.failure(
                GlassesError.Unsupported("INMO runtime microphone: OPUS not supported (use PCM + app-side encoder)")
            )
            AudioEncoding.LC3 -> return Result.failure(
                GlassesError.Unsupported("INMO runtime microphone: LC3 not supported (use PCM + app-side encoder)")
            )
        }

        val sampleRate = options.preferredSampleRateHz ?: 16_000
        val channels = options.preferredChannelCount ?: 1
        when (channels) {
            1, 2 -> Unit
            else -> return Result.failure(GlassesError.Unsupported("INMO runtime microphone: channelCount=$channels"))
        }
        return try {
            val session = openAndroidMicrophone(
                options = options,
                audioSource = MediaRecorder.AudioSource.MIC,
                sampleRateHz = sampleRate,
                channelCount = channels,
                unsupportedOpusMessage = "INMO runtime microphone: OPUS not supported (use PCM + app-side encoder)",
                unsupportedChannelMessage = { "INMO runtime microphone: channelCount=$it" },
                minBufferErrorMessage = { "INMO AudioRecord.getMinBufferSize failed: $it" },
                uninitializedMessage = "INMO AudioRecord not initialized",
                breakOnNegativeRead = false,
                afterStop = { activeMic = null },
            ).getOrThrow()
            activeMic = session
            Result.success(session)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("INMO startMicrophone failed: ${e.message}", e))
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
                val target = (max / 2).coerceAtLeast(1)
                am.setStreamVolume(stream, target, 0)
            }
        } catch (_: Exception) {
            // ignore (may fail without MODIFY_AUDIO_SETTINGS on some ROMs)
        }
    }

    private suspend fun captureJpegOnce(width: Int, height: Int): InmoJpegCapture {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = chooseCameraId(cameraManager)
            ?: throw GlassesError.Transport("No camera available")

        val (actualWidth, actualHeight) = chooseBestSize(cameraManager, cameraId, width, height)
        val rotationDegrees = sensorRotationDegrees(cameraManager, cameraId)

        val thread = HandlerThread("inmo-camera").apply { start() }
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
                    cont.resume(
                        InmoJpegCapture(
                            bytes = bytes,
                            width = actualWidth,
                            height = actualHeight,
                            rotationDegrees = rotationDegrees,
                        )
                    )
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

        for (id in ids) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return ids.firstOrNull()
    }

    private fun sensorRotationDegrees(cameraManager: CameraManager, cameraId: String): Int? {
        val raw = cameraManager
            .getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION)
        return InmoAir3RuntimePolicy.normalizeRotationDegrees(raw)
    }

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
            return 1920 to 1080
        }

        val targetPixels = targetW.toLong() * targetH
        val best = sizes.minByOrNull {
            val px = it.width.toLong() * it.height
            kotlin.math.abs(px - targetPixels)
        }!!

        return best.width to best.height
    }

    private data class InmoJpegCapture(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int?,
    )
}

fun interface InmoDisplaySink {
    suspend fun display(context: Context, text: String, options: DisplayOptions)
}

internal class ToastDisplaySink : InmoDisplaySink {
    override suspend fun display(context: Context, text: String, options: DisplayOptions) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}

private fun inmoPcmBufferSize(minBuffer: Int): Int {
    return max(minBuffer.coerceAtLeast(0), 8 * 1024)
}
