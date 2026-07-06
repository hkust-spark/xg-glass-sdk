package com.xgglass.device.even

import com.xgglass.core.GlassesError

/*
 * Even Realities G1 protocol constants are extracted from:
 * - Official demo: https://github.com/even-realities/EvenDemoApp (local ref /tmp/even-ref/EvenDemoApp, commit 3899aac).
 * - Community protocol notes: https://github.com/AGiXT/mobile/blob/main/Even%20Realities%20G1%20BLE%20Protocol.txt
 *   (local ref /tmp/even-ref/g1-ble-protocol.txt).
 *
 * Do not add protocol bytes here without adding a source file/line citation.
 */

internal enum class EvenArm {
    LEFT,
    RIGHT,
}

internal data class EvenDeviceName(
    val model: String,
    val channel: String,
    val arm: EvenArm,
    val serial: String,
)

internal object EvenDeviceNames {
    // Source: EvenDemoApp Android scan accepts names matching G + digits, split into four "_" parts,
    // with "_L_"/"_R_" arms; /tmp/even-ref/EvenDemoApp/android/.../BleManager.kt lines 65-76.
    private val modelPattern = Regex("G\\d+")

    fun parse(name: String?): EvenDeviceName? {
        if (name.isNullOrBlank()) return null
        val parts = name.split("_")
        if (parts.size != 4) return null
        if (!modelPattern.matches(parts[0])) return null
        val arm = when (parts[2]) {
            "L" -> EvenArm.LEFT
            "R" -> EvenArm.RIGHT
            else -> return null
        }
        if (parts[1].isBlank() || parts[3].isBlank()) return null
        return EvenDeviceName(
            model = parts[0],
            channel = parts[1],
            arm = arm,
            serial = parts[3],
        )
    }
}

internal object EvenBleUuids {
    // Source: official Android constants; /tmp/even-ref/EvenDemoApp/android/.../BleManager.kt lines 35-37.
    // Cross-check: iOS ServiceIdentifiers.swift lines 11-15 and docs/G1_BLE_CONNECTION.en.md lines 50-56.
    const val NORDIC_UART_SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val NORDIC_UART_TX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    const val NORDIC_UART_RX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

    // Source: official Android descriptor write; /tmp/even-ref/EvenDemoApp/android/.../BleManager.kt lines 274-278.
    const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
}

internal object EvenResponses {
    // Source: community generic command response; /tmp/even-ref/g1-ble-protocol.txt lines 32-46.
    const val SUCCESS = 0xC9
    const val FAILURE = 0xCA
    const val CONTINUE_DATA = 0xCB

    fun isSuccessOrContinue(packet: ByteArray): Boolean {
        if (packet.size < 2) return false
        val status = packet[1].toInt() and 0xFF
        return status == SUCCESS || status == CONTINUE_DATA
    }

    fun isSuccess(packet: ByteArray): Boolean =
        packet.size >= 2 && (packet[1].toInt() and 0xFF) == SUCCESS
}

internal object EvenInitProtocol {
    // Source: official Android post-GATT first packet; /tmp/even-ref/EvenDemoApp/android/.../BleManager.kt line 290.
    // Cross-check: docs/G1_BLE_CONNECTION.en.md lines 58, 95, and 146.
    fun androidInitialPacket(): ByteArray = byteArrayOf(0xF4.toByte(), 0x01)
}

internal object EvenHeartbeatProtocol {
    // Source: community heartbeat command and keepalive note; /tmp/even-ref/g1-ble-protocol.txt lines 79-84.
    const val COMMAND = 0x25

    // Source: official demo heartbeat timer; /tmp/even-ref/EvenDemoApp/lib/ble_manager.dart lines 103-108.
    const val INTERVAL_MS = 8_000L

    // Source: official heartbeat frame; /tmp/even-ref/EvenDemoApp/lib/services/proto.dart lines 75-85.
    const val FRAME_LENGTH = 0x06
    const val MARKER = 0x04

    fun frame(sequence: Int): ByteArray {
        val seq = sequence and 0xFF
        return byteArrayOf(
            COMMAND.toByte(),
            (FRAME_LENGTH and 0xFF).toByte(),
            ((FRAME_LENGTH ushr 8) and 0xFF).toByte(),
            seq.toByte(),
            MARKER.toByte(),
            seq.toByte(),
        )
    }

    fun isAck(packet: ByteArray): Boolean =
        packet.size > 4 &&
            (packet[0].toInt() and 0xFF) == COMMAND &&
            (packet[4].toInt() and 0xFF) == MARKER
}

internal enum class EvenTextStatus(val highBits: Int) {
    // Source: EvenDemoApp README Send AI Result statuses; lines 147-151.
    EVEN_AI_DISPLAYING(0x30),
    EVEN_AI_COMPLETE(0x40),
    EVEN_AI_MANUAL(0x50),
    EVEN_AI_NETWORK_ERROR(0x60),
    // Source: EvenDemoApp README Text Sending status; lines 216-226.
    TEXT_SHOW(0x70),
}

internal data class EvenTextPage(
    val text: String,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val charPosition: Int = 0,
    val screenStatus: Int = EvenTextProtocol.screenStatus(EvenTextStatus.TEXT_SHOW),
)

internal object EvenTextProtocol {
    // Source: community notes and official packetizer use command 0x4E; g1-ble-protocol.txt lines 105-117
    // and /tmp/even-ref/EvenDemoApp/lib/services/proto.dart lines 40-49.
    const val COMMAND = 0x4E

    // Source: official EvenaiProto default len=191; /tmp/even-ref/EvenDemoApp/lib/services/evenai_proto.dart lines 5-18.
    const val MAX_PAYLOAD_BYTES = 191

    // Source: official packet header stores current_page_num and max_page_num as one byte each;
    // /tmp/even-ref/EvenDemoApp/lib/services/evenai_proto.dart lines 23-33.
    const val MAX_LOGICAL_PAGES = 255

    // Source: official README lower-bit action for new content; /tmp/even-ref/EvenDemoApp/README.md lines 141-145 and 216-220.
    const val ACTION_NEW_CONTENT = 0x01

    // Source: official text service displays five measured lines per screen; README lines 30-35,
    // text_service.dart lines 133-147, and evenai.dart lines 165-180.
    const val LINES_PER_PAGE = 5

    fun screenStatus(status: EvenTextStatus, action: Int = ACTION_NEW_CONTENT): Int =
        (status.highBits or (action and 0x0F)) and 0xFF

    fun packets(page: EvenTextPage, syncSequence: Int): List<ByteArray> {
        require(page.currentPage in 0..255) { "currentPage must fit one byte" }
        require(page.totalPages in 0..255) { "totalPages must fit one byte" }
        require(page.charPosition in 0..0xFFFF) { "charPosition must fit two bytes" }
        require(page.screenStatus in 0..255) { "screenStatus must fit one byte" }

        val data = page.text.encodeToByteArray()
        if (data.isEmpty()) return emptyList()

        val chunks = data.chunked(MAX_PAYLOAD_BYTES)
        require(chunks.size in 1..255) { "G1 text command supports 1..255 packets" }

        val posHigh = (page.charPosition ushr 8) and 0xFF
        val posLow = page.charPosition and 0xFF
        val sync = syncSequence and 0xFF
        val total = chunks.size

        return chunks.mapIndexed { index, chunk ->
            byteArrayOf(
                COMMAND.toByte(),
                sync.toByte(),
                total.toByte(),
                (index and 0xFF).toByte(),
                page.screenStatus.toByte(),
                posHigh.toByte(),
                posLow.toByte(),
                page.currentPage.toByte(),
                page.totalPages.toByte(),
            ) + chunk
        }
    }

    fun totalPages(lineCount: Int): Int {
        if (lineCount <= 0) return 0
        if (lineCount < LINES_PER_PAGE + 1) return 1
        val full = lineCount / LINES_PER_PAGE
        return full + if (lineCount % LINES_PER_PAGE == 0) 0 else 1
    }

    fun currentPageForLine(currentLine: Int): Int {
        if (currentLine <= 0) return 1
        val full = currentLine / LINES_PER_PAGE
        return 1 + full + if (currentLine % LINES_PER_PAGE == 0) 0 else 1
    }

    fun paginateText(text: String, linesPerPage: Int = LINES_PER_PAGE): List<String> {
        require(linesPerPage > 0) { "linesPerPage must be positive" }
        val lines = text.lines()
        if (lines.isEmpty() || (lines.size == 1 && lines[0].isEmpty())) return emptyList()
        return lines.chunked(linesPerPage).map { it.joinToString(separator = "\n") }
    }

    fun validatedDisplayPages(text: String, linesPerPage: Int = LINES_PER_PAGE): List<String> {
        val pages = paginateText(text, linesPerPage)
        if (pages.size > MAX_LOGICAL_PAGES) {
            throw GlassesError.Unsupported(
                "Even G1 display supports at most $MAX_LOGICAL_PAGES logical pages; got ${pages.size}",
            )
        }
        return pages
    }
}

internal object EvenClearScreenProtocol {
    // Source: community notes and official exit command; g1-ble-protocol.txt lines 121-122,
    // /tmp/even-ref/EvenDemoApp/lib/services/proto.dart lines 121-130.
    const val COMMAND = 0x18
    fun frame(): ByteArray = byteArrayOf(COMMAND.toByte())
}

internal data class EvenMicFrame(
    val sequence: Int,
    val lc3Bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EvenMicFrame) return false
        return sequence == other.sequence && lc3Bytes.contentEquals(other.lc3Bytes)
    }

    override fun hashCode(): Int = 31 * sequence + lc3Bytes.contentHashCode()
}

internal sealed class EvenMicNotificationResult {
    data class Frame(val frame: EvenMicFrame) : EvenMicNotificationResult()
    data class Malformed(val size: Int) : EvenMicNotificationResult()
    data object NotMic : EvenMicNotificationResult()
}

internal class EvenMicSequenceTracker {
    private var lastByte: Int? = null
    private var wrapCount = 0L
    private var lastSequence = -1L

    /**
     * Reconstructs a monotonic sequence from the device's one-byte counter.
     *
     * Rule: delta 0 is a duplicate and is skipped; deltas over 128 are treated as stale/rewound
     * packets and skipped; lower byte values with a small positive delta are considered wraparound.
     */
    fun next(sequenceByte: Int): Long? {
        val current = sequenceByte and 0xFF
        val previous = lastByte
        if (previous == null) {
            lastByte = current
            lastSequence = current.toLong()
            return lastSequence
        }

        val delta = (current - previous + 256) % 256
        if (delta == 0 || delta > 128) return null

        if (current < previous) {
            wrapCount += 1
        }
        lastByte = current
        lastSequence = wrapCount * 256 + current
        return lastSequence
    }

    fun reset() {
        lastByte = null
        wrapCount = 0L
        lastSequence = -1L
    }

    fun nextEndOfStreamSequence(): Long = lastSequence + 1
}

internal object EvenMicProtocol {
    // Source: community and official mic control command; g1-ble-protocol.txt lines 51-67,
    // /tmp/even-ref/EvenDemoApp/lib/services/proto.dart lines 16-29.
    const val CONTROL_COMMAND = 0x0E
    const val MIC_DISABLE = 0x00
    const val MIC_ENABLE = 0x01

    // Source: community and official mic notification command; g1-ble-protocol.txt lines 263-275,
    // /tmp/even-ref/EvenDemoApp/ios/Runner/GattProtocal.swift lines 9-14.
    const val DATA_COMMAND = 0xF1

    // Source: official Android notification parser; /tmp/even-ref/EvenDemoApp/android/.../BleManager.kt lines 312-321.
    const val NOTIFICATION_SIZE = 202
    const val LC3_PAYLOAD_BYTES_PER_NOTIFICATION = 200

    // Source: official LC3 decoder setup; android liblc3.cpp lines 20-30 and iOS PcmConverter.m lines 13-18.
    const val LC3_FRAME_DURATION_US = 10_000
    const val LC3_SAMPLE_RATE_HZ = 16_000
    const val LC3_FRAME_BYTES = 20

    fun controlFrame(enable: Boolean): ByteArray =
        byteArrayOf(CONTROL_COMMAND.toByte(), (if (enable) MIC_ENABLE else MIC_DISABLE).toByte())

    fun isControlSuccess(packet: ByteArray): Boolean =
        packet.isNotEmpty() &&
            (packet[0].toInt() and 0xFF) == CONTROL_COMMAND &&
            EvenResponses.isSuccess(packet)

    fun parseNotification(packet: ByteArray): EvenMicNotificationResult {
        if (packet.isEmpty() || (packet[0].toInt() and 0xFF) != DATA_COMMAND) {
            return EvenMicNotificationResult.NotMic
        }
        if (packet.size != NOTIFICATION_SIZE) {
            return EvenMicNotificationResult.Malformed(packet.size)
        }
        val sequence = packet[1].toInt() and 0xFF
        return EvenMicNotificationResult.Frame(
            EvenMicFrame(
                sequence = sequence,
                lc3Bytes = packet.copyOfRange(2, 2 + LC3_PAYLOAD_BYTES_PER_NOTIFICATION),
            ),
        )
    }
}

internal object EvenStateEvents {
    // Source: community and official state-event command; g1-ble-protocol.txt lines 523-531,
    // /tmp/even-ref/EvenDemoApp/lib/ble_manager.dart lines 154-177.
    const val COMMAND = 0xF5

    // Source: official README TouchBar events; /tmp/even-ref/EvenDemoApp/README.md lines 53-65.
    const val DOUBLE_TAP = 0x00
    const val SINGLE_TAP = 0x01
    const val TRIPLE_TAP_A = 0x04
    const val TRIPLE_TAP_B = 0x05

    // Source: official demo event handling; /tmp/even-ref/EvenDemoApp/lib/ble_manager.dart lines 168-172,
    // and README lines 68-81 (decimal 23/24).
    const val EVEN_AI_START = 0x17
    const val EVEN_AI_RECORD_OVER = 0x18

    fun tapCount(packet: ByteArray): Int? {
        if (packet.size < 2) return null
        if ((packet[0].toInt() and 0xFF) != COMMAND) return null
        return when (packet[1].toInt() and 0xFF) {
            SINGLE_TAP -> 1
            DOUBLE_TAP -> 2
            TRIPLE_TAP_A, TRIPLE_TAP_B -> 3
            else -> null
        }
    }
}

private fun ByteArray.chunked(size: Int): List<ByteArray> {
    val chunks = ArrayList<ByteArray>()
    var offset = 0
    while (offset < this.size) {
        val end = minOf(offset + size, this.size)
        chunks.add(copyOfRange(offset, end))
        offset = end
    }
    return chunks
}
