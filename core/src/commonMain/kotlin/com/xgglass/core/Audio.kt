package com.xgglass.core

import kotlinx.coroutines.flow.Flow

/**
 * Unified audio models for microphone capture and playback across different glasses.
 *
 * Design notes:
 * - Microphone: SDK exposes "audio as a stream of chunks" (plus format metadata).
 *   Whether the app writes WAV/AAC or streams to ASR is an application-level decision.
 * - Playback: a single `playAudio(source, options)` method supports both TTS (text input)
 *   and raw audio bytes. Devices declare `canPlayTts` / `canPlayAudioBytes` independently.
 */
enum class AudioEncoding {
    /** Signed 16-bit little-endian PCM. */
    PCM_S16_LE,
    /** Signed 8-bit PCM. */
    PCM_S8,
    /** Opus frames (container-less). */
    OPUS,
}

/**
 * Input for [GlassesClient.playAudio].
 *
 * - [Tts]: text → on-device TTS engine renders speech. Requires [DeviceCapabilities.canPlayTts].
 * - [RawBytes]: pre-encoded audio bytes (WAV/MP3/PCM/etc.) → direct playback.
 *   Requires [DeviceCapabilities.canPlayAudioBytes].
 */
sealed class AudioSource {
    /** Text-to-speech. The glasses' built-in TTS engine converts [text] to audio. */
    data class Tts(val text: String) : AudioSource()

    /**
     * Raw audio bytes.
     *
     * - When [pcmFormat] is null the implementation auto-detects the container (WAV/MP3/OGG …).
     * - When [pcmFormat] is provided the bytes are treated as headerless PCM.
     */
    data class RawBytes(
        val data: ByteArray,
        val pcmFormat: PcmFormat? = null,
    ) : AudioSource() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawBytes) return false

            return data.contentEquals(other.data) &&
                pcmFormat == other.pcmFormat
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + (pcmFormat?.hashCode() ?: 0)
            return result
        }
    }
}

/** Format descriptor for headerless PCM data passed via [AudioSource.RawBytes]. */
data class PcmFormat(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val encoding: AudioEncoding = AudioEncoding.PCM_S16_LE,
)

data class PlayAudioOptions(
    /**
     * TTS speech rate multiplier (only meaningful for [AudioSource.Tts]).
     * - Rokid local TTS: doc range is roughly [0.75, 4.0].
     * Implementations may clamp/ignore if unsupported.
     */
    val speechRate: Float? = null,
    /** If true, interrupt any in-progress playback where possible. */
    val interrupt: Boolean = true,
)

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
    val timestampMs: Long = nowMillis(),
    /** True when this chunk indicates end-of-stream (may have empty bytes). */
    val endOfStream: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false

        return bytes.contentEquals(other.bytes) &&
            format == other.format &&
            sequence == other.sequence &&
            timestampMs == other.timestampMs &&
            endOfStream == other.endOfStream
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + endOfStream.hashCode()
        return result
    }
}

enum class AudioCaptureHint {
    DEFAULT,
    VOICE_ASSISTANT,
    TRANSLATION,
    CAMCORDER,
}

data class MicrophoneOptions(
    val preferredEncoding: AudioEncoding = AudioEncoding.PCM_S16_LE,
    /** Preference only; implementations may ignore if unsupported. */
    val preferredSampleRateHz: Int? = 16_000,
    /** Preference only; implementations may ignore if unsupported. */
    val preferredChannelCount: Int? = 1,
    /**
     * Optional capture routing hint. Only some devices honor it.
     *
     * - RayNeo: maps to `AudioManager.setParameters("audio_source_record=<mode>")`.
     * - Others: ignored.
     * - [AudioCaptureHint.DEFAULT] preserves the platform default capture path.
     */
    val audioHint: AudioCaptureHint = AudioCaptureHint.DEFAULT,
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
