package com.universalglasses.core

enum class GlassesModel {
    FRAME,
    META,
    ROKID,
    RAYNEO,
    ANDROID_XR,
    SIMULATOR,
    OMI,
}

data class DeviceCapabilities(
    val canCapturePhoto: Boolean = true,
    val canDisplayText: Boolean = true,
    val canRecordAudio: Boolean = false,
    /** Device can render text-to-speech via a built-in TTS engine (e.g. Rokid). */
    val canPlayTts: Boolean = false,
    /** Device can play raw/encoded audio bytes on the glasses speaker. */
    val canPlayAudioBytes: Boolean = false,
    val supportsTapEvents: Boolean = false,
    val supportsStreamingTextUpdates: Boolean = false,
)

enum class PhotoQuality {
    LOWEST,
    LOW,
    MEDIUM,
    HIGH,
    HIGHEST,
}

data class CaptureOptions(
    /**
     * Unified photo quality preference.
     * Implementations map this to their native JPEG quality or preset.
     * [PhotoQuality.HIGH] preserves the previous default behavior.
     */
    val photoQuality: PhotoQuality = PhotoQuality.HIGH,
    /**
     * A unified "target size" knob.
     * - Rokid: mapped to width/height
     * - Frame: mapped to square resolution (implementation-defined)
     */
    val targetWidth: Int? = null,
    val targetHeight: Int? = null,
    val timeoutMs: Long = 30_000,
)

enum class DisplayMode { REPLACE, APPEND }

data class DisplayOptions(
    val mode: DisplayMode = DisplayMode.REPLACE,
    /** If true, bypass any throttling/dedup logic in the adapter. */
    val force: Boolean = false,
)

data class CapturedImage(
    val jpegBytes: ByteArray,
    val timestampMs: Long = System.currentTimeMillis(),
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    val sourceModel: GlassesModel,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedImage) return false

        return jpegBytes.contentEquals(other.jpegBytes) &&
            timestampMs == other.timestampMs &&
            width == other.width &&
            height == other.height &&
            rotationDegrees == other.rotationDegrees &&
            sourceModel == other.sourceModel
    }

    override fun hashCode(): Int {
        var result = jpegBytes.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        result = 31 * result + (rotationDegrees ?: 0)
        result = 31 * result + sourceModel.hashCode()
        return result
    }
}
