package com.xgglass.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame

class CoreValueEqualityTest {
    @Test
    fun `captured images with identical jpeg content are equal and share hash code`() {
        // Arrange
        val first = CapturedImage(
            jpegBytes = byteArrayOf(1, 2, 3),
            timestampMs = 100L,
            width = 640,
            height = 480,
            rotationDegrees = 90,
            sourceModel = GlassesModel.SIMULATOR,
        )
        val second = CapturedImage(
            jpegBytes = byteArrayOf(1, 2, 3),
            timestampMs = 100L,
            width = 640,
            height = 480,
            rotationDegrees = 90,
            sourceModel = GlassesModel.SIMULATOR,
        )

        // Act / Assert
        assertNotSame(first.jpegBytes, second.jpegBytes)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `captured images with different jpeg content are not equal`() {
        // Arrange
        val first = CapturedImage(
            jpegBytes = byteArrayOf(1, 2, 3),
            timestampMs = 100L,
            width = 640,
            height = 480,
            rotationDegrees = 90,
            sourceModel = GlassesModel.SIMULATOR,
        )
        val second = first.copy(jpegBytes = byteArrayOf(1, 2, 4))

        // Act / Assert
        assertNotEquals(first, second)
    }

    @Test
    fun `display images with identical encoded content are equal and share hash code`() {
        // Arrange
        val first = DisplayImage(
            bytes = byteArrayOf(1, 2, 3),
            encoding = ImageEncoding.PNG,
        )
        val second = DisplayImage(
            bytes = byteArrayOf(1, 2, 3),
            encoding = ImageEncoding.PNG,
        )

        // Act / Assert
        assertNotSame(first.bytes, second.bytes)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `display images compare both bytes and encoding`() {
        // Arrange
        val first = DisplayImage(
            bytes = byteArrayOf(1, 2, 3),
            encoding = ImageEncoding.PNG,
        )
        val differentBytes = first.copy(bytes = byteArrayOf(1, 2, 4))
        val differentEncoding = first.copy(encoding = ImageEncoding.JPEG)

        // Act / Assert
        assertNotEquals(first, differentBytes)
        assertNotEquals(first, differentEncoding)
    }

    @Test
    fun `display image options default to fit and preserve explicit scale mode`() {
        assertEquals(ImageScaleMode.FIT, DisplayImageOptions().scaleMode)
        assertEquals(ImageScaleMode.FILL, DisplayImageOptions(scaleMode = ImageScaleMode.FILL).scaleMode)
        assertEquals(ImageScaleMode.CENTER, DisplayImageOptions(scaleMode = ImageScaleMode.CENTER).scaleMode)
    }

    @Test
    fun `audio chunks with identical byte content are equal and share hash code`() {
        // Arrange
        val format = AudioFormat(
            encoding = AudioEncoding.PCM_S16_LE,
            sampleRateHz = 16_000,
            channelCount = 1,
        )
        val first = AudioChunk(
            bytes = byteArrayOf(10, 20, 30),
            format = format,
            sequence = 7L,
            timestampMs = 200L,
            endOfStream = false,
        )
        val second = AudioChunk(
            bytes = byteArrayOf(10, 20, 30),
            format = format,
            sequence = 7L,
            timestampMs = 200L,
            endOfStream = false,
        )

        // Act / Assert
        assertNotSame(first.bytes, second.bytes)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `audio chunks with different byte content are not equal`() {
        // Arrange
        val format = AudioFormat(
            encoding = AudioEncoding.PCM_S16_LE,
            sampleRateHz = 16_000,
            channelCount = 1,
        )
        val first = AudioChunk(
            bytes = byteArrayOf(10, 20, 30),
            format = format,
            sequence = 7L,
            timestampMs = 200L,
            endOfStream = false,
        )
        val second = first.copy(bytes = byteArrayOf(10, 20, 31))

        // Act / Assert
        assertNotEquals(first, second)
    }

    @Test
    fun `raw audio byte sources with identical data and pcm format are equal and share hash code`() {
        // Arrange
        val pcmFormat = PcmFormat(
            sampleRateHz = 24_000,
            channelCount = 2,
            encoding = AudioEncoding.PCM_S16_LE,
        )
        val first = AudioSource.RawBytes(
            data = byteArrayOf(4, 5, 6),
            pcmFormat = pcmFormat,
        )
        val second = AudioSource.RawBytes(
            data = byteArrayOf(4, 5, 6),
            pcmFormat = pcmFormat,
        )

        // Act / Assert
        assertNotSame(first.data, second.data)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `raw audio byte sources compare both data and pcm format`() {
        // Arrange
        val pcmFormat = PcmFormat(
            sampleRateHz = 24_000,
            channelCount = 2,
            encoding = AudioEncoding.PCM_S16_LE,
        )
        val first = AudioSource.RawBytes(
            data = byteArrayOf(4, 5, 6),
            pcmFormat = pcmFormat,
        )
        val differentData = AudioSource.RawBytes(
            data = byteArrayOf(4, 5, 7),
            pcmFormat = pcmFormat,
        )
        val differentFormat = AudioSource.RawBytes(
            data = byteArrayOf(4, 5, 6),
            pcmFormat = pcmFormat.copy(channelCount = 1),
        )

        // Act / Assert
        assertNotEquals(first, differentData)
        assertNotEquals(first, differentFormat)
    }
}
