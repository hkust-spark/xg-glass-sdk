package com.example.xgglassapp.logic

import com.xgglass.appcontract.UniversalAppContext
import com.xgglass.appcontract.UniversalAppEntrySimple
import com.xgglass.appcontract.UniversalCommand
import com.xgglass.core.CaptureOptions
import com.xgglass.core.DisplayOptions
import com.xgglass.core.MicrophoneOptions
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
}
