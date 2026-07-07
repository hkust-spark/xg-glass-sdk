package com.xgglass.device.omi.ios

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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.CFDataCreate
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerStatePoweredOff
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCentralManagerStateUnauthorized
import platform.CoreBluetooth.CBCentralManagerStateUnsupported
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OmiOptions(
    val connectTimeoutMs: Long = 30_000,
)

@OptIn(ExperimentalForeignApi::class)
class OmiIosGlassesClient(
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
        supportsStreamingTextUpdates = false,
    ),
    eventBufferOverflow = BufferOverflow.SUSPEND,
) {
    override val model: GlassesModel = GlassesModel.OMI
    override val rethrowConnectCancellation: Boolean = true

    private val bleDelegate = OmiBleDelegate()
    private val photoAssembler = OmiPhotoAssembler()

    private var centralManager: CBCentralManager? = null
    private var peripheral: CBPeripheral? = null
    private var audioCharacteristic: CBCharacteristic? = null
    private var photoControlCharacteristic: CBCharacteristic? = null
    private var photoDataCharacteristic: CBCharacteristic? = null
    private var timeSyncCharacteristic: CBCharacteristic? = null
    private var buttonCharacteristic: CBCharacteristic? = null

    private var connectContinuation: CancellableContinuation<Unit>? = null
    private var captureContinuation: CancellableContinuation<Result<CapturedImage>>? = null
    private var audioSession: OmiMicrophoneSession? = null
    private var ignoredButtonReleaseEvents = 0
    private var droppedButtonEvents = 0

    override suspend fun doConnect() {
        withContext(Dispatchers.Main) {
            try {
                withTimeout(options.connectTimeoutMs) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        connectContinuation = continuation
                        _state.value = ConnectionState.Connecting

                        val manager = centralManager ?: CBCentralManager(bleDelegate, null).also {
                            centralManager = it
                        }
                        handleCentralState(manager)

                        continuation.invokeOnCancellation {
                            if (connectContinuation === continuation) {
                                connectContinuation = null
                            }
                            manager.stopScan()
                            peripheral?.let(manager::cancelPeripheralConnection)
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                cleanupConnectionObjects()
                throw GlassesError.Timeout("Omi connect")
            } catch (error: CancellationException) {
                cleanupConnectionObjects()
                throw error
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            failCapture(GlassesError.NotConnected)
            stopActiveAudioSession()
            centralManager?.stopScan()
            peripheral?.let { centralManager?.cancelPeripheralConnection(it) }
            cleanupConnectionObjects()
            resetCapabilities()
            _state.value = ConnectionState.Disconnected
        }
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        photoControlCharacteristic
            ?: return Result.failure(GlassesError.Unsupported("Photo control not available"))
        val data = photoDataCharacteristic
            ?: return Result.failure(GlassesError.Unsupported("Photo data characteristic not found"))
        val currentPeripheral = peripheral ?: return Result.failure(GlassesError.NotConnected)
        if (_state.value !is ConnectionState.Connected) {
            return Result.failure(GlassesError.NotConnected)
        }

        return withContext(Dispatchers.Main) {
            // Confine the busy check-then-set to the BLE (main) queue so two concurrent
            // callers can't both pass the guard and orphan a continuation.
            if (captureContinuation != null) {
                return@withContext Result.failure(GlassesError.Busy)
            }
            val result = withTimeoutOrNull(options.timeoutMs) {
                suspendCancellableCoroutine<Result<CapturedImage>> { continuation ->
                    captureContinuation = continuation
                    photoAssembler.reset()
                    emitLog("Omi iOS: enabling photo data notifications")
                    currentPeripheral.setNotifyValue(true, data)

                    continuation.invokeOnCancellation {
                        if (captureContinuation === continuation) {
                            captureContinuation = null
                        }
                        photoAssembler.reset()
                    }
                }
            }
            if (result == null) {
                // Timed out: clear pending state and disable photo notifications so the next
                // capture reliably re-triggers the notification-state callback (CoreBluetooth
                // does not re-fire it when notifications are already enabled).
                captureContinuation = null
                photoAssembler.reset()
                disablePhotoNotifications()
                Result.failure(GlassesError.Timeout("capturePhoto"))
            } else {
                result
            }
        }
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        val currentPeripheral = peripheral ?: return Result.failure(GlassesError.NotConnected)
        val audio = audioCharacteristic
            ?: return Result.failure(GlassesError.Unsupported("Omi audio characteristic not found"))
        if (_state.value !is ConnectionState.Connected) {
            return Result.failure(GlassesError.NotConnected)
        }

        stopActiveAudioSession()
        val session = OmiMicrophoneSession()
        audioSession = session
        return try {
            withContext(Dispatchers.Main) {
                currentPeripheral.setNotifyValue(true, audio)
            }
            Result.success(session)
        } catch (error: CancellationException) {
            audioSession = null
            throw error
        } catch (error: Throwable) {
            audioSession = null
            Result.failure(mapThrowableToGlassesError("startMicrophone", error))
        }
    }

    /** Best-effort teardown of any active audio session; never swallows cancellation. */
    private suspend fun stopActiveAudioSession() {
        val session = audioSession ?: return
        audioSession = null
        try {
            session.stop()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Best-effort: the session is being torn down anyway.
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> =
        Result.failure(GlassesError.Unsupported("Omi Glass BLE SDK does not offer display primitives."))

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> =
        Result.failure(GlassesError.Unsupported("Omi integration is audio-input-only; playback to glasses speakers is not supported."))

    override fun mapConnectError(error: Exception): GlassesError =
        (error as? GlassesError) ?: GlassesError.Transport("Omi connect failed: ${error.message}", error)

    private fun handleCentralState(manager: CBCentralManager) {
        when (manager.state) {
            CBCentralManagerStatePoweredOn -> startScan(manager)
            CBCentralManagerStateUnauthorized -> failConnect(GlassesError.PermissionDenied)
            CBCentralManagerStateUnsupported -> failConnect(
                GlassesError.Transport("Bluetooth LE not supported")
            )
            CBCentralManagerStatePoweredOff -> failConnect(
                GlassesError.Transport("Bluetooth is off")
            )
        }
    }

    private fun startScan(manager: CBCentralManager) {
        if (connectContinuation == null) return
        emitLog("Omi iOS: scanning for Omi BLE services")
        manager.scanForPeripheralsWithServices(
            listOf(
                cbUuid(OmiBleUuids.AUDIO_SERVICE),
            ),
            null,
        )
    }

    private fun completeConnect() {
        centralManager?.stopScan()
        emitLog("Omi iOS: audio service ready")
        _state.value = ConnectionState.Connected

        val continuation = connectContinuation ?: return
        connectContinuation = null
        if (continuation.isActive) {
            continuation.resume(Unit)
        }
    }

    private fun failConnect(error: GlassesError) {
        centralManager?.stopScan()
        cleanupConnectionObjects()
        _state.value = ConnectionState.Error(error)

        val continuation = connectContinuation ?: return
        connectContinuation = null
        if (continuation.isActive) {
            continuation.resumeWithException(error)
        }
    }

    private fun completeCapture(jpegBytes: ByteArray) {
        val continuation = captureContinuation ?: return
        captureContinuation = null
        photoAssembler.reset()
        disablePhotoNotifications()

        val image = CapturedImage(
            jpegBytes = jpegBytes,
            sourceModel = GlassesModel.OMI,
        )
        emitLog("Omi iOS: photo received (${jpegBytes.size} bytes)")
        if (continuation.isActive) {
            continuation.resume(Result.success(image))
        }
    }

    private fun failCapture(error: GlassesError) {
        val continuation = captureContinuation ?: return
        captureContinuation = null
        photoAssembler.reset()
        disablePhotoNotifications()
        if (continuation.isActive) {
            continuation.resume(Result.failure(error))
        }
    }

    /**
     * Disable PHOTO_DATA notifications after a capture ends. Called only from the BLE (main)
     * queue. No-op if the peripheral/characteristic is already gone.
     */
    private fun disablePhotoNotifications() {
        val data = photoDataCharacteristic ?: return
        peripheral?.setNotifyValue(false, data)
    }

    /** Terminate the active audio flow with an end-of-stream marker (non-suspending; callback-safe). */
    private fun finishAudioSession() {
        audioSession?.emitEndOfStream()
        audioSession = null
    }

    private fun cleanupConnectionObjects() {
        peripheral = null
        audioCharacteristic = null
        photoControlCharacteristic = null
        photoDataCharacteristic = null
        timeSyncCharacteristic = null
        buttonCharacteristic = null
        ignoredButtonReleaseEvents = 0
        droppedButtonEvents = 0
        photoAssembler.reset()
        updateCapabilities {
            it.copy(
                canCapturePhoto = false,
                supportsTapEvents = false,
                supportsLongPressEvents = false,
            )
        }
    }

    private fun writeTimeSyncIfAvailable() {
        val currentPeripheral = peripheral ?: return
        val characteristic = timeSyncCharacteristic ?: return
        val epochSeconds = (CFAbsoluteTimeGetCurrent() + COCOA_EPOCH_SECONDS).toInt()
        runCatching {
            currentPeripheral.writeValue(
                OmiTimeSync.epochSecondsLE(epochSeconds).toNSData(),
                characteristic,
                CBCharacteristicWriteWithResponse,
            )
        }.onSuccess {
            emitLog("Omi iOS: wrote time sync")
        }.onFailure { error ->
            emitLog("Omi iOS: time sync failed: ${error.message}")
        }
    }

    private fun emitAudio(packet: ByteArray) {
        val session = audioSession ?: return
        val payload = OmiAudioFraming.payload(packet)
        if (payload == null || payload.isEmpty()) return
        session.emit(payload)
    }

    private fun handlePhotoPacket(packet: ByteArray) {
        when (val result = photoAssembler.addPacket(packet)) {
            is OmiPhotoResult.Complete -> completeCapture(result.jpegBytes)
            is OmiPhotoResult.DroppedChunk -> emitWarn(
                "Omi photo chunk dropped: expected ${result.expected}, got ${result.got}"
            )
            OmiPhotoResult.Incomplete -> Unit
        }
    }

    private fun handleButtonPacket(packet: ByteArray) {
        when (val event = OmiButtonEvents.parse(packet)) {
            is OmiButtonEvent.Tap -> emitButtonEvent(GlassesEvent.Tap(event.count))
            OmiButtonEvent.LongPress -> emitButtonEvent(GlassesEvent.LongPress)
            is OmiButtonEvent.Ignored -> {
                if (event.code == OmiButtonEvents.BUTTON_RELEASE) {
                    ignoredButtonReleaseEvents += 1
                    if (shouldRateLimitLog(ignoredButtonReleaseEvents)) {
                        emitLog("Omi iOS: ignored button release event; count=$ignoredButtonReleaseEvents")
                    }
                }
            }
            null -> Unit
        }
    }

    private fun emitButtonEvent(event: GlassesEvent) {
        if (!emitEvent(event)) {
            droppedButtonEvents += 1
            if (shouldRateLimitLog(droppedButtonEvents)) {
                emitWarn("Omi iOS: button event dropped because event buffer is full; count=$droppedButtonEvents")
            }
        }
    }

    private fun shouldRateLimitLog(count: Int): Boolean = count == 1 || count % RATE_LIMIT_EVERY == 0

    private fun mapThrowableToGlassesError(operation: String, error: Throwable): GlassesError =
        when (error) {
            is GlassesError -> error
            else -> GlassesError.Transport("$operation failed: ${error.message ?: error::class.simpleName}")
        }

    private inner class OmiMicrophoneSession : MicrophoneSession {
        override val format: AudioFormat = AudioFormat(
            encoding = AudioEncoding.OPUS,
            sampleRateHz = 16_000,
            channelCount = 1,
        )

        private val chunks = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 128)
        private var sequence = 0L
        private var stopped = false

        override val audio: Flow<AudioChunk> = chunks

        fun emit(payload: ByteArray) {
            if (stopped) return
            chunks.tryEmit(
                AudioChunk(
                    bytes = payload,
                    format = format,
                    sequence = sequence++,
                )
            )
        }

        /** Non-suspending EOS emission, safe to call from a BLE delegate callback. */
        fun emitEndOfStream() {
            if (stopped) return
            stopped = true
            emitEndOfStreamChunk()
        }

        override suspend fun stop() {
            if (stopped) return
            stopped = true
            withContext(Dispatchers.Main) {
                audioCharacteristic?.let { characteristic ->
                    peripheral?.setNotifyValue(false, characteristic)
                }
            }
            emitEndOfStreamChunk()
        }

        private fun emitEndOfStreamChunk() {
            // tryEmit (not emit) so teardown never blocks on a slow subscriber; consumers
            // treat ConnectionState.Disconnected as authoritative if this chunk is dropped.
            chunks.tryEmit(
                AudioChunk(
                    bytes = ByteArray(0),
                    format = format,
                    sequence = sequence,
                    endOfStream = true,
                )
            )
        }
    }

    private inner class OmiBleDelegate : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            handleCentralState(central)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            if (connectContinuation == null) return
            emitLog("Omi iOS: discovered ${didDiscoverPeripheral.name ?: "Omi peripheral"}")
            peripheral = didDiscoverPeripheral
            didDiscoverPeripheral.delegate = this
            central.stopScan()
            central.connectPeripheral(didDiscoverPeripheral, null)
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral,
        ) {
            emitLog("Omi iOS: connected, discovering services")
            peripheral = didConnectPeripheral
            didConnectPeripheral.delegate = this
            didConnectPeripheral.discoverServices(
                listOf(
                    cbUuid(OmiBleUuids.AUDIO_SERVICE),
                    cbUuid(OmiBleUuids.TIME_SYNC_SERVICE),
                    cbUuid(OmiBleUuids.BUTTON_SERVICE),
                )
            )
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            failConnect(error.toTransportError("Omi connect failed"))
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            if (connectContinuation != null) {
                failConnect(error.toTransportError("Omi disconnected during connect"))
                return
            }
            // An in-flight capture can never resume once the link drops; fail it explicitly
            // instead of letting the caller wait for the timeout with a misleading error.
            failCapture(GlassesError.NotConnected)
            finishAudioSession()
            cleanupConnectionObjects()
            _state.value = ConnectionState.Disconnected
            emitLog("Omi iOS: disconnected")
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                failConnect(didDiscoverServices.toTransportError("Omi service discovery failed"))
                return
            }
            peripheral.servicesList().forEach { service ->
                peripheral.discoverCharacteristics(
                    when {
                        service.matches(OmiBleUuids.AUDIO_SERVICE) -> listOf(
                            cbUuid(OmiBleUuids.AUDIO_DATA),
                            cbUuid(OmiBleUuids.PHOTO_CONTROL),
                            cbUuid(OmiBleUuids.PHOTO_DATA),
                        )
                        service.matches(OmiBleUuids.TIME_SYNC_SERVICE) -> listOf(
                            cbUuid(OmiBleUuids.TIME_SYNC_WRITE),
                        )
                        service.matches(OmiBleUuids.BUTTON_SERVICE) -> listOf(
                            cbUuid(OmiBleUuids.BUTTON_TRIGGER),
                        )
                        else -> emptyList()
                    },
                    service,
                )
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            if (error != null) {
                failConnect(error.toTransportError("Omi characteristic discovery failed"))
                return
            }

            didDiscoverCharacteristicsForService.characteristicsList().forEach { characteristic ->
                when {
                    characteristic.matches(OmiBleUuids.AUDIO_DATA) -> audioCharacteristic = characteristic
                    characteristic.matches(OmiBleUuids.PHOTO_DATA) -> photoDataCharacteristic = characteristic
                    characteristic.matches(OmiBleUuids.PHOTO_CONTROL) -> photoControlCharacteristic = characteristic
                    characteristic.matches(OmiBleUuids.TIME_SYNC_WRITE) -> timeSyncCharacteristic = characteristic
                    characteristic.matches(OmiBleUuids.BUTTON_TRIGGER) -> buttonCharacteristic = characteristic
                }
            }

            if (didDiscoverCharacteristicsForService.matches(OmiBleUuids.TIME_SYNC_SERVICE)) {
                writeTimeSyncIfAvailable()
            }

            if (didDiscoverCharacteristicsForService.matches(OmiBleUuids.AUDIO_SERVICE)) {
                updateCapabilities {
                    it.copy(canCapturePhoto = photoControlCharacteristic != null && photoDataCharacteristic != null)
                }
                completeConnect()
            }

            if (didDiscoverCharacteristicsForService.matches(OmiBleUuids.BUTTON_SERVICE)) {
                val button = buttonCharacteristic ?: return
                // Capability is gated on service/characteristic discovery, never name/model.
                updateCapabilities {
                    it.copy(
                        supportsTapEvents = true,
                        supportsLongPressEvents = false,
                    )
                }
                peripheral.setNotifyValue(true, button)
                emitLog("Omi iOS: button service ready")
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) {
                when {
                    didUpdateValueForCharacteristic.matches(OmiBleUuids.PHOTO_DATA) -> {
                        failCapture(error.toTransportError("Omi photo notification failed"))
                    }
                    didUpdateValueForCharacteristic.matches(OmiBleUuids.AUDIO_DATA) -> {
                        emitWarn("Omi audio notification failed: ${error.localizedDescription}")
                    }
                    didUpdateValueForCharacteristic.matches(OmiBleUuids.BUTTON_TRIGGER) -> {
                        emitWarn("Omi button notification failed: ${error.localizedDescription}")
                    }
                }
                return
            }

            val packet = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            when {
                didUpdateValueForCharacteristic.matches(OmiBleUuids.AUDIO_DATA) -> emitAudio(packet)
                didUpdateValueForCharacteristic.matches(OmiBleUuids.PHOTO_DATA) -> handlePhotoPacket(packet)
                didUpdateValueForCharacteristic.matches(OmiBleUuids.BUTTON_TRIGGER) -> handleButtonPacket(packet)
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (didUpdateNotificationStateForCharacteristic.matches(OmiBleUuids.AUDIO_DATA)) {
                if (error != null) {
                    emitWarn("Omi audio notification enable failed: ${error.localizedDescription}")
                    finishAudioSession()
                }
                return
            }
            if (didUpdateNotificationStateForCharacteristic.matches(OmiBleUuids.BUTTON_TRIGGER)) {
                if (error != null) {
                    emitWarn("Omi button notification enable failed: ${error.localizedDescription}")
                    updateCapabilities {
                        it.copy(
                            supportsTapEvents = false,
                            supportsLongPressEvents = false,
                        )
                    }
                }
                return
            }
            if (!didUpdateNotificationStateForCharacteristic.matches(OmiBleUuids.PHOTO_DATA)) {
                return
            }
            if (captureContinuation == null) {
                return
            }
            if (error != null) {
                failCapture(error.toTransportError("Omi photo notification enable failed"))
                return
            }
            val control = photoControlCharacteristic
                ?: return failCapture(GlassesError.Unsupported("Omi photo control characteristic not found"))

            peripheral.writeValue(
                byteArrayOf(OMI_PHOTO_CAPTURE_COMMAND).toNSData(),
                control,
                CBCharacteristicWriteWithResponse,
            )
            emitLog("Omi iOS: wrote photo capture command 0x05 after notifications enabled")
        }
    }
}

private fun cbUuid(value: String): CBUUID = CBUUID.UUIDWithString(value)

private const val COCOA_EPOCH_SECONDS: Double = 978_307_200.0
private const val RATE_LIMIT_EVERY = 50

private fun CBService.matches(expected: String): Boolean = UUID.matches(expected)

private fun CBCharacteristic.matches(expected: String): Boolean = UUID.matches(expected)

private fun CBUUID.matches(expected: String): Boolean {
    val normalizedActual = UUIDString.uppercase().replace("-", "")
    val normalizedExpected = expected.uppercase().replace("-", "")
    return normalizedActual == normalizedExpected
}

@Suppress("UNCHECKED_CAST")
private fun CBPeripheral.servicesList(): List<CBService> = services as? List<CBService> ?: emptyList()

@Suppress("UNCHECKED_CAST")
private fun CBService.characteristicsList(): List<CBCharacteristic> =
    characteristics as? List<CBCharacteristic> ?: emptyList()

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = bytes?.readBytes(length.toInt()) ?: ByteArray(0)

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.convert()) as NSData
    }
}

private fun NSError?.toTransportError(fallback: String): GlassesError.Transport =
    GlassesError.Transport(this?.localizedDescription ?: fallback)
