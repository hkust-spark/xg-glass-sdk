package com.xgglass.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VideoApiDefaultsTest {
    @Test
    fun deviceCapabilitiesDefaultToNoVideoStreaming() {
        val capabilities = DeviceCapabilities()

        assertFalse(capabilities.canStreamVideo)
        assertTrue(capabilities.supportedVideoFormats.isEmpty())
    }

    @Test
    fun glassesClientDefaultStartVideoStreamReturnsUnsupported() = runTest {
        val result = MinimalClient.startVideoStream()

        assertTrue(result.isFailure)
        assertIs<GlassesError.Unsupported>(result.exceptionOrNull())
    }

    private object MinimalClient : GlassesClient {
        override val model: GlassesModel = GlassesModel.SIMULATOR
        override val capabilities: DeviceCapabilities = DeviceCapabilities()
        override val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val events: Flow<GlassesEvent>
            get() = kotlinx.coroutines.flow.emptyFlow()

        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() = Unit
        override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> =
            Result.failure(GlassesError.Unsupported("capture"))

        override suspend fun display(text: String, options: DisplayOptions): Result<Unit> =
            Result.failure(GlassesError.Unsupported("display"))

        override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> =
            Result.failure(GlassesError.Unsupported("audio"))

        override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> =
            Result.failure(GlassesError.Unsupported("microphone"))
    }
}
