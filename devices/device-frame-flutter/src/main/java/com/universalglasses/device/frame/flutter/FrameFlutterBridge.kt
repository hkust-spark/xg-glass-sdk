package com.universalglasses.device.frame.flutter

import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.AudioChunk
import com.universalglasses.core.AudioFormat
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.MicrophoneOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bridge interface implemented by the HOST APP, backed by its embedded Flutter module.
 *
 * Why this exists:
 * - This SDK wants to support Frame via Flutter without forcing a Flutter dependency on all consumers.
 * - So this module defines the contract; the app wires it to MethodChannel/EventChannel.
 */
interface FrameFlutterBridge {
    /** Bridge-side connection state (mirrors the Flutter module state). */
    val state: StateFlow<FrameFlutterState>

    /** Bridge-side events from Flutter (logs/tap/etc.). */
    val events: Flow<GlassesEvent>

    /** Raw microphone audio chunks pushed from the embedded Flutter module. */
    val microphone: Flow<AudioChunk>

    suspend fun connect(): Result<Unit>
    suspend fun disconnect()

    suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage>
    suspend fun displayText(text: String, options: DisplayOptions): Result<Unit>

    suspend fun startMicrophone(options: MicrophoneOptions): Result<AudioFormat>
    suspend fun stopMicrophone(): Result<Unit>
}

sealed class FrameFlutterState {
    data object Disconnected : FrameFlutterState()
    data object Connecting : FrameFlutterState()
    data object Connected : FrameFlutterState()
    data class Error(val message: String) : FrameFlutterState()
}


