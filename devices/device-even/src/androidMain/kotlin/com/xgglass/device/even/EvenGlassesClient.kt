package com.xgglass.device.even

import android.Manifest
import android.annotation.SuppressLint
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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * Even Realities G1 Android adapter.
 *
 * Protocol sources are cited in [EvenProtocol.kt]. The G1 exposes two BLE peripherals, one per
 * arm, over Nordic UART. The adapter connects both arms, enables RX notifications on both, sends
 * display/heartbeat commands left first and right after the left acknowledgment, and starts the
 * microphone on the right arm only.
 *
 * [MicrophoneSession.stop] emits end-of-stream synchronously. The device-side mic disable is
 * launched asynchronously as best effort for external callers; failures are surfaced as warn events.
 */
@SuppressLint("MissingPermission")
class EvenGlassesClient(
    private val context: Context,
    private val options: EvenOptions = EvenOptions(),
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val heartbeatSequence = AtomicInteger(0)
    private val textSequence = AtomicInteger(0)
    private val micSequenceTracker = EvenMicSequenceTracker()
    private val micSequenceLock = Any()
    private val commandMutex = Mutex()
    private val malformedMicDrops = AtomicInteger(0)
    private val unhandledNotifications = AtomicInteger(0)

    @Volatile
    private var leftArm: ArmConnection? = null

    @Volatile
    private var rightArm: ArmConnection? = null

    @Volatile
    private var microphoneSession: PushMicrophoneSession? = null

    @Volatile
    private var heartbeatJob: Job? = null

    private var pendingConnectAdapter: BluetoothAdapter? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    override suspend fun beforeConnect(): Result<Unit>? {
        pendingConnectAdapter = null
        if (!hasBlePermission()) {
            return Result.failure(GlassesError.PermissionDenied)
        }
        val adapter = bluetoothAdapter
            ?: return Result.failure(GlassesError.Transport("Bluetooth adapter not available"))
        if (!adapter.isEnabled) {
            return Result.failure(GlassesError.Transport("Bluetooth adapter is disabled"))
        }
        pendingConnectAdapter = adapter
        return null
    }

    override suspend fun doConnect() {
        val adapter = pendingConnectAdapter
            ?: bluetoothAdapter
            ?: throw GlassesError.Transport("Bluetooth adapter not available")
        pendingConnectAdapter = null

        withContext(Dispatchers.IO) {
            try {
                withTimeout(options.connectTimeoutMs) {
                    emitLog("Even G1: scanning for paired left/right arms...")
                    val pair = scanForPair(adapter)
                    emitLog("Even G1: found channel ${pair.channel}; connecting both arms...")

                    var connectedLeft: ArmConnection? = null
                    var connectedRight: ArmConnection? = null
                    try {
                        supervisorScope {
                            val leftDeferred = async { connectArm(pair.left.device, EvenArm.LEFT) }
                            val rightDeferred = async { connectArm(pair.right.device, EvenArm.RIGHT) }
                            val leftResult = runCatching { leftDeferred.await() }
                            val rightResult = runCatching { rightDeferred.await() }

                            connectedLeft = leftResult.getOrNull()
                            connectedRight = rightResult.getOrNull()

                            val failure = leftResult.exceptionOrNull() ?: rightResult.exceptionOrNull()
                            if (failure != null) {
                                connectedLeft?.close()
                                connectedRight?.close()
                                if (failure is CancellationException) throw failure
                                throw failure
                            }
                        }

                        leftArm = connectedLeft
                        rightArm = connectedRight
                        sendInitialPackets()
                        startHeartbeat()
                        emitLog("Even G1: connected channel ${pair.channel}")
                    } catch (ce: CancellationException) {
                        connectedLeft?.close()
                        connectedRight?.close()
                        closeConnections()
                        throw ce
                    } catch (e: Exception) {
                        connectedLeft?.close()
                        connectedRight?.close()
                        closeConnections()
                        throw e
                    }
                }
            } catch (_: TimeoutCancellationException) {
                closeConnections()
                throw GlassesError.Timeout("Even G1 connect")
            } catch (ce: CancellationException) {
                closeConnections()
                throw ce
            }
        }
    }

    override fun mapConnectError(error: Exception): GlassesError =
        (error as? GlassesError) ?: GlassesError.Transport("Even G1 connect failed: ${error.message}", error)

    override suspend fun disconnect() {
        try {
            microphoneSession?.stop()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }
        microphoneSession = null
        closeConnections()
        resetCapabilities()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> =
        Result.failure(GlassesError.Unsupported("Even Realities G1 has no camera path in this adapter."))

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) {
            return Result.failure(GlassesError.NotConnected)
        }
        if (!hasBlePermission()) {
            return Result.failure(GlassesError.PermissionDenied)
        }

        var attemptedDisplayPacket = false
        return try {
            if (text.isEmpty()) {
                sendCommandBoth(EvenClearScreenProtocol.frame(), ::isGenericAck)
                return Result.success(Unit)
            }

            val rawPages = EvenTextProtocol.validatedDisplayPages(text).ifEmpty { listOf(text) }
            val sync = textSequence.getAndIncrement() and 0xFF
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
            emitLog("Even G1: display sent ${text.length} chars across ${rawPages.size} page(s)")
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (attemptedDisplayPacket) {
                bestEffortClearScreen()
            }
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Even G1 display failed: ${e.message}", e))
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
        if (!hasBlePermission()) {
            return Result.failure(GlassesError.PermissionDenied)
        }

        val right = rightArm ?: return Result.failure(GlassesError.NotConnected)
        return try {
            microphoneSession?.stop()
            stopMicrophoneOnRight()
            synchronized(micSequenceLock) {
                micSequenceTracker.reset()
            }

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
                return Result.failure(GlassesError.Transport("Even G1 microphone enable was not acknowledged"))
            }

            emitLog("Even G1: microphone enabled; streaming raw LC3 frames")
            Result.success(session)
        } catch (ce: CancellationException) {
            microphoneSession = null
            throw ce
        } catch (e: Exception) {
            microphoneSession = null
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Even G1 startMicrophone failed: ${e.message}", e))
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
        } catch (e: Exception) {
            emitWarn("Even G1: microphone disable failed: ${e.message}")
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
            throw GlassesError.Transport("Even G1 initial left-arm packet write failed")
        }
        if (!right.write(packet)) {
            throw GlassesError.Transport("Even G1 initial right-arm packet write failed")
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val seq = heartbeatSequence.getAndIncrement()
                val frame = EvenHeartbeatProtocol.frame(seq)
                try {
                    sendCommandBoth(frame, EvenHeartbeatProtocol::isAck)
                    consecutiveFailures = 0
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    consecutiveFailures += 1
                    val deadLink = e === GlassesError.NotConnected || consecutiveFailures >= HEARTBEAT_FAILURE_LIMIT
                    if (deadLink) {
                        emitWarn(
                            "Even G1: heartbeat failed $consecutiveFailures time(s); tearing down link: ${e.message}",
                        )
                        teardownAfterLinkLoss("heartbeat failure", e)
                        return@launch
                    }
                    emitWarn("Even G1: heartbeat failed: ${e.message}")
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
            throw GlassesError.Transport("Even G1 left-arm command 0x${packet.commandHex()} rejected")
        }

        val rightAck = right.request(packet)
        if (!accepted(rightAck)) {
            throw GlassesError.Transport("Even G1 right-arm command 0x${packet.commandHex()} rejected")
        }
    }

    private suspend fun bestEffortClearScreen() {
        try {
            sendCommandBoth(EvenClearScreenProtocol.frame(), ::isGenericAck)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }
    }

    private fun isGenericAck(packet: ByteArray): Boolean = EvenResponses.isSuccessOrContinue(packet)

    private fun hasBlePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private suspend fun scanForPair(adapter: BluetoothAdapter): EvenScanPair {
        val scanner = adapter.bluetoothLeScanner
            ?: throw GlassesError.Transport("Bluetooth LE scanner not available")

        return suspendCancellableCoroutine { cont ->
            val completed = AtomicBoolean(false)
            val byChannel = linkedMapOf<String, MutableMap<EvenArm, EvenScannedArm>>()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            var scanCallback: ScanCallback? = null

            fun stop() {
                try {
                    scanCallback?.let { scanner.stopScan(it) }
                } catch (_: Exception) {
                }
            }

            fun finish(pair: EvenScanPair) {
                if (completed.compareAndSet(false, true)) {
                    stop()
                    cont.resume(pair, onCancellation = null)
                }
            }

            fun fail(error: Exception) {
                if (completed.compareAndSet(false, true)) {
                    stop()
                    cont.resumeWithException(error)
                }
            }

            val seenAddresses = mutableSetOf<String>()
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val device = result?.device ?: return
                    if (!seenAddresses.add(device.address)) return
                    val parsed = EvenDeviceNames.parse(device.name) ?: return
                    val arm = EvenScannedArm(device = device, parsed = parsed)
                    val channelArms = byChannel.getOrPut(parsed.channel) { linkedMapOf() }
                    channelArms[parsed.arm] = arm
                    val left = channelArms[EvenArm.LEFT]
                    val right = channelArms[EvenArm.RIGHT]
                    if (left != null && right != null) {
                        finish(EvenScanPair(channel = parsed.channel, left = left, right = right))
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    fail(GlassesError.Transport("Even G1 BLE scan failed: $errorCode"))
                }
            }

            cont.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    stop()
                }
            }

            try {
                scanner.startScan(null, settings, scanCallback)
            } catch (e: Exception) {
                fail(GlassesError.Transport("Even G1 startScan failed: ${e.message}", e))
            }
        }
    }

    private suspend fun connectArm(device: BluetoothDevice, arm: EvenArm): ArmConnection =
        suspendCancellableCoroutine { cont ->
            val completed = AtomicBoolean(false)
            val connection = ArmConnection(arm = arm, device = device)

            fun succeed() {
                if (completed.compareAndSet(false, true)) {
                    cont.resume(connection, onCancellation = null)
                }
            }

            fun fail(error: Exception) {
                if (completed.compareAndSet(false, true)) {
                    connection.close()
                    cont.resumeWithException(error)
                }
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val error = if (status != BluetoothGatt.GATT_SUCCESS) {
                            GlassesError.Transport("Even G1 $arm GATT error $status")
                        } else {
                            GlassesError.Transport("Even G1 $arm disconnected")
                        }
                        connection.failPending(error)
                        if (completed.compareAndSet(false, true)) {
                            connection.close()
                            cont.resumeWithException(error)
                        } else {
                            handleUnexpectedDisconnect(connection, error)
                        }
                        return
                    }

                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            emitLog("Even G1: $arm GATT connected; discovering services")
                            if (!gatt.discoverServices()) {
                                fail(GlassesError.Transport("Even G1 $arm service discovery did not start"))
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail(GlassesError.Transport("Even G1 $arm service discovery failed: $status"))
                        return
                    }

                    val service = gatt.getService(NORDIC_UART_SERVICE_UUID)
                    val tx = service?.getCharacteristic(NORDIC_UART_TX_UUID)
                    val rx = service?.getCharacteristic(NORDIC_UART_RX_UUID)
                    if (service == null || tx == null || rx == null) {
                        fail(GlassesError.Transport("Even G1 $arm Nordic UART service/characteristics missing"))
                        return
                    }

                    connection.gatt = gatt
                    connection.txCharacteristic = tx
                    connection.rxCharacteristic = rx

                    scope.launch {
                        try {
                            if (!connection.enableNotifications()) {
                                fail(GlassesError.Transport("Even G1 $arm notification descriptor write failed"))
                                return@launch
                            }
                            val mtu = connection.requestMtu(REQUESTED_MTU)
                            if (mtu == null) {
                                emitWarn(
                                    "Even G1: $arm MTU request timed out or failed; display/mic may fail",
                                )
                            } else {
                                emitLog("Even G1: $arm MTU granted $mtu")
                                if (mtu < MIN_DISPLAY_MIC_MTU) {
                                    emitWarn(
                                        "Even G1: $arm MTU $mtu is below $MIN_DISPLAY_MIC_MTU; display/mic may fail",
                                    )
                                }
                            }
                            succeed()
                        } catch (ce: CancellationException) {
                            fail(ce)
                        } catch (e: Exception) {
                            fail((e as? GlassesError) ?: GlassesError.Transport("Even G1 $arm setup failed: ${e.message}", e))
                        }
                    }
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    connection.handleDescriptorWrite(descriptor, status)
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    connection.handleMtuChanged(mtu, status)
                }

                @Deprecated("Deprecated by Android API 33, kept for pre-33 callbacks")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    val value = characteristic.value
                    if (value == null) {
                        emitLog("Even G1: $arm notification callback had null characteristic.value")
                        return
                    }
                    handleNotification(connection, value)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    handleNotification(connection, value)
                }
            }

            connection.gatt = device.connectGatt(context, false, callback)
            if (connection.gatt == null) {
                fail(GlassesError.Transport("Even G1 $arm connectGatt returned null"))
            }

            cont.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    connection.close()
                }
            }
        }

    private fun handleNotification(connection: ArmConnection, value: ByteArray) {
        when (val mic = EvenMicProtocol.parseNotification(value)) {
            is EvenMicNotificationResult.Frame -> {
                val sequence = synchronized(micSequenceLock) {
                    micSequenceTracker.next(mic.frame.sequence)
                } ?: return
                microphoneSession?.emit(mic.frame.lc3Bytes, sequence)
                return
            }
            is EvenMicNotificationResult.Malformed -> {
                val count = malformedMicDrops.incrementAndGet()
                if (shouldRateLimitLog(count)) {
                    emitWarn(
                        "Even G1: dropped malformed mic notification from ${connection.arm}; size=${mic.size}",
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

        val count = unhandledNotifications.incrementAndGet()
        if (shouldRateLimitLog(count)) {
            emitLog("Even G1: unhandled ${connection.arm} notification firstByte=0x${value.commandHex()}")
        }
    }

    private fun handleUnexpectedDisconnect(connection: ArmConnection, error: Exception) {
        emitWarn("Even G1: ${connection.arm} arm disconnected: ${error.message}")
        teardownAfterLinkLoss("${connection.arm} disconnect", error)
    }

    private fun teardownAfterLinkLoss(reason: String, error: Exception) {
        val session = microphoneSession
        microphoneSession = null
        val eosSequence = synchronized(micSequenceLock) {
            micSequenceTracker.nextEndOfStreamSequence()
        }
        session?.emitEndOfStream(eosSequence)
        closeConnections()
        resetCapabilities()
        emitLog("Even G1: disconnected after $reason: ${error.message}")
        _state.value = ConnectionState.Disconnected
    }

    private fun shouldRateLimitLog(count: Int): Boolean =
        count == 1 || count % RATE_LIMIT_EVERY == 0

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun closeConnections() {
        stopHeartbeat()
        leftArm?.close()
        rightArm?.close()
        leftArm = null
        rightArm = null
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
        writeType: Int,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    private inner class ArmConnection(
        val arm: EvenArm,
        val device: BluetoothDevice,
    ) {
        @Volatile
        var gatt: BluetoothGatt? = null

        @Volatile
        var txCharacteristic: BluetoothGattCharacteristic? = null

        @Volatile
        var rxCharacteristic: BluetoothGattCharacteristic? = null

        private val lock = Any()
        private val pendingResponses = mutableMapOf<Int, CancellableContinuation<ByteArray>>()
        private var descriptorContinuation: CancellableContinuation<Boolean>? = null
        private var mtuContinuation: CancellableContinuation<Int?>? = null

        suspend fun enableNotifications(): Boolean {
            val gatt = gatt ?: return false
            val rx = rxCharacteristic ?: return false
            if (!gatt.setCharacteristicNotification(rx, true)) return false
            val descriptor = rx.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return false

            return suspendCancellableCoroutine { cont ->
                synchronized(lock) {
                    descriptorContinuation = cont
                }
                val started = writeDescriptorCompat(
                    gatt = gatt,
                    descriptor = descriptor,
                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                )
                if (!started) {
                    val stillOwned = synchronized(lock) {
                        if (descriptorContinuation === cont) {
                            descriptorContinuation = null
                            true
                        } else {
                            false
                        }
                    }
                    if (stillOwned) {
                        cont.resume(false, onCancellation = null)
                    }
                }
                cont.invokeOnCancellation {
                    synchronized(lock) {
                        if (descriptorContinuation === cont) {
                            descriptorContinuation = null
                        }
                    }
                }
            }
        }

        fun handleDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            val pending = synchronized(lock) {
                val cont = descriptorContinuation
                descriptorContinuation = null
                cont
            }
            pending?.resume(status == BluetoothGatt.GATT_SUCCESS, onCancellation = null)
        }

        suspend fun requestMtu(requestedMtu: Int): Int? {
            val gatt = gatt ?: return null
            return try {
                withTimeout(MTU_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        synchronized(lock) {
                            mtuContinuation = cont
                        }
                        val started = gatt.requestMtu(requestedMtu)
                        if (!started) {
                            val stillOwned = synchronized(lock) {
                                if (mtuContinuation === cont) {
                                    mtuContinuation = null
                                    true
                                } else {
                                    false
                                }
                            }
                            if (stillOwned) {
                                cont.resume(null, onCancellation = null)
                            }
                        }
                        cont.invokeOnCancellation {
                            synchronized(lock) {
                                if (mtuContinuation === cont) {
                                    mtuContinuation = null
                                }
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                synchronized(lock) {
                    if (mtuContinuation != null) {
                        mtuContinuation = null
                    }
                }
                null
            }
        }

        fun handleMtuChanged(mtu: Int, status: Int) {
            val pending = synchronized(lock) {
                val cont = mtuContinuation
                mtuContinuation = null
                cont
            }
            pending?.resume(if (status == BluetoothGatt.GATT_SUCCESS) mtu else null, onCancellation = null)
        }

        suspend fun request(packet: ByteArray, timeoutMs: Long = COMMAND_TIMEOUT_MS): ByteArray {
            if (packet.isEmpty()) {
                throw GlassesError.Transport("Even G1 cannot send empty command")
            }
            val command = packet[0].toInt() and 0xFF
            return try {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        synchronized(lock) {
                            pendingResponses.remove(command)?.resumeWithException(
                                GlassesError.Transport("Even G1 $arm command 0x${packet.commandHex()} superseded"),
                            )
                            pendingResponses[command] = cont
                        }

                        val started = write(packet)
                        if (!started) {
                            val stillOwned = synchronized(lock) {
                                if (pendingResponses[command] === cont) {
                                    pendingResponses.remove(command)
                                    true
                                } else {
                                    false
                                }
                            }
                            if (stillOwned) {
                                cont.resumeWithException(
                                    GlassesError.Transport("Even G1 $arm command 0x${packet.commandHex()} write failed"),
                                )
                            }
                        }

                        cont.invokeOnCancellation {
                            synchronized(lock) {
                                if (pendingResponses[command] === cont) {
                                    pendingResponses.remove(command)
                                }
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw GlassesError.Timeout("Even G1 $arm command 0x${packet.commandHex()}")
            }
        }

        fun write(packet: ByteArray): Boolean {
            val gatt = gatt ?: return false
            val tx = txCharacteristic ?: return false
            return writeCharacteristicCompat(
                gatt = gatt,
                characteristic = tx,
                value = packet,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
        }

        fun completeResponse(packet: ByteArray): Boolean {
            if (packet.isEmpty()) return false
            val command = packet[0].toInt() and 0xFF
            val pending = synchronized(lock) { pendingResponses.remove(command) }
            pending?.resume(packet, onCancellation = null)
            return pending != null
        }

        fun failPending(error: Exception) {
            val pending = synchronized(lock) {
                val allResponses = pendingResponses.values.toList()
                pendingResponses.clear()
                val pendingDescriptor = descriptorContinuation
                descriptorContinuation = null
                val pendingMtu = mtuContinuation
                mtuContinuation = null
                PendingContinuations(
                    responses = allResponses,
                    descriptor = pendingDescriptor,
                    mtu = pendingMtu,
                )
            }
            pending.responses.forEach { it.resumeWithException(error) }
            pending.descriptor?.resumeWithException(error)
            pending.mtu?.resumeWithException(error)
        }

        fun close() {
            failPending(GlassesError.Transport("Even G1 $arm connection closed"))
            try {
                gatt?.disconnect()
            } catch (_: Exception) {
            }
            try {
                gatt?.close()
            } catch (_: Exception) {
            }
            gatt = null
            txCharacteristic = null
            rxCharacteristic = null
        }
    }

    data class EvenOptions(
        val connectTimeoutMs: Long = 30_000,
    )

    private data class EvenScannedArm(
        val device: BluetoothDevice,
        val parsed: EvenDeviceName,
    )

    private data class EvenScanPair(
        val channel: String,
        val left: EvenScannedArm,
        val right: EvenScannedArm,
    )

    private data class PendingContinuations(
        val responses: List<CancellableContinuation<ByteArray>>,
        val descriptor: CancellableContinuation<Boolean>?,
        val mtu: CancellableContinuation<Int?>?,
    )

    companion object {
        private const val TAG = "EvenGlassesClient"
        private const val COMMAND_TIMEOUT_MS = 2_000L
        private const val MTU_TIMEOUT_MS = 2_000L
        // Source: official Android setup requests MTU 251 before the initial packet;
        // /tmp/even-ref/EvenDemoApp/android/.../BleManager.kt line 281.
        private const val REQUESTED_MTU = 251
        // Transport threshold: 200-byte app payload plus the 3-byte ATT header, not a protocol byte.
        private const val MIN_DISPLAY_MIC_MTU = 203
        private const val HEARTBEAT_FAILURE_LIMIT = 2
        private const val RATE_LIMIT_EVERY = 50

        private val NORDIC_UART_SERVICE_UUID: UUID = UUID.fromString(EvenBleUuids.NORDIC_UART_SERVICE)
        private val NORDIC_UART_TX_UUID: UUID = UUID.fromString(EvenBleUuids.NORDIC_UART_TX)
        private val NORDIC_UART_RX_UUID: UUID = UUID.fromString(EvenBleUuids.NORDIC_UART_RX)
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString(EvenBleUuids.CLIENT_CHARACTERISTIC_CONFIG)
    }
}

private fun ByteArray.commandHex(): String =
    if (isEmpty()) "??" else (this[0].toInt() and 0xFF).toString(16).padStart(2, '0')
