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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.xgglass.core.AudioChunk
import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioFormat
import com.xgglass.core.AudioSource
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PlayAudioOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException

/**
 * Omi implementation of [GlassesClient].
 *
 * This client mirrors the host-side Omi SDK behavior:
 * - Connects to devices advertising as "Omi" over BLE.
 * - Subscribes to the Omi Audio Service to receive audio packets.
 *
 * Notes:
 * - Current public docs expose audio-focused capabilities only; camera/display/audio playback
 *   are not available over the documented BLE services, so those APIs return [GlassesError.Unsupported].
 * - Audio packets are surfaced as an [AudioEncoding.OPUS] or PCM stream depending on the codec
 *   reported by the device.
 */
class OmiGlassesClient(
    private val context: Context,
    private val options: OmiOptions = OmiOptions(),
) : BaseGlassesClient(
    initialCapabilities = DeviceCapabilities(
        canCapturePhoto = false,
        canDisplayText = false,
        canRecordAudio = true,
        canPlayTts = false,
        canPlayAudioBytes = false,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = false,
    ),
    eventBufferOverflow = BufferOverflow.SUSPEND,
) {

    override val model: GlassesModel = GlassesModel.OMI

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // GATT plumbing
    private var bluetoothGatt: BluetoothGatt? = null
    private var audioCharacteristic: BluetoothGattCharacteristic? = null
    private var photoControlCharacteristic: BluetoothGattCharacteristic? = null
    private var photoDataCharacteristic: BluetoothGattCharacteristic? = null
    private var timeSyncCharacteristic: BluetoothGattCharacteristic? = null

    // Photo retrieval state
    private val photoLock = Any()
    private var photoBuffer = mutableListOf<Byte>()
    private var lastPhotoChunkId = -1
    private var photoContinuation: kotlinx.coroutines.CancellableContinuation<Result<CapturedImage>>? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    @Volatile
    private var audioSession: MicrophoneSession? = null
    private var pendingConnectAdapter: BluetoothAdapter? = null

    override val rethrowConnectCancellation: Boolean = true

    override suspend fun beforeConnect(): Result<Unit>? {
        pendingConnectAdapter = null
        if (!hasBlePermission()) {
            return Result.failure(GlassesError.PermissionDenied)
        }

        val adapter = bluetoothAdapter
            ?: return Result.failure(GlassesError.Transport("Bluetooth adapter not available"))

        pendingConnectAdapter = adapter
        return null
    }

    override suspend fun doConnect() {
        val adapter = pendingConnectAdapter
            ?: bluetoothAdapter
            ?: throw GlassesError.Transport("Bluetooth adapter not available")
        pendingConnectAdapter = null

        emitLog("Omi: scanning for devices with Omi service...")

        withContext(Dispatchers.IO) {
            try {
                withTimeout(options.connectTimeoutMs) {
                    val device = scanFirstOmiDevice(adapter)
                    emitLog("Omi: found device ${device.address} (${device.name}), initiating GATT connection...")

                    connectGatt(device)
                }
            } catch (_: TimeoutCancellationException) {
                closeGatt()
                throw GlassesError.Timeout("Omi connect")
            } catch (ce: CancellationException) {
                closeGatt()
                throw ce
            }
        }
    }

    override fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError) ?: GlassesError.Transport("Omi connect failed: ${error.message}", error)
    }

    override suspend fun disconnect() {
        try {
            audioSession?.stop()
        } catch (_: Exception) {
        }
        audioSession = null
        closeGatt()
        resetCapabilities()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        val ctrlChar = photoControlCharacteristic ?: return Result.failure(GlassesError.Unsupported("Photo control not available"))
        val dataChar = photoDataCharacteristic ?: return Result.failure(GlassesError.Unsupported("Photo data characteristic not found"))
        val gatt = bluetoothGatt ?: return Result.failure(GlassesError.NotConnected)

        return kotlinx.coroutines.withTimeoutOrNull(options.timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                synchronized(photoLock) {
                    photoContinuation = cont
                    photoBuffer.clear()
                    lastPhotoChunkId = -1
                }

                // Enable notifications for photo data
                gatt.setCharacteristicNotification(dataChar, true)
                dataChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let { desc ->
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    gatt.writeDescriptor(desc)
                }

                scope.launch {
                    // Small delay to ensure descriptor write is processed if needed, 
                    // though in production we should wait for onDescriptorWrite.
                    kotlinx.coroutines.delay(200) 
                    
                    synchronized(photoLock) {
                        lastPhotoChunkId = -1
                    }
                    // Write 0x05 to trigger single photo (like React Native SDK)
                    ctrlChar.value = byteArrayOf(0x05.toByte())
                    gatt.writeCharacteristic(ctrlChar)
                    emitLog("Omi: capture photo command sent [0x05]")
                }

                cont.invokeOnCancellation {
                    synchronized(photoLock) {
                        if (photoContinuation === cont) {
                            photoContinuation = null
                        }
                        photoBuffer.clear()
                        lastPhotoChunkId = -1
                    }
                }
            }
        } ?: Result.failure(GlassesError.Timeout("capturePhoto"))
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        return Result.failure(
            GlassesError.Unsupported("Omi Glass BLE SDK does not offer display primitives.")
        )
    }

    override suspend fun playAudio(
        source: AudioSource,
        options: PlayAudioOptions,
    ): Result<Unit> {
        return Result.failure(
            GlassesError.Unsupported("Omi integration is audio-input-only; playback to glasses speakers is not supported.")
        )
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (_state.value !is ConnectionState.Connected) {
            return Result.failure(GlassesError.NotConnected)
        }
        if (!hasBlePermission()) {
            return Result.failure(GlassesError.PermissionDenied)
        }
        // Defensive: clear stale session that was already stopped but not cleaned up
        val existing = audioSession
        if (existing != null) {
            try {
                existing.stop()
            } catch (_: Exception) {}
            audioSession = null
        }

        return try {
            val session = createAudioSession(options)
            audioSession = session
            
            // Ensure notifications are enabled for the audio characteristic
            audioCharacteristic?.let { char ->
                bluetoothGatt?.setCharacteristicNotification(char, true)
                char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let { desc ->
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    bluetoothGatt?.writeDescriptor(desc)
                }
            }
            
            Result.success(session)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Omi startMicrophone failed: ${e.message}", e))
        }
    }

    private fun hasBlePermission(): Boolean {
        val sdk = android.os.Build.VERSION.SDK_INT
        return if (sdk >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private suspend fun connectGatt(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            emitLog("Omi: GATT connected, requesting MTU 512...")
                            // Request larger MTU for better audio/photo performance
                            gatt.requestMtu(512)
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            emitLog("Omi: GATT disconnected")
                            updatePhotoCapability(false)
                            _state.value = ConnectionState.Disconnected
                        }
                    } else {
                        emitLog("Omi: GATT error status=$status")
                        updatePhotoCapability(false)
                        _state.value = ConnectionState.Disconnected
                        if (cont.isActive) cont.resumeWithException(GlassesError.Transport("GATT error $status"))
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    emitLog("Omi: MTU updated to $mtu (status=$status), discovering services...")
                    gatt.discoverServices()
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        emitLog("Omi: services discovered")
                        val omiService = gatt.getService(AUDIO_SERVICE_UUID)
                        audioCharacteristic = omiService?.getCharacteristic(AUDIO_DATA_UUID)
                        photoControlCharacteristic = omiService?.getCharacteristic(PHOTO_CONTROL_UUID)
                        photoDataCharacteristic = omiService?.getCharacteristic(PHOTO_DATA_UUID)
                        updatePhotoCapability(
                            photoControlCharacteristic != null &&
                                photoDataCharacteristic != null
                        )
                        
                        val timeSyncService = gatt.getService(TIME_SYNC_SERVICE_UUID)
                        timeSyncCharacteristic = timeSyncService?.getCharacteristic(TIME_SYNC_WRITE_UUID)

                        // If we have services, we are effectively connected
                        _state.value = ConnectionState.Connected
                        
                        // Perform time sync handshake (silent if service missing)
                        scope.launch {
                            performTimeSync(gatt)
                        }

                        if (cont.isActive) cont.resume(Unit, onCancellation = null)
                    } else {
                        updatePhotoCapability(false)
                        if (cont.isActive) cont.resumeWithException(GlassesError.Transport("Service discovery failed $status"))
                    }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (characteristic.uuid == AUDIO_DATA_UUID) {
                        val data = characteristic.value
                        if (data != null && data.size > 3) {
                            // Header: 2 bytes index, 1 byte sub-index
                            // Omi firmware: audio_packet_buffer[0] = index & 0xFF, [1] = index >> 8, [2] = sub-index
                            val audioData = data.sliceArray(3 until data.size)
                            audioSession?.let { session ->
                                if (session is OmiMicrophoneSession) {
                                    session.emitAudio(audioData)
                                }
                            }
                        }
                    } else if (characteristic.uuid == PHOTO_DATA_UUID) {
                        val data = characteristic.value ?: return
                        if (data.size >= 2) {
                            val isEof = (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xFF
                            if (isEof) {
                                // End of photo reached
                                val (cont, rawBytes) = synchronized(photoLock) {
                                    val pending = photoContinuation
                                    photoContinuation = null
                                    val bytes = photoBuffer.toByteArray()
                                    photoBuffer.clear()
                                    lastPhotoChunkId = -1
                                    pending to bytes
                                }
                                val jpegStart = findJpegStart(rawBytes)
                                val jpegBytes = if (jpegStart > 0) {
                                    emitLog("Omi: stripping $jpegStart leading bytes before JPEG header")
                                    rawBytes.copyOfRange(jpegStart, rawBytes.size)
                                } else {
                                    rawBytes 
                                }
                                val captured = CapturedImage(jpegBytes = jpegBytes, sourceModel = GlassesModel.OMI)
                                cont?.resumeWith(Result.success(Result.success(captured)))
                                emitLog("Omi: photo received (${jpegBytes.size} bytes)")
                            } else {
                                val packetId = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                                val payload = data.sliceArray(2 until data.size)

                                val warning = synchronized(photoLock) {
                                    if (packetId == 0) {
                                        photoBuffer.clear()
                                        lastPhotoChunkId = 0
                                        // Hardware may prepend a 1-byte orientation header before JPEG
                                        photoBuffer.addAll(payload.toList())
                                        null
                                    } else if (packetId == lastPhotoChunkId + 1) {
                                        lastPhotoChunkId = packetId
                                        photoBuffer.addAll(payload.toList())
                                        null
                                    } else {
                                        val message = "Omi: WARN dropped photo chunk (expected ${lastPhotoChunkId + 1}, got $packetId)"
                                        lastPhotoChunkId = packetId
                                        photoBuffer.addAll(payload.toList())
                                        message
                                    }
                                }
                                if (warning != null) {
                                    emitLog(warning)
                                }
                            }
                        }
                    }
                }
            })

            cont.invokeOnCancellation {
                updatePhotoCapability(false)
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }
    }

    private fun updatePhotoCapability(canCapturePhoto: Boolean) {
        updateCapabilities { it.copy(canCapturePhoto = canCapturePhoto) }
    }

    private fun closeGatt() {
        updatePhotoCapability(false)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private suspend fun performTimeSync(gatt: BluetoothGatt) {
        val char = timeSyncCharacteristic ?: return
        try {
            val epochSeconds = System.currentTimeMillis() / 1000
            val bytes = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(epochSeconds.toInt()).array()
            char.setValue(bytes)
            gatt.writeCharacteristic(char)
            emitLog("Omi: time sync sent ($epochSeconds)")
        } catch (e: Exception) {
            emitLog("Omi: time sync failed: ${e.message}")
        }
    }

    private suspend fun scanFirstOmiDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val scanner = adapter.bluetoothLeScanner
            ?: throw GlassesError.Transport("Bluetooth LE scanner not available")

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(AUDIO_SERVICE_UUID))
                    .build(),
                ScanFilter.Builder()
                    .setDeviceName("Omi")
                    .build(),
                ScanFilter.Builder()
                    .setDeviceName("OMI Glass")
                    .build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val seenAddresses = mutableSetOf<String>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val device = result?.device ?: return
                    if (!seenAddresses.add(device.address)) return
                    emitLog("Omi: discovered ${device.name} (${device.address})")
                    try {
                        scanner.stopScan(this)
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(device))
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    try {
                        scanner.stopScan(this)
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) {
                        cont.resumeWithException(
                            GlassesError.Transport("Omi BLE scan failed: $errorCode")
                        )
                    }
                }
            }

            cont.invokeOnCancellation {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) {
                }
            }

            try {
                scanner.startScan(filters, settings, callback)
            } catch (e: Exception) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        GlassesError.Transport("Omi startScan failed: ${e.message}", e)
                    )
                }
            }
        }
    }

    private fun createAudioSession(options: MicrophoneOptions): MicrophoneSession {
        val fmt = AudioFormat(
            encoding = AudioEncoding.OPUS, // Omi default is OPUS 32kbps
            sampleRateHz = 16_000,
            channelCount = 1,
        )

        val audioFlow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 128)
        val seq = AtomicLong(0)

        return object : OmiMicrophoneSession {
            override val format: AudioFormat = fmt
            override val audio: Flow<AudioChunk> = audioFlow

            override fun emitAudio(data: ByteArray) {
                audioFlow.tryEmit(
                    AudioChunk(
                        bytes = data,
                        format = fmt,
                        sequence = seq.incrementAndGet(),
                    )
                )
            }

            override suspend fun stop() {
                audioSession = null
                audioFlow.tryEmit(
                    AudioChunk(
                        bytes = ByteArray(0),
                        format = fmt,
                        sequence = seq.incrementAndGet(),
                        endOfStream = true,
                    )
                )
            }
        }
    }

    private interface OmiMicrophoneSession : MicrophoneSession {
        fun emitAudio(data: ByteArray)
    }

    /** Find the index of the JPEG SOI marker (FFD8) in the byte array. Returns -1 if not found. */
    private fun findJpegStart(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if (data[i] == (0xFF).toByte() && data[i + 1] == (0xD8).toByte()) {
                return i
            }
        }
        return -1
    }

    data class OmiOptions(
        val connectTimeoutMs: Long = 30_000,
    )

    companion object {
        // BLE UUIDs from the Omi report; kept for future BLE GATT implementation.
        val AUDIO_SERVICE_UUID: UUID =
            UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
        val AUDIO_DATA_UUID: UUID =
            UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
        val AUDIO_CODEC_UUID: UUID =
            UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")

        val BATTERY_SERVICE_UUID: UUID =
            UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_LEVEL_UUID: UUID =
            UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        val DEVICE_INFO_SERVICE_UUID: UUID =
            UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")

        val PHOTO_CONTROL_UUID: UUID =
            UUID.fromString("19B10006-E8F2-537E-4F6C-D104768A1214")
        val PHOTO_DATA_UUID: UUID =
            UUID.fromString("19B10005-E8F2-537E-4F6C-D104768A1214")

        val TIME_SYNC_SERVICE_UUID: UUID =
            UUID.fromString("19B10030-E8F2-537E-4F6C-D104768A1214")
        val TIME_SYNC_WRITE_UUID: UUID =
            UUID.fromString("19B10031-E8F2-537E-4F6C-D104768A1214")
    }
}
