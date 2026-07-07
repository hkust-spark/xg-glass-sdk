package com.xgglass.device.even

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import com.xgglass.core.ConnectionState
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesEvent
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EvenBleFakeHarnessTest {

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun happyDualArmConnectReachesConnectedAndInitialCapabilities() = runBlocking<Unit> {
        val fake = FakeEvenBlePeripherals()
        val client = EvenGlassesClient(fake.context)
        try {
            val result = connectThroughServices(client, fake)

            assertTrue(result.isSuccess)
            assertEquals(ConnectionState.Connected, client.state.value)
            assertFalse(client.capabilities.canCapturePhoto)
            assertTrue(client.capabilities.canDisplayText)
            assertTrue(client.capabilities.canRecordAudio)
            assertTrue(client.capabilities.supportsTapEvents)
            assertTrue(client.capabilities.supportsLongPressEvents)
            assertTrue(client.capabilities.supportsStreamingTextUpdates)
            fake.awaitCommandWrite(EvenArm.LEFT, EvenInitProtocol.androidInitialPacket().command)
            fake.awaitCommandWrite(EvenArm.RIGHT, EvenInitProtocol.androidInitialPacket().command)
        } finally {
            disconnectQuietly(client)
        }
    }

    @Test
    fun oneArmGattFailureFailsConnectAndLeavesErrorState() = runBlocking<Unit> {
        val fake = FakeEvenBlePeripherals()
        val client = EvenGlassesClient(fake.context)

        val result = coroutineScope {
            val connected = async { client.connect() }
            fake.awaitScanStarted()
            fake.deliverScanResult(EvenArm.LEFT)
            fake.deliverScanResult(EvenArm.RIGHT)
            fake.awaitGattCallback(EvenArm.LEFT)
            fake.awaitGattCallback(EvenArm.RIGHT)

            connectOneArm(fake, EvenArm.LEFT)
            fake.connectionStateChange(
                arm = EvenArm.RIGHT,
                status = 133,
                newState = BluetoothProfile.STATE_DISCONNECTED,
            )

            withTimeout(5_000) { connected.await() }
        }

        assertTrue(result.isFailure)
        assertIs<GlassesError.Transport>(result.exceptionOrNull())
        assertIs<ConnectionState.Error>(client.state.value)
        assertFalse(fake.hasCommandWrite(EvenArm.LEFT, EvenInitProtocol.androidInitialPacket().command))
        assertFalse(fake.hasCommandWrite(EvenArm.RIGHT, EvenInitProtocol.androidInitialPacket().command))
    }

    @Test
    fun tapAndEvenAiBeginNotificationsEmitTapAndLongPress() = runBlocking<Unit> {
        val fake = FakeEvenBlePeripherals()
        val client = EvenGlassesClient(fake.context)
        try {
            val result = connectThroughServices(client, fake)
            assertTrue(result.isSuccess)

            val events = mutableListOf<GlassesEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.collect { event ->
                    if (event !is GlassesEvent.Log && event !is GlassesEvent.Warning) {
                        events.add(event)
                    }
                }
            }

            fake.notifyCharacteristic(
                EvenArm.LEFT,
                stateEvent(EvenStateEvents.SINGLE_TAP),
            )
            fake.notifyCharacteristic(
                EvenArm.RIGHT,
                stateEvent(EvenStateEvents.EVEN_AI_START),
            )
            eventually { if (events.size >= 2) events else null }

            assertEquals(
                listOf(GlassesEvent.Tap(1), GlassesEvent.LongPress),
                events.take(2),
            )
            collector.cancelAndJoin()
        } finally {
            disconnectQuietly(client)
        }
    }

    @Test
    fun displayWritesNonEmptyTextFrameToBothArmsAfterAcks() = runBlocking<Unit> {
        val fake = FakeEvenBlePeripherals()
        val client = EvenGlassesClient(fake.context)
        try {
            val result = connectThroughServices(client, fake)
            assertTrue(result.isSuccess)

            val displayResult = displayAndAck(client, fake, "Hi")

            assertTrue(displayResult.isSuccess)
        } finally {
            disconnectQuietly(client)
        }
    }

    @Test
    fun gattDisconnectOnOneArmMovesToDisconnectedAndKeepsDefaultCapabilities() = runBlocking<Unit> {
        val fake = FakeEvenBlePeripherals()
        val client = EvenGlassesClient(fake.context)

        val result = connectThroughServices(client, fake)
        assertTrue(result.isSuccess)

        fake.connectionStateChange(
            arm = EvenArm.LEFT,
            status = BluetoothGatt.GATT_SUCCESS,
            newState = BluetoothProfile.STATE_DISCONNECTED,
        )
        eventually { client.state.value as? ConnectionState.Disconnected }

        assertEquals(ConnectionState.Disconnected, client.state.value)
        assertFalse(client.capabilities.canCapturePhoto)
        assertTrue(client.capabilities.canDisplayText)
        assertTrue(client.capabilities.canRecordAudio)
        assertTrue(client.capabilities.supportsTapEvents)
        assertTrue(client.capabilities.supportsLongPressEvents)
    }

    private suspend fun connectThroughServices(
        client: EvenGlassesClient,
        fake: FakeEvenBlePeripherals,
    ): Result<Unit> = coroutineScope {
        val connected = async { client.connect() }
        fake.awaitScanStarted()
        fake.deliverScanResult(EvenArm.LEFT)
        fake.deliverScanResult(EvenArm.RIGHT)
        fake.awaitGattCallback(EvenArm.LEFT)
        fake.awaitGattCallback(EvenArm.RIGHT)
        connectOneArm(fake, EvenArm.LEFT)
        connectOneArm(fake, EvenArm.RIGHT)
        withTimeout(5_000) { connected.await() }
    }

    private suspend fun connectOneArm(
        fake: FakeEvenBlePeripherals,
        arm: EvenArm,
    ) {
        fake.connectionStateChange(arm)
        fake.servicesDiscovered(arm)
        fake.awaitDescriptorWrite(arm)
        fake.ackDescriptorWrite(arm)
        fake.awaitMtuRequest(arm)
        fake.mtuChanged(arm)
    }

    private suspend fun displayAndAck(
        client: EvenGlassesClient,
        fake: FakeEvenBlePeripherals,
        text: String,
    ): Result<Unit> = coroutineScope {
        val startIndex = fake.recordedOps().size
        val display = async { client.display(text, DisplayOptions()) }
        var afterIndex = startIndex

        while (true) {
            val left = fake.awaitAnyCommandWrite(
                arm = EvenArm.LEFT,
                commands = setOf(EvenHeartbeatProtocol.COMMAND, EvenTextProtocol.COMMAND),
                afterIndex = afterIndex,
            )
            afterIndex = fake.opIndex(left) + 1

            if (left.command == EvenHeartbeatProtocol.COMMAND) {
                fake.ackCommandWrite(left)
                val rightHeartbeat = fake.awaitCommandWrite(
                    arm = EvenArm.RIGHT,
                    command = EvenHeartbeatProtocol.COMMAND,
                    afterIndex = afterIndex,
                )
                afterIndex = fake.opIndex(rightHeartbeat) + 1
                fake.ackCommandWrite(rightHeartbeat)
                continue
            }

            assertEquals(EvenTextProtocol.COMMAND, left.command)
            assertTrue(left.value.isNotEmpty())
            fake.ackCommandWrite(left)
            val right = fake.awaitCommandWrite(
                arm = EvenArm.RIGHT,
                command = EvenTextProtocol.COMMAND,
                afterIndex = afterIndex,
            )
            assertTrue(right.value.isNotEmpty())
            fake.ackCommandWrite(right)
            return@coroutineScope withTimeout(5_000) { display.await() }
        }
        error("unreachable")
    }

    private suspend fun disconnectQuietly(client: EvenGlassesClient) {
        runCatching { client.disconnect() }
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

    private fun stateEvent(code: Int): ByteArray =
        byteArrayOf(EvenStateEvents.COMMAND.toByte(), code.toByte())

    private val ByteArray.command: Int
        get() = first().toInt() and 0xFF
}
