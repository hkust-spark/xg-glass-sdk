package com.universalglasses.core

import kotlinx.coroutines.flow.Flow

/**
 * Unified audio models for microphone capture across different glasses.
 *
 * Design notes:
 * - The SDK exposes "audio as a stream of chunks" (plus format metadata).
 * - Whether the app writes WAV/AAC files or streams to ASR is an application-level decision.
 */
enum class AudioEncoding {
    /** Signed 16-bit little-endian PCM. */
    PCM_S16_LE,
    /** Signed 8-bit PCM. */
    PCM_S8,
    /** Opus frames (container-less). */
    OPUS,
}

data class AudioFormat(
    val encoding: AudioEncoding,
    /** Optional because some vendor SDK callbacks do not expose it. */
    val sampleRateHz: Int? = null,
    /** Optional because some vendor SDK callbacks do not expose it. */
    val channelCount: Int? = null,
)

data class AudioChunk(
    val bytes: ByteArray,
    val format: AudioFormat,
    val sequence: Long,
    val timestampMs: Long = System.currentTimeMillis(),
    /** True when this chunk indicates end-of-stream (may have empty bytes). */
    val endOfStream: Boolean = false,
)

data class MicrophoneOptions(
    val preferredEncoding: AudioEncoding = AudioEncoding.PCM_S16_LE,
    /** Preference only; implementations may ignore if unsupported. */
    val preferredSampleRateHz: Int? = 16_000,
    /** Preference only; implementations may ignore if unsupported. */
    val preferredChannelCount: Int? = 1,
    /**
     * Vendor-specific mode/hint string.
     *
     * - RayNeo: maps to `AudioManager.setParameters("audio_source_record=<mode>")`, e.g.
     *   "voiceassistant" / "translation" / "camcorder" (see RayNeo docs).
     * - Others: ignored.
     */
    val vendorMode: String? = null,
)

/**
 * A running microphone capture session.
 *
 * - [audio] is a hot stream: it should emit once capture starts, even if multiple collectors attach.
 * - Call [stop] to release underlying resources (Bluetooth streams / AudioRecord / vendor listeners).
 */
interface MicrophoneSession {
    val format: AudioFormat
    val audio: Flow<AudioChunk>
    suspend fun stop()
}


