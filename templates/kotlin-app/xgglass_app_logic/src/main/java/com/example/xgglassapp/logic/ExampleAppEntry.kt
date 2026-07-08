package com.example.xgglassapp.logic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.xgglass.appcontract.UniversalAppContext
import com.xgglass.appcontract.UniversalAppEntrySimple
import com.xgglass.appcontract.UniversalCommand
import com.xgglass.core.CaptureOptions
import com.xgglass.core.DisplayImage
import com.xgglass.core.DisplayImageOptions
import com.xgglass.core.DisplayOptions
import com.xgglass.core.ImageEncoding
import com.xgglass.core.ImageScaleMode
import com.xgglass.core.MicrophoneOptions
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Example entry (ExampleAppEntry)
 *
 * IMPORTANT: Rename/replace this file.
 *
 * - This is a compilable, runnable starter entry so `xg-glass init` projects work out of the box.
 * - In real apps, you should rename this file/class (e.g. `MyAppEntry.kt`) and update `entryClass`
 *   in the project root `xg-glass.yaml`.
 * - The host app loads this class via reflection using the manifest meta-data key
 *   `com.xgglass.app_entry_class`.
 */
class ExampleAppEntry : UniversalAppEntrySimple {
    override val id: String = "example_app"
    override val displayName: String = "Example XgGlass App"

    override fun commands(): List<UniversalCommand> {
        return listOf(
            capturePhotoCommand(),
            displayHelloCommand(),
            displayImageCommand(),
            micRecordCommand(seconds = 3),
        )
    }

    private fun capturePhotoCommand(): UniversalCommand = object : UniversalCommand {
        override val id: String = "capture_photo"
        override val title: String = "Capture photo"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            if (!ctx.client.capabilities.canCapturePhoto) {
                return Result.failure(IllegalStateException("capture_photo: selected device cannot capture photos"))
            }

            ctx.log("capture_photo: starting")
            val image = ctx.client.capturePhoto(CaptureOptions()).getOrElse { error ->
                ctx.log("capture_photo: failed: ${error.message ?: error::class.simpleName}")
                return Result.failure(error)
            }
            ctx.onCapturedImage?.invoke(image)
            ctx.log(
                "capture_photo: ${image.jpegBytes.size} bytes " +
                    "(${image.width ?: "?"}x${image.height ?: "?"}, rotation=${image.rotationDegrees ?: "?"})"
            )
            return Result.success(Unit)
        }
    }

    private fun displayHelloCommand(): UniversalCommand = object : UniversalCommand {
        override val id: String = "display_hello"
        override val title: String = "Display hello"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            if (!ctx.client.capabilities.canDisplayText) {
                return Result.failure(IllegalStateException("display_hello: selected device cannot display text"))
            }

            val text = "hello"
            ctx.log("display_hello: sending \"$text\"")
            return ctx.client.display(text, DisplayOptions()).also { result ->
                if (result.isSuccess) ctx.log("display_hello: ok")
            }
        }
    }

    private fun displayImageCommand(): UniversalCommand = object : UniversalCommand {
        override val id: String = "display_image"
        override val title: String = "Display image"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            if (!ctx.client.capabilities.canDisplayImages) {
                return Result.failure(IllegalStateException("display_image: selected device cannot display images"))
            }

            val bytes = createSmokePng()
            ctx.log("display_image: sending PNG (${bytes.size} bytes)")
            return ctx.client.displayImage(
                DisplayImage(bytes = bytes, encoding = ImageEncoding.PNG),
                DisplayImageOptions(scaleMode = ImageScaleMode.FIT),
            ).also { result ->
                if (result.isSuccess) ctx.log("display_image: ok")
            }
        }
    }

    private fun micRecordCommand(seconds: Int): UniversalCommand = object : UniversalCommand {
        override val id: String = "mic_record_${seconds}s"
        override val title: String = "Mic record ${seconds}s"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            if (!ctx.client.capabilities.canRecordAudio) {
                return Result.failure(IllegalStateException("mic_record: selected device cannot record audio"))
            }

            ctx.log("mic_record: starting ${seconds}s")
            val session = ctx.client.startMicrophone(MicrophoneOptions()).getOrElse { error ->
                ctx.log("mic_record: failed to start: ${error.message ?: error::class.simpleName}")
                return Result.failure(error)
            }

            val captured = mutableListOf<com.xgglass.core.AudioChunk>()
            try {
                withTimeoutOrNull(seconds * 1_000L) {
                    session.audio.collect { captured += it }
                }
            } catch (error: Throwable) {
                try {
                    session.stop()
                } catch (stopError: Throwable) {
                    error.addSuppressed(stopError)
                }
                return Result.failure(error)
            }

            return try {
                session.stop()
                val chunks = captured.count { !it.endOfStream }
                val bytes = captured.sumOf { it.bytes.size }
                ctx.log(
                    "mic_record: ${chunks} chunks, ${bytes} bytes, " +
                        "format=${session.format.encoding}/${session.format.sampleRateHz ?: "?"}Hz/" +
                        "${session.format.channelCount ?: "?"}ch"
                )
                Result.success(Unit)
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
    }

    private fun createSmokePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(Color.rgb(24, 36, 48))
        paint.color = Color.rgb(0, 184, 148)
        canvas.drawRect(0f, 0f, 48f, 64f, paint)
        paint.color = Color.rgb(255, 214, 10)
        canvas.drawCircle(70f, 32f, 18f, paint)
        paint.color = Color.WHITE
        paint.textSize = 16f
        canvas.drawText("xg", 8f, 38f, paint)

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
