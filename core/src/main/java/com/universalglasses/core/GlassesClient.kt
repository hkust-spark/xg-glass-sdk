package com.universalglasses.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A minimal, unified API for AI glasses.
 *
 * Design goals:
 * - Keep app developers on a stable API surface
 * - Hide transport differences (Frame BLE messages vs Rokid Wi‑Fi P2P sync)
 * - Provide observability via state + events
 */
interface GlassesClient {
    val model: GlassesModel
    val capabilities: DeviceCapabilities

    /** Connection lifecycle state. */
    val state: StateFlow<ConnectionState>

    /** Non-fatal events (logs, warnings, tap events, etc.). */
    val events: Flow<GlassesEvent>

    /** Establish connection to the glasses (and any required side-channels like Wi‑Fi P2P). */
    suspend fun connect(): Result<Unit>

    /** Tear down connection and release resources. Safe to call multiple times. */
    suspend fun disconnect()

    /** Capture a photo and return JPEG bytes (plus metadata when available). */
    suspend fun capturePhoto(options: CaptureOptions = CaptureOptions()): Result<CapturedImage>

    /** Display text on the glasses. */
    suspend fun display(text: String, options: DisplayOptions = DisplayOptions()): Result<Unit>

    /**
     * Start microphone capture and return a session that streams audio chunks.
     *
     * Notes:
     * - This is an "audio stream" primitive. Apps can choose to save to WAV/AAC or stream to ASR.
     * - Implementations may require permissions (e.g. RECORD_AUDIO); the host app is responsible.
     */
    suspend fun startMicrophone(options: MicrophoneOptions = MicrophoneOptions()): Result<MicrophoneSession>
}


