package com.xgglass.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StateAndEventsTest {
    @Test
    fun `long press is a singleton glasses event`() {
        assertSame(GlassesEvent.LongPress, GlassesEvent.LongPress)
    }

    @Test
    fun `long press capability defaults to false and can be enabled`() {
        assertFalse(DeviceCapabilities().supportsLongPressEvents)
        assertTrue(DeviceCapabilities(supportsLongPressEvents = true).supportsLongPressEvents)
    }

    @Test
    fun `battery level carries percent value`() {
        assertEquals(42, GlassesEvent.BatteryLevel(42).percent)
    }

    @Test
    fun `battery capability defaults to false and can be enabled`() {
        assertFalse(DeviceCapabilities().supportsBatteryEvents)
        assertTrue(DeviceCapabilities(supportsBatteryEvents = true).supportsBatteryEvents)
    }

    @Test
    fun `display image capability defaults to false and can be enabled`() {
        assertFalse(DeviceCapabilities().canDisplayImages)
        assertTrue(DeviceCapabilities(canDisplayImages = true).canDisplayImages)
    }

    @Test
    fun `display image default implementation reports unsupported`() = runTest {
        val result = MinimalClient.displayImage(
            DisplayImage(
                bytes = byteArrayOf(1, 2, 3),
                encoding = ImageEncoding.PNG,
            )
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GlassesError.Unsupported)
    }

    private object MinimalClient : GlassesClient {
        override val model: GlassesModel = GlassesModel.SIMULATOR
        override val capabilities: DeviceCapabilities = DeviceCapabilities()
        override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        override val events: Flow<GlassesEvent> = MutableSharedFlow()

        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() = Unit
        override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> =
            Result.failure(GlassesError.Unsupported("capturePhoto"))
        override suspend fun display(text: String, options: DisplayOptions): Result<Unit> =
            Result.failure(GlassesError.Unsupported("display"))
        override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> =
            Result.failure(GlassesError.Unsupported("playAudio"))
        override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> =
            Result.failure(GlassesError.Unsupported("startMicrophone"))
    }
}
