package com.xgglass.device.androidxr

import android.content.Context
import com.xgglass.core.AudioSource
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PlayAudioOptions

/**
 * Proof-of-concept scaffold for Google Android XR projected AI glasses.
 *
 * This class intentionally does not call Jetpack XR preview APIs directly yet. The Projected APIs
 * are still in developer preview, and this repository does not have an XR glasses/emulator target
 * to validate exact method names, dependency coordinates, permission behavior, or media routing.
 *
 * Integrators can provide bridge lambdas in [AndroidXrOptions] while replacing each
 * TODO(android-xr) block with verified Jetpack Projected calls.
 */
class AndroidXrGlassesClient(
    private val hostContext: Context,
    private val options: AndroidXrOptions = AndroidXrOptions(),
) : BaseGlassesClient(initialCapabilities = options.assumedCapabilities) {

    override val model: GlassesModel = GlassesModel.ANDROID_XR

    @Volatile private var projectedContext: Context? = null
    @Volatile private var activeMic: MicrophoneSession? = null

    override suspend fun doConnect() {
        emitLog("Android XR: resolving projected context")

        val context = resolveProjectedContext()
        if (context == null) {
            val err = GlassesError.Unsupported(
                "Android XR projected context is not wired. Provide AndroidXrOptions.projectedContextProvider " +
                    "or replace resolveProjectedContext() with verified Jetpack Projected API."
            )
            emitWarn(err.message ?: "Android XR projected context is not wired")
            throw err
        }

        projectedContext = context
        emitLog("Android XR: connected to projected context scaffold")
    }

    override fun mapConnectError(error: Exception): GlassesError {
        return mapError("connect", error)
    }

    override suspend fun disconnect() {
        emitLog("Android XR: disconnecting")
        try {
            activeMic?.stop()
        } catch (_: Exception) {
        }
        activeMic = null
        projectedContext = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        val context = projectedContext ?: return Result.failure(GlassesError.NotConnected)

        return try {
            val bridge = this.options.capturePhotoBridge
                ?: return unsupported(
                    "Android XR capturePhoto is a preview scaffold. TODO(android-xr): replace with verified " +
                        "projected-context glasses camera capture."
                )

            val image = bridge(context, options, model)
            emitLog("Android XR: capturePhoto => ok (${image.jpegBytes.size} bytes)")
            Result.success(image)
        } catch (e: Exception) {
            Result.failure(mapError("capturePhoto", e))
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        val context = projectedContext ?: return Result.failure(GlassesError.NotConnected)

        return try {
            val bridge = this.options.displayBridge
                ?: return unsupported(
                    "Android XR display is a preview scaffold. TODO(android-xr): replace with verified " +
                        "projected-context display rendering."
                )

            bridge(context, text, options)
            emitLog("Android XR: display => ok")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(mapError("display", e))
        }
    }

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> {
        val context = projectedContext ?: return Result.failure(GlassesError.NotConnected)

        return try {
            val bridge = this.options.playAudioBridge
                ?: return unsupported(
                    "Android XR playAudio is a preview scaffold. TODO(android-xr): replace with verified " +
                        "projected-context glasses speaker playback."
                )

            bridge(context, source, options)
            emitLog("Android XR: playAudio => ok")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(mapError("playAudio", e))
        }
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        val context = projectedContext ?: return Result.failure(GlassesError.NotConnected)
        if (activeMic != null) return Result.failure(GlassesError.Busy)

        return try {
            val bridge = this.options.microphoneBridge
                ?: return unsupported(
                    "Android XR startMicrophone is a preview scaffold. TODO(android-xr): replace with verified " +
                        "projected-context glasses microphone capture."
                )

            val session = bridge(context, options)
            val managedSession = object : MicrophoneSession {
                override val format = session.format
                override val audio = session.audio

                override suspend fun stop() {
                    try {
                        session.stop()
                    } finally {
                        activeMic = null
                    }
                }
            }
            activeMic = managedSession
            emitLog("Android XR: startMicrophone => ok")
            Result.success(managedSession)
        } catch (e: Exception) {
            Result.failure(mapError("startMicrophone", e))
        }
    }

    private suspend fun resolveProjectedContext(): Context? {
        options.projectedContextProvider?.let { provider ->
            return provider(hostContext)
        }

        if (options.assumeHostContextIsProjected) {
            return hostContext
        }

        // TODO(android-xr): replace with verified Jetpack Projected API.
        // Intended shape:
        // 1. Observe whether an AI glasses projected device is connected.
        // 2. Request/obtain a projected Context whose hardware services target the glasses.
        // 3. Store that Context so camera, microphone, speaker, and display calls use glasses hardware.
        return null
    }

    private fun unsupported(detail: String): Result<Nothing> {
        emitWarn(detail)
        return Result.failure(GlassesError.Unsupported(detail))
    }

    private fun mapError(operation: String, error: Exception): GlassesError {
        return (error as? GlassesError)
            ?: GlassesError.Transport("Android XR $operation failed: ${error.message}", error)
    }

    data class AndroidXrOptions(
        /**
         * Use only when [hostContext] is already a projected-context Activity/Context created by
         * verified Jetpack Projected APIs. False by default to avoid silently using phone hardware.
         */
        val assumeHostContextIsProjected: Boolean = false,
        /**
         * Bridge point for the current developer-preview projected-context call.
         *
         * TODO(android-xr): replace this with the verified Jetpack Projected API once pinned.
         */
        val projectedContextProvider: (suspend (Context) -> Context?)? = null,
        val assumedCapabilities: DeviceCapabilities = DeviceCapabilities(
            canCapturePhoto = true,
            canDisplayText = true,
            canRecordAudio = true,
            canPlayTts = false,
            canPlayAudioBytes = true,
            supportsTapEvents = false,
            supportsStreamingTextUpdates = true,
        ),
        /**
         * TODO(android-xr): replace with glasses camera capture from the projected context.
         */
        val capturePhotoBridge: (suspend (Context, CaptureOptions, GlassesModel) -> CapturedImage)? = null,
        /**
         * TODO(android-xr): replace with rendering inside the projected display surface/activity.
         */
        val displayBridge: (suspend (Context, String, DisplayOptions) -> Unit)? = null,
        /**
         * TODO(android-xr): replace with speaker playback created from the projected context.
         */
        val playAudioBridge: (suspend (Context, AudioSource, PlayAudioOptions) -> Unit)? = null,
        /**
         * TODO(android-xr): replace with microphone capture created from the projected context.
         */
        val microphoneBridge: (suspend (Context, MicrophoneOptions) -> MicrophoneSession)? = null,
    )
}
