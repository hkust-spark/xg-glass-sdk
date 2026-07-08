package com.xgglass.device.sim

import com.xgglass.core.VideoFrameRateTier
import java.util.concurrent.atomic.AtomicBoolean

internal object SimulatorVideoStreamPolicy {
    fun framesPerSecond(tier: VideoFrameRateTier, nativeFramesPerSecond: Int? = null): Int =
        when (tier) {
            VideoFrameRateTier.SLOW -> 1
            VideoFrameRateTier.LOW -> 3
            VideoFrameRateTier.MEDIUM -> 8
            VideoFrameRateTier.HIGH -> 15
            VideoFrameRateTier.NATIVE -> nativeFramesPerSecond?.coerceIn(1, 60) ?: 15
        }

    fun frameIntervalMs(tier: VideoFrameRateTier, nativeFramesPerSecond: Int? = null): Long {
        val fps = framesPerSecond(tier, nativeFramesPerSecond)
        return (1_000L / fps).coerceAtLeast(1L)
    }
}

internal class SimulatorSingleVideoStreamGate {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): Boolean = active.compareAndSet(false, true)

    fun release() {
        active.set(false)
    }

    fun isActive(): Boolean = active.get()
}
