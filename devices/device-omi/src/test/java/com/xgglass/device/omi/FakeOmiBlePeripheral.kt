package com.xgglass.device.omi

import android.Manifest
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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
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

internal class FakeOmiBlePeripheral {
    val context: Context = mockk(relaxed = true)
    val gatt: BluetoothGatt = mockk(relaxed = true)

    private val bluetoothManager: BluetoothManager = mockk()
    private val bluetoothAdapter: BluetoothAdapter = mockk()
    private val scanner: BluetoothLeScanner = mockk(relaxed = true)
    private val device: BluetoothDevice = mockk(relaxed = true)
    private val ops = Collections.synchronizedList(mutableListOf<GattOp>())
    private val services = mutableMapOf<UUID, BluetoothGattService>()
    private val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private val characteristicValues = mutableMapOf<UUID, ByteArray?>()
    private val rejectedDescriptorWrites = mutableSetOf<UUID>()

    private var scanCallback: ScanCallback? = null
    private var gattCallback: BluetoothGattCallback? = null

    init {
        mockkStatic(ContextCompat::class)
        seedBluetoothGattDescriptorConstants()
        mockAndroidScanBuilders()
        every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns bluetoothAdapter
        every { bluetoothAdapter.bluetoothLeScanner } returns scanner
        every {
            scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>())
        } answers {
            scanCallback = arg(2)
        }
        every { scanner.stopScan(any<ScanCallback>()) } just runs

        every { device.name } returns "Omi"
        every { device.address } returns "AA:BB:CC:DD:EE:01"
        every { device.connectGatt(context, false, any()) } answers {
            gattCallback = arg(2)
            gatt
        }

        every { gatt.requestMtu(any()) } returns true
        every { gatt.discoverServices() } returns true
        every { gatt.disconnect() } just runs
        every { gatt.close() } just runs
        every { gatt.setCharacteristicNotification(any(), any()) } returns true
        every { gatt.getService(any()) } answers { services[firstArg()] }
        every { gatt.writeDescriptor(any<BluetoothGattDescriptor>()) } answers {
            recordDescriptorWrite(firstArg())
        }
        every { gatt.writeDescriptor(any<BluetoothGattDescriptor>(), any()) } answers {
            if (recordDescriptorWrite(firstArg())) BluetoothStatusCodes.SUCCESS else -1
        }
        every { gatt.writeCharacteristic(any<BluetoothGattCharacteristic>()) } answers {
            val characteristic = firstArg<BluetoothGattCharacteristic>()
            recordCharacteristicWrite(characteristic, characteristic.value ?: ByteArray(0))
            true
        }
        every {
            gatt.writeCharacteristic(any<BluetoothGattCharacteristic>(), any(), any())
        } answers {
            recordCharacteristicWrite(firstArg(), secondArg())
            BluetoothStatusCodes.SUCCESS
        }
    }

    suspend fun awaitScanStarted() {
        eventually("scan callback captured") { scanCallback }
    }

    suspend fun awaitGattCallback() {
        eventually("GATT callback captured") { gattCallback }
    }

    fun rejectDescriptorWriteFor(characteristicUuid: UUID) {
        rejectedDescriptorWrites.add(characteristicUuid)
    }

    fun deliverScanResult() {
        val result = mockk<ScanResult>()
        every { result.device } returns device
        callback().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
    }

    fun connectionStateChange(
        status: Int = BluetoothGatt.GATT_SUCCESS,
        newState: Int = BluetoothProfile.STATE_CONNECTED,
    ) {
        gattCallback().onConnectionStateChange(gatt, status, newState)
    }

    fun mtuChanged(mtu: Int = 512, status: Int = BluetoothGatt.GATT_SUCCESS) {
        gattCallback().onMtuChanged(gatt, mtu, status)
    }

    fun servicesDiscovered(
        withButtonService: Boolean,
        withPhotoService: Boolean = true,
        withTimeSyncService: Boolean = true,
        status: Int = BluetoothGatt.GATT_SUCCESS,
    ) {
        configureServices(
            withButtonService = withButtonService,
            withPhotoService = withPhotoService,
            withTimeSyncService = withTimeSyncService,
        )
        gattCallback().onServicesDiscovered(gatt, status)
    }

    suspend fun awaitDescriptorWrite(characteristicUuid: UUID): DescriptorWrite =
        eventually("descriptor write for $characteristicUuid") {
            recordedOps().filterIsInstance<DescriptorWrite>().lastOrNull {
                it.characteristicUuid == characteristicUuid
            }
        }

    suspend fun awaitCharacteristicWrite(characteristicUuid: UUID): CharacteristicWrite =
        eventually("characteristic write for $characteristicUuid") {
            recordedOps().filterIsInstance<CharacteristicWrite>().lastOrNull {
                it.characteristicUuid == characteristicUuid
            }
        }

    fun ackDescriptorWrite(characteristicUuid: UUID, status: Int = BluetoothGatt.GATT_SUCCESS) {
        val op = recordedOps().filterIsInstance<DescriptorWrite>().lastOrNull {
            it.characteristicUuid == characteristicUuid
        } ?: error("No descriptor write recorded for $characteristicUuid")
        gattCallback().onDescriptorWrite(gatt, op.descriptor, status)
    }

    fun notifyCharacteristic(characteristicUuid: UUID, value: ByteArray) {
        val characteristic = characteristics[characteristicUuid]
            ?: error("No characteristic configured for $characteristicUuid")
        characteristicValues[characteristicUuid] = value
        gattCallback().onCharacteristicChanged(gatt, characteristic)
    }

    fun recordedOps(): List<GattOp> = synchronized(ops) { ops.toList() }

    fun hasDescriptorWrite(characteristicUuid: UUID): Boolean =
        recordedOps().filterIsInstance<DescriptorWrite>().any { it.characteristicUuid == characteristicUuid }

    fun hasCharacteristicWrite(characteristicUuid: UUID): Boolean =
        recordedOps().filterIsInstance<CharacteristicWrite>().any { it.characteristicUuid == characteristicUuid }

    private fun callback(): ScanCallback = scanCallback ?: error("Scan callback not captured")

    private fun gattCallback(): BluetoothGattCallback = gattCallback ?: error("GATT callback not captured")

    private fun configureServices(
        withButtonService: Boolean,
        withPhotoService: Boolean,
        withTimeSyncService: Boolean,
    ) {
        services.clear()
        characteristics.clear()
        characteristicValues.clear()

        val audioCharacteristics = mutableMapOf(
            OmiGlassesClient.AUDIO_DATA_UUID to characteristic(OmiGlassesClient.AUDIO_DATA_UUID),
        )
        if (withPhotoService) {
            audioCharacteristics[OmiGlassesClient.PHOTO_CONTROL_UUID] =
                characteristic(OmiGlassesClient.PHOTO_CONTROL_UUID)
            audioCharacteristics[OmiGlassesClient.PHOTO_DATA_UUID] =
                characteristic(OmiGlassesClient.PHOTO_DATA_UUID, withCccd = true)
        }
        services[OmiGlassesClient.AUDIO_SERVICE_UUID] = service(audioCharacteristics)

        if (withTimeSyncService) {
            services[OmiGlassesClient.TIME_SYNC_SERVICE_UUID] = service(
                mapOf(
                    OmiGlassesClient.TIME_SYNC_WRITE_UUID to
                        characteristic(OmiGlassesClient.TIME_SYNC_WRITE_UUID),
                ),
            )
        }

        if (withButtonService) {
            services[OmiGlassesClient.BUTTON_SERVICE_UUID] = service(
                mapOf(
                    OmiGlassesClient.BUTTON_TRIGGER_UUID to
                        characteristic(OmiGlassesClient.BUTTON_TRIGGER_UUID, withCccd = true),
                ),
            )
        }
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

    private fun recordDescriptorWrite(descriptor: BluetoothGattDescriptor): Boolean {
        val characteristicUuid = descriptor.characteristic?.uuid
            ?: error("Descriptor write had no characteristic")
        ops.add(DescriptorWrite(characteristicUuid, descriptor))
        return !rejectedDescriptorWrites.remove(characteristicUuid)
    }

    private fun recordCharacteristicWrite(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        ops.add(CharacteristicWrite(characteristic.uuid, value.copyOf()))
    }

    private fun mockAndroidScanBuilders() {
        mockkConstructor(ParcelUuid::class)
        mockkConstructor(ScanFilter.Builder::class)
        mockkConstructor(ScanSettings.Builder::class)
        every { anyConstructed<ScanFilter.Builder>().setServiceUuid(any()) } answers {
            self as ScanFilter.Builder
        }
        every { anyConstructed<ScanFilter.Builder>().setDeviceName(any()) } answers {
            self as ScanFilter.Builder
        }
        every { anyConstructed<ScanFilter.Builder>().build() } returns mockk(relaxed = true)
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

    sealed interface GattOp

    data class DescriptorWrite(
        val characteristicUuid: UUID,
        val descriptor: BluetoothGattDescriptor,
    ) : GattOp

    data class CharacteristicWrite(
        val characteristicUuid: UUID,
        val value: ByteArray,
    ) : GattOp

    private companion object {
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
