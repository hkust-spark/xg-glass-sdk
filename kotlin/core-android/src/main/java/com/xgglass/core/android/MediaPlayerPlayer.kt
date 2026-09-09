package com.xgglass.core.android

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import com.xgglass.core.GlassesError
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val encodedPlaybacks = ConcurrentHashMap<MediaPlayer, () -> Unit>()

private fun onPlaybackThread(action: () -> Unit) {
    val dispatcher = Dispatchers.Main.immediate
    if (dispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
        dispatcher.dispatch(EmptyCoroutineContext, Runnable(action))
    } else {
        action()
    }
}

/** Stop an encoded playback and finish its suspended caller, including during disconnect. */
fun stopEncodedPlayback(player: MediaPlayer?) {
    if (player == null) return
    onPlaybackThread {
        val stop = encodedPlaybacks[player]
        if (stop != null) stop() else runCatching { player.release() }
    }
}

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
): Result<Unit> = playEncodedWithPlayer(
    data, usageAttributes, interrupt, tempFileFactory, legacyStreamType,
    preferredDevice, currentPlayer, setCurrentPlayer, errorMessage, ::MediaPlayer,
)

internal suspend fun playEncodedWithPlayer(
    data: ByteArray,
    usageAttributes: Int,
    interrupt: Boolean,
    tempFileFactory: () -> File,
    legacyStreamType: Int? = null,
    preferredDevice: AudioDeviceInfo? = null,
    currentPlayer: (() -> MediaPlayer?)? = null,
    setCurrentPlayer: ((MediaPlayer?) -> Unit)? = null,
    errorMessage: (Int, Int) -> String = { what, extra -> "MediaPlayer error: $what/$extra" },
    playerFactory: () -> MediaPlayer,
    attributesFactory: (Int, Int?) -> AudioAttributes = { usage, streamType ->
        AudioAttributes.Builder().setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .apply { if (streamType != null) setLegacyStreamType(streamType) }
            .build()
    },
): Result<Unit> {
    var cleanupFile: File? = null
    return try {
        val tmpFile = tempFileFactory().also { cleanupFile = it }
        withContext(Dispatchers.IO) { tmpFile.writeBytes(data) }
        withContext(Dispatchers.Main.immediate) {
            val previous = currentPlayer?.invoke()
            if (interrupt) {
                stopEncodedPlayback(previous)
            } else if (previous != null) {
                // A single active-player slot cannot own concurrent sessions. Keep
                // the existing playback reachable for disconnect instead of orphaning it.
                throw GlassesError.Busy
            }
            suspendCancellableCoroutine { cont ->
                val player = playerFactory()
                val completed = AtomicBoolean(false)
                fun finish(error: Throwable?) {
                    if (!completed.compareAndSet(false, true)) return
                    encodedPlaybacks.remove(player)
                    runCatching { player.setOnPreparedListener(null) }
                    runCatching { player.setOnCompletionListener(null) }
                    runCatching { player.setOnErrorListener(null) }
                    runCatching { player.release() }
                    if (currentPlayer?.invoke() === player) setCurrentPlayer?.invoke(null)
                    tmpFile.delete()
                    if (cont.isActive) {
                        if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
                    }
                }
                encodedPlaybacks[player] = {
                    finish(GlassesError.Transport("Encoded audio playback was interrupted"))
                }
                cont.invokeOnCancellation { onPlaybackThread { finish(null) } }
                if (!cont.isActive) return@suspendCancellableCoroutine
                try {
                    setCurrentPlayer?.invoke(player)
                    player.setDataSource(tmpFile.absolutePath)
                    player.setAudioAttributes(attributesFactory(usageAttributes, legacyStreamType))
                    if (preferredDevice != null) runCatching { player.preferredDevice = preferredDevice }
                    player.setOnPreparedListener {
                        if (!completed.get()) {
                            try { player.start() } catch (e: Exception) { finish(e) }
                        }
                    }
                    player.setOnCompletionListener { finish(null) }
                    player.setOnErrorListener { _, what, extra ->
                        finish(GlassesError.Transport(errorMessage(what, extra)))
                        true
                    }
                    player.prepareAsync()
                } catch (e: Exception) {
                    finish(e)
                }
            }
        }
        Result.success(Unit)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        Result.failure(e)
    } finally {
        cleanupFile?.delete()
    }
}
