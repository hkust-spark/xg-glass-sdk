package com.xgglass.device.inmo.runtime

import com.xgglass.core.VideoFrameRateTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InmoVideoStreamPolicyTest {
    @Test
    fun mapsFrameRateTiersToIntervals() {
        assertEquals(1_000L, InmoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.SLOW))
        assertEquals(333L, InmoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.LOW))
        assertEquals(125L, InmoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.MEDIUM))
        assertEquals(66L, InmoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.HIGH))
        assertEquals(66L, InmoVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.NATIVE))
    }

    @Test
    fun reportsFpsForTiers() {
        assertEquals(1, InmoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.SLOW))
        assertEquals(3, InmoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.LOW))
        assertEquals(8, InmoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.MEDIUM))
        assertEquals(15, InmoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.HIGH))
        assertEquals(15, InmoVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.NATIVE))
    }

    @Test
    fun singleStreamGateAllowsOnlyOneActiveStream() {
        val gate = InmoSingleVideoStreamGate()

        assertTrue(gate.tryAcquire())
        assertTrue(gate.isActive())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertFalse(gate.isActive())
        assertTrue(gate.tryAcquire())
    }
}
