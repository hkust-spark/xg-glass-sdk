package com.universalglasses.device.rayneo.installer

import android.content.Context
import android.net.Uri
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.DeviceCapabilities
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.core.MicrophoneOptions
import com.universalglasses.core.MicrophoneSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RayNeo (phone-side) client.
 *
 * This adapter maps `connect()` to "deploy/install the on-glasses APK" via ADB-over-TCP (adbd:5555).
 *
 * It intentionally does NOT support `capturePhoto()` / `display()` from the phone, because those
 * operations are executed inside the on-glasses app.
 */
class RayNeoInstallerGlassesClient(
    private val context: Context,
    private val config: RayNeoInstallerConfig,
) : GlassesClient {

    override val model: GlassesModel = GlassesModel.RAYNEO

    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        canCapturePhoto = false,
        canDisplayText = false,
        canRecordAudio = false,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = false,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 64)
    override val events: Flow<GlassesEvent> = _events

    private val connectMutex = Mutex()

    override suspend fun connect(): Result<Unit> = connectMutex.withLock {
        if (_state.value is ConnectionState.Connected) return Result.success(Unit)
        _state.value = ConnectionState.Connecting

        return withContext(Dispatchers.IO) {
            try {
                val installer = AdbRemoteInstaller(context.applicationContext)
                val apk = openApkSource(config.apk)

                apk.input.use { input ->
                    val output = installer.pushAndInstall(
                        host = config.host,
                        input = input,
                        totalBytes = apk.totalBytes,
                        remoteDir = config.remoteDir,
                        preferredRemoteFileName = config.preferredRemoteFileName,
                        log = { msg -> _events.tryEmit(GlassesEvent.Log("RayNeo installer: $msg")) },
                    )

                    val ok = output.contains("Success", ignoreCase = true)
                    if (!ok) {
                        _state.value = ConnectionState.Error(GlassesError.Transport("Install failed: $output"))
                        return@withContext Result.failure(GlassesError.Transport("Install failed: $output"))
                    }
                }

                _state.value = ConnectionState.Connected
                Result.success(Unit)
            } catch (e: Exception) {
                val err = when (e) {
                    is GlassesError -> e
                    else -> GlassesError.Transport("RayNeo install/connect failed: ${e.message ?: e::class.java.simpleName}", e)
                }
                _state.value = ConnectionState.Error(err)
                Result.failure(err)
            }
        }
    }

    override suspend fun disconnect() {
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        return Result.failure(
            GlassesError.Unsupported("RayNeo capture runs on-glasses. Install/open the glasses app and trigger capture there.")
        )
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        return Result.failure(
            GlassesError.Unsupported("RayNeo display runs on-glasses. Install/open the glasses app and display there.")
        )
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        return Result.failure(
            GlassesError.Unsupported("RayNeo microphone runs on-glasses. Install/open the glasses app and record there.")
        )
    }

    private data class OpenedApk(val input: InputStream, val totalBytes: Long?)

    private fun openApkSource(source: RayNeoApkSource): OpenedApk {
        return when (source) {
            is RayNeoApkSource.Bytes -> OpenedApk(
                input = ByteArrayInputStream(source.bytes),
                totalBytes = source.bytes.size.toLong(),
            )
            is RayNeoApkSource.Asset -> {
                val input = context.assets.open(source.assetPath)
                OpenedApk(input = input, totalBytes = null)
            }
            is RayNeoApkSource.FilePath -> {
                val f = File(source.path)
                OpenedApk(input = FileInputStream(f), totalBytes = f.length())
            }
            is RayNeoApkSource.ContentUri -> {
                val input = context.contentResolver.openInputStream(source.uri)
                    ?: throw IllegalStateException("Unable to open APK InputStream from Uri: ${source.uri}")
                OpenedApk(input = input, totalBytes = source.totalBytes)
            }
        }
    }
}

data class RayNeoInstallerConfig(
    /** RayNeo glasses IP (adbd must listen on port 5555). */
    val host: String,
    /** The APK to be installed onto the glasses. */
    val apk: RayNeoApkSource,
    /** Default: /data/local/tmp */
    val remoteDir: String = "/data/local/tmp",
    /** Optional remote file name; if null, a timestamped name will be used. */
    val preferredRemoteFileName: String? = null,
)

sealed interface RayNeoApkSource {
    data class Bytes(val bytes: ByteArray) : RayNeoApkSource
    data class Asset(val assetPath: String) : RayNeoApkSource
    data class FilePath(val path: String) : RayNeoApkSource
    data class ContentUri(val uri: Uri, val totalBytes: Long? = null) : RayNeoApkSource
}

/**
 * ADB-over-TCP installer.
 *
 * - Connect to adbd on host:5555
 * - Push APK to /data/local/tmp via sync:
 * - Install via `pm install -r`
 */
private class AdbRemoteInstaller(private val context: Context) {

    fun pushAndInstall(
        host: String,
        input: InputStream,
        totalBytes: Long?,
        remoteDir: String,
        preferredRemoteFileName: String?,
        log: (String) -> Unit,
    ): String {
        val (connection, socket) = connect(host, log)
        connection.use {
            socket.use {
                val fileName = buildRemoteApkFileName(preferredRemoteFileName)
                val remotePath = remoteDir.trimEnd('/') + "/" + fileName

                log("Push APK -> $remotePath")
                SyncProtocol.push(
                    connection = connection,
                    remotePath = remotePath,
                    input = input,
                    totalBytes = totalBytes,
                    log = log,
                )

                log("Install: pm install -r $remotePath")
                return ShellProtocol.run(connection, "pm install -r \"$remotePath\"", log).trim()
            }
        }
    }

    private fun buildRemoteApkFileName(preferred: String?): String {
        val cleaned = preferred
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::sanitizeFileName)
            ?.takeIf { it.isNotEmpty() }

        val base = cleaned ?: "app-${System.currentTimeMillis()}"
        return if (base.lowercase(Locale.US).endsWith(".apk")) base else "$base.apk"
    }

    private fun sanitizeFileName(raw: String): String {
        val stripped = raw
            .replace('\\', '_')
            .replace('/', '_')
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .trim()

        val safe = stripped.trim('.').ifEmpty { "app" }
        return if (safe.length <= 120) safe else safe.take(120)
    }

    private fun connect(host: String, log: (String) -> Unit): Pair<AdbConnection, Socket> {
        val socket = Socket(host, 5555)
        socket.tcpNoDelay = true

        val crypto = loadOrCreateKeys()
        val connection = AdbConnection.create(socket, crypto)

        try {
            // Abort on unauthorized to show clearer logs (same behavior as the sample).
            connection.connect(Long.MAX_VALUE, TimeUnit.MILLISECONDS, true)
        } catch (e: Exception) {
            log("May be unauthorized: accept the ADB prompt on the glasses and retry.")
            connection.connect()
        }

        return connection to socket
    }

    private fun loadOrCreateKeys(): AdbCrypto {
        val dir = File(context.filesDir, "adbkeys").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")

        val base64 = AdbBase64 { data -> android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP) }

        return if (priv.exists() && pub.exists()) {
            AdbCrypto.loadAdbKeyPair(base64, priv, pub)
        } else {
            val crypto = AdbCrypto.generateAdbKeyPair(base64)
            crypto.saveAdbKeyPair(priv, pub)
            crypto
        }
    }
}

private object SyncProtocol {
    private const val ID_SEND = 0x444e4553 // "SEND"
    private const val ID_DATA = 0x41544144 // "DATA"
    private const val ID_DONE = 0x454e4f44 // "DONE"
    private const val ID_OKAY = 0x59414b4f // "OKAY"
    private const val ID_FAIL = 0x4c494146 // "FAIL"

    fun push(
        connection: AdbConnection,
        remotePath: String,
        input: InputStream,
        totalBytes: Long?,
        log: (String) -> Unit,
    ) {
        val maxData = try {
            // adblib exposes this as getMaxData() after connection negotiation.
            connection.getMaxData().coerceAtLeast(1024)
        } catch (_: Exception) {
            4096
        }

        val stream = connection.open("sync:")
        stream.use {
            val mode = 420 // 0644
            val sendSpec = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
            writeSyncPacketPacked(stream, ID_SEND, sendSpec)

            val maxSyncPayload = (maxData - 8).coerceAtLeast(1)
            val packet = ByteArray(maxData)
            var total: Long = 0

            while (true) {
                val read = input.read(packet, 8, maxSyncPayload)
                if (read <= 0) break
                total += read

                putIntLE(packet, 0, ID_DATA)
                putIntLE(packet, 4, read)

                if (read == maxSyncPayload) {
                    stream.write(packet, true)
                } else {
                    val out = ByteArray(8 + read)
                    System.arraycopy(packet, 0, out, 0, 8 + read)
                    stream.write(out, true)
                }

                if (totalBytes != null && totalBytes > 0 && total % (1024 * 1024) < read) {
                    val pct = (total * 100 / totalBytes).coerceIn(0, 100)
                    log("Sent ${total / (1024 * 1024)} MB ($pct%)")
                } else if (total % (1024 * 1024) < read) {
                    log("Sent ${total / (1024 * 1024)} MB")
                }
            }

            val mtimeSeconds = (System.currentTimeMillis() / 1000L).toInt()
            writeSyncHeaderPacked(stream, ID_DONE, mtimeSeconds)

            val (id, payload) = readSyncStatus(stream)
            if (id == ID_OKAY) {
                log("Push OK")
                return
            }
            if (id == ID_FAIL) {
                val msg = payload.toString(Charsets.UTF_8)
                throw IllegalStateException("Push FAIL: $msg")
            }
            throw IllegalStateException("Push unknown response: 0x${id.toString(16)}")
        }
    }

    private fun writeSyncPacketPacked(stream: AdbStream, id: Int, payload: ByteArray) {
        val out = ByteArray(8 + payload.size)
        putIntLE(out, 0, id)
        putIntLE(out, 4, payload.size)
        System.arraycopy(payload, 0, out, 8, payload.size)
        stream.write(out, true)
    }

    private fun writeSyncHeaderPacked(stream: AdbStream, id: Int, arg: Int) {
        val out = ByteArray(8)
        putIntLE(out, 0, id)
        putIntLE(out, 4, arg)
        stream.write(out, true)
    }

    private fun putIntLE(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xff).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xff).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xff).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun readSyncStatus(stream: AdbStream): Pair<Int, ByteArray> {
        val header = readExactly(stream, 8)
        val id = readIntLE(header, 0)
        val len = readIntLE(header, 4)
        val payload = if (len > 0) readExactly(stream, len) else ByteArray(0)
        return id to payload
    }

    private fun readExactly(stream: AdbStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val chunk = stream.read()
            val copy = minOf(chunk.size, len - off)
            System.arraycopy(chunk, 0, out, off, copy)
            off += copy
            if (copy < chunk.size) {
                // extra data is ignored; adblib reads packetized data; for sync status this is fine.
            }
        }
        return out
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }
}

private object ShellProtocol {
    fun run(connection: AdbConnection, command: String, log: (String) -> Unit): String {
        val stream = connection.open("shell:$command")
        stream.use {
            val sb = StringBuilder()
            while (true) {
                try {
                    val bytes = stream.read()
                    val text = bytes.toString(Charsets.UTF_8)
                    sb.append(text)
                    text.lines().filter { it.isNotBlank() }.forEach { log(it) }
                } catch (_: Exception) {
                    break
                }
            }
            return sb.toString()
        }
    }
}


