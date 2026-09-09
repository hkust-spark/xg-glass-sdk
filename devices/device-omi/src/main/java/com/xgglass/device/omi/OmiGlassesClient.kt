package com.xgglass.device.omi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
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
import com.xgglass.core.GlassesEvent
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PlayAudioOptions
import com.xgglass.device.omi.protocol.OmiAudioCodec
import com.xgglass.device.omi.protocol.OmiButtonEvent
import com.xgglass.device.omi.protocol.OmiButtonEvents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
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
 * - Photo capture is available when its control/data characteristics are discovered.
 * - Display and speaker playback are not provided by these BLE services.
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
        // Only legacy firmware may emit code 3; advertising long-press would over-claim support.
        supportsLongPressEvents = false,
        supportsBatteryEvents = false,
        supportsStreamingTextUpdates = false,
    ),
    eventBufferOverflow = BufferOverflow.SUSPEND,
) {

    override val model: GlassesModel = GlassesModel.OMI

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Android allows one outstanding GATT read/write. Hold this through its callback.
    private val gattWriteMutex = Mutex()
    private val microphoneMutex = Mutex()
    private val captureMutex = Mutex()
    private val operationLock = Any()
    private var pendingOperation: PendingOperation? = null
    private val connectionLock = Any()
    private var connectionReady = false

    private enum class OperationKind { READ, WRITE, DESCRIPTOR }
    private data class PendingOperation(
        val gatt: BluetoothGatt,
        val kind: OperationKind,
        val uuid: UUID,
        val continuation: CancellableContinuation<ByteArray>,
    )

    // GATT plumbing
    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    private var audioCharacteristic: BluetoothGattCharacteristic? = null
    private var audioCodecCharacteristic: BluetoothGattCharacteristic? = null
    private var photoControlCharacteristic: BluetoothGattCharacteristic? = null
    private var photoDataCharacteristic: BluetoothGattCharacteristic? = null
    private var timeSyncCharacteristic: BluetoothGattCharacteristic? = null
    private var buttonCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null

    // Photo retrieval state
    private val photoLock = Any()
    private var photoBuffer = mutableListOf<Byte>()
    private var lastPhotoChunkId = -1
    private var photoContinuation: kotlinx.coroutines.CancellableContinuation<Result<CapturedImage>>? = null

    private val ignoredButtonReleaseEvents = AtomicLong(0)
    private val droppedButtonEvents = AtomicLong(0)

    private val droppedBatteryEvents = AtomicLong(0)

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

    override fun publishConnectedState(): Boolean = synchronized(connectionLock) {
        // doConnect resumes on its caller's dispatcher. A disconnect can run before that
        // continuation reaches BaseGlassesClient's final state publication.
        if (!connectionReady || bluetoothGatt == null) false else super.publishConnectedState()
    }

    override suspend fun disconnect() {
        // Closing the link cancels all subscriptions without waiting for a remote CCCD reply.
        closeGatt()
        resetCapabilities()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        val ctrlChar = photoControlCharacteristic ?: return Result.failure(GlassesError.Unsupported("Photo control not available"))
        val dataChar = photoDataCharacteristic ?: return Result.failure(GlassesError.Unsupported("Photo data characteristic not found"))
        val gatt = bluetoothGatt ?: return Result.failure(GlassesError.NotConnected)

        if (!captureMutex.tryLock()) return Result.failure(GlassesError.Busy)
        return try {
            withTimeoutOrNull(options.timeoutMs) {
                val notificationsEnabled = gattWriteMutex.withLock {
                    enablePhotoDataNotifications(gatt, dataChar)
                }
                if (!notificationsEnabled) {
                    return@withTimeoutOrNull Result.failure(
                        GlassesError.Transport("Photo notification descriptor write failed")
                    )
                }

                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    synchronized(photoLock) {
                        photoContinuation = cont
                        photoBuffer.clear()
                        lastPhotoChunkId = -1
                    }

                    val commandJob = scope.launch {
                        try {
                            gattWriteMutex.withLock {
                                awaitGattOperation(gatt, OperationKind.WRITE, ctrlChar.uuid) {
                                    writeCharacteristicCompat(gatt, ctrlChar, byteArrayOf(0x05))
                                }
                            }
                            emitLog("Omi: capture photo command confirmed [0x05]")
                        } catch (error: Exception) {
                            synchronized(photoLock) {
                                if (photoContinuation === cont) photoContinuation = null
                            }
                            if (cont.isActive) cont.resumeWith(Result.success(Result.failure(error)))
                        }
                    }

                    cont.invokeOnCancellation {
                        commandJob.cancel()
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure((error as? GlassesError) ?: GlassesError.Transport("Omi capture failed", error))
        } finally {
            captureMutex.unlock()
        }
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

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> =
        microphoneMutex.withLock {
            if (_state.value !is ConnectionState.Connected) {
                return@withLock Result.failure(GlassesError.NotConnected)
            }
            if (!hasBlePermission()) return@withLock Result.failure(GlassesError.PermissionDenied)
            if (audioSession != null) return@withLock Result.failure(GlassesError.Busy)
            val gatt = bluetoothGatt ?: return@withLock Result.failure(GlassesError.NotConnected)
            val audio = audioCharacteristic
                ?: return@withLock Result.failure(GlassesError.Unsupported("Omi audio characteristic not found"))
            val codec = audioCodecCharacteristic
                ?: return@withLock Result.failure(GlassesError.Unsupported("Omi audio codec characteristic not found"))
            try {
                val session = gattWriteMutex.withLock {
                    val format = OmiAudioCodec.format(awaitGattOperation(gatt, OperationKind.READ, codec.uuid) {
                        gatt.readCharacteristic(codec)
                    })
                    enableNotifications(gatt, audio)
                    val session = createAudioSession(format)
                    // Publish before the final link check: disconnect either sees and finishes
                    // this session, or the check below rolls back a session published too late.
                    audioSession = session
                    if (gatt !== bluetoothGatt || _state.value !is ConnectionState.Connected) {
                        if (audioSession === session) audioSession = null
                        (session as OmiMicrophoneSession).finish()
                        throw GlassesError.NotConnected
                    }
                    session
                }
                Result.success(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure((error as? GlassesError)
                    ?: GlassesError.Transport("Omi startMicrophone failed: ${error.message}", error))
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
                    if (gatt !== bluetoothGatt) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            emitLog("Omi: GATT connected, requesting MTU 512...")
                            // Request larger MTU for better audio/photo performance
                            gatt.requestMtu(512)
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            emitLog("Omi: GATT disconnected")
                            closeGatt()
                            _state.value = ConnectionState.Disconnected
                        }
                    } else {
                        emitLog("Omi: GATT error status=$status")
                        closeGatt()
                        _state.value = ConnectionState.Disconnected
                        if (cont.isActive) cont.resumeWithException(GlassesError.Transport("GATT error $status"))
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (gatt !== bluetoothGatt) return
                    emitLog("Omi: MTU updated to $mtu (status=$status), discovering services...")
                    gatt.discoverServices()
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (gatt !== bluetoothGatt) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        synchronized(connectionLock) {
                            if (gatt !== bluetoothGatt) return
                            emitLog("Omi: services discovered")
                            val omiService = gatt.getService(AUDIO_SERVICE_UUID)
                            audioCharacteristic = omiService?.getCharacteristic(AUDIO_DATA_UUID)
                            audioCodecCharacteristic = omiService?.getCharacteristic(AUDIO_CODEC_UUID)
                            updateCapabilities { it.copy(canRecordAudio = audioCharacteristic != null && audioCodecCharacteristic != null) }
                            photoControlCharacteristic = omiService?.getCharacteristic(PHOTO_CONTROL_UUID)
                            photoDataCharacteristic = omiService?.getCharacteristic(PHOTO_DATA_UUID)
                            updatePhotoCapability(
                                photoControlCharacteristic != null &&
                                    photoDataCharacteristic != null
                            )
                        
                            val timeSyncService = gatt.getService(TIME_SYNC_SERVICE_UUID)
                            timeSyncCharacteristic = timeSyncService?.getCharacteristic(TIME_SYNC_WRITE_UUID)

                            val buttonService = gatt.getService(BUTTON_SERVICE_UUID)
                            buttonCharacteristic = buttonService?.getCharacteristic(BUTTON_TRIGGER_UUID)
                            // Capability is gated on service/characteristic discovery, never name/model.
                            updateButtonCapability(buttonCharacteristic != null)

                            val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                            batteryCharacteristic = batteryService?.getCharacteristic(BATTERY_LEVEL_UUID)
                            // Source: Bluetooth SIG Battery Service 1.1 defines Battery Service 0x180F
                            // and Battery Level 0x2A19; read is mandatory and notify is optional.
                            updateBatteryCapability(batteryCharacteristic != null)

                            connectionReady = true
                        }
                        
                        // Serialize connect-time GATT writes: button CCCD, battery CCCD/read, then time sync.
                        scope.launch {
                            try {
                                gattWriteMutex.withLock {
                                    buttonCharacteristic?.let { characteristic ->
                                        val subscribed = withTimeoutOrNull(BUTTON_NOTIFY_TIMEOUT_MS) {
                                            enableButtonNotifications(gatt, characteristic)
                                        } == true
                                        if (subscribed) {
                                            emitLog("Omi: button notifications enabled")
                                        } else {
                                            updateButtonCapability(false)
                                            emitWarn("Omi: button notification descriptor write failed")
                                        }
                                    }
                                    batteryCharacteristic?.let { characteristic ->
                                        val subscribed = withTimeoutOrNull(BATTERY_NOTIFY_TIMEOUT_MS) {
                                            enableBatteryNotifications(gatt, characteristic)
                                        } == true
                                        if (subscribed) {
                                            emitLog("Omi: battery notifications enabled")
                                            val read = withTimeoutOrNull(BATTERY_READ_TIMEOUT_MS) {
                                                readBatteryLevel(gatt, characteristic)
                                            } == true
                                            if (!read) {
                                                emitWarn("Omi: initial battery read failed")
                                            }
                                        } else {
                                            updateBatteryCapability(false)
                                            emitWarn("Omi: battery notification descriptor write failed")
                                        }
                                    }
                                    performTimeSync(gatt)
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                emitWarn("Omi: connection setup failed: ${error.message}")
                            }
                        }

                        if (cont.isActive) cont.resume(Unit, onCancellation = null)
                    } else {
                        updatePhotoCapability(false)
                        updateButtonCapability(false)
                        updateBatteryCapability(false)
                        if (cont.isActive) cont.resumeWithException(GlassesError.Transport("Service discovery failed $status"))
                    }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (gatt !== bluetoothGatt) return
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
                    } else if (characteristic.uuid == BUTTON_TRIGGER_UUID) {
                        val data = characteristic.value ?: return
                        handleButtonPacket(data)
                    } else if (characteristic.uuid == BATTERY_LEVEL_UUID) {
                        val data = characteristic.value ?: return
                        handleBatteryPacket(data)
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    characteristic.value = value
                    onCharacteristicChanged(gatt, characteristic)
                }

                @Deprecated("Deprecated by Android API 33, kept for pre-33 callbacks")
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
                }

                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    if (gatt !== bluetoothGatt) return
                    if (characteristic.uuid == BATTERY_LEVEL_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                        handleBatteryPacket(value)
                    }
                    completeOperation(gatt, OperationKind.READ, characteristic.uuid, status, value)
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    completeOperation(gatt, OperationKind.WRITE, characteristic.uuid, status)
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                        descriptor.characteristic?.uuid?.let { uuid ->
                            completeOperation(gatt, OperationKind.DESCRIPTOR, uuid, status)
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

    private fun updateButtonCapability(supportsTapEvents: Boolean) {
        updateCapabilities {
            it.copy(
                supportsTapEvents = supportsTapEvents,
                // Only legacy firmware emits code 3; advertising long-press would over-claim support.
                supportsLongPressEvents = false,
            )
        }
    }

    private fun updateBatteryCapability(supportsBatteryEvents: Boolean) {
        updateCapabilities { it.copy(supportsBatteryEvents = supportsBatteryEvents) }
    }

    private fun handleButtonPacket(packet: ByteArray) {
        when (val event = OmiButtonEvents.parse(packet)) {
            is OmiButtonEvent.Tap -> emitButtonEvent(GlassesEvent.Tap(event.count))
            OmiButtonEvent.LongPress -> emitButtonEvent(GlassesEvent.LongPress)
            is OmiButtonEvent.Ignored -> {
                if (event.code == OmiButtonEvents.BUTTON_RELEASE) {
                    val count = ignoredButtonReleaseEvents.incrementAndGet()
                    if (shouldRateLimitLog(count)) {
                        emitLog("Omi: ignored button release event; count=$count")
                    }
                }
            }
            null -> Unit
        }
    }

    private fun emitButtonEvent(event: GlassesEvent) {
        if (!emitEvent(event)) {
            val count = droppedButtonEvents.incrementAndGet()
            if (shouldRateLimitLog(count)) {
                emitWarn("Omi: button event dropped because event buffer is full; count=$count")
            }
        }
    }

    private fun handleBatteryPacket(packet: ByteArray) {
        val raw = packet.firstOrNull()?.toInt()?.and(0xFF) ?: return
        emitBatteryLevel(raw.coerceIn(0, 100))
    }

    private fun emitBatteryLevel(percent: Int) {
        if (!emitEvent(GlassesEvent.BatteryLevel(percent))) {
            val count = droppedBatteryEvents.incrementAndGet()
            if (shouldRateLimitLog(count)) {
                emitWarn("Omi: battery event dropped because event buffer is full; count=$count")
            }
        }
    }

    private fun shouldRateLimitLog(count: Long): Boolean = count == 1L || count % RATE_LIMIT_EVERY == 0L

    private suspend fun enableButtonNotifications(gatt: BluetoothGatt, dataChar: BluetoothGattCharacteristic): Boolean =
        optionalGattOperation { enableNotifications(gatt, dataChar) }

    private suspend fun enableBatteryNotifications(gatt: BluetoothGatt, dataChar: BluetoothGattCharacteristic): Boolean =
        optionalGattOperation { enableNotifications(gatt, dataChar) }

    private suspend fun enablePhotoDataNotifications(gatt: BluetoothGatt, dataChar: BluetoothGattCharacteristic): Boolean =
        optionalGattOperation { enableNotifications(gatt, dataChar) }

    private suspend fun readBatteryLevel(gatt: BluetoothGatt, dataChar: BluetoothGattCharacteristic): Boolean =
        optionalGattOperation {
            val value = awaitGattOperation(gatt, OperationKind.READ, dataChar.uuid) { gatt.readCharacteristic(dataChar) }
            if (value.isEmpty()) throw GlassesError.Transport("Omi battery response is empty")
        }

    private suspend fun optionalGattOperation(operation: suspend () -> Unit): Boolean = try {
        operation()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emitWarn("Omi: GATT operation failed: ${error.message}")
        false
    }

    private suspend fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            ?: throw GlassesError.Unsupported("Omi characteristic ${characteristic.uuid} has no CCCD")
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw GlassesError.Transport("Omi local notification registration failed")
        }
        try {
            awaitGattOperation(gatt, OperationKind.DESCRIPTOR, characteristic.uuid) {
                writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        } catch (error: Throwable) {
            runCatching { gatt.setCharacteristicNotification(characteristic, false) }
            throw error
        }
    }

    /** Caller owns gattWriteMutex until the callback; enqueue success is not device success. */
    private suspend fun awaitGattOperation(
        gatt: BluetoothGatt,
        kind: OperationKind,
        uuid: UUID,
        start: () -> Boolean,
    ): ByteArray {
        if (gatt !== bluetoothGatt) throw GlassesError.NotConnected
        try {
            return withTimeout(options.gattOperationTimeoutMs) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    val operation = PendingOperation(gatt, kind, uuid, continuation)
                    synchronized(operationLock) {
                        check(pendingOperation == null) { "Concurrent Omi GATT operation" }
                        pendingOperation = operation
                    }
                    continuation.invokeOnCancellation {
                        val outstanding = synchronized(operationLock) {
                            if (pendingOperation === operation) {
                                pendingOperation = null
                                true
                            } else false
                        }
                        // A cancelled Android operation cannot be cancelled on the wire. Close the
                        // link so a delayed callback cannot satisfy a later request for this UUID.
                        if (outstanding && bluetoothGatt === gatt) {
                            closeGatt()
                            _state.value = ConnectionState.Disconnected
                        }
                    }
                    try {
                        if (continuation.isActive && !start()) {
                            completeOperation(gatt, kind, uuid, BluetoothGatt.GATT_FAILURE)
                        }
                    } catch (error: Exception) {
                        synchronized(operationLock) {
                            if (pendingOperation === operation) pendingOperation = null
                        }
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw GlassesError.Timeout("Omi GATT $kind $uuid")
        }
    }

    private fun completeOperation(
        gatt: BluetoothGatt,
        kind: OperationKind,
        uuid: UUID,
        status: Int,
        value: ByteArray = ByteArray(0),
    ) {
        val operation = synchronized(operationLock) {
            pendingOperation?.takeIf { it.gatt === gatt && it.kind == kind && it.uuid == uuid }?.also {
                pendingOperation = null
            }
        } ?: return
        if (!operation.continuation.isActive) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            operation.continuation.resumeWith(Result.success(value.copyOf()))
        } else {
            operation.continuation.resumeWithException(GlassesError.Transport("Omi GATT $kind $uuid failed: $status"))
        }
    }

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.setValue(value)
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun closeGatt() {
        val gatt = synchronized(connectionLock) {
            connectionReady = false
            bluetoothGatt.also { bluetoothGatt = null }
        }
        val pending = synchronized(operationLock) { pendingOperation.also { pendingOperation = null } }
        pending?.continuation?.let { if (it.isActive) it.resumeWithException(GlassesError.NotConnected) }
        updatePhotoCapability(false)
        updateButtonCapability(false)
        updateBatteryCapability(false)
        val capture = synchronized(photoLock) {
            photoContinuation.also {
                photoContinuation = null
                photoBuffer.clear()
                lastPhotoChunkId = -1
            }
        }
        capture?.let { if (it.isActive) it.resumeWith(Result.success(Result.failure(GlassesError.NotConnected))) }
        audioSession?.let { (it as? OmiMicrophoneSession)?.finish() }
        audioSession = null
        ignoredButtonReleaseEvents.set(0)
        droppedButtonEvents.set(0)
        droppedBatteryEvents.set(0)
        audioCharacteristic = null
        audioCodecCharacteristic = null
        photoControlCharacteristic = null
        photoDataCharacteristic = null
        timeSyncCharacteristic = null
        buttonCharacteristic = null
        batteryCharacteristic = null
        gatt?.disconnect()
        gatt?.close()
    }

    private suspend fun performTimeSync(gatt: BluetoothGatt) {
        val characteristic = timeSyncCharacteristic ?: return
        val epochSeconds = System.currentTimeMillis() / 1000
        val bytes = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(epochSeconds.toInt()).array()
        awaitGattOperation(gatt, OperationKind.WRITE, characteristic.uuid) {
            writeCharacteristicCompat(gatt, characteristic, bytes)
        }
        emitLog("Omi: time sync confirmed ($epochSeconds)")
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

    private fun createAudioSession(fmt: AudioFormat): MicrophoneSession {

        val audioFlow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 128)
        val seq = AtomicLong(0)

        return object : OmiMicrophoneSession {
            override val format: AudioFormat = fmt
            override val audio: Flow<AudioChunk> = audioFlow

            private val emissionLock = Any()
            private var stopped = false

            override fun emitAudio(data: ByteArray) {
                synchronized(emissionLock) {
                    if (stopped) return
                    audioFlow.tryEmit(
                        AudioChunk(
                            bytes = data,
                            format = fmt,
                            sequence = seq.incrementAndGet(),
                        )
                    )
                }
            }

            override suspend fun stop() = microphoneMutex.withLock {
                try {
                    if (audioSession === this) {
                        audioSession = null
                        val gatt = bluetoothGatt
                        val characteristic = audioCharacteristic
                        if (gatt != null && characteristic != null) {
                            gattWriteMutex.withLock {
                                val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                                if (descriptor != null) {
                                    awaitGattOperation(gatt, OperationKind.DESCRIPTOR, characteristic.uuid) {
                                        writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                                    }
                                }
                                gatt.setCharacteristicNotification(characteristic, false)
                            }
                        }
                    }
                } finally {
                    finish()
                }
            }

            override fun finish() {
                synchronized(emissionLock) {
                    if (stopped) return
                    stopped = true
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
    }

    private interface OmiMicrophoneSession : MicrophoneSession {
        fun emitAudio(data: ByteArray)
        fun finish()
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
        val gattOperationTimeoutMs: Long = 5_000,
    )

    companion object {
        // BLE UUIDs from the Omi report; kept for future BLE GATT implementation.
        internal val AUDIO_SERVICE_UUID: UUID =
            UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
        internal val AUDIO_DATA_UUID: UUID =
            UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
        internal val AUDIO_CODEC_UUID: UUID =
            UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")

        // Source: Bluetooth SIG Battery Service 1.1, https://www.bluetooth.com/specifications/specs/battery-service/
        internal val BATTERY_SERVICE_UUID: UUID =
            UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        // Source: Bluetooth SIG Battery Service 1.1 Battery Level characteristic, same BAS spec.
        internal val BATTERY_LEVEL_UUID: UUID =
            UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        internal val DEVICE_INFO_SERVICE_UUID: UUID =
            UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")

        internal val PHOTO_CONTROL_UUID: UUID =
            UUID.fromString("19B10006-E8F2-537E-4F6C-D104768A1214")
        internal val PHOTO_DATA_UUID: UUID =
            UUID.fromString("19B10005-E8F2-537E-4F6C-D104768A1214")

        internal val TIME_SYNC_SERVICE_UUID: UUID =
            UUID.fromString("19B10030-E8F2-537E-4F6C-D104768A1214")
        internal val TIME_SYNC_WRITE_UUID: UUID =
            UUID.fromString("19B10031-E8F2-537E-4F6C-D104768A1214")

        // Source: https://github.com/BasedHardware/omi/blob/51db883/firmware/devkit/src/button.c#L30-L44
        internal val BUTTON_SERVICE_UUID: UUID =
            UUID.fromString("23BA7924-0000-1000-7450-346EAC492E92")

        // Source: https://github.com/BasedHardware/omi/blob/51db883/firmware/devkit/src/button.c#L30-L44
        internal val BUTTON_TRIGGER_UUID: UUID =
            UUID.fromString("23BA7925-0000-1000-7450-346EAC492E92")

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val BUTTON_NOTIFY_TIMEOUT_MS = 5_000L
        private const val BATTERY_NOTIFY_TIMEOUT_MS = 5_000L
        private const val BATTERY_READ_TIMEOUT_MS = 5_000L
        private const val RATE_LIMIT_EVERY = 50L
    }
}
