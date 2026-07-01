package com.universalglasses.device.rayneo.installer

import android.content.Context
import android.net.Uri
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import com.tananaev.adblib.AdbStream
import com.universalglasses.core.DeviceManager
import com.universalglasses.core.DeviceManagerState
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RayNeo phone-side device manager.
 *
 * Installs or updates the on-glasses APK via ADB-over-TCP (adbd:5555), then the actual
 * [com.universalglasses.core.GlassesClient] runs inside the installed glasses app.
 */
class RayNeoDeviceManager(
    private val context: Context,
    private val config: RayNeoInstallerConfig,
) : DeviceManager {

    override val model: GlassesModel = GlassesModel.RAYNEO

    private val _state = MutableStateFlow<DeviceManagerState>(DeviceManagerState.Idle)
    override val state: StateFlow<DeviceManagerState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<GlassesEvent> = _events

    private val installMutex = Mutex()

    override suspend fun install(): Result<Unit> = installMutex.withLock {
        if (_state.value is DeviceManagerState.Installed) return Result.success(Unit)
        _state.value = DeviceManagerState.Installing

        return withContext(Dispatchers.IO) {
            val result = try {
                try {
                    withTimeout(config.connectTimeoutMs) {
                        val installer = AdbRemoteInstaller(context.applicationContext, config.connectTimeoutMs)
                        val apk = openApkSource(config.apk)

                        apk.input.use { input ->
                            val output = installer.pushAndInstall(
                                host = config.host,
                                input = input,
                                totalBytes = apk.totalBytes,
                                remoteDir = config.remoteDir,
                                preferredRemoteFileName = config.preferredRemoteFileName,
                                log = { msg -> emitLog("RayNeo installer: $msg") },
                            )

                            val ok = output.contains("Success", ignoreCase = true)
                            if (!ok) {
                                throw GlassesError.Transport("Install failed: $output")
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    throw GlassesError.Timeout("RayNeo installer connect")
                }

                Result.success(Unit)
            } catch (ce: CancellationException) {
                _state.value = DeviceManagerState.Idle
                Result.failure(ce)
            } catch (e: Exception) {
                val err = mapInstallError(e)
                _state.value = DeviceManagerState.Error(err)
                Result.failure(err)
            }

            if (result.isSuccess) {
                _state.value = DeviceManagerState.Installed
            }
            result
        }
    }

    private fun mapInstallError(error: Exception): GlassesError {
        return when (error) {
            is GlassesError -> error
            else -> GlassesError.Transport("RayNeo install/connect failed: ${error.message ?: error::class.java.simpleName}", error)
        }
    }

    override suspend fun close() {
        _state.value = DeviceManagerState.Idle
    }

    /**
     * Push user settings to the glasses via ADB so the on-glasses app can read them at startup.
     *
     * The settings are written as a JSON file to [SETTINGS_REMOTE_PATH].
     * The on-glasses host reads this file in `onCreate()` and passes the values
     * to [com.universalglasses.appcontract.UniversalAppContext.settings].
     */
    override suspend fun pushSettings(settings: Map<String, String>): Result<Unit> {
        if (settings.isEmpty()) return Result.success(Unit)

        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(settings).toString()
                val jsonBytes = json.toByteArray(Charsets.UTF_8)

                val installer = AdbRemoteInstaller(context.applicationContext, config.connectTimeoutMs)
                installer.pushFile(
                    host = config.host,
                    remotePath = SETTINGS_REMOTE_PATH,
                    input = ByteArrayInputStream(jsonBytes),
                    totalBytes = jsonBytes.size.toLong(),
                    log = { msg -> emitLog("RayNeo settings: $msg") },
                )

                Result.success(Unit)
            } catch (e: Exception) {
                val err = GlassesError.Transport("Failed to push settings: ${e.message ?: e::class.java.simpleName}", e)
                Result.failure(err)
            }
        }
    }

    private fun emitLog(message: String) {
        _events.tryEmit(GlassesEvent.Log(message))
    }

    companion object {
        /**
         * Well-known path on the glasses where the phone pushes user settings.
         * `/data/local/tmp/` is world-readable on Android, so the glasses app process can read it.
         */
        const val SETTINGS_REMOTE_PATH = "/data/local/tmp/ug_user_settings.json"
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
    val connectTimeoutMs: Long = 30_000,
)

sealed interface RayNeoApkSource {
    data class Bytes(val bytes: ByteArray) : RayNeoApkSource {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false

            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }
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
private class AdbRemoteInstaller(
    private val context: Context,
    private val connectTimeoutMs: Long,
) {

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

    fun pushFile(
        host: String,
        remotePath: String,
        input: InputStream,
        totalBytes: Long?,
        log: (String) -> Unit,
    ) {
        val (connection, socket) = connect(host, log)
        connection.use {
            socket.use {
                log("Push file -> $remotePath")
                SyncProtocol.push(
                    connection = connection,
                    remotePath = remotePath,
                    input = input,
                    totalBytes = totalBytes,
                    log = log,
                )
                log("File pushed OK")
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
        val socket = Socket()
        socket.connect(InetSocketAddress(host, 5555), connectTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        socket.tcpNoDelay = true

        val crypto = loadOrCreateKeys()
        val connection = AdbConnection.create(socket, crypto)

        try {
            // Abort on unauthorized to show clearer logs (same behavior as the sample).
            connection.connect(connectTimeoutMs, TimeUnit.MILLISECONDS, true)
        } catch (e: Exception) {
            log("May be unauthorized: accept the ADB prompt on the glasses and retry.")
            connection.connect(connectTimeoutMs, TimeUnit.MILLISECONDS, false)
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
