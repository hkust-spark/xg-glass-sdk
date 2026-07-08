package com.xgglass.core

import kotlinx.coroutines.flow.Flow

enum class VideoFrameEncoding {
    JPEG,
    YUV_420_888,
    NV21,
    RGBA_8888,
    META_RAW,
}

enum class VideoFrameRateTier {
    SLOW,
    LOW,
    MEDIUM,
    HIGH,
    NATIVE,
}

data class VideoFormat(
    val encoding: VideoFrameEncoding,
    val width: Int? = null,
    val height: Int? = null,
    val framesPerSecond: Int? = null,
)

data class VideoStreamOptions(
    val preferredEncoding: VideoFrameEncoding = VideoFrameEncoding.JPEG,
    val preferredWidth: Int? = 640,
    val preferredHeight: Int? = 480,
    val frameRateTier: VideoFrameRateTier = VideoFrameRateTier.LOW,
)

data class VideoFrame(
    val bytes: ByteArray,
    val format: VideoFormat,
    val sequence: Long,
    val timestampMs: Long = nowMillis(),
    val rotationDegrees: Int? = null,
    val endOfStream: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoFrame) return false

        return bytes.contentEquals(other.bytes) &&
            format == other.format &&
            sequence == other.sequence &&
            timestampMs == other.timestampMs &&
            rotationDegrees == other.rotationDegrees &&
            endOfStream == other.endOfStream
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + (rotationDegrees ?: 0)
        result = 31 * result + endOfStream.hashCode()
        return result
    }
}

interface VideoStreamSession {
    val format: VideoFormat
    val frames: Flow<VideoFrame>
    val droppedFrameCount: Long
    suspend fun stop()
}
