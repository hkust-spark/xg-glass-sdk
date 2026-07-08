package com.xgglass.device.rayneo.runtime

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

    @Test
    fun captureCacheReturnsLatestFrame() {
        runBlocking {
            val cache = RayNeoVideoFrameCaptureCache()
            cache.update(frame(bytes = byteArrayOf(1, 2, 3), rotationDegrees = null))

            val image = cache.capture(timeoutMs = 1_000, sourceModel = GlassesModel.RAYNEO).getOrThrow()

            assertContentEquals(byteArrayOf(1, 2, 3), image.jpegBytes)
            assertEquals(320, image.width)
            assertEquals(240, image.height)
            assertEquals(null, image.rotationDegrees)
            assertEquals(GlassesModel.RAYNEO, image.sourceModel)
        }
    }

    @Test
    fun captureCacheWaitsForNextFrame() {
        runBlocking {
            val cache = RayNeoVideoFrameCaptureCache()
            val capture = async { cache.capture(timeoutMs = 1_000, sourceModel = GlassesModel.RAYNEO) }

            yield()
            cache.update(frame(bytes = byteArrayOf(9), rotationDegrees = null))
            val image = capture.await().getOrThrow()

            assertContentEquals(byteArrayOf(9), image.jpegBytes)
            assertEquals(null, image.rotationDegrees)
        }
    }

    @Test
    fun captureCacheTimesOutWhenNoFrameArrives() {
        runBlocking {
            val result = RayNeoVideoFrameCaptureCache().capture(
                timeoutMs = 1,
                sourceModel = GlassesModel.RAYNEO,
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
