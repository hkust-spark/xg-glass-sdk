package com.universalglasses.core

enum class GlassesModel {
    FRAME,
    ROKID,
    RAYNEO,
    SIMULATOR,
}

data class DeviceCapabilities(
    val canCapturePhoto: Boolean = true,
    val canDisplayText: Boolean = true,
    val canRecordAudio: Boolean = false,
    val supportsTapEvents: Boolean = false,
    val supportsStreamingTextUpdates: Boolean = false,
)

data class CaptureOptions(
    /**
     * A unified "quality" knob.
     * - Rokid: mapped to JPEG quality 0..100 (default 90)
     * - Frame: mapped to SDK quality index or preset (implementation-defined)
     */
    val quality: Int? = null,
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
)


