package com.universalglasses.core.android

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import com.universalglasses.core.GlassesError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun playEncodedViaMediaPlayer(
    data: ByteArray,
    usageAttributes: Int,
    interrupt: Boolean,
    tempFileFactory: () -> File,
    legacyStreamType: Int? = null,
    preferredDevice: AudioDeviceInfo? = null,
    currentPlayer: (() -> MediaPlayer?)? = null,
    setCurrentPlayer: ((MediaPlayer?) -> Unit)? = null,
    errorMessage: (what: Int, extra: Int) -> String = { what, extra ->
        "MediaPlayer error: what=$what extra=$extra"
    },
): Result<Unit> {
    if (interrupt) {
        try {
            currentPlayer?.invoke()?.release()
        } catch (_: Exception) {}
        setCurrentPlayer?.invoke(null)
    }

    val tmpFile = tempFileFactory()
    return try {
        tmpFile.writeBytes(data)
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val player = MediaPlayer()
                setCurrentPlayer?.invoke(player)
                player.setDataSource(tmpFile.absolutePath)
                val attrs = AudioAttributes.Builder()
                    .setUsage(usageAttributes)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                if (legacyStreamType != null) {
                    attrs.setLegacyStreamType(legacyStreamType)
                }
                player.setAudioAttributes(attrs.build())
                if (preferredDevice != null) {
                    runCatching { player.preferredDevice = preferredDevice }
                }
                player.setOnCompletionListener {
                    player.release()
                    setCurrentPlayer?.invoke(null)
                    tmpFile.delete()
                    if (cont.isActive) cont.resume(Unit)
                }
                player.setOnErrorListener { _, what, extra ->
                    player.release()
                    setCurrentPlayer?.invoke(null)
                    tmpFile.delete()
                    if (cont.isActive) {
                        cont.resumeWithException(GlassesError.Transport(errorMessage(what, extra)))
                    }
                    true
                }
                player.prepare()
                player.start()
                cont.invokeOnCancellation {
                    player.release()
                    setCurrentPlayer?.invoke(null)
                    tmpFile.delete()
                }
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    } finally {
        tmpFile.delete()
    }
}
