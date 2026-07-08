package com.xgglass.device.inmo.runtime

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

    @Test
    fun captureCacheReturnsLatestFrame() {
        runBlocking {
            val cache = InmoVideoFrameCaptureCache()
            cache.update(frame(bytes = byteArrayOf(1, 2, 3), rotationDegrees = 90))

            val image = cache.capture(timeoutMs = 1_000, sourceModel = GlassesModel.INMO).getOrThrow()

            assertContentEquals(byteArrayOf(1, 2, 3), image.jpegBytes)
            assertEquals(320, image.width)
            assertEquals(240, image.height)
            assertEquals(90, image.rotationDegrees)
            assertEquals(GlassesModel.INMO, image.sourceModel)
        }
    }

    @Test
    fun captureCacheWaitsForNextFrame() {
        runBlocking {
            val cache = InmoVideoFrameCaptureCache()
            val capture = async { cache.capture(timeoutMs = 1_000, sourceModel = GlassesModel.INMO) }

            yield()
            cache.update(frame(bytes = byteArrayOf(9), rotationDegrees = 270))
            val image = capture.await().getOrThrow()

            assertContentEquals(byteArrayOf(9), image.jpegBytes)
            assertEquals(270, image.rotationDegrees)
        }
    }

    @Test
    fun captureCacheTimesOutWhenNoFrameArrives() {
        runBlocking {
            val result = InmoVideoFrameCaptureCache().capture(
                timeoutMs = 1,
                sourceModel = GlassesModel.INMO,
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
