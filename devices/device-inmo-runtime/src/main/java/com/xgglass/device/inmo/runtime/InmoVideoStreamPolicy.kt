package com.xgglass.device.inmo.runtime

import com.xgglass.core.VideoFrameRateTier
import java.util.concurrent.atomic.AtomicBoolean

internal object InmoVideoStreamPolicy {
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

internal class InmoSingleVideoStreamGate {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): Boolean = active.compareAndSet(false, true)

    fun release() {
        active.set(false)
    }

    fun isActive(): Boolean = active.get()
}
