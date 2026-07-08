package com.xgglass.device.sim

import com.xgglass.core.VideoFrameRateTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulatorVideoStreamPolicyTest {
    @Test
    fun mapsFrameRateTiersToIntervals() {
        assertEquals(1_000L, SimulatorVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.SLOW))
        assertEquals(333L, SimulatorVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.LOW))
        assertEquals(125L, SimulatorVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.MEDIUM))
        assertEquals(66L, SimulatorVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.HIGH))
        assertEquals(41L, SimulatorVideoStreamPolicy.frameIntervalMs(VideoFrameRateTier.NATIVE, nativeFramesPerSecond = 24))
    }

    @Test
    fun reportsFpsForTiers() {
        assertEquals(1, SimulatorVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.SLOW))
        assertEquals(3, SimulatorVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.LOW))
        assertEquals(8, SimulatorVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.MEDIUM))
        assertEquals(15, SimulatorVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.HIGH))
        assertEquals(15, SimulatorVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.NATIVE))
        assertEquals(60, SimulatorVideoStreamPolicy.framesPerSecond(VideoFrameRateTier.NATIVE, nativeFramesPerSecond = 90))
    }

    @Test
    fun singleStreamGateAllowsOnlyOneActiveStream() {
        val gate = SimulatorSingleVideoStreamGate()

        assertTrue(gate.tryAcquire())
        assertTrue(gate.isActive())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertFalse(gate.isActive())
        assertTrue(gate.tryAcquire())
    }
}
