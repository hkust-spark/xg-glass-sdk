package com.xgglass.device.rokid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Environment
import android.os.ParcelUuid
import androidx.appcompat.app.AppCompatActivity
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.PhotoPathCallback
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import android.media.AudioAttributes
import com.xgglass.core.AudioEncoding
import com.xgglass.core.AudioFormat
import com.xgglass.core.AudioSource
import com.xgglass.core.BaseGlassesClient
import com.xgglass.core.CaptureOptions
import com.xgglass.core.CapturedImage
import com.xgglass.core.ConnectionState
import com.xgglass.core.DeviceCapabilities
import com.xgglass.core.DisplayMode
import com.xgglass.core.DisplayOptions
import com.xgglass.core.GlassesError
import com.xgglass.core.GlassesModel
import com.xgglass.core.MicrophoneOptions
import com.xgglass.core.MicrophoneSession
import com.xgglass.core.PcmFormat
import com.xgglass.core.PhotoQuality
import com.xgglass.core.PlayAudioOptions
import com.xgglass.core.android.playPcmViaAudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Rokid implementation of [GlassesClient].
 *
 * Internals:
 * - Bluetooth: scan -> initBluetooth() -> connectBluetooth()
 * - Wi‑Fi P2P: initWifiP2P() is required before syncSingleFile()
 * - Photo: takeGlassPhoto() returns remote path -> syncSingleFile() gives local absolute path -> readBytes()
 *
 * Notes:
 * - This SDK does NOT request runtime permissions; the host app must handle permissions.
 * - CXR-M v1.2.2 requires an SN authorization file (`.lc`) + developer `clientSecret` to connect.
 */
class RokidGlassesClient(
    private val activity: AppCompatActivity,
    private val options: RokidOptions = RokidOptions(),
) : BaseGlassesClient(
    initialCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        canRecordAudio = true,
        canPlayTts = true,
        canPlayAudioBytes = true,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = true,
    ),
) {

    override val model: GlassesModel = GlassesModel.ROKID

    private val display = RokidDisplayController(
        isConnected = { connection?.bluetoothReady == true },
        onAsyncFailure = { emitWarn("Rokid queued display failed: ${it.message}") },
    )

    private val prefs by lazy { activity.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE) }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mgr.adapter
    }

    private val transportMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var connection: RokidConnectionSession? = null
    private val btReady: Boolean get() = connection?.bluetoothReady == true
    private val microphoneLock = Any()
    @Volatile private var activeMic: RokidMicrophoneStream? = null

    override suspend fun doConnect() = transportMutex.withLock {
        // A previously lost connection may still have a queued cleanup job.
        // Finish its teardown before creating another SDK connection.
        connection?.let {
            it.close(GlassesError.Transport("Rokid connection replaced"))
            releaseTransport()
        }
        val session = RokidConnectionSession()
        connection = session
        emitLog("Rokid: connecting (BT + Wi-Fi P2P)...")
        try {
            withTimeout(options.connectTimeoutMs) {
                ensureBluetoothConnected(session)
                ensureWifiP2pConnected(session)
            }
            check(session.isReady) { "Rokid connection closed during setup" }
        } catch (e: Exception) {
            session.close(e)
            // This must also run when the caller's coroutine has been cancelled.
            withContext(kotlinx.coroutines.NonCancellable) { releaseTransport() }
            if (connection === session) connection = null
            throw e
        }
    }

    override fun shouldShortCircuitConnect(state: ConnectionState): Boolean =
        state is ConnectionState.Connected && connection?.isReady == true

    override fun publishConnectedState(): Boolean =
        connection?.publishIfReady { _state.value = ConnectionState.Connected } == true

    override fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError) ?: GlassesError.Transport("Rokid connect failed: ${error.message}", error)
    }

    private fun transportLost(session: RokidConnectionSession, error: GlassesError) {
        if (!session.close(error)) return
        if (connection === session) _state.value = ConnectionState.Disconnected
        emitWarn(error.message ?: "Rokid transport disconnected")
        cleanupScope.launch {
            transportMutex.withLock {
                if (connection === session) {
                    releaseTransport()
                    connection = null
                    _state.value = ConnectionState.Disconnected
                }
            }
        }
    }

    override suspend fun disconnect() {
        emitLog("Rokid: disconnecting...")
        // Unblock setup before waiting for its mutex.
        connection?.close(CancellationException("Rokid disconnected by caller"))
        withContext(kotlinx.coroutines.NonCancellable) {
            transportMutex.withLock {
                releaseTransport()
                connection = null
                _state.value = ConnectionState.Disconnected
            }
        }
    }

    private suspend fun releaseTransport() {
        synchronized(microphoneLock) {
            try { activeMic?.stopNow() } catch (_: Exception) {}
            activeMic = null
        }
        // Cancel queued display work while the transport is still available.
        try { display.close() } catch (_: Exception) {}
        try { stopScan() } catch (_: Exception) {}
        try { CxrApi.getInstance().deinitWifiP2P() } catch (_: Exception) {}
        try { CxrApi.getInstance().deinitBluetooth() } catch (_: Exception) {}
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        val connectionSession = connection
        if (_state.value !is ConnectionState.Connected || connectionSession?.isReady != true) {
            return Result.failure(GlassesError.NotConnected)
        }

        val quality = options.photoQuality.toRokidJpegQuality(this.options.defaultJpegQuality)
        val width = options.targetWidth ?: this.options.defaultWidth
        val height = options.targetHeight ?: this.options.defaultHeight

        return try {
            val bytes = withTimeoutOrNull(options.timeoutMs) {
                val remotePath = takeGlassPhotoSuspend(connectionSession, width, height, quality)
                val localPath = syncSingleFileSuspend(connectionSession, remotePath)
                withContext(Dispatchers.IO) { File(localPath).readBytes() }
            } ?: throw GlassesError.Transport("Rokid capture timed out after ${options.timeoutMs} ms")
            Result.success(
                CapturedImage(
                    jpegBytes = bytes,
                    width = width,
                    height = height,
                    rotationDegrees = null,
                    sourceModel = GlassesModel.ROKID,
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val err = (e as? GlassesError) ?: GlassesError.Transport("Rokid capture failed: ${e.message}", e)
            Result.failure(err)
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        val session = connection
        if (_state.value !is ConnectionState.Connected || session?.bluetoothReady != true) {
            return Result.failure(GlassesError.NotConnected)
        }
        return try {
            withContext(Dispatchers.Main) {
                if (connection !== session || !session.bluetoothReady) throw GlassesError.NotConnected
                display.showText(text, force = options.force, append = options.mode == DisplayMode.APPEND)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Rokid display failed: ${e.message}", e))
        }
    }

    override suspend fun playAudio(source: AudioSource, options: PlayAudioOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected || !btReady) return Result.failure(GlassesError.NotConnected)

        return when (source) {
            is AudioSource.Tts -> playTts(source, options)
            is AudioSource.RawBytes -> playRawBytes(source, options)
        }
    }

    private fun playTts(source: AudioSource.Tts, options: PlayAudioOptions): Result<Unit> {
        val content = source.text.trim()
        if (content.isEmpty()) return Result.success(Unit)

        return try {
            val rate = options.speechRate
            if (rate != null) {
                CxrApi.getInstance().setLocalTtsSpeed(rate.coerceIn(0.75f, 4.0f))
            }

            val st = CxrApi.getInstance().sendGlobalTtsContent(content)
            when (st) {
                ValueUtil.CxrStatus.REQUEST_SUCCEED -> Result.success(Unit)
                ValueUtil.CxrStatus.REQUEST_WAITING -> Result.failure(GlassesError.Busy)
                ValueUtil.CxrStatus.REQUEST_FAILED -> Result.failure(GlassesError.Transport("Rokid sendGlobalTtsContent REQUEST_FAILED"))
                else -> Result.failure(GlassesError.Transport("Rokid sendGlobalTtsContent status=$st"))
            }
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Rokid playAudio(TTS) failed: ${e.message}", e))
        }
    }

    /**
     * Play raw audio bytes on Rokid glasses via BT audio routing.
     *
     * Approach: route phone audio to glasses via [CxrApi.setCommunicationDevice],
     * play on the phone side with [AudioTrack], then restore routing.
     * Only PCM with explicit [PcmFormat] is supported for now.
     */
    private suspend fun playRawBytes(source: AudioSource.RawBytes, options: PlayAudioOptions): Result<Unit> {
        val data = source.data
        if (data.isEmpty()) return Result.success(Unit)
        val pcm = source.pcmFormat
            ?: return Result.failure(GlassesError.Unsupported(
                "Rokid playAudio(RawBytes) requires explicit PcmFormat (container auto-detect not supported)"
            ))
        if (pcm.encoding == AudioEncoding.OPUS || pcm.encoding == AudioEncoding.LC3) {
            return Result.failure(GlassesError.Unsupported("Rokid playAudio: ${pcm.encoding} not supported"))
        }

        return try {
            CxrApi.getInstance().setCommunicationDevice()

            try {
                playPcmViaAudioTrack(
                    data = data,
                    format = pcm,
                    usageAttributes = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    interrupt = options.interrupt,
                    unsupportedOpusMessage = "Rokid playAudio: OPUS not supported",
                    checkInitialized = false,
                ).getOrThrow()
            } finally {
                runCatching { CxrApi.getInstance().clearCommunicationDevice() }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            runCatching { CxrApi.getInstance().clearCommunicationDevice() }
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Rokid playAudio(RawBytes) failed: ${e.message}", e))
        }
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> = synchronized(microphoneLock) {
        val connectionSession = connection
        if (_state.value !is ConnectionState.Connected || connectionSession?.bluetoothReady != true) {
            return@synchronized Result.failure(GlassesError.NotConnected)
        }
        if (activeMic != null) return@synchronized Result.failure(GlassesError.Busy)
        val encoding = when (options.preferredEncoding) {
            AudioEncoding.OPUS -> AudioEncoding.OPUS
            AudioEncoding.LC3 -> return@synchronized Result.failure(GlassesError.Unsupported("Rokid microphone: LC3 not supported"))
            AudioEncoding.PCM_S8, AudioEncoding.PCM_S16_LE -> AudioEncoding.PCM_S16_LE
        }
        val codecType = if (encoding == AudioEncoding.OPUS) 2 else 1
        val streamType = "xgglass"
        val session = RokidMicrophoneStream(
            format = AudioFormat(encoding = encoding, sampleRateHz = null, channelCount = null),
            lock = microphoneLock,
            isCurrentConnection = { connection === connectionSession && connectionSession.isOpen },
            closeRecorder = { CxrApi.getInstance().closeAudioRecord(streamType) },
            onFinished = { finished ->
                if (activeMic === finished) {
                    activeMic = null
                    runCatching { CxrApi.getInstance().setAudioStreamListener(null) }
                }
            },
            warn = ::emitWarn,
        )
        activeMic = session
        try {
            CxrApi.getInstance().setAudioStreamListener(session)
            // 1.2.2's three-argument overload delegates with denoiseMode=2.
            // mode=1 preserves the migration's capture mode; validate this on hardware.
            val status = CxrApi.getInstance().openAudioRecord(
                codecType, ROKID_AUDIO_RECORD_MODE_COMPAT, streamType, ROKID_AUDIO_DENOISE_MODE_DEFAULT,
            )
            when (status) {
                ValueUtil.CxrStatus.REQUEST_SUCCEED -> {
                    if (session.isRunning) Result.success(session)
                    else Result.failure(GlassesError.Transport("Rokid audio stream ended during startup"))
                }
                ValueUtil.CxrStatus.REQUEST_WAITING -> {
                    session.cancelStart()
                    Result.failure(GlassesError.Busy)
                }
                else -> {
                    session.cancelStart()
                    Result.failure(GlassesError.Transport("Rokid openAudioRecord failed: $status"))
                }
            }
        } catch (e: Exception) {
            runCatching { session.stopNow() }
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Rokid startMicrophone failed: ${e.message}", e))
        }
    }

    // -----------------------
    // Bluetooth + Wi‑Fi P2P
    // -----------------------

    private suspend fun ensureBluetoothConnected(session: RokidConnectionSession) {
        val socketUuid = prefs.getString(PREF_KEY_SOCKET_UUID, null)?.trim().orEmpty()
        val macAddress = prefs.getString(PREF_KEY_MAC_ADDRESS, null)?.trim().orEmpty()
        if (socketUuid.isNotBlank() && macAddress.isNotBlank()) {
            emitLog("Rokid: trying BT reconnect...")
            try {
                connectBluetoothSuspend(session, socketUuid, macAddress, useApplicationContext = true)
                return
            } catch (e: Exception) {
                if (e is CancellationException || !session.isOpen) throw e
                emitWarn("Rokid: BT reconnect failed, falling back to scan/init: ${e.message}")
                clearReconnectInfo()
                runCatching { CxrApi.getInstance().deinitBluetooth() }
            }
        }
        emitLog("Rokid: scanning for device...")
        val device = scanFirstDeviceSuspend(session)
        val (uuid, mac) = initBluetoothSuspend(session, device)
        saveReconnectInfo(uuid, mac)
        connectBluetoothSuspend(session, uuid, mac, useApplicationContext = false)
    }

    private suspend fun ensureWifiP2pConnected(session: RokidConnectionSession) {
        if (session.wifiReady) return
        emitLog("Rokid: initWifiP2P...")
        val ready = session.newWaiter<Unit>()
        try {
            val status = CxrApi.getInstance().initWifiP2P(object : WifiP2PStatusCallback {
                override fun onConnected() = session.callback {
                    session.markWifiReady()
                    emitLog("Rokid: Wi-Fi P2P connected")
                    ready.complete(Unit)
                }

                override fun onDisconnected() = session.callback {
                    transportLost(session, GlassesError.Transport("Rokid Wi-Fi P2P disconnected"))
                }

                override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) = session.callback {
                    transportLost(session, GlassesError.Transport("Rokid Wi-Fi P2P failed: $errorCode"))
                }

                override fun onP2pDeviceAvailable(name: String?, address: String?, info: String?) = session.callback {
                    emitLog("Rokid: Wi-Fi P2P device available name=$name")
                }
            })
            if (status == ValueUtil.CxrStatus.REQUEST_FAILED) {
                ready.completeExceptionally(GlassesError.Transport("Rokid initWifiP2P REQUEST_FAILED"))
            }
            ready.await()
        } finally {
            ready.cancel()
            // doConnect tears down both transports on any setup failure or cancellation.
        }
    }

    // -----------------------
    // Photo capture + sync
    // -----------------------

    private suspend fun takeGlassPhotoSuspend(
        session: RokidConnectionSession, width: Int, height: Int, quality: Int,
    ): String {
        val photo = session.newWaiter<String>()
        try {
            val status = CxrApi.getInstance().takeGlassPhoto(width, height, quality, object : PhotoPathCallback {
                override fun onPhotoPath(status: ValueUtil.CxrStatus?, path: String?) = session.callback {
                    if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED && !path.isNullOrBlank()) {
                        photo.complete(path)
                    } else {
                        photo.completeExceptionally(GlassesError.Transport("Rokid takeGlassPhoto failed: $status"))
                    }
                }
            })
            when (status) {
                ValueUtil.CxrStatus.REQUEST_FAILED -> photo.completeExceptionally(GlassesError.Transport("Rokid takeGlassPhoto REQUEST_FAILED"))
                else -> Unit
            }
            return photo.await()
        } finally { photo.cancel() }
    }

    private suspend fun syncSingleFileSuspend(session: RokidConnectionSession, remotePath: String): String {
        val saveDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw GlassesError.Transport("No external pictures dir")
        val synced = session.newWaiter<String>()
        try {
            // The vendor concatenates the directory and filename directly.
            val ok = CxrApi.getInstance().syncSingleFile(
                saveDir.absolutePath + File.separator,
                ValueUtil.CxrMediaType.PICTURE,
                remotePath,
                object : SyncStatusCallback {
                    override fun onSyncStart() = Unit
                    override fun onSingleFileSynced(fileName: String?) = session.callback {
                        if (fileName.isNullOrBlank()) {
                            synced.completeExceptionally(GlassesError.Transport("syncSingleFile returned empty fileName"))
                        } else {
                            synced.complete(fileName)
                        }
                    }
                    override fun onSyncFailed() = session.callback {
                        synced.completeExceptionally(GlassesError.Transport("syncSingleFile failed"))
                    }
                    override fun onSyncFinished() = Unit
                },
            )
            if (!ok) synced.completeExceptionally(GlassesError.Transport("syncSingleFile request failed"))
            return synced.await()
        } finally { synced.cancel() }
    }

    // -----------------------
    // BLE scan + connect
    // -----------------------

    @Volatile private var activeScan: ScanCallback? = null

    @SuppressLint("MissingPermission")
    private suspend fun scanFirstDeviceSuspend(session: RokidConnectionSession): BluetoothDevice {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: throw GlassesError.Transport("Bluetooth LE scanner not available")
        val result = session.newWaiter<BluetoothDevice>()
        val filters = listOf(ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(ROKID_SERVICE_UUID)).build())
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scanResult: ScanResult?) = session.callback {
                val device = scanResult?.device ?: return@callback
                result.complete(device)
            }
            override fun onScanFailed(errorCode: Int) = session.callback {
                result.completeExceptionally(GlassesError.Transport("BLE scan failed: $errorCode"))
            }
        }
        activeScan = callback
        try {
            scanner.startScan(filters, ScanSettings.Builder().build(), callback)
            return result.await()
        } finally {
            result.cancel()
            stopScan(callback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(callback: ScanCallback? = activeScan) {
        if (callback == null) return
        if (activeScan === callback) activeScan = null
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private suspend fun initBluetoothSuspend(
        session: RokidConnectionSession,
        device: BluetoothDevice,
    ): Pair<String, String> {
        val info = session.newWaiter<Pair<String, String>>()
        val attempt = session.nextBluetoothAttempt()
        try {
            CxrApi.getInstance().initBluetooth(activity, device, object : BluetoothStatusCallback {
                override fun onConnectionInfo(
                    socketUuid: String?, macAddress: String?, rokidAccount: String?, glassesType: Int,
                ) = session.bluetoothCallback(attempt) {
                    if (!socketUuid.isNullOrBlank() && !macAddress.isNullOrBlank()) {
                        info.complete(socketUuid to macAddress)
                    } else {
                        info.completeExceptionally(GlassesError.Transport("onConnectionInfo missing uuid/mac"))
                    }
                }
                override fun onConnected() = Unit
                override fun onInActiveConnected(socketUuid: String?, macAddress: String?) = session.bluetoothCallback(attempt) {
                    emitWarn("Rokid: inactive Bluetooth connection during init; awaiting authorized connection info")
                }
                override fun onDisconnected() = session.bluetoothCallback(attempt) {
                    info.completeExceptionally(GlassesError.Transport("Rokid initBluetooth disconnected"))
                }
                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) = session.bluetoothCallback(attempt) {
                    info.completeExceptionally(GlassesError.Transport("Rokid initBluetooth failed: $errorCode"))
                }
            })
            return info.await()
        } finally {
            session.retireBluetoothAttempt(attempt)
            info.cancel()
        }
    }

    private suspend fun connectBluetoothSuspend(
        session: RokidConnectionSession,
        socketUuid: String,
        macAddress: String,
        useApplicationContext: Boolean,
    ) {
        val (snLc, clientSecret) = requireAuthorization()
        val ctx = if (useApplicationContext) activity.applicationContext else activity
        val ready = session.newWaiter<Unit>()
        val attempt = session.nextBluetoothAttempt()
        var connected = false
        try {
            CxrApi.getInstance().connectBluetooth(ctx, socketUuid, macAddress, object : BluetoothStatusCallback {
                override fun onConnectionInfo(
                    socketUuid: String?, macAddress: String?, rokidAccount: String?, glassesType: Int,
                ) = Unit
                override fun onConnected() = session.bluetoothCallback(attempt) {
                    session.markBluetoothReady()
                    ready.complete(Unit)
                }
                override fun onInActiveConnected(socketUuid: String?, macAddress: String?) = session.bluetoothCallback(attempt) {
                    emitWarn("Rokid: inactive Bluetooth connection; awaiting authorized onConnected callback")
                }
                override fun onDisconnected() = session.bluetoothCallback(attempt) {
                    val error = GlassesError.Transport("Rokid Bluetooth disconnected")
                    if (session.bluetoothReady) transportLost(session, error)
                    else {
                        session.retireBluetoothAttempt(attempt)
                        ready.completeExceptionally(error)
                    }
                }
                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) = session.bluetoothCallback(attempt) {
                    val error = GlassesError.Transport("Rokid Bluetooth failed: $errorCode")
                    if (session.bluetoothReady) transportLost(session, error)
                    else {
                        session.retireBluetoothAttempt(attempt)
                        ready.completeExceptionally(error)
                    }
                }
            }, snLc, clientSecret)
            ready.await()
            connected = true
        } finally {
            if (!connected) session.retireBluetoothAttempt(attempt)
            ready.cancel()
        }
    }

    private fun requireAuthorization(): Pair<ByteArray, String> {
        val auth = options.authorization
            ?: throw GlassesError.Transport(
                "Rokid authorization missing. CXR-M v1.2.2 requires SN authorization file (.lc) bytes + clientSecret. " +
                    "Provide them via RokidGlassesClient.RokidOptions(authorization = RokidAuthorization(...))."
            )
        if (auth.snLc.isEmpty()) {
            throw GlassesError.Transport("Rokid SN authorization file bytes are empty (.lc)")
        }
        val secret = auth.clientSecret.replace("-", "").trim()
        if (secret.isBlank()) {
            throw GlassesError.Transport("Rokid clientSecret is blank")
        }
        return auth.snLc to secret
    }

    private fun saveReconnectInfo(socketUuid: String, macAddress: String) {
        prefs.edit()
            .putString(PREF_KEY_SOCKET_UUID, socketUuid)
            .putString(PREF_KEY_MAC_ADDRESS, macAddress)
            .apply()
    }

    private fun clearReconnectInfo() {
        prefs.edit()
            .remove(PREF_KEY_SOCKET_UUID)
            .remove(PREF_KEY_MAC_ADDRESS)
            .apply()
    }

    data class RokidOptions(
        val connectTimeoutMs: Long = 30_000,
        val defaultWidth: Int = 2400,
        val defaultHeight: Int = 1800,
        val defaultJpegQuality: Int = 90,
        val authorization: RokidAuthorization? = null,
    )

    /**
     * CXR-M v1.2.2 Bluetooth connect requires:
     * - `snLc`: SN authorization file (`.lc`) bound to the device SN (downloaded from Rokid console)
     * - `clientSecret`: developer credential (will be normalized by removing `-`)
     *
     * Treat both as secrets and avoid committing them into git.
     */
    data class RokidAuthorization(
        val snLc: ByteArray,
        val clientSecret: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RokidAuthorization) return false

            return snLc.contentEquals(other.snLc) &&
                clientSecret == other.clientSecret
        }

        override fun hashCode(): Int {
            var result = snLc.contentHashCode()
            result = 31 * result + clientSecret.hashCode()
            return result
        }
    }

    private companion object {
        const val ROKID_SERVICE_UUID = "00009100-0000-1000-8000-00805f9b34fb"
        const val ROKID_AUDIO_RECORD_MODE_COMPAT = 1
        const val ROKID_AUDIO_DENOISE_MODE_DEFAULT = 2

        const val PREFS_BT = "xgglass_rokid_bt_reconnect"
        const val PREF_KEY_SOCKET_UUID = "socket_uuid"
        const val PREF_KEY_MAC_ADDRESS = "mac_address"
    }
}

private fun PhotoQuality.toRokidJpegQuality(defaultHigh: Int): Int = when (this) {
    PhotoQuality.LOWEST -> 25
    PhotoQuality.LOW -> 50
    PhotoQuality.MEDIUM -> 75
    PhotoQuality.HIGH -> defaultHigh
    PhotoQuality.HIGHEST -> 100
}.coerceIn(1, 100)
