package com.universalglasses.device.sim

import android.Manifest
import android.graphics.BitmapFactory
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.DeviceCapabilities
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Simulator implementation of [GlassesClient] for running on an Android Emulator.
 *
 * Behavior:
 * - connect()/disconnect(): no physical glasses, so it's effectively a no-op lifecycle.
 * - capturePhoto(): uses Android camera (on Emulator this can be backed by host webcam).
 * - display(): shows text via an Activity on the emulator screen.
 */
class EmulatorGlassesClient(
    private val activity: AppCompatActivity,
) : GlassesClient {

    override val model: GlassesModel = GlassesModel.SIMULATOR

    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = false,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<GlassesEvent> = _events

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    override suspend fun connect(): Result<Unit> {
        if (_state.value is ConnectionState.Connected || _state.value is ConnectionState.Connecting) {
            return Result.success(Unit)
        }

        _state.value = ConnectionState.Connecting
        emitLog("Simulator: connect (no-op)")

        return try {
            ensureCameraUseCase(jpegQuality = 90)
            _state.value = ConnectionState.Connected
            Result.success(Unit)
        } catch (ce: CancellationException) {
            _state.value = ConnectionState.Disconnected
            Result.failure(ce)
        } catch (e: Exception) {
            val err = (e as? GlassesError) ?: GlassesError.Transport("Simulator connect failed: ${e.message}", e)
            _state.value = ConnectionState.Error(err)
            Result.failure(err)
        }
    }

    override suspend fun disconnect() {
        emitLog("Simulator: disconnect (no-op)")
        withContext(Dispatchers.Main) {
            cameraProvider?.unbindAll()
            imageCapture = null
        }
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (!hasCameraPermission()) {
            emitWarn("Simulator: CAMERA permission missing")
            return Result.failure(GlassesError.PermissionDenied)
        }

        return try {
            val quality = (options.quality ?: 90).coerceIn(1, 100)
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
                activity.startActivity(SimDisplayActivity.newIntent(activity, text))
            }
            emitLog("Simulator: display => ok")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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

    private fun emitLog(msg: String) {
        _events.tryEmit(GlassesEvent.Log(msg))
    }

    private fun emitWarn(msg: String) {
        _events.tryEmit(GlassesEvent.Warning(msg))
    }
}
