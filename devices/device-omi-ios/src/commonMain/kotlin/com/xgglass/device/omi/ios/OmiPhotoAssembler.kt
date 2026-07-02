package com.xgglass.device.omi.ios

/** Outcome of feeding one [OmiBleUuids.PHOTO_DATA] packet to [OmiPhotoAssembler]. */
internal sealed interface OmiPhotoResult {
    /** More packets are expected; the payload was buffered. */
    object Incomplete : OmiPhotoResult

    /** End-of-image reached; [jpegBytes] is the assembled (SOI-aligned) JPEG. */
    class Complete(val jpegBytes: ByteArray) : OmiPhotoResult

    /**
     * A packet arrived out of order. The payload is still buffered (best-effort,
     * matching the Android client), but callers should log the gap.
     */
    data class DroppedChunk(val expected: Int, val got: Int) : OmiPhotoResult
}

/**
 * Reassembles Omi's chunked photo stream.
 *
 * Wire format (per [OmiBleUuids.PHOTO_DATA] notification):
 * - `[0xFF, 0xFF, ...]` marks end-of-image.
 * - Otherwise the first two bytes are a little-endian packet id, followed by the
 *   JPEG payload chunk. Packet id 0 starts a new image.
 *
 * Not thread-safe; the transport confines all calls to the BLE delegate queue.
 */
internal class OmiPhotoAssembler {
    private val buffer = ArrayList<Byte>()
    private var lastChunkId = -1

    /** Discard any partially-assembled image (e.g. before starting a new capture). */
    fun reset() {
        buffer.clear()
        lastChunkId = -1
    }

    /**
     * Feed one PHOTO_DATA packet. Returns [OmiPhotoResult.Complete] with the
     * finished JPEG once the end-of-image marker is seen; the assembler resets
     * itself so it can be reused for the next capture.
     */
    fun addPacket(data: ByteArray): OmiPhotoResult {
        if (data.size < 2) return OmiPhotoResult.Incomplete

        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF

        // End-of-image marker (checked before treating the bytes as a packet id).
        if (b0 == 0xFF && b1 == 0xFF) {
            if (buffer.isEmpty()) {
                // A spurious EOF with nothing buffered is not a real image; keep waiting
                // rather than reporting a bogus zero-byte capture as success.
                reset()
                return OmiPhotoResult.Incomplete
            }
            val raw = buffer.toByteArray()
            reset()
            val start = findJpegStart(raw)
            val jpeg = if (start > 0) raw.copyOfRange(start, raw.size) else raw
            return OmiPhotoResult.Complete(jpeg)
        }

        val packetId = b0 or (b1 shl 8)
        val payload = data.copyOfRange(2, data.size)

        return when {
            packetId == 0 -> {
                buffer.clear()
                lastChunkId = 0
                appendPayload(payload)
                OmiPhotoResult.Incomplete
            }

            packetId == lastChunkId + 1 -> {
                lastChunkId = packetId
                appendPayload(payload)
                OmiPhotoResult.Incomplete
            }

            else -> {
                val result = OmiPhotoResult.DroppedChunk(expected = lastChunkId + 1, got = packetId)
                lastChunkId = packetId
                appendPayload(payload)
                result
            }
        }
    }

    private fun appendPayload(payload: ByteArray) {
        for (byte in payload) buffer.add(byte)
    }
}
