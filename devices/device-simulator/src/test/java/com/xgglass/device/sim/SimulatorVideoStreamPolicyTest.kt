package com.xgglass.device.sim

import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesModel
import com.xgglass.core.VideoFormat
import com.xgglass.core.VideoFrame
import com.xgglass.core.VideoFrameEncoding
import com.xgglass.core.VideoFrameRateTier
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    @Test
    fun captureCacheReturnsLatestFrame() {
        runBlocking {
            val cache = SimulatorVideoFrameCaptureCache()
            cache.update(frame(bytes = byteArrayOf(1, 2, 3), rotationDegrees = 90))

            val image = cache.capture(timeoutMs = 1_000, sourceModel = GlassesModel.SIMULATOR).getOrThrow()

            assertContentEquals(byteArrayOf(1, 2, 3), image.jpegBytes)
            assertEquals(320, image.width)
            assertEquals(240, image.height)
            assertEquals(90, image.rotationDegrees)
            assertEquals(GlassesModel.SIMULATOR, image.sourceModel)
        }
    }

    @Test
    fun captureCacheWaitsForNextFrame() {
        runBlocking {
            val cache = SimulatorVideoFrameCaptureCache()
            val capture = async { cache.capture(timeoutMs = 1_000, sourceModel = GlassesModel.SIMULATOR) }

            yield()
            cache.update(frame(bytes = byteArrayOf(9), rotationDegrees = 180))
            val image = capture.await().getOrThrow()

            assertContentEquals(byteArrayOf(9), image.jpegBytes)
            assertEquals(180, image.rotationDegrees)
        }
    }

    @Test
    fun captureCacheTimesOutWhenNoFrameArrives() {
        runBlocking {
            val result = SimulatorVideoFrameCaptureCache().capture(
                timeoutMs = 1,
                sourceModel = GlassesModel.SIMULATOR,
            )

            assertTrue(result.isFailure)
            assertIs<GlassesError.Timeout>(result.exceptionOrNull())
        }
    }

    private fun frame(bytes: ByteArray, rotationDegrees: Int?): VideoFrame =
        VideoFrame(
            bytes = bytes,
            format = VideoFormat(
                encoding = VideoFrameEncoding.JPEG,
                width = 320,
                height = 240,
                framesPerSecond = 3,
            ),
            sequence = 7,
            timestampMs = 123,
            rotationDegrees = rotationDegrees,
        )
}
