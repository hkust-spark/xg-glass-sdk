package com.xgglass.device.sim

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
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
import com.xgglass.core.GlassesEvent
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Simulator implementation of [GlassesClient] that simulates glasses functionality
 * without physical hardware. Designed to run on an Android Emulator.
 *
 * Behavior:
 * - connect()/disconnect(): no physical glasses, so it's effectively a no-op lifecycle.
 * - capturePhoto(): uses Android camera (on Android Emulator this can be backed by host webcam),
 *   OR reads frames from a local video file when [videoPath] is set (see --local_video / --url).
 * - display(): shows text in the host UI via a sink provided by the host app.
 *
 * When [videoPath] is non-null the video is played back at its original frame rate in an
 * infinite loop. Each call to [capturePhoto] returns the frame that the virtual playback
 * head is currently at.
 */
class SimulatorGlassesClient(
    private val activity: AppCompatActivity,
    private val displaySink: ((String) -> Unit)? = null,
    private val imageDisplaySink: ((Bitmap, DisplayImageOptions) -> Unit)? = null,
    private val videoPath: String? = null,
) : BaseGlassesClient(
    initialCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        canDisplayImages = true,
        canRecordAudio = true,
        canPlayTts = true,
        canPlayAudioBytes = true,
        supportsTapEvents = true,
        supportsLongPressEvents = true,
        supportsBatteryEvents = true,
        supportsStreamingTextUpdates = false,
    ),
) {

    override val model: GlassesModel = GlassesModel.SIMULATOR

    /** Testing hook that emits a synthetic [GlassesEvent.Tap] without glasses hardware. */
    fun simulateTap(count: Int) {
        require(count > 0) { "Tap count must be positive." }
        emitEvent(GlassesEvent.Tap(count))
    }

    /** Testing hook that emits a synthetic [GlassesEvent.LongPress] without glasses hardware. */
    fun simulateLongPress() {
        emitEvent(GlassesEvent.LongPress)
    }

    /** Testing hook that emits a synthetic [GlassesEvent.BatteryLevel] without glasses hardware. */
    fun simulateBatteryLevel(percent: Int) {
        require(percent in 0..100) { "Battery percent must be in 0..100." }
        emitEvent(GlassesEvent.BatteryLevel(percent))
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    @Volatile private var activeMic: MicrophoneSession? = null
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var activePlayer: MediaPlayer? = null

    // ── Video-file playback state (used when videoPath != null) ────────
    /** The retriever used to extract frames from the video file. */
    private var videoRetriever: MediaMetadataRetriever? = null
    /** App-private copy of the video (copied from /data/local/tmp). */
    private var videoCacheFile: File? = null
    /** Total duration of the video in milliseconds. */
    private var videoDurationMs: Long = 0L
    /** System.currentTimeMillis() when the virtual playback started. */
    private var videoStartTimeMs: Long = 0L

    private val useVideoSource: Boolean get() = videoPath != null

    override suspend fun doConnect() {
        emitLog("Simulator: connect (no-op)")

        if (useVideoSource) {
            initVideoSource()
        } else {
            ensureCameraUseCase(jpegQuality = 90)
        }
    }

    override fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError) ?: GlassesError.Transport("Simulator connect failed: ${error.message}", error)
    }

    override suspend fun disconnect() {
        emitLog("Simulator: disconnect (no-op)")
        try { activeMic?.stop() } catch (_: Exception) {}
        activeMic = null
        try { activePlayer?.release() } catch (_: Exception) {}
        activePlayer = null
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null

        // Release video retriever if active.
        try { videoRetriever?.release() } catch (_: Exception) {}
        videoRetriever = null
        try { videoCacheFile?.delete() } catch (_: Exception) {}
        videoCacheFile = null

        withContext(Dispatchers.Main) {
            cameraProvider?.unbindAll()
            imageCapture = null
        }
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        // ── Video-file mode: extract the frame at the current virtual playback position ──
        if (useVideoSource) {
            return capturePhotoFromVideo(options)
        }

        // ── Camera mode (original behavior) ──
        if (!hasCameraPermission()) {
            emitWarn("Simulator: CAMERA permission missing")
            return Result.failure(GlassesError.PermissionDenied)
        }

        return try {
            val quality = options.photoQuality.toSimulatorJpegQuality()
            ensureCameraUseCase(jpegQuality = quality)
            val ic = imageCapture ?: return Result.failure(GlassesError.Busy)

            val cacheFile = File(activity.cacheDir, "sim_capture_${System.currentTimeMillis()}.jpg")

            val r = withTimeout(options.timeoutMs) {
                suspendCancellableCoroutine<Result<CapturedImage>> { cont ->
                    val out = ImageCapture.OutputFileOptions.Builder(cacheFile).build()
                    val executor = ContextCompat.getMainExecutor(activity)

                    ic.takePicture(
                        out,
                        executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                try {
                                    val bytes = cacheFile.readBytes()
                                    val (w, h) = decodeJpegSize(bytes)
                                    cacheFile.delete()
                                    cont.resume(
                                        Result.success(
                                            CapturedImage(
                                                jpegBytes = bytes,
                                                width = w,
                                                height = h,
                                                rotationDegrees = null,
                                                sourceModel = model,
                                            )
                                        )
                                    )
                                } catch (e: Exception) {
                                    cacheFile.delete()
                                    cont.resume(Result.failure(e))
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                cacheFile.delete()
                                cont.resume(Result.failure(exception))
                            }
                        }
                    )
                }
            }

            emitLog("Simulator: capturePhoto => ${r.isSuccess}")
            r
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        return try {
            withContext(Dispatchers.Main) {
                val sink = displaySink
                if (sink != null) {
                    sink.invoke(text)
                } else {
                    emitWarn("Simulator: display ignored (no displaySink provided)")
                }
            }
            emitLog("Simulator: display => ok")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun displayImage(image: DisplayImage, options: DisplayImageOptions): Result<Unit> {
        return try {
            val bitmap = withContext(Dispatchers.Default) {
                decodeDisplayBitmap(image)
            }
            withContext(Dispatchers.Main) {
                val sink = imageDisplaySink
                if (sink != null) {
                    sink.invoke(bitmap, options)
                } else {
                    emitWarn("Simulator: displayImage ignored (no imageDisplaySink provided)")
                }
            }
            emitLog("Simulator: displayImage => ok (${bitmap.width}x${bitmap.height}, ${image.encoding})")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                (e as? GlassesError)
                    ?: GlassesError.Transport("Simulator displayImage failed: ${e.message}", e)
            )
        }
    }

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> {
        return when (source) {
            is AudioSource.Tts -> playTts(source, options)
            is AudioSource.RawBytes -> playRawBytes(source, options)
        }
    }

    private suspend fun playTts(source: AudioSource.Tts, options: PlayAudioOptions): Result<Unit> {
        val content = source.text.trim()
        if (content.isEmpty()) return Result.success(Unit)

        return try {
            val engine = ensureTts()
            val utteranceId = "sim-${System.currentTimeMillis()}"
            val done = CompletableDeferred<Unit>()

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                @Deprecated("Deprecated in API 21")
                override fun onError(id: String?) {
                    if (id == utteranceId) done.completeExceptionally(
                        GlassesError.Transport("Simulator TTS error")
                    )
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) done.completeExceptionally(
                        GlassesError.Transport("Simulator TTS error: $errorCode")
                    )
                }
                override fun onDone(id: String?) {
                    if (id == utteranceId) done.complete(Unit)
                }
            })

            withContext(Dispatchers.Main) {
                val rate = options.speechRate
                if (rate != null) engine.setSpeechRate(rate.coerceIn(0.1f, 4.0f))
                val q = if (options.interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val r = engine.speak(content, q, null, utteranceId)
                if (r != TextToSpeech.SUCCESS) {
                    throw GlassesError.Transport("Simulator TTS speak() returned $r")
                }
            }

            withTimeoutOrNull(30_000) { done.await() }
            emitLog("Simulator: playAudio(TTS) => done")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                (e as? GlassesError) ?: GlassesError.Transport("Simulator playAudio(TTS) failed: ${e.message}", e)
            )
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

            val pcm = source.pcmFormat
            if (pcm != null) {
                playPcm(data, pcm)
            } else {
                playEncoded(data)
            }
            emitLog("Simulator: playAudio(RawBytes) => done (${data.size} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                (e as? GlassesError) ?: GlassesError.Transport("Simulator playAudio(RawBytes) failed: ${e.message}", e)
            )
        }
    }

    private suspend fun playPcm(data: ByteArray, pcm: PcmFormat) {
        playPcmViaAudioTrack(
            data = data,
            format = pcm,
            usageAttributes = AudioAttributes.USAGE_MEDIA,
            interrupt = false,
            unsupportedOpusMessage = "Simulator playAudio: OPUS not supported",
            uninitializedMessage = "Simulator AudioTrack not initialized",
        ).getOrThrow()
    }

    private suspend fun playEncoded(data: ByteArray) {
        playEncodedViaMediaPlayer(
            data = data,
            usageAttributes = AudioAttributes.USAGE_MEDIA,
            interrupt = false,
            tempFileFactory = { File(activity.cacheDir, "sim_audio_${System.currentTimeMillis()}.tmp") },
            currentPlayer = { activePlayer },
            setCurrentPlayer = { activePlayer = it },
            errorMessage = { what, extra -> "Simulator MediaPlayer error: what=$what extra=$extra" },
        ).getOrThrow()
    }

    private suspend fun ensureTts(): TextToSpeech {
        val existing = tts
        if (existing != null) return existing

        return withContext(Dispatchers.Main) {
            val again = tts
            if (again != null) return@withContext again

            suspendCancellableCoroutine { cont ->
                var engine: TextToSpeech? = null
                engine = TextToSpeech(activity) { status ->
                    val inst = engine ?: return@TextToSpeech
                    if (!cont.isActive) return@TextToSpeech
                    if (status != TextToSpeech.SUCCESS) {
                        try { inst.shutdown() } catch (_: Exception) {}
                        cont.resumeWithException(GlassesError.Transport("TextToSpeech init failed: $status"))
                        return@TextToSpeech
                    }
                    tts = inst
                    cont.resume(inst)
                }
                cont.invokeOnCancellation {
                    try { engine?.shutdown() } catch (_: Exception) {}
                }
            }
        }
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        if (!hasRecordAudioPermission()) return Result.failure(GlassesError.PermissionDenied)
        if (activeMic != null) return Result.failure(GlassesError.Busy)

        // Emulator implementation uses Android's AudioRecord which (on the Android Emulator)
        // captures the host machine's microphone when mic passthrough is enabled.
        val encoding = when (options.preferredEncoding) {
            AudioEncoding.PCM_S16_LE -> AudioEncoding.PCM_S16_LE
            AudioEncoding.PCM_S8 -> AudioEncoding.PCM_S8
            AudioEncoding.OPUS -> return Result.failure(
                GlassesError.Unsupported("Simulator microphone: OPUS not supported (use PCM + app-side encoder)")
            )
            AudioEncoding.LC3 -> return Result.failure(
                GlassesError.Unsupported("Simulator microphone: LC3 not supported (use PCM + app-side encoder)")
            )
        }

        val sampleRate = options.preferredSampleRateHz ?: 16_000
        val channels = options.preferredChannelCount ?: 1
        when (channels) {
            1, 2 -> Unit
            else -> return Result.failure(GlassesError.Unsupported("Simulator microphone: channelCount=$channels"))
        }
        return try {
            val session = openAndroidMicrophone(
                options = options,
                audioSource = MediaRecorder.AudioSource.MIC,
                sampleRateHz = sampleRate,
                channelCount = channels,
                unsupportedOpusMessage = "Simulator microphone: OPUS not supported (use PCM + app-side encoder)",
                unsupportedChannelMessage = { "Simulator microphone: channelCount=$it" },
                minBufferErrorMessage = { "AudioRecord.getMinBufferSize failed: $it" },
                uninitializedMessage = "AudioRecord not initialized",
                afterStop = { activeMic = null },
            ).getOrThrow()
            activeMic = session
            emitLog("Simulator: startMicrophone => ok ($sampleRate Hz, $channels ch, $encoding)")
            Result.success(session)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Simulator startMicrophone failed: ${e.message}", e))
        }
    }

    // ── Video-file source helpers ──────────────────────────────────────

    /**
     * Initialise the [MediaMetadataRetriever] for [videoPath] and record the
     * video duration so we can compute the looping playback position later.
     *
     * The video file lives on the device filesystem (pushed there by the CLI
     * via `adb push`).  Because `/data/local/tmp/` may not be directly
     * readable by the app process (SELinux / file-mode restrictions), we first
     * copy the file into the app's private cache directory, then open it from
     * there.
     */
    private fun initVideoSource() {
        val path = videoPath ?: throw GlassesError.Transport("videoPath is null")
        val srcFile = File(path)
        emitLog("Simulator: initVideoSource – srcFile=$path exists=${srcFile.exists()} " +
                "canRead=${srcFile.canRead()} length=${srcFile.length()}")

        // Copy to app-private cache so we are guaranteed read access.
        val cacheFile = File(activity.cacheDir, "xg_sim_video.mp4")
        try {
            if (srcFile.exists() && srcFile.canRead()) {
                srcFile.copyTo(cacheFile, overwrite = true)
                emitLog("Simulator: copied video to cache: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            } else {
                // Fallback: try reading via FileInputStream (sometimes canRead() lies
                // on certain SELinux contexts while the actual read still works).
                FileInputStream(srcFile).use { fis ->
                    cacheFile.outputStream().use { out -> fis.copyTo(out) }
                }
                emitLog("Simulator: stream-copied video to cache: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            }
        } catch (e: Exception) {
            throw GlassesError.Transport(
                "Cannot read video file: $path – ${e.message}. " +
                "Make sure the file was pushed with: adb push <video> $path", e
            )
        }

        if (cacheFile.length() == 0L) {
            throw GlassesError.Transport("Video file is empty after copy: $path")
        }

        val retriever = MediaMetadataRetriever()
        try {
            // Use the app-private cache path – guaranteed readable by our process.
            retriever.setDataSource(cacheFile.absolutePath)
        } catch (e: Exception) {
            retriever.release()
            throw GlassesError.Transport(
                "MediaMetadataRetriever.setDataSource failed for ${cacheFile.absolutePath}: ${e.message}", e
            )
        }

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        emitLog("Simulator: METADATA_KEY_DURATION raw = '$durationStr'")
        videoDurationMs = durationStr?.toLongOrNull() ?: 0L
        if (videoDurationMs <= 0L) {
            retriever.release()
            throw GlassesError.Transport("Cannot determine video duration for: $path (raw='$durationStr')")
        }

        videoRetriever = retriever
        videoCacheFile = cacheFile
        videoStartTimeMs = System.currentTimeMillis()
        emitLog("Simulator: video source initialised – ${videoDurationMs}ms, path=$path")
    }

    /**
     * Capture a frame from the looping video.
     *
     * The "virtual playback head" is:
     *   elapsed = (now - videoStartTimeMs) mod videoDurationMs
     * so the video effectively loops at its original speed.
     */
    private suspend fun capturePhotoFromVideo(options: CaptureOptions): Result<CapturedImage> {
        return try {
            val retriever = videoRetriever
                ?: return Result.failure(GlassesError.Transport("Video source not initialised"))

            val elapsed = System.currentTimeMillis() - videoStartTimeMs
            val positionMs = if (videoDurationMs > 0) elapsed % videoDurationMs else 0L
            // MediaMetadataRetriever uses microseconds.
            val positionUs = positionMs * 1000L

            val bitmap: Bitmap = withContext(Dispatchers.IO) {
                retriever.getFrameAtTime(
                    positionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: throw GlassesError.Transport("Failed to extract frame at ${positionMs}ms")
            }

            val quality = options.photoQuality.toSimulatorJpegQuality()
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val jpegBytes = baos.toByteArray()
            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()

            val result = Result.success(
                CapturedImage(
                    jpegBytes = jpegBytes,
                    width = w,
                    height = h,
                    rotationDegrees = null,
                    sourceModel = model,
                )
            )
            emitLog("Simulator: capturePhoto (video @${positionMs}ms) => ${result.isSuccess}")
            result
        } catch (e: Exception) {
            Result.failure(
                (e as? GlassesError) ?: GlassesError.Transport("capturePhoto(video) failed: ${e.message}", e)
            )
        }
    }

    // ── Camera helpers ─────────────────────────────────────────────────

    private suspend fun ensureCameraUseCase(jpegQuality: Int) {
        if (_state.value is ConnectionState.Error) return
        if (imageCapture != null && cameraProvider != null) return

        withContext(Dispatchers.Main) {
            val provider = cameraProvider ?: run {
                val p = awaitCameraProvider()
                cameraProvider = p
                p
            }

            provider.unbindAll()

            val ic = ImageCapture.Builder()
                .setJpegQuality(jpegQuality.coerceIn(1, 100))
                .build()

            // Prefer back camera; fall back to front camera (some emulator configs only expose one).
            try {
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, ic)
            } catch (_: Throwable) {
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, ic)
            }

            imageCapture = ic
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun decodeDisplayBitmap(image: DisplayImage): Bitmap {
        if (image.bytes.isEmpty()) {
            throw GlassesError.Transport("Simulator displayImage failed: empty ${image.encoding} payload")
        }
        return BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
            ?: throw GlassesError.Transport("Simulator displayImage failed: invalid ${image.encoding} bytes")
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider {
        val future = ProcessCameraProvider.getInstance(activity)
        return suspendCancellableCoroutine { cont ->
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(activity)
            )
        }
    }

    private fun decodeJpegSize(bytes: ByteArray): Pair<Int?, Int?> {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val w = if (opts.outWidth > 0) opts.outWidth else null
            val h = if (opts.outHeight > 0) opts.outHeight else null
            w to h
        } catch (_: Throwable) {
            null to null
        }
    }

}

private fun PhotoQuality.toSimulatorJpegQuality(): Int = when (this) {
    PhotoQuality.LOWEST -> 25
    PhotoQuality.LOW -> 50
    PhotoQuality.MEDIUM -> 75
    PhotoQuality.HIGH -> 90
    PhotoQuality.HIGHEST -> 100
}
