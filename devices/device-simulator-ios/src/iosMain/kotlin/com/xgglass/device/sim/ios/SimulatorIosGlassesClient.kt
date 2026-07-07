package com.xgglass.device.sim.ios

import com.xgglass.core.AudioSource
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesEvent
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesModel
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PhotoQuality
import com.xgglass.core.PlayAudioOptions
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.UIKit.UIColor
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat

class SimulatorIosGlassesClient(
    private val displaySink: (String) -> Unit = {},
) : BaseGlassesClient(
    initialCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        canRecordAudio = false,
        canPlayTts = false,
        canPlayAudioBytes = false,
        supportsTapEvents = true,
        supportsLongPressEvents = true,
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

    override suspend fun doConnect() {
        emitLog("Simulator (iOS): connect (no-op)")
    }

    override fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError)
            ?: GlassesError.Transport("Simulator connect failed: ${error.message}", error)
    }

    override suspend fun disconnect() {
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        return runCatching {
            val width = options.targetWidth?.coerceAtLeast(1) ?: 640
            val height = options.targetHeight?.coerceAtLeast(1) ?: 480
            val jpegBytes = renderPlaceholderJpeg(width, height, options.photoQuality.jpegQuality)

            CapturedImage(
                jpegBytes = jpegBytes,
                width = width,
                height = height,
                rotationDegrees = null,
                sourceModel = model,
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(GlassesError.Transport("Simulator capture failed: ${it.message}", it)) },
        )
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        displaySink(text)
        return Result.success(Unit)
    }

    override suspend fun playAudio(
        source: AudioSource,
        options: PlayAudioOptions,
    ): Result<Unit> {
        return Result.failure(GlassesError.Unsupported("Simulator (iOS) audio not implemented"))
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        return Result.failure(GlassesError.Unsupported("Simulator (iOS) mic not implemented"))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun renderPlaceholderJpeg(width: Int, height: Int, quality: Double): ByteArray {
        val rendererFormat = UIGraphicsImageRendererFormat.defaultFormat()
        rendererFormat.opaque = true
        rendererFormat.scale = 1.0

        val renderer = UIGraphicsImageRenderer(
            size = CGSizeMake(width.toDouble(), height.toDouble()),
            format = rendererFormat,
        )

        val image = renderer.imageWithActions {
            val canvasWidth = width.toDouble()
            val canvasHeight = height.toDouble()
            val contentWidth = (canvasWidth - 88.0).coerceAtLeast(1.0)
            val panelHeight = (canvasHeight - 184.0).coerceAtLeast(1.0)

            UIColor.colorWithRed(red = 0.06, green = 0.09, blue = 0.16, alpha = 1.0).setFill()
            UIRectFill(CGRectMake(0.0, 0.0, canvasWidth, canvasHeight))

            UIColor.colorWithRed(red = 0.0, green = 0.72, blue = 0.64, alpha = 1.0).setFill()
            UIRectFill(CGRectMake(0.0, 0.0, canvasWidth, 84.0))

            UIColor.colorWithRed(red = 0.15, green = 0.24, blue = 0.36, alpha = 1.0).setFill()
            UIRectFill(CGRectMake(28.0, 128.0, (canvasWidth - 56.0).coerceAtLeast(1.0), panelHeight))

            UIColor.colorWithRed(red = 1.0, green = 1.0, blue = 1.0, alpha = 0.82).setFill()
            UIRectFill(CGRectMake(44.0, 156.0, contentWidth, 18.0))
            UIRectFill(CGRectMake(44.0, 190.0, contentWidth * 0.62, 18.0))
            UIRectFill(CGRectMake(44.0, 224.0, contentWidth * 0.38, 18.0))
        }

        val jpegData = UIImageJPEGRepresentation(image, quality)
            ?: throw IllegalStateException("UIKit did not create JPEG data")
        return jpegData.toByteArray()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        return this.bytes?.readBytes(length.toInt()) ?: ByteArray(0)
    }
}

private val PhotoQuality.jpegQuality: Double
    get() = when (this) {
        PhotoQuality.LOWEST -> 0.35
        PhotoQuality.LOW -> 0.55
        PhotoQuality.MEDIUM -> 0.75
        PhotoQuality.HIGH -> 0.90
        PhotoQuality.HIGHEST -> 0.98
    }
