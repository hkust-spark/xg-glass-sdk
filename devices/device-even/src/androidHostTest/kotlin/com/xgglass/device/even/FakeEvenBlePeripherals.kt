package com.xgglass.device.even

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

internal class FakeEvenBlePeripherals {
    val context: Context = mockk(relaxed = true)

    private val bluetoothManager: BluetoothManager = mockk()
    private val bluetoothAdapter: BluetoothAdapter = mockk()
    private val scanner: BluetoothLeScanner = mockk(relaxed = true)
    private val peripherals = EvenArm.values().associateWith { ArmPeripheral(it) }
    private val ops = Collections.synchronizedList(mutableListOf<GattOp>())

    private var scanCallback: ScanCallback? = null

    init {
        mockkStatic(ContextCompat::class)
        seedBluetoothGattDescriptorConstants()
        mockAndroidScanSettingsBuilder()
        every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns bluetoothAdapter
        every { bluetoothAdapter.isEnabled } returns true
        every { bluetoothAdapter.bluetoothLeScanner } returns scanner
        every { scanner.startScan(null, any<ScanSettings>(), any<ScanCallback>()) } answers {
            scanCallback = arg(2)
        }
        every { scanner.stopScan(any<ScanCallback>()) } just runs

        peripherals.values.forEach { peripheral ->
            configureDevice(peripheral)
            configureGatt(peripheral)
        }
    }

    suspend fun awaitScanStarted() {
        eventually("scan callback captured") { scanCallback }
    }

    suspend fun awaitGattCallback(arm: EvenArm) {
        eventually("$arm GATT callback captured") { peripheral(arm).gattCallback }
    }

    fun deliverScanResult(arm: EvenArm) {
        val result = mockk<ScanResult>()
        every { result.device } returns peripheral(arm).device
        callback().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
    }

    fun connectionStateChange(
        arm: EvenArm,
        status: Int = BluetoothGatt.GATT_SUCCESS,
        newState: Int = BluetoothProfile.STATE_CONNECTED,
    ) {
        val peripheral = peripheral(arm)
        peripheral.gattCallback().onConnectionStateChange(peripheral.gatt, status, newState)
    }

    fun servicesDiscovered(
        arm: EvenArm,
        withNordicService: Boolean = true,
        status: Int = BluetoothGatt.GATT_SUCCESS,
    ) {
        val peripheral = peripheral(arm)
        peripheral.configureServices(withNordicService = withNordicService)
        peripheral.gattCallback().onServicesDiscovered(peripheral.gatt, status)
    }

    suspend fun awaitDescriptorWrite(arm: EvenArm): DescriptorWrite =
        eventually("$arm descriptor write") {
            recordedOps().filterIsInstance<DescriptorWrite>().lastOrNull {
                it.arm == arm && it.characteristicUuid == NORDIC_UART_RX_UUID
            }
        }

    fun ackDescriptorWrite(arm: EvenArm, status: Int = BluetoothGatt.GATT_SUCCESS) {
        val peripheral = peripheral(arm)
        val op = recordedOps().filterIsInstance<DescriptorWrite>().lastOrNull {
            it.arm == arm && it.characteristicUuid == NORDIC_UART_RX_UUID
        } ?: error("No RX descriptor write recorded for $arm")
        peripheral.gattCallback().onDescriptorWrite(peripheral.gatt, op.descriptor, status)
    }

    suspend fun awaitMtuRequest(arm: EvenArm): MtuRequest =
        eventually("$arm MTU request") {
            recordedOps().filterIsInstance<MtuRequest>().lastOrNull { it.arm == arm }
        }

    fun mtuChanged(arm: EvenArm, mtu: Int = 251, status: Int = BluetoothGatt.GATT_SUCCESS) {
        val peripheral = peripheral(arm)
        peripheral.gattCallback().onMtuChanged(peripheral.gatt, mtu, status)
    }

    suspend fun awaitCommandWrite(
        arm: EvenArm,
        command: Int,
        afterIndex: Int = 0,
    ): CharacteristicWrite =
        eventually("$arm command 0x${command.toString(16)} write") {
            recordedOps().drop(afterIndex).filterIsInstance<CharacteristicWrite>().lastOrNull {
                it.arm == arm && it.command == command
            }
        }

    suspend fun awaitAnyCommandWrite(
        arm: EvenArm,
        commands: Set<Int>,
        afterIndex: Int = 0,
    ): CharacteristicWrite =
        eventually("$arm command write") {
            recordedOps().drop(afterIndex).filterIsInstance<CharacteristicWrite>().lastOrNull {
                it.arm == arm && it.command in commands
            }
        }

    fun notifyCharacteristic(arm: EvenArm, value: ByteArray) {
        val peripheral = peripheral(arm)
        val characteristic = peripheral.characteristics[NORDIC_UART_RX_UUID]
            ?: error("No RX characteristic configured for $arm")
        peripheral.characteristicValues[NORDIC_UART_RX_UUID] = value
        peripheral.gattCallback().onCharacteristicChanged(peripheral.gatt, characteristic)
    }

    fun ackCommandWrite(op: CharacteristicWrite) {
        val response = if (op.command == EvenHeartbeatProtocol.COMMAND) {
            op.value.copyOf()
        } else {
            byteArrayOf(op.command.toByte(), EvenResponses.SUCCESS.toByte())
        }
        notifyCharacteristic(op.arm, response)
    }

    fun hasCommandWrite(arm: EvenArm, command: Int): Boolean =
        recordedOps().filterIsInstance<CharacteristicWrite>().any {
            it.arm == arm && it.command == command
        }

    fun opIndex(op: GattOp): Int = recordedOps().indexOfFirst { it === op }

    fun recordedOps(): List<GattOp> = synchronized(ops) { ops.toList() }

    private fun configureDevice(peripheral: ArmPeripheral) {
        every { peripheral.device.name } returns deviceName(peripheral.arm)
        every { peripheral.device.address } returns deviceAddress(peripheral.arm)
        every { peripheral.device.connectGatt(context, false, any()) } answers {
            peripheral.gattCallback = arg(2)
            peripheral.gatt
        }
    }

    private fun configureGatt(peripheral: ArmPeripheral) {
        every { peripheral.gatt.discoverServices() } returns true
        every { peripheral.gatt.requestMtu(any()) } answers {
            ops.add(MtuRequest(peripheral.arm, firstArg()))
            true
        }
        every { peripheral.gatt.disconnect() } just runs
        every { peripheral.gatt.close() } just runs
        every { peripheral.gatt.setCharacteristicNotification(any(), any()) } returns true
        every { peripheral.gatt.getService(any()) } answers { peripheral.services[firstArg()] }
        every { peripheral.gatt.writeDescriptor(any<BluetoothGattDescriptor>()) } answers {
            recordDescriptorWrite(peripheral.arm, firstArg())
        }
        every { peripheral.gatt.writeDescriptor(any<BluetoothGattDescriptor>(), any()) } answers {
            if (recordDescriptorWrite(peripheral.arm, firstArg())) BluetoothStatusCodes.SUCCESS else -1
        }
        every { peripheral.gatt.writeCharacteristic(any<BluetoothGattCharacteristic>()) } answers {
            val characteristic = firstArg<BluetoothGattCharacteristic>()
            recordCharacteristicWrite(
                arm = peripheral.arm,
                characteristic = characteristic,
                value = characteristic.value ?: ByteArray(0),
            )
            true
        }
        every {
            peripheral.gatt.writeCharacteristic(any<BluetoothGattCharacteristic>(), any(), any())
        } answers {
            recordCharacteristicWrite(
                arm = peripheral.arm,
                characteristic = firstArg(),
                value = secondArg(),
            )
            BluetoothStatusCodes.SUCCESS
        }
    }

    private fun recordDescriptorWrite(
        arm: EvenArm,
        descriptor: BluetoothGattDescriptor,
    ): Boolean {
        val characteristicUuid = descriptor.characteristic?.uuid
            ?: error("$arm descriptor write had no characteristic")
        ops.add(DescriptorWrite(arm, characteristicUuid, descriptor))
        return true
    }

    private fun recordCharacteristicWrite(
        arm: EvenArm,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        ops.add(CharacteristicWrite(arm, characteristic.uuid, value.copyOf()))
    }

    private fun peripheral(arm: EvenArm): ArmPeripheral =
        peripherals[arm] ?: error("No fake peripheral for $arm")

    private fun callback(): ScanCallback = scanCallback ?: error("Scan callback not captured")

    private fun mockAndroidScanSettingsBuilder() {
        mockkConstructor(ScanSettings.Builder::class)
        every { anyConstructed<ScanSettings.Builder>().setScanMode(any()) } answers {
            self as ScanSettings.Builder
        }
        every { anyConstructed<ScanSettings.Builder>().build() } returns mockk(relaxed = true)
    }

    private fun seedBluetoothGattDescriptorConstants() {
        setStaticByteArray("ENABLE_NOTIFICATION_VALUE", byteArrayOf(0x01, 0x00))
        setStaticByteArray("ENABLE_INDICATION_VALUE", byteArrayOf(0x02, 0x00))
        setStaticByteArray("DISABLE_NOTIFICATION_VALUE", byteArrayOf(0x00, 0x00))
    }

    private fun setStaticByteArray(fieldName: String, value: ByteArray) {
        val field = BluetoothGattDescriptor::class.java.getDeclaredField(fieldName)
        if (field.get(null) != null) return

        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }
        val unsafe = unsafeField.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod(
            "putObject",
            Any::class.java,
            Long::class.javaPrimitiveType,
            Any::class.java,
        ).invoke(unsafe, base, offset, value)
    }

    private suspend fun <T : Any> eventually(
        label: String,
        timeoutMs: Long = 5_000,
        block: () -> T?,
    ): T = withTimeout(timeoutMs) {
        while (true) {
            val value = block()
            if (value != null) return@withTimeout value
            yield()
            delay(1)
        }
        error("unreachable: $label")
    }

    private inner class ArmPeripheral(
        val arm: EvenArm,
    ) {
        val device: BluetoothDevice = mockk(relaxed = true)
        val gatt: BluetoothGatt = mockk(relaxed = true)
        val services = mutableMapOf<UUID, BluetoothGattService>()
        val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()
        val characteristicValues = mutableMapOf<UUID, ByteArray?>()

        var gattCallback: BluetoothGattCallback? = null

        fun gattCallback(): BluetoothGattCallback =
            gattCallback ?: error("$arm GATT callback not captured")

        fun configureServices(withNordicService: Boolean) {
            services.clear()
            characteristics.clear()
            characteristicValues.clear()

            if (!withNordicService) return

            val tx = characteristic(NORDIC_UART_TX_UUID)
            val rx = characteristic(NORDIC_UART_RX_UUID, withCccd = true)
            services[NORDIC_UART_SERVICE_UUID] = service(
                mapOf(
                    NORDIC_UART_TX_UUID to tx,
                    NORDIC_UART_RX_UUID to rx,
                ),
            )
        }

        private fun service(
            characteristicsByUuid: Map<UUID, BluetoothGattCharacteristic>,
        ): BluetoothGattService {
            val service = mockk<BluetoothGattService>(relaxed = true)
            every { service.getCharacteristic(any()) } answers { characteristicsByUuid[firstArg()] }
            return service
        }

        private fun characteristic(
            uuid: UUID,
            withCccd: Boolean = false,
        ): BluetoothGattCharacteristic {
            val characteristic = mockk<BluetoothGattCharacteristic>(relaxed = true)
            val descriptors = mutableMapOf<UUID, BluetoothGattDescriptor>()
            characteristics[uuid] = characteristic
            every { characteristic.uuid } returns uuid
            every { characteristic.value } answers { characteristicValues[uuid] }
            every { characteristic.value = any() } answers {
                characteristicValues[uuid] = firstArg()
            }
            every { characteristic.writeType = any() } just runs
            every { characteristic.setValue(any<ByteArray>()) } answers {
                characteristicValues[uuid] = firstArg()
                true
            }
            every { characteristic.getDescriptor(any()) } answers { descriptors[firstArg()] }
            if (withCccd) {
                val descriptor = descriptor(characteristic)
                descriptors[CLIENT_CHARACTERISTIC_CONFIG_UUID] = descriptor
            }
            return characteristic
        }

        private fun descriptor(
            characteristic: BluetoothGattCharacteristic,
        ): BluetoothGattDescriptor {
            val descriptor = mockk<BluetoothGattDescriptor>(relaxed = true)
            var value: ByteArray? = null
            every { descriptor.uuid } returns CLIENT_CHARACTERISTIC_CONFIG_UUID
            every { descriptor.characteristic } returns characteristic
            every { descriptor.value } answers { value }
            every { descriptor.setValue(any()) } answers {
                value = firstArg()
                true
            }
            return descriptor
        }
    }

    sealed interface GattOp {
        val arm: EvenArm
    }

    data class DescriptorWrite(
        override val arm: EvenArm,
        val characteristicUuid: UUID,
        val descriptor: BluetoothGattDescriptor,
    ) : GattOp

    data class CharacteristicWrite(
        override val arm: EvenArm,
        val characteristicUuid: UUID,
        val value: ByteArray,
    ) : GattOp {
        val command: Int
            get() = value.first().toInt() and 0xFF
    }

    data class MtuRequest(
        override val arm: EvenArm,
        val mtu: Int,
    ) : GattOp

    private companion object {
        val NORDIC_UART_SERVICE_UUID: UUID = UUID.fromString(EvenBleUuids.NORDIC_UART_SERVICE)
        val NORDIC_UART_TX_UUID: UUID = UUID.fromString(EvenBleUuids.NORDIC_UART_TX)
        val NORDIC_UART_RX_UUID: UUID = UUID.fromString(EvenBleUuids.NORDIC_UART_RX)
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString(EvenBleUuids.CLIENT_CHARACTERISTIC_CONFIG)

        fun deviceName(arm: EvenArm): String = when (arm) {
            EvenArm.LEFT -> "G1_45_L_92333"
            EvenArm.RIGHT -> "G1_45_R_92334"
        }

        fun deviceAddress(arm: EvenArm): String = when (arm) {
            EvenArm.LEFT -> "AA:BB:CC:DD:EE:10"
            EvenArm.RIGHT -> "AA:BB:CC:DD:EE:11"
        }
    }
}
