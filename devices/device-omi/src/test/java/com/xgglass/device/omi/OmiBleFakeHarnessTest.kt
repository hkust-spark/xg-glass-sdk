package com.xgglass.device.omi

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import com.xgglass.core.CaptureOptions
import com.xgglass.core.ConnectionState
import com.xgglass.core.GlassesEvent
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test

class OmiBleFakeHarnessTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test(timeout = 10_000)
    fun happyConnectWithButtonServiceSerializesButtonCccdBeforeTimeSync() = runBlocking {
        val fake = FakeOmiBlePeripheral()
        val client = OmiGlassesClient(fake.context)

        val result = connectThroughServices(client, fake, withButtonService = true)

        assertTrue(result.isSuccess)
        assertEquals(ConnectionState.Connected, client.state.value)
        val buttonWrite = fake.awaitDescriptorWrite(OmiGlassesClient.BUTTON_TRIGGER_UUID)
        assertFalse(fake.hasCharacteristicWrite(OmiGlassesClient.TIME_SYNC_WRITE_UUID))

        fake.ackDescriptorWrite(OmiGlassesClient.BUTTON_TRIGGER_UUID)
        val timeSyncWrite = fake.awaitCharacteristicWrite(OmiGlassesClient.TIME_SYNC_WRITE_UUID)

        val ops = fake.recordedOps()
        assertTrue(ops.indexOf(buttonWrite) < ops.indexOf(timeSyncWrite))
        assertTrue(client.capabilities.supportsTapEvents)
    }

    @Test(timeout = 10_000)
    fun connectWithoutButtonServiceLeavesTapCapabilityFalseAndStillTimeSyncs() = runBlocking {
        val fake = FakeOmiBlePeripheral()
        val client = OmiGlassesClient(fake.context)

        val result = connectThroughServices(client, fake, withButtonService = false)

        assertTrue(result.isSuccess)
        assertEquals(ConnectionState.Connected, client.state.value)
        fake.awaitCharacteristicWrite(OmiGlassesClient.TIME_SYNC_WRITE_UUID)
        assertFalse(client.capabilities.supportsTapEvents)
        assertFalse(fake.hasDescriptorWrite(OmiGlassesClient.BUTTON_TRIGGER_UUID))
    }

    @Test(timeout = 10_000)
    fun connectWithoutBatteryServiceLeavesBatteryCapabilityFalse() = runBlocking {
        val fake = FakeOmiBlePeripheral()
        val client = OmiGlassesClient(fake.context)

        val result = connectThroughServices(client, fake, withButtonService = false, withBatteryService = false)

        assertTrue(result.isSuccess)
        fake.awaitCharacteristicWrite(OmiGlassesClient.TIME_SYNC_WRITE_UUID)
        assertFalse(client.capabilities.supportsBatteryEvents)
        assertFalse(fake.hasDescriptorWrite(OmiGlassesClient.BATTERY_LEVEL_UUID))
    }

    @Test(timeout = 10_000)
    fun batteryReadAndNotificationsEmitClampedBatteryEvents() = runBlocking {
        val fake = FakeOmiBlePeripheral()
        val client = OmiGlassesClient(fake.context)

        val events = mutableListOf<GlassesEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                if (event is GlassesEvent.BatteryLevel) {
                    events.add(event)
                }
            }
        }

        val result = connectThroughServices(client, fake, withButtonService = false, withBatteryService = true)

        assertTrue(result.isSuccess)
        assertTrue(client.capabilities.supportsBatteryEvents)
        fake.awaitDescriptorWrite(OmiGlassesClient.BATTERY_LEVEL_UUID)
        fake.ackDescriptorWrite(OmiGlassesClient.BATTERY_LEVEL_UUID)
        fake.awaitCharacteristicRead(OmiGlassesClient.BATTERY_LEVEL_UUID)
        fake.ackCharacteristicRead(OmiGlassesClient.BATTERY_LEVEL_UUID, byteArrayOf(88.toByte()))
        eventually { if (events.size >= 1) events else null }

        fake.notifyCharacteristic(OmiGlassesClient.BATTERY_LEVEL_UUID, byteArrayOf(0xFF.toByte()))
        eventually { if (events.size >= 2) events else null }

        assertEquals(
            listOf(GlassesEvent.BatteryLevel(88), GlassesEvent.BatteryLevel(100)),
            events.take(2),
        )
        collector.cancelAndJoin()
    }

    @Test(timeout = 10_000)
    fun rejectedBatteryCccdRollsBackBatteryCapability() = runBlocking {
        val fake = FakeOmiBlePeripheral()
        val client = OmiGlassesClient(fake.context)
        fake.rejectDescriptorWriteFor(OmiGlassesClient.BATTERY_LEVEL_UUID)

        val result = connectThroughServices(client, fake, withButtonService = false, withBatteryService = true)

        assertTrue(result.isSuccess)
        fake.awaitDescriptorWrite(OmiGlassesClient.BATTERY_LEVEL_UUID)
        fake.awaitCharacteristicWrite(OmiGlassesClient.TIME_SYNC_WRITE_UUID)
        assertFalse(client.capabilities.supportsBatteryEvents)
    }

    @Test(timeout = 10_000)
    fun buttonNotificationsEmitTapAndLongPressButIgnoreCodeFive() = runBlocking {
        val fake = FakeOmiBlePeripheral()
        val client = OmiGlassesClient(fake.context)
        connectAndEnableButtons(client, fake)

        val events = mutableListOf<GlassesEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                if (event !is GlassesEvent.Log) {
                    events.add(event)
                }
            }
        }

        fake.notifyCharacteristic(OmiGlassesClient.BUTTON_TRIGGER_UUID, buttonPacket(1))
        fake.notifyCharacteristic(OmiGlassesClient.BUTTON_TRIGGER_UUID, buttonPacket(2))
        fake.notifyCharacteristic(OmiGlassesClient.BUTTON_TRIGGER_UUID, buttonPacket(3))
        eventually { if (events.size >= 3) events else null }

        assertEquals(
            listOf(GlassesEvent.Tap(1), GlassesEvent.Tap(2), GlassesEvent.LongPress),
            events.take(3),
        )

        fake.notifyCharacteristic(OmiGlassesClient.BUTTON_TRIGGER_UUID, buttonPacket(5))
        val fourth = withTimeoutOrNull(100) {
            while (events.size < 4) {
                yield()
                delay(1)
            }
            events[3]
        }
        assertNull(fourth)
        collector.cancelAndJoin()
    }

    @Test(timeout = 10_000)
    fun capturePhotoWaitsBehindUnackedButtonCccdThenReceivesChunkedJpeg() = runBlocking {
        val fake = FakeOmiBlePeripheral()
        val client = OmiGlassesClient(fake.context)
        connectThroughServices(client, fake, withButtonService = true)
        fake.awaitDescriptorWrite(OmiGlassesClient.BUTTON_TRIGGER_UUID)

        val capture = async {
            client.capturePhoto(CaptureOptions(timeoutMs = 5_000))
        }
        repeat(10) {
            yield()
        }
        assertFalse(capture.isCompleted)
        assertFalse(fake.hasDescriptorWrite(OmiGlassesClient.PHOTO_DATA_UUID))

        fake.ackDescriptorWrite(OmiGlassesClient.BUTTON_TRIGGER_UUID)
        fake.awaitCharacteristicWrite(OmiGlassesClient.TIME_SYNC_WRITE_UUID)
        fake.awaitDescriptorWrite(OmiGlassesClient.PHOTO_DATA_UUID)
        fake.ackDescriptorWrite(OmiGlassesClient.PHOTO_DATA_UUID)
        fake.awaitCharacteristicWrite(OmiGlassesClient.PHOTO_CONTROL_UUID)

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        fake.notifyCharacteristic(
            OmiGlassesClient.PHOTO_DATA_UUID,
            byteArrayOf(0x00, 0x00) + jpeg,
        )
        fake.notifyCharacteristic(
            OmiGlassesClient.PHOTO_DATA_UUID,
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
        )

        val captured = capture.await().getOrThrow()
        assertContentEquals(jpeg, captured.jpegBytes)
    }

    @Test(timeout = 10_000)
    fun rejectedButtonCccdRollsBackTapCapability() = runBlocking {
        val fake = FakeOmiBlePeripheral()
        val client = OmiGlassesClient(fake.context)
        fake.rejectDescriptorWriteFor(OmiGlassesClient.BUTTON_TRIGGER_UUID)

        val result = connectThroughServices(client, fake, withButtonService = true)

        assertTrue(result.isSuccess)
        fake.awaitDescriptorWrite(OmiGlassesClient.BUTTON_TRIGGER_UUID)
        fake.awaitCharacteristicWrite(OmiGlassesClient.TIME_SYNC_WRITE_UUID)
        assertFalse(client.capabilities.supportsTapEvents)
    }

    @Test(timeout = 10_000)
    fun disconnectResetsStateAndCapabilities() = runBlocking {
        val fake = FakeOmiBlePeripheral()
        val client = OmiGlassesClient(fake.context)
        connectAndEnableButtons(client, fake)

        assertTrue(client.capabilities.canCapturePhoto)
        assertTrue(client.capabilities.supportsTapEvents)

        fake.connectionStateChange(
            status = BluetoothGatt.GATT_SUCCESS,
            newState = BluetoothProfile.STATE_DISCONNECTED,
        )

        assertEquals(ConnectionState.Disconnected, client.state.value)
        assertFalse(client.capabilities.canCapturePhoto)
        assertFalse(client.capabilities.supportsTapEvents)
    }

    private suspend fun connectAndEnableButtons(
        client: OmiGlassesClient,
        fake: FakeOmiBlePeripheral,
    ) {
        val result = connectThroughServices(client, fake, withButtonService = true)
        assertTrue(result.isSuccess)
        fake.awaitDescriptorWrite(OmiGlassesClient.BUTTON_TRIGGER_UUID)
        fake.ackDescriptorWrite(OmiGlassesClient.BUTTON_TRIGGER_UUID)
        fake.awaitCharacteristicWrite(OmiGlassesClient.TIME_SYNC_WRITE_UUID)
        assertTrue(client.capabilities.supportsTapEvents)
    }

    private suspend fun connectThroughServices(
        client: OmiGlassesClient,
        fake: FakeOmiBlePeripheral,
        withButtonService: Boolean,
        withBatteryService: Boolean = false,
    ): Result<Unit> = coroutineScope {
        val connected = async { client.connect() }
        fake.awaitScanStarted()
        fake.deliverScanResult()
        fake.awaitGattCallback()
        fake.connectionStateChange()
        fake.mtuChanged()
        fake.servicesDiscovered(withButtonService = withButtonService, withBatteryService = withBatteryService)
        val result = withTimeout(5_000) { connected.await() }
        result
    }

    private suspend fun <T : Any> eventually(block: () -> T?): T =
        withTimeout(5_000) {
            while (true) {
                val value = block()
                if (value != null) return@withTimeout value
                yield()
                delay(1)
            }
            error("unreachable")
        }

    private fun buttonPacket(code: Int): ByteArray = byteArrayOf(
        (code and 0xFF).toByte(),
        ((code ushr 8) and 0xFF).toByte(),
        ((code ushr 16) and 0xFF).toByte(),
        ((code ushr 24) and 0xFF).toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
    )
}
