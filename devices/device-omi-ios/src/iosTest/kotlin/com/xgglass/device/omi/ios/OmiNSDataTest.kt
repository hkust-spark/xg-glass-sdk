package com.xgglass.device.omi.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class OmiNSDataTest {
    @Test
    fun emptyPayloadCreatesEmptyFoundationData() {
        assertEquals(0uL, byteArrayOf().toNSData().length)
    }

    @Test
    fun binaryPayloadRoundTripsThroughFoundation() {
        val payload = byteArrayOf(0, 1, 127, -128, -1)
        val data = payload.toNSData()

        assertEquals(payload.size.toULong(), data.length)
        assertContentEquals(payload, data.bytes?.readBytes(data.length.toInt()))
    }

    @Test
    fun foundationDataOwnsCopyAfterSourceChanges() {
        val payload = byteArrayOf(0, 1, 127, -128, -1)
        val expected = payload.copyOf()
        val data = payload.toNSData()
        payload.fill(42)

        assertContentEquals(expected, data.bytes?.readBytes(data.length.toInt()))
    }
}
