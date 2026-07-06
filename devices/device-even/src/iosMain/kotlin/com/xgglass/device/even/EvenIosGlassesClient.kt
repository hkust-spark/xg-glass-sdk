package com.xgglass.device.even

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
import com.xgglass.core.PushMicrophoneSession
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerStatePoweredOff
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCentralManagerStateResetting
import platform.CoreBluetooth.CBCentralManagerStateUnauthorized
import platform.CoreBluetooth.CBCentralManagerStateUnknown
import platform.CoreBluetooth.CBCentralManagerStateUnsupported
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSThread
import platform.darwin.NSObject
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class EvenIosOptions(
    val connectTimeoutMs: Long = 30_000,
)

/**
 * Even Realities G1 iOS adapter backed by CoreBluetooth.
 *
 * The G1 exposes two Nordic UART peripherals. CoreBluetooth has no explicit MTU request API, so
 * after each arm is connected this adapter checks `maximumWriteValueLength(.withoutResponse)` and
 * warns if writes may be too small for display packets.
 *
 * Mutable state is confined to the main GCD queue: CoreBluetooth delegates dispatch there because
 * the central manager is created without an explicit queue, and every coroutine entry point runs on
 * Dispatchers.Main. Do not move parsing/decoding off Main or pass a queue to CBCentralManager
 * without adding real synchronization around every mutable BLE/session structure.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
class EvenIosGlassesClient(
    private val options: EvenIosOptions = EvenIosOptions(),
) : BaseGlassesClient(
    initialCapabilities = DeviceCapabilities(
        canCapturePhoto = false,
        canDisplayText = true,
        canRecordAudio = true,
        canPlayTts = false,
        canPlayAudioBytes = false,
        supportsTapEvents = true,
        supportsStreamingTextUpdates = true,
    ),
    eventBufferOverflow = BufferOverflow.SUSPEND,
) {
    override val model: GlassesModel = GlassesModel.EVEN
    override val rethrowConnectCancellation: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bleDelegate = EvenBleDelegate()
    private val commandMutex = Mutex()
    private val micSequenceTracker = EvenMicSequenceTracker()

    private var centralManager: CBCentralManager? = null
    private var powerContinuation: CancellableContinuation<Unit>? = null
    private var scanContinuation: CancellableContinuation<EvenScanPair>? = null
    private val scanByChannel = linkedMapOf<String, MutableMap<EvenArm, EvenScannedPeripheral>>()
    private val connectionsByPeripheralId = mutableMapOf<String, ArmConnection>()

    @Volatile
    private var leftArm: ArmConnection? = null

    @Volatile
    private var rightArm: ArmConnection? = null

    @Volatile
    private var microphoneSession: PushMicrophoneSession? = null

    @Volatile
    private var heartbeatJob: Job? = null

    private var heartbeatSequence = 0
    private var textSequence = 0
    private var malformedMicDrops = 0
    private var unhandledNotifications = 0
    private var droppedMicFrames = 0

    override suspend fun doConnect() {
        withContext(Dispatchers.Main) {
            try {
                withTimeout(options.connectTimeoutMs) {
                    val manager = centralManager ?: CBCentralManager(bleDelegate, null).also {
                        centralManager = it
                    }
                    awaitPoweredOn(manager)

                    emitLog("Even G1 iOS: scanning for paired left/right arms...")
                    val pair = scanForPair(manager)
                    emitLog("Even G1 iOS: found channel ${pair.channel}; connecting both arms...")

                    var connectedLeft: ArmConnection? = null
                    var connectedRight: ArmConnection? = null
                    try {
                        supervisorScope {
                            val leftDeferred = async { connectArm(manager, pair.left.peripheral, EvenArm.LEFT) }
                            val rightDeferred = async { connectArm(manager, pair.right.peripheral, EvenArm.RIGHT) }
                            val leftResult = runCatching { leftDeferred.await() }
                            val rightResult = runCatching { rightDeferred.await() }

                            connectedLeft = leftResult.getOrNull()
                            connectedRight = rightResult.getOrNull()

                            val failure = leftResult.exceptionOrNull() ?: rightResult.exceptionOrNull()
                            if (failure != null) {
                                if (failure is CancellationException) throw failure
                                val error = mapThrowableToGlassesError("Even G1 iOS connect", failure)
                                connectedLeft?.close(manager, error)
                                connectedRight?.close(manager, error)
                                throw failure
                            }
                        }

                        leftArm = connectedLeft
                        rightArm = connectedRight
                        sendInitialPackets()
                        startHeartbeat()
                        emitLog("Even G1 iOS: connected channel ${pair.channel}")
                    } catch (ce: CancellationException) {
                        connectedLeft?.close(manager, ce)
                        connectedRight?.close(manager, ce)
                        closeConnections(ce)
                        throw ce
                    } catch (e: Throwable) {
                        val error = mapThrowableToGlassesError("Even G1 iOS connect", e)
                        connectedLeft?.close(manager, error)
                        connectedRight?.close(manager, error)
                        closeConnections(error)
                        throw e
                    }
                }
            } catch (_: TimeoutCancellationException) {
                closeConnections(GlassesError.Timeout("Even G1 iOS connect"))
                throw GlassesError.Timeout("Even G1 iOS connect")
            } catch (ce: CancellationException) {
                closeConnections(ce)
                throw ce
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            val disconnectCancellation = CancellationException("Even G1 iOS connect cancelled by disconnect")
            try {
                microphoneSession?.stop()
                stopMicrophoneOnRight()
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                emitLog("Even G1 iOS: microphone stop during disconnect failed: ${error.message}")
            }
            microphoneSession = null
            stopHeartbeat()
            closeConnections(disconnectCancellation)
            resetCapabilities()
            _state.value = ConnectionState.Disconnected
        }
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> =
        Result.failure(GlassesError.Unsupported("Even Realities G1 has no camera path in this adapter."))

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) {
            return Result.failure(GlassesError.NotConnected)
        }
        return withContext(Dispatchers.Main) {
            var attemptedDisplayPacket = false
            try {
                if (text.isEmpty()) {
                    sendCommandBoth(EvenClearScreenProtocol.frame(), ::isGenericAck)
                    return@withContext Result.success(Unit)
                }

                val rawPages = EvenTextProtocol.validatedDisplayPages(text).ifEmpty { listOf(text) }
                val sync = textSequence++ and 0xFF
                commandMutex.withLock {
                    rawPages.forEachIndexed { index, pageText ->
                        val packets = EvenTextProtocol.packets(
                            page = EvenTextPage(
                                text = pageText,
                                currentPage = index + 1,
                                totalPages = rawPages.size,
                                screenStatus = EvenTextProtocol.screenStatus(EvenTextStatus.TEXT_SHOW),
                            ),
                            syncSequence = sync,
                        )
                        for (packet in packets) {
                            attemptedDisplayPacket = true
                            sendCommandBothUnlocked(packet, ::isGenericAck)
                        }
                    }
                }
                emitLog("Even G1 iOS: display sent ${text.length} chars across ${rawPages.size} page(s)")
                Result.success(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                if (attemptedDisplayPacket) {
                    bestEffortClearScreen()
                }
                Result.failure(mapThrowableToGlassesError("Even G1 iOS display", error))
            }
        }
    }

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> =
        Result.failure(
            GlassesError.Unsupported(
                "Even Realities G1 adapter does not expose a speaker, TTS, or raw audio playback path.",
            ),
        )

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (_state.value !is ConnectionState.Connected) {
            return Result.failure(GlassesError.NotConnected)
        }
        return withContext(Dispatchers.Main) {
            val right = rightArm ?: return@withContext Result.failure(GlassesError.NotConnected)
            try {
                microphoneSession?.stop()
                stopMicrophoneOnRight()
                micSequenceTracker.reset()

                lateinit var session: PushMicrophoneSession
                session = PushMicrophoneSession(
                    format = AudioFormat(
                        encoding = AudioEncoding.LC3,
                        sampleRateHz = EvenMicProtocol.LC3_SAMPLE_RATE_HZ,
                        channelCount = 1,
                    ),
                    onStop = {
                        scope.launch {
                            if (microphoneSession === session) {
                                stopMicrophoneOnRight()
                            }
                        }
                    },
                )
                microphoneSession = session

                val ack = commandMutex.withLock {
                    right.request(EvenMicProtocol.controlFrame(enable = true))
                }
                if (!EvenMicProtocol.isControlSuccess(ack)) {
                    microphoneSession = null
                    session.emitEndOfStream(0)
                    return@withContext Result.failure(
                        GlassesError.Transport("Even G1 iOS microphone enable was not acknowledged"),
                    )
                }

                emitLog("Even G1 iOS: microphone enabled; streaming raw LC3 frames")
                Result.success(session)
            } catch (ce: CancellationException) {
                microphoneSession = null
                throw ce
            } catch (error: Throwable) {
                microphoneSession = null
                Result.failure(mapThrowableToGlassesError("Even G1 iOS startMicrophone", error))
            }
        }
    }

    override fun mapConnectError(error: Exception): GlassesError =
        (error as? GlassesError) ?: GlassesError.Transport("Even G1 iOS connect failed: ${error.message}", error)

    private suspend fun awaitPoweredOn(manager: CBCentralManager) {
        when (manager.state) {
            CBCentralManagerStatePoweredOn -> return
            CBCentralManagerStateUnauthorized -> throw GlassesError.PermissionDenied
            CBCentralManagerStateUnsupported -> throw GlassesError.Transport("Bluetooth LE not supported")
            CBCentralManagerStatePoweredOff -> throw GlassesError.Transport("Bluetooth is off")
            CBCentralManagerStateResetting -> emitWarn("Even G1 iOS: Bluetooth is resetting; waiting for powered on")
            CBCentralManagerStateUnknown -> emitLog("Even G1 iOS: Bluetooth state unknown; waiting for powered on")
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            powerContinuation = continuation
            continuation.invokeOnCancellation {
                if (powerContinuation === continuation) {
                    powerContinuation = null
                }
            }
        }
    }

    private suspend fun scanForPair(manager: CBCentralManager): EvenScanPair =
        suspendCancellableCoroutine { continuation ->
            scanContinuation = continuation
            scanByChannel.clear()

            fun finishIfAlreadyConnected() {
                manager.retrieveConnectedPeripheralsWithServices(listOf(cbUuid(EvenBleUuids.NORDIC_UART_SERVICE)))
                    .peripheralsList()
                    .forEach { handleCandidatePeripheral(manager, it) }
            }

            continuation.invokeOnCancellation {
                if (scanContinuation === continuation) {
                    scanContinuation = null
                    manager.stopScan()
                }
            }

            finishIfAlreadyConnected()
            if (scanContinuation === continuation) {
                manager.scanForPeripheralsWithServices(
                    listOf(cbUuid(EvenBleUuids.NORDIC_UART_SERVICE)),
                    null,
                )
            }
        }

    private suspend fun connectArm(
        manager: CBCentralManager,
        peripheral: CBPeripheral,
        arm: EvenArm,
    ): ArmConnection =
        suspendCancellableCoroutine { continuation ->
            val connection = ArmConnection(arm = arm, peripheral = peripheral)
            connection.setupContinuation = continuation
            connectionsByPeripheralId[peripheral.identifier.UUIDString] = connection
            peripheral.delegate = bleDelegate
            manager.connectPeripheral(peripheral, null)

            continuation.invokeOnCancellation {
                if (connection.setupContinuation === continuation) {
                    connection.setupContinuation = null
                }
                connection.close(manager, CancellationException("Even G1 iOS $arm connect cancelled"))
            }
        }

    private fun handleCentralState(manager: CBCentralManager) {
        when (manager.state) {
            CBCentralManagerStatePoweredOn -> {
                val continuation = powerContinuation ?: return
                powerContinuation = null
                if (continuation.isActive) continuation.resume(Unit)
            }
            CBCentralManagerStateResetting -> {
                emitWarn("Even G1 iOS: Bluetooth is resetting")
                failPowerOrDisconnect(GlassesError.Transport("Bluetooth is resetting"))
            }
            CBCentralManagerStateUnknown -> {
                emitLog("Even G1 iOS: Bluetooth state unknown")
            }
            CBCentralManagerStateUnauthorized -> failPowerOrDisconnect(GlassesError.PermissionDenied)
            CBCentralManagerStateUnsupported -> failPowerOrDisconnect(
                GlassesError.Transport("Bluetooth LE not supported")
            )
            CBCentralManagerStatePoweredOff -> failPowerOrDisconnect(
                GlassesError.Transport("Bluetooth is off")
            )
        }
    }

    private fun failPowerOrDisconnect(error: GlassesError) {
        val continuation = powerContinuation
        if (continuation != null) {
            powerContinuation = null
            if (continuation.isActive) continuation.resumeWithException(error)
            return
        }
        if (_state.value is ConnectionState.Connected || _state.value is ConnectionState.Connecting) {
            teardownAfterLinkLoss("central state changed", error)
        }
    }

    private fun handleCandidatePeripheral(manager: CBCentralManager, peripheral: CBPeripheral) {
        val parsed = EvenDeviceNames.parse(peripheral.name) ?: return
        val channelArms = scanByChannel.getOrPut(parsed.channel) { linkedMapOf() }
        channelArms[parsed.arm] = EvenScannedPeripheral(peripheral, parsed)

        val left = channelArms[EvenArm.LEFT]
        val right = channelArms[EvenArm.RIGHT]
        val continuation = scanContinuation
        if (left != null && right != null && continuation != null) {
            scanContinuation = null
            manager.stopScan()
            if (continuation.isActive) {
                continuation.resume(EvenScanPair(parsed.channel, left, right))
            }
        }
    }

    private suspend fun stopMicrophoneOnRight() {
        val right = rightArm ?: return
        try {
            commandMutex.withLock {
                right.request(EvenMicProtocol.controlFrame(enable = false))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            emitWarn("Even G1 iOS: microphone disable failed: ${error.message}")
        } finally {
            microphoneSession = null
        }
    }

    private suspend fun sendInitialPackets() {
        val packet = EvenInitProtocol.androidInitialPacket()
        val left = leftArm ?: throw GlassesError.NotConnected
        val right = rightArm ?: throw GlassesError.NotConnected
        // Ack-less per official Android setup: BleManager.kt lines 281-290 writes 0xF4 0x01
        // after requestMtu/createBond without routing it through the command response handler.
        // docs/G1_BLE_CONNECTION.en.md lines 58 and 95 describe the same setup step.
        if (!left.write(packet)) {
            throw GlassesError.Transport("Even G1 iOS initial left-arm packet write failed")
        }
        if (!right.write(packet)) {
            throw GlassesError.Transport("Even G1 iOS initial right-arm packet write failed")
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val frame = EvenHeartbeatProtocol.frame(heartbeatSequence++)
                try {
                    sendCommandBoth(frame, EvenHeartbeatProtocol::isAck)
                    consecutiveFailures = 0
                } catch (ce: CancellationException) {
                    throw ce
                } catch (error: Throwable) {
                    consecutiveFailures += 1
                    val deadLink = error === GlassesError.NotConnected ||
                        consecutiveFailures >= HEARTBEAT_FAILURE_LIMIT
                    if (deadLink) {
                        emitWarn(
                            "Even G1 iOS: heartbeat failed $consecutiveFailures time(s); tearing down link: ${error.message}",
                        )
                        teardownAfterLinkLoss("heartbeat failure", mapThrowableToGlassesError("heartbeat", error))
                        return@launch
                    }
                    emitWarn("Even G1 iOS: heartbeat failed: ${error.message}")
                }
                delay(EvenHeartbeatProtocol.INTERVAL_MS)
            }
        }
    }

    private suspend fun sendCommandBoth(
        packet: ByteArray,
        accepted: (ByteArray) -> Boolean,
    ) {
        commandMutex.withLock {
            sendCommandBothUnlocked(packet, accepted)
        }
    }

    private suspend fun sendCommandBothUnlocked(
        packet: ByteArray,
        accepted: (ByteArray) -> Boolean,
    ) {
        val left = leftArm ?: throw GlassesError.NotConnected
        val right = rightArm ?: throw GlassesError.NotConnected

        val leftAck = left.request(packet)
        if (!accepted(leftAck)) {
            throw GlassesError.Transport("Even G1 iOS left-arm command 0x${packet.commandHex()} rejected")
        }

        val rightAck = right.request(packet)
        if (!accepted(rightAck)) {
            throw GlassesError.Transport("Even G1 iOS right-arm command 0x${packet.commandHex()} rejected")
        }
    }

    private suspend fun bestEffortClearScreen() {
        try {
            sendCommandBoth(EvenClearScreenProtocol.frame(), ::isGenericAck)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            emitLog("Even G1 iOS: best-effort clear screen failed: ${error.message}")
        }
    }

    private fun isGenericAck(packet: ByteArray): Boolean = EvenResponses.isSuccessOrContinue(packet)

    private fun handleNotification(connection: ArmConnection, value: ByteArray) {
        assertMainThread("handleNotification")
        when (val mic = EvenMicProtocol.parseNotification(value)) {
            is EvenMicNotificationResult.Frame -> {
                val sequence = micSequenceTracker.next(mic.frame.sequence) ?: return
                val session = microphoneSession ?: return
                if (!session.emit(mic.frame.lc3Bytes, sequence)) {
                    droppedMicFrames += 1
                    if (shouldRateLimitLog(droppedMicFrames)) {
                        emitWarn("Even G1 iOS: dropped mic frame due to audio backpressure; count=$droppedMicFrames")
                    }
                }
                return
            }
            is EvenMicNotificationResult.Malformed -> {
                malformedMicDrops += 1
                if (shouldRateLimitLog(malformedMicDrops)) {
                    emitWarn(
                        "Even G1 iOS: dropped malformed mic notification from ${connection.arm}; size=${mic.size}",
                    )
                }
                return
            }
            EvenMicNotificationResult.NotMic -> Unit
        }

        val tapCount = EvenStateEvents.tapCount(value)
        if (tapCount != null) {
            _events.tryEmit(GlassesEvent.Tap(tapCount))
            return
        }

        if (connection.completeResponse(value)) return

        unhandledNotifications += 1
        if (shouldRateLimitLog(unhandledNotifications)) {
            emitLog("Even G1 iOS: unhandled ${connection.arm} notification firstByte=0x${value.commandHex()}")
        }
    }

    private fun teardownAfterLinkLoss(reason: String, error: Exception) {
        assertMainThread("teardownAfterLinkLoss")
        val session = microphoneSession
        microphoneSession = null
        val eosSequence = micSequenceTracker.nextEndOfStreamSequence()
        session?.emitEndOfStream(eosSequence)
        closeConnections(error)
        resetCapabilities()
        emitLog("Even G1 iOS: disconnected after $reason: ${error.message}")
        _state.value = ConnectionState.Disconnected
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun closeConnections(error: Exception = GlassesError.Transport("Even G1 iOS connection closed")) {
        assertMainThread("closeConnections")
        stopHeartbeat()
        val manager = centralManager
        val scan = scanContinuation
        scanContinuation = null
        if (scan != null) {
            manager?.stopScan()
            if (scan.isActive) scan.resumeWithException(error)
        }
        val power = powerContinuation
        powerContinuation = null
        if (power != null && power.isActive) {
            power.resumeWithException(error)
        }
        val connections = (
            connectionsByPeripheralId.values.toList() + listOfNotNull(leftArm, rightArm)
            ).distinct()
        leftArm = null
        rightArm = null
        scanByChannel.clear()
        connections.forEach { it.close(manager, error) }
        connectionsByPeripheralId.clear()
    }

    private fun shouldRateLimitLog(count: Int): Boolean =
        count == 1 || count % RATE_LIMIT_EVERY == 0

    private fun mapThrowableToGlassesError(operation: String, error: Throwable): GlassesError =
        when (error) {
            is GlassesError -> error
            else -> GlassesError.Transport("$operation failed: ${error.message ?: error::class.simpleName}")
        }

    private inner class ArmConnection(
        val arm: EvenArm,
        val peripheral: CBPeripheral,
    ) {
        var txCharacteristic: CBCharacteristic? = null
        var rxCharacteristic: CBCharacteristic? = null
        var setupContinuation: CancellableContinuation<ArmConnection>? = null

        private val pendingResponses = mutableMapOf<Int, CancellableContinuation<ByteArray>>()
        private var notificationContinuation: CancellableContinuation<Boolean>? = null
        private var writeReadyContinuation: CancellableContinuation<Unit>? = null

        fun handleConnected() {
            peripheral.discoverServices(listOf(cbUuid(EvenBleUuids.NORDIC_UART_SERVICE)))
        }

        fun handleServices(error: NSError?) {
            if (error != null) {
                failSetup(error.toTransportError("Even G1 iOS $arm service discovery failed"))
                return
            }
            val service = peripheral.servicesList()
                .firstOrNull { it.matches(EvenBleUuids.NORDIC_UART_SERVICE) }
            if (service == null) {
                failSetup(GlassesError.Transport("Even G1 iOS $arm Nordic UART service missing"))
                return
            }
            peripheral.discoverCharacteristics(
                listOf(
                    cbUuid(EvenBleUuids.NORDIC_UART_TX),
                    cbUuid(EvenBleUuids.NORDIC_UART_RX),
                ),
                service,
            )
        }

        fun handleCharacteristics(service: CBService, error: NSError?) {
            if (!service.matches(EvenBleUuids.NORDIC_UART_SERVICE)) return
            if (error != null) {
                failSetup(error.toTransportError("Even G1 iOS $arm characteristic discovery failed"))
                return
            }

            service.characteristicsList().forEach { characteristic ->
                when {
                    characteristic.matches(EvenBleUuids.NORDIC_UART_TX) -> txCharacteristic = characteristic
                    characteristic.matches(EvenBleUuids.NORDIC_UART_RX) -> rxCharacteristic = characteristic
                }
            }

            if (txCharacteristic == null || rxCharacteristic == null) {
                failSetup(GlassesError.Transport("Even G1 iOS $arm Nordic UART characteristics missing"))
                return
            }

            scope.launch {
                try {
                    if (!enableNotifications()) {
                        failSetup(GlassesError.Transport("Even G1 iOS $arm notification enable failed"))
                        return@launch
                    }
                    val maxWrite = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
                        .toInt()
                    emitLog("Even G1 iOS: $arm max write without response is $maxWrite bytes")
                    if (maxWrite < MIN_WRITE_WITHOUT_RESPONSE_BYTES) {
                        emitWarn(
                            "Even G1 iOS: $arm write length $maxWrite is below $MIN_WRITE_WITHOUT_RESPONSE_BYTES; display/mic may fail",
                        )
                    }
                    succeedSetup()
                } catch (ce: CancellationException) {
                    failSetup(ce)
                } catch (error: Throwable) {
                    failSetup(mapThrowableToGlassesError("Even G1 iOS $arm setup", error))
                }
            }
        }

        suspend fun enableNotifications(): Boolean {
            val rx = rxCharacteristic ?: return false
            return suspendCancellableCoroutine { continuation ->
                notificationContinuation = continuation
                peripheral.setNotifyValue(true, rx)
                continuation.invokeOnCancellation {
                    if (notificationContinuation === continuation) {
                        notificationContinuation = null
                    }
                }
            }
        }

        fun handleNotificationState(characteristic: CBCharacteristic, error: NSError?) {
            if (!characteristic.matches(EvenBleUuids.NORDIC_UART_RX)) return
            val pending = notificationContinuation
            notificationContinuation = null
            pending?.resume(error == null && characteristic.isNotifying, onCancellation = null)
        }

        suspend fun request(packet: ByteArray, timeoutMs: Long = COMMAND_TIMEOUT_MS): ByteArray {
            if (packet.isEmpty()) {
                throw GlassesError.Transport("Even G1 iOS cannot send empty command")
            }
            val command = packet[0].toInt() and 0xFF
            awaitWriteReady(packet)
            return try {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine<ByteArray> { continuation ->
                        pendingResponses.remove(command)?.resumeWithException(
                            GlassesError.Transport("Even G1 iOS $arm command 0x${packet.commandHex()} superseded"),
                        )
                        pendingResponses[command] = continuation

                        val started = writeNow(packet)
                        if (!started) {
                            val stillOwned = if (pendingResponses[command] === continuation) {
                                pendingResponses.remove(command)
                                true
                            } else {
                                false
                            }
                            if (stillOwned) {
                                continuation.resumeWithException(
                                    GlassesError.Transport("Even G1 iOS $arm command 0x${packet.commandHex()} write failed"),
                                )
                            }
                        }

                        continuation.invokeOnCancellation {
                            if (pendingResponses[command] === continuation) {
                                pendingResponses.remove(command)
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw GlassesError.Timeout("Even G1 iOS $arm command 0x${packet.commandHex()}")
            }
        }

        suspend fun write(packet: ByteArray): Boolean {
            awaitWriteReady(packet)
            return writeNow(packet)
        }

        private fun writeNow(packet: ByteArray): Boolean {
            val tx = txCharacteristic ?: return false
            peripheral.writeValue(packet.toNSData(), tx, CBCharacteristicWriteWithoutResponse)
            return true
        }

        private suspend fun awaitWriteReady(packet: ByteArray) {
            if (peripheral.canSendWriteWithoutResponse) return
            val commandHex = packet.commandHex()
            var ownedContinuation: CancellableContinuation<Unit>? = null
            try {
                withTimeout(WRITE_BACKPRESSURE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        if (peripheral.canSendWriteWithoutResponse) {
                            continuation.resume(Unit, onCancellation = null)
                            return@suspendCancellableCoroutine
                        }
                        writeReadyContinuation?.resumeWithException(
                            GlassesError.Transport("Even G1 iOS $arm command 0x$commandHex write backpressure superseded"),
                        )
                        writeReadyContinuation = continuation
                        ownedContinuation = continuation
                        continuation.invokeOnCancellation {
                            if (writeReadyContinuation === continuation) {
                                writeReadyContinuation = null
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (writeReadyContinuation === ownedContinuation) {
                    writeReadyContinuation = null
                }
                throw GlassesError.Transport("Even G1 iOS $arm command 0x$commandHex write backpressure")
            }
        }

        fun handleReadyToWrite() {
            val pending = writeReadyContinuation
            writeReadyContinuation = null
            pending?.resume(Unit, onCancellation = null)
        }

        fun completeResponse(packet: ByteArray): Boolean {
            assertMainThread("completeResponse")
            if (packet.isEmpty()) return false
            val command = packet[0].toInt() and 0xFF
            val pending = pendingResponses.remove(command)
            pending?.resume(packet, onCancellation = null)
            return pending != null
        }

        fun succeedSetup() {
            val pending = setupContinuation ?: return
            setupContinuation = null
            if (pending.isActive) pending.resume(this)
        }

        fun failSetup(error: Exception) {
            val pending = setupContinuation
            setupContinuation = null
            failPending(error)
            if (pending != null && pending.isActive) {
                pending.resumeWithException(error)
            }
        }

        fun failPending(error: Exception) {
            assertMainThread("failPending")
            val responses = pendingResponses.values.toList()
            pendingResponses.clear()
            val notification = notificationContinuation
            notificationContinuation = null
            val writeReady = writeReadyContinuation
            writeReadyContinuation = null
            val pending = PendingContinuations(responses, notification, writeReady)
            pending.responses.forEach { it.resumeWithException(error) }
            pending.notification?.resumeWithException(error)
            pending.writeReady?.resumeWithException(error)
        }

        fun close(
            manager: CBCentralManager?,
            error: Exception = GlassesError.Transport("Even G1 iOS $arm connection closed"),
        ) {
            failPending(error)
            setupContinuation?.let { continuation ->
                setupContinuation = null
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
            rxCharacteristic?.let { peripheral.setNotifyValue(false, it) }
            manager?.cancelPeripheralConnection(peripheral)
            connectionsByPeripheralId.remove(peripheral.identifier.UUIDString)
            txCharacteristic = null
            rxCharacteristic = null
        }
    }

    private inner class EvenBleDelegate : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            handleCentralState(central)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            if (scanContinuation == null) return
            handleCandidatePeripheral(central, didDiscoverPeripheral)
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral,
        ) {
            val connection = connectionsByPeripheralId[didConnectPeripheral.identifier.UUIDString] ?: return
            emitLog("Even G1 iOS: ${connection.arm} connected; discovering services")
            didConnectPeripheral.delegate = this
            connection.handleConnected()
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val connection = connectionsByPeripheralId[didFailToConnectPeripheral.identifier.UUIDString]
            connection?.failSetup(error.toTransportError("Even G1 iOS connect failed"))
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val connection = connectionsByPeripheralId[didDisconnectPeripheral.identifier.UUIDString]
            val transportError = error.toTransportError("Even G1 iOS ${connection?.arm ?: "arm"} disconnected")
            if (connection?.setupContinuation != null) {
                connection.failSetup(transportError)
                return
            }
            if (_state.value is ConnectionState.Connected || _state.value is ConnectionState.Connecting) {
                teardownAfterLinkLoss("${connection?.arm ?: "arm"} disconnect", transportError)
            }
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            connectionsByPeripheralId[peripheral.identifier.UUIDString]?.handleServices(didDiscoverServices)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            connectionsByPeripheralId[peripheral.identifier.UUIDString]
                ?.handleCharacteristics(didDiscoverCharacteristicsForService, error)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val connection = connectionsByPeripheralId[peripheral.identifier.UUIDString] ?: return
            if (error != null) {
                emitWarn("Even G1 iOS: notification failed on ${connection.arm}: ${error.localizedDescription}")
                return
            }
            val packet = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            handleNotification(connection, packet)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            connectionsByPeripheralId[peripheral.identifier.UUIDString]
                ?.handleNotificationState(didUpdateNotificationStateForCharacteristic, error)
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            connectionsByPeripheralId[peripheral.identifier.UUIDString]?.handleReadyToWrite()
        }
    }

    private data class EvenScannedPeripheral(
        val peripheral: CBPeripheral,
        val parsed: EvenDeviceName,
    )

    private data class EvenScanPair(
        val channel: String,
        val left: EvenScannedPeripheral,
        val right: EvenScannedPeripheral,
    )

    private data class PendingContinuations(
        val responses: List<CancellableContinuation<ByteArray>>,
        val notification: CancellableContinuation<Boolean>?,
        val writeReady: CancellableContinuation<Unit>?,
    )

    companion object {
        private const val COMMAND_TIMEOUT_MS = 2_000L
        private const val WRITE_BACKPRESSURE_TIMEOUT_MS = 1_000L
        private const val HEARTBEAT_FAILURE_LIMIT = 2
        private const val RATE_LIMIT_EVERY = 50
        private const val MIN_WRITE_WITHOUT_RESPONSE_BYTES = 200
    }
}

private fun cbUuid(value: String): CBUUID = CBUUID.UUIDWithString(value)

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

@Suppress("UNCHECKED_CAST")
private fun List<*>.peripheralsList(): List<CBPeripheral> = this as? List<CBPeripheral> ?: emptyList()

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = bytes?.readBytes(length.toInt()) ?: ByteArray(0)

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned ->
        platform.CoreFoundation.CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.convert()) as NSData
    }
}

private fun NSError?.toTransportError(fallback: String): GlassesError.Transport =
    GlassesError.Transport(this?.localizedDescription ?: fallback)

private fun assertMainThread(operation: String) {
    if (!NSThread.isMainThread) {
        throw IllegalStateException("Even G1 iOS mutable state accessed off Main during $operation")
    }
}

private fun ByteArray.commandHex(): String =
    if (isEmpty()) "??" else (this[0].toInt() and 0xFF).toString(16).padStart(2, '0')
