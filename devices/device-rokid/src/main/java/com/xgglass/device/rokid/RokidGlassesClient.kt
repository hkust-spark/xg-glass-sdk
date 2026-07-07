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
import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.PhotoPathCallback
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import android.media.AudioAttributes
import com.xgglass.core.AudioChunk
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    private val display = RokidDisplayController()

    private val prefs by lazy { activity.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE) }

    private val scanResultMap: ConcurrentHashMap<String, BluetoothDevice> = ConcurrentHashMap()
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mgr.adapter
    }

    @Volatile private var wifiReady: Boolean = false
    @Volatile private var btReady: Boolean = false
    @Volatile private var activeMic: MicrophoneSession? = null

    override suspend fun doConnect() {
        emitLog("Rokid: connecting (BT + Wi‑Fi P2P)...")

        withTimeout(options.connectTimeoutMs) {
            // 1) Bluetooth
            ensureBluetoothConnected()
            // 2) Wi‑Fi P2P
            ensureWifiP2pConnected()
        }
    }

    override fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError) ?: GlassesError.Transport("Rokid connect failed: ${error.message}", error)
    }

    override suspend fun disconnect() {
        emitLog("Rokid: disconnecting...")
        try {
            activeMic?.stop()
        } catch (_: Exception) {}
        activeMic = null
        try {
            CxrApi.getInstance().deinitWifiP2P()
        } catch (_: Exception) {}
        try {
            CxrApi.getInstance().deinitBluetooth()
        } catch (_: Exception) {}
        try {
            stopScan()
        } catch (_: Exception) {}
        try {
            display.close()
        } catch (_: Exception) {}
        wifiReady = false
        btReady = false
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (_state.value !is ConnectionState.Connected || !btReady || !wifiReady) {
            return Result.failure(GlassesError.NotConnected)
        }

        val quality = options.photoQuality.toRokidJpegQuality(this.options.defaultJpegQuality)
        val width = options.targetWidth ?: this.options.defaultWidth
        val height = options.targetHeight ?: this.options.defaultHeight

        return try {
            val bytes = withTimeout(options.timeoutMs) {
                val remotePath = takeGlassPhotoSuspend(width, height, quality)
                val localPath = syncSingleFileSuspend(remotePath)
                File(localPath).readBytes()
            }
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
            val err = (e as? GlassesError) ?: GlassesError.Transport("Rokid capture failed: ${e.message}", e)
            Result.failure(err)
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected || !btReady) {
            return Result.failure(GlassesError.NotConnected)
        }
        val finalText = when (options.mode) {
            DisplayMode.REPLACE -> text
            DisplayMode.APPEND -> display.lastText + text
        }
        return try {
            withContext(Dispatchers.Main) {
                display.showText(finalText, force = options.force)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(GlassesError.Transport("Rokid display failed: ${e.message}", e))
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

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (_state.value !is ConnectionState.Connected || !btReady) return Result.failure(GlassesError.NotConnected)
        if (activeMic != null) return Result.failure(GlassesError.Busy)

        // Rokid supports PCM or OPUS streams. Sample rate/bit depth are not exposed here.
        val encoding = when (options.preferredEncoding) {
            AudioEncoding.OPUS -> AudioEncoding.OPUS
            AudioEncoding.LC3 -> return Result.failure(GlassesError.Unsupported("Rokid microphone: LC3 not supported"))
            AudioEncoding.PCM_S8, AudioEncoding.PCM_S16_LE -> AudioEncoding.PCM_S16_LE
        }
        val codecType = when (encoding) {
            AudioEncoding.OPUS -> 2
            AudioEncoding.LC3 -> 2
            else -> 1 // pcm
        }

        val streamType = "xgglass"
        val fmt = AudioFormat(encoding = encoding, sampleRateHz = null, channelCount = null)

        return try {
            val shared = MutableSharedFlow<AudioChunk>(
                extraBufferCapacity = 128,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val running = AtomicBoolean(true)
            val seq = AtomicLong(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            fun finishStream() {
                if (!running.compareAndSet(true, false)) return
                scope.cancel()
                activeMic = null
                shared.tryEmit(
                    AudioChunk(
                        bytes = ByteArray(0),
                        format = fmt,
                        sequence = seq.incrementAndGet(),
                        endOfStream = true,
                    )
                )
            }

            val listener = object : AudioStreamListener {
                override fun onStartAudioStream(
                    streamId: Int,
                    codecType: Int,
                    modeOrChannels: Int,
                    streamType: String?
                ) {
                    emitLog(
                        "Rokid: audio stream started id=$streamId codec=$codecType " +
                            "modeOrChannels=$modeOrChannels stream=$streamType"
                    )
                }

                override fun onAudioStream(streamId: Int, data: ByteArray?, offset: Int, length: Int) {
                    if (!running.get()) return
                    if (data == null) return
                    if (length <= 0) return
                    val start = offset.coerceAtLeast(0)
                    val end = (offset + length).coerceAtMost(data.size)
                    if (end <= start) return
                    val bytes = data.copyOfRange(start, end)
                    shared.tryEmit(
                        AudioChunk(
                            bytes = bytes,
                            format = fmt,
                            sequence = seq.incrementAndGet(),
                        )
                    )
                }

                override fun onAudioStreamFinish(streamId: Int) {
                    emitLog("Rokid: audio stream finished id=$streamId")
                    finishStream()
                }
            }

            // Register listener first to avoid losing the first chunks.
            CxrApi.getInstance().setAudioStreamListener(listener)

            // CXR-M 1.2.x replaces openAudioRecord(codec, cmd) with explicit
            // (codec, mode, intent, denoiseMode). Decompiled 1.2.2 bytecode
            // confirms denoiseMode=2 is the SDK default; no sample-rate or
            // channel-count parameter is exposed on the microphone capture API.
            // Keep the added mode value centralized until the compatibility
            // capture mode can be revalidated on Rokid hardware.
            val st = CxrApi.getInstance().openAudioRecord(
                codecType,
                ROKID_AUDIO_RECORD_MODE_COMPAT,
                streamType,
                ROKID_AUDIO_DENOISE_MODE_DEFAULT,
            )
            if (st == ValueUtil.CxrStatus.REQUEST_FAILED) {
                CxrApi.getInstance().setAudioStreamListener(null)
                return Result.failure(GlassesError.Transport("Rokid openAudioRecord REQUEST_FAILED"))
            }
            if (st == ValueUtil.CxrStatus.REQUEST_WAITING) {
                CxrApi.getInstance().setAudioStreamListener(null)
                return Result.failure(GlassesError.Busy)
            }

            val session = object : MicrophoneSession {
                override val format: AudioFormat = fmt
                override val audio: Flow<AudioChunk> = shared

                override suspend fun stop() {
                    if (!running.get()) return
                    try {
                        CxrApi.getInstance().closeAudioRecord(streamType)
                    } catch (_: Exception) {}
                    try {
                        CxrApi.getInstance().setAudioStreamListener(null)
                    } catch (_: Exception) {}
                    finishStream()
                }
            }

            activeMic = session
            Result.success(session)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Rokid startMicrophone failed: ${e.message}", e))
        }
    }

    // -----------------------
    // Bluetooth + Wi‑Fi P2P
    // -----------------------

    private suspend fun ensureBluetoothConnected() {
        // Prefer reconnect if we have cached info.
        val socketUuid = prefs.getString(PREF_KEY_SOCKET_UUID, null)?.trim().orEmpty()
        val macAddress = prefs.getString(PREF_KEY_MAC_ADDRESS, null)?.trim().orEmpty()
        if (socketUuid.isNotBlank() && macAddress.isNotBlank()) {
            emitLog("Rokid: trying BT reconnect...")
            try {
                connectBluetoothSuspend(socketUuid, macAddress, useApplicationContext = true)
                btReady = true
                return
            } catch (e: Exception) {
                // Common in practice: cached reconnect info becomes stale after re-pair/reset/firmware changes.
                // Fall back to scan+init flow automatically.
                emitWarn("Rokid: BT reconnect failed, falling back to scan/init: ${e.message}")
                clearReconnectInfo()
            }
        }

        emitLog("Rokid: scanning for device...")
        val device = scanFirstDeviceSuspend()
        emitLog("Rokid: initBluetooth for ${device.address}")
        val (uuid, mac) = initBluetoothSuspend(device)
        saveReconnectInfo(uuid, mac)
        emitLog("Rokid: connectBluetooth...")
        connectBluetoothSuspend(uuid, mac, useApplicationContext = false)
        btReady = true
    }

    private suspend fun ensureWifiP2pConnected() {
        if (wifiReady) return

        emitLog("Rokid: initWifiP2P...")
        suspendCancellableCoroutine<Unit> { cont ->
            var completed = false
            val status = CxrApi.getInstance().initWifiP2P(object : WifiP2PStatusCallback {
                override fun onConnected() {
                    wifiReady = true
                    emitLog("Rokid: Wi‑Fi P2P connected")
                    if (!completed) {
                        completed = true
                        cont.resume(Unit)
                    }
                }

                override fun onDisconnected() {
                    wifiReady = false
                    emitWarn("Rokid: Wi‑Fi P2P disconnected")
                }

                override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
                    wifiReady = false
                    emitWarn("Rokid: Wi‑Fi P2P init failed: $errorCode")
                    if (!completed) {
                        completed = true
                        cont.resumeWithException(GlassesError.Transport("Rokid initWifiP2P failed: $errorCode"))
                    }
                }

                override fun onP2pDeviceAvailable(name: String?, address: String?, info: String?) {
                    emitLog("Rokid: Wi‑Fi P2P device available name=$name address=$address info=$info")
                }
            })

            if (status == ValueUtil.CxrStatus.REQUEST_FAILED && !completed) {
                completed = true
                cont.resumeWithException(GlassesError.Transport("Rokid initWifiP2P REQUEST_FAILED"))
            }
        }
    }

    // -----------------------
    // Photo capture + sync
    // -----------------------

    private suspend fun takeGlassPhotoSuspend(width: Int, height: Int, quality: Int): String =
        suspendCancellableCoroutine { cont ->
            val status = CxrApi.getInstance().takeGlassPhoto(width, height, quality, object : PhotoPathCallback {
                override fun onPhotoPath(status: ValueUtil.CxrStatus?, path: String?) {
                    if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED && !path.isNullOrBlank()) {
                        cont.resume(path)
                    } else {
                        cont.resumeWithException(
                            GlassesError.Transport("Rokid takeGlassPhoto failed: status=$status path=$path")
                        )
                    }
                }
            })
            if (status == ValueUtil.CxrStatus.REQUEST_FAILED) {
                cont.resumeWithException(GlassesError.Transport("Rokid takeGlassPhoto REQUEST_FAILED"))
            }
        }

    private suspend fun syncSingleFileSuspend(remotePath: String): String =
        suspendCancellableCoroutine { cont ->
            val saveDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: run {
                    cont.resumeWithException(GlassesError.Transport("No external pictures dir"))
                    return@suspendCancellableCoroutine
                }

            // CXR-M concatenates with string addition internally; always include trailing "/"
            val savePath = saveDir.absolutePath + File.separator

            val ok = CxrApi.getInstance().syncSingleFile(
                savePath,
                ValueUtil.CxrMediaType.PICTURE,
                remotePath,
                object : SyncStatusCallback {
                    override fun onSyncStart() = Unit
                    override fun onSingleFileSynced(fileName: String?) {
                        if (fileName.isNullOrBlank()) {
                            cont.resumeWithException(GlassesError.Transport("syncSingleFile returned empty fileName"))
                            return
                        }
                        cont.resume(fileName)
                    }

                    override fun onSyncFailed() {
                        cont.resumeWithException(GlassesError.Transport("syncSingleFile failed"))
                    }

                    override fun onSyncFinished() = Unit
                }
            )

            if (!ok) {
                cont.resumeWithException(GlassesError.Transport("syncSingleFile request failed (returned false)"))
            }
        }

    // -----------------------
    // BLE scan + connect
    // -----------------------

    @SuppressLint("MissingPermission")
    private suspend fun scanFirstDeviceSuspend(): BluetoothDevice = suspendCancellableCoroutine { cont ->
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            cont.resumeWithException(GlassesError.Transport("Bluetooth LE scanner not available"))
            return@suspendCancellableCoroutine
        }

        scanResultMap.clear()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(ROKID_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder().build()

        var completed = false
        lateinit var cb: ScanCallback
        cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                if (!scanResultMap.containsKey(device.address)) {
                    scanResultMap[device.address] = device
                    stopScan(cb)
                    if (!completed) {
                        completed = true
                        cont.resume(device)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan(cb)
                if (!completed) {
                    completed = true
                    cont.resumeWithException(GlassesError.Transport("BLE scan failed: $errorCode"))
                }
            }
        }

        // Make the scan cancellable so connect()'s withTimeout(connectTimeoutMs) actually stops it
        // when no Rokid device is present (otherwise the BLE scan runs forever and connect hangs).
        cont.invokeOnCancellation { stopScan(cb) }

        try {
            scanner.startScan(filters, settings, cb)
        } catch (e: Exception) {
            if (!completed) {
                completed = true
                cont.resumeWithException(GlassesError.Transport("startScan failed: ${e.message}", e))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(callback: ScanCallback? = null) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            if (callback != null) scanner.stopScan(callback)
        } catch (_: Exception) {}
    }

    private suspend fun initBluetoothSuspend(device: BluetoothDevice): Pair<String, String> =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            // Tear down the BT stack if connect() is cancelled/timed out mid-init.
            cont.invokeOnCancellation { runCatching { CxrApi.getInstance().deinitBluetooth() } }
            CxrApi.getInstance().initBluetooth(activity, device, object : BluetoothStatusCallback {
                override fun onConnectionInfo(
                    socketUuid: String?,
                    macAddress: String?,
                    rokidAccount: String?,
                    glassesType: Int
                ) {
                    if (!socketUuid.isNullOrBlank() && !macAddress.isNullOrBlank()) {
                        if (done.compareAndSet(false, true)) {
                            cont.resume(socketUuid to macAddress)
                        }
                    } else {
                        if (done.compareAndSet(false, true)) {
                            cont.resumeWithException(GlassesError.Transport("onConnectionInfo missing uuid/mac"))
                        }
                    }
                }

                override fun onConnected() = Unit
                override fun onInActiveConnected(socketUuid: String?, macAddress: String?) {
                    emitLog("Rokid: inactive Bluetooth connection available uuid=$socketUuid mac=$macAddress")
                }
                override fun onDisconnected() = Unit

                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                    if (done.compareAndSet(false, true)) {
                        cont.resumeWithException(GlassesError.Transport("initBluetooth failed: $errorCode"))
                    }
                }
            })
        }

    private suspend fun connectBluetoothSuspend(socketUuid: String, macAddress: String, useApplicationContext: Boolean) {
        suspendCancellableCoroutine<Unit> { cont ->
            val (snLc, clientSecret) = requireAuthorization()
            val ctx = if (useApplicationContext) activity.applicationContext else activity
            val done = AtomicBoolean(false)
            // Tear down the BT stack if connect() is cancelled/timed out mid-connect.
            cont.invokeOnCancellation { runCatching { CxrApi.getInstance().deinitBluetooth() } }
            CxrApi.getInstance().connectBluetooth(ctx, socketUuid, macAddress, object : BluetoothStatusCallback {
                override fun onConnectionInfo(
                    socketUuid: String?,
                    macAddress: String?,
                    rokidAccount: String?,
                    glassesType: Int
                ) = Unit

                override fun onConnected() {
                    if (done.compareAndSet(false, true)) {
                        cont.resume(Unit)
                    }
                }

                override fun onInActiveConnected(socketUuid: String?, macAddress: String?) {
                    emitLog("Rokid: inactive Bluetooth connection available uuid=$socketUuid mac=$macAddress")
                }

                override fun onDisconnected() {
                    if (done.compareAndSet(false, true)) {
                        cont.resumeWithException(GlassesError.Transport("connectBluetooth disconnected"))
                    }
                }

                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                    if (done.compareAndSet(false, true)) {
                        cont.resumeWithException(GlassesError.Transport("connectBluetooth failed: $errorCode"))
                    }
                }
            }, snLc, clientSecret)
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
