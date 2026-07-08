package com.xgglass.device.rayneo.runtime

import com.xgglass.core.CapturedImage
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesModel
import com.xgglass.core.VideoFrame
import com.xgglass.core.VideoFrameRateTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal object RayNeoVideoStreamPolicy {
    fun framesPerSecond(tier: VideoFrameRateTier): Int =
        when (tier) {
            VideoFrameRateTier.SLOW -> 1
            VideoFrameRateTier.LOW -> 3
            VideoFrameRateTier.MEDIUM -> 8
            VideoFrameRateTier.HIGH -> 15
            VideoFrameRateTier.NATIVE -> 15
        }

    fun frameIntervalMs(tier: VideoFrameRateTier): Long {
        val fps = framesPerSecond(tier)
        return (1_000L / fps).coerceAtLeast(1L)
    }
}

internal class RayNeoSingleVideoStreamGate {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): Boolean = active.compareAndSet(false, true)

    fun release() {
        active.set(false)
    }

    fun isActive(): Boolean = active.get()
}

internal class RayNeoVideoFrameCaptureCache {
    private val lock = Any()
    private var latestFrame: VideoFrame? = null
    private var nextFrame: CompletableDeferred<VideoFrame>? = null

    fun update(frame: VideoFrame) {
        if (frame.endOfStream) return
        val waiter = synchronized(lock) {
            latestFrame = frame
            nextFrame.also { nextFrame = null }
        }
        waiter?.complete(frame)
    }

    suspend fun capture(timeoutMs: Long, sourceModel: GlassesModel): Result<CapturedImage> {
        val frame = awaitLatestOrNext(timeoutMs)
            ?: return Result.failure(GlassesError.Timeout("capturePhoto"))
        return Result.success(frame.toCapturedImage(sourceModel))
    }

    private suspend fun awaitLatestOrNext(timeoutMs: Long): VideoFrame? {
        var waiter: CompletableDeferred<VideoFrame>? = null
        val cached = synchronized(lock) {
            latestFrame ?: run {
                val next = nextFrame?.takeIf { it.isActive }
                    ?: CompletableDeferred<VideoFrame>().also { nextFrame = it }
                waiter = next
                null
            }
        }
        if (cached != null) return cached
        return withTimeoutOrNull(timeoutMs) { waiter?.await() }
    }

    private fun VideoFrame.toCapturedImage(sourceModel: GlassesModel): CapturedImage =
        CapturedImage(
            jpegBytes = bytes,
            width = format.width,
            height = format.height,
            rotationDegrees = rotationDegrees,
            sourceModel = sourceModel,
        )
}
