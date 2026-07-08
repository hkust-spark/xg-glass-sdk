package com.xgglass.device.rayneo.runtime

import com.xgglass.core.VideoFrameRateTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RayNeoVideoStreamPolicyTest {
    @Test
    fun mapsFrameRateTiersToIntervals() {
        assertEquals(1_000L, RayNeoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.SLOW))
        assertEquals(333L, RayNeoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.LOW))
        assertEquals(125L, RayNeoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.MEDIUM))
        assertEquals(66L, RayNeoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.HIGH))
        assertEquals(66L, RayNeoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.NATIVE))
    }

    @Test
    fun reportsFpsForTiers() {
        assertEquals(1, RayNeoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.SLOW))
        assertEquals(3, RayNeoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.LOW))
        assertEquals(8, RayNeoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.MEDIUM))
        assertEquals(15, RayNeoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.HIGH))
        assertEquals(15, RayNeoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.NATIVE))
    }

    @Test
    fun singleStreamGateAllowsOnlyOneActiveStream() {
        val gate = RayNeoSingleVideoStreamGate()

        assertTrue(gate.tryAcquire())
        assertTrue(gate.isActive())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertFalse(gate.isActive())
        assertTrue(gate.tryAcquire())
    }
}
