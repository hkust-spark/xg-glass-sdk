package com.xgglass.core

/** Device families supported by the unified xg.glass API. */
enum class GlassesModel {
    /** Brilliant Labs Frame. */
    FRAME,

    /** Meta wearables, including Ray-Ban Meta glasses. */
    META,

    /** Rokid glasses. */
    ROKID,

    /** RayNeo glasses running the xg.glass on-glasses host. */
    RAYNEO,

    /** INMO Air3 glasses running the xg.glass on-glasses runtime adapter. */
    INMO,

    /** Preview scaffold for Google Android XR devices. */
    ANDROID_XR,

    /** Local simulator adapter for app development without glasses hardware. */
    SIMULATOR,

    /** Omi Glass BLE device. */
    OMI,

    /** Even Realities G1 dual-BLE glasses. */
    EVEN,
}

data class DeviceCapabilities(
    /** True when apps may call [GlassesClient.capturePhoto]. */
    val canCapturePhoto: Boolean = true,

    /** True when apps may call [GlassesClient.display]. */
    val canDisplayText: Boolean = true,

    /** True when apps may call [GlassesClient.startMicrophone]. */
    val canRecordAudio: Boolean = false,

    /** Device can render text-to-speech via a built-in TTS engine (e.g. Rokid). */
    val canPlayTts: Boolean = false,

    /** Device can play raw/encoded audio bytes on the glasses speaker. */
    val canPlayAudioBytes: Boolean = false,

    /** True when apps may observe physical tap gestures in [GlassesClient.events]. */
    val supportsTapEvents: Boolean = false,

    /** True when repeated [DisplayMode.APPEND] or forced updates are suitable for streamed text. */
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

enum class DisplayMode {
    /** Replace the current display contents with the new text. */
    REPLACE,

    /** Append the new text to the current display contents when the adapter supports it. */
    APPEND,
}

data class DisplayOptions(
    val mode: DisplayMode = DisplayMode.REPLACE,
    /** If true, bypass any throttling/dedup logic in the adapter. */
    val force: Boolean = false,
)

data class CapturedImage(
    /** JPEG-encoded image bytes. */
    val jpegBytes: ByteArray,

    /** Capture timestamp in epoch milliseconds. */
    val timestampMs: Long = nowMillis(),

    /** Optional vendor-reported image width in pixels. */
    val width: Int? = null,

    /** Optional vendor-reported image height in pixels. */
    val height: Int? = null,

    /** Optional vendor-reported clockwise image rotation in degrees. */
    val rotationDegrees: Int? = null,

    /** Adapter model that produced this image. */
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
