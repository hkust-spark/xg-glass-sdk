package com.xgglass.device.even

import com.xgglass.core.GlassesError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvenDeviceNamesTest {
    @Test
    fun parsesLeftAndRightG1Names() {
        val left = EvenDeviceNames.parse("G1_45_L_92333")
        val right = EvenDeviceNames.parse("G1_45_R_92334")

        assertNotNull(left)
        assertEquals("G1", left.model)
        assertEquals("45", left.channel)
        assertEquals(EvenArm.LEFT, left.arm)
        assertEquals("92333", left.serial)

        assertNotNull(right)
        assertEquals(EvenArm.RIGHT, right.arm)
    }

    @Test
    fun rejectsNamesOutsideOfficialShape() {
        assertNull(EvenDeviceNames.parse(null))
        assertNull(EvenDeviceNames.parse("G1_45_L"))
        assertNull(EvenDeviceNames.parse("G1_45_X_92333"))
        assertNull(EvenDeviceNames.parse("Even_45_L_92333"))
    }
}

class EvenHeartbeatProtocolTest {
    @Test
    fun buildsOfficialHeartbeatFrame() {
        assertContentEquals(
            byteArrayOf(0x25, 0x06, 0x00, 0x7F, 0x04, 0x7F),
            EvenHeartbeatProtocol.frame(0x7F),
        )
    }

    @Test
    fun wrapsHeartbeatSequenceToOneByte() {
        assertContentEquals(
            byteArrayOf(0x25, 0x06, 0x00, 0x02, 0x04, 0x02),
            EvenHeartbeatProtocol.frame(0x102),
        )
    }

    @Test
    fun detectsHeartbeatAckByCommandAndMarker() {
        assertTrue(EvenHeartbeatProtocol.isAck(byteArrayOf(0x25, 0x06, 0x00, 0x02, 0x04, 0x02)))
        assertFalse(EvenHeartbeatProtocol.isAck(byteArrayOf(0x25, 0x06, 0x00, 0x02, 0x05, 0x02)))
        assertFalse(EvenHeartbeatProtocol.isAck(byteArrayOf(0x24, 0x06, 0x00, 0x02, 0x04, 0x02)))
    }
}

class EvenResponsesTest {
    @Test
    fun parsesGenericSuccessContinueAndFailure() {
        assertTrue(EvenResponses.isSuccess(byteArrayOf(0x4E, 0xC9.toByte())))
        assertTrue(EvenResponses.isSuccessOrContinue(byteArrayOf(0x4E, 0xC9.toByte())))
        assertTrue(EvenResponses.isSuccessOrContinue(byteArrayOf(0x4E, 0xCB.toByte())))
        assertFalse(EvenResponses.isSuccessOrContinue(byteArrayOf(0x4E, 0xCA.toByte())))
        assertFalse(EvenResponses.isSuccess(ByteArray(0)))
    }
}

class EvenTextProtocolTest {
    @Test
    fun combinesTextShowStatusWithNewContentAction() {
        assertEquals(0x71, EvenTextProtocol.screenStatus(EvenTextStatus.TEXT_SHOW))
        assertEquals(0x31, EvenTextProtocol.screenStatus(EvenTextStatus.EVEN_AI_DISPLAYING))
        assertEquals(0x50, EvenTextProtocol.screenStatus(EvenTextStatus.EVEN_AI_MANUAL, action = 0))
    }

    @Test
    fun packetizesShortTextWithOfficialHeaderLayout() {
        val packets = EvenTextProtocol.packets(
            page = EvenTextPage(text = "Hi", currentPage = 1, totalPages = 3),
            syncSequence = 0x12,
        )

        assertEquals(1, packets.size)
        assertContentEquals(
            byteArrayOf(0x4E, 0x12, 0x01, 0x00, 0x71, 0x00, 0x00, 0x01, 0x03, 0x48, 0x69),
            packets.single(),
        )
    }

    @Test
    fun encodesCharacterPositionBigEndian() {
        val packet = EvenTextProtocol.packets(
            page = EvenTextPage(text = "A", charPosition = 0x1234),
            syncSequence = 0,
        ).single()

        assertEquals(0x12, packet[5].toInt() and 0xFF)
        assertEquals(0x34, packet[6].toInt() and 0xFF)
    }

    @Test
    fun splitsUtf8PayloadAtOneHundredNinetyOneBytes() {
        val text = "a".repeat(192)
        val packets = EvenTextProtocol.packets(EvenTextPage(text = text), syncSequence = 0xFE)

        assertEquals(2, packets.size)
        assertEquals(200, packets[0].size)
        assertEquals(10, packets[1].size)
        assertEquals(2, packets[0][2].toInt() and 0xFF)
        assertEquals(0, packets[0][3].toInt() and 0xFF)
        assertEquals(1, packets[1][3].toInt() and 0xFF)
    }

    @Test
    fun keepsExactPayloadLimitInOnePacket() {
        val packets = EvenTextProtocol.packets(EvenTextPage(text = "b".repeat(191)), syncSequence = 0)
        assertEquals(1, packets.size)
        assertEquals(200, packets.single().size)
    }

    @Test
    fun returnsNoPacketsForEmptyText() {
        assertEquals(0, EvenTextProtocol.packets(EvenTextPage(text = ""), syncSequence = 0).size)
    }

    @Test
    fun rejectsMoreThanTwoHundredFiftyFivePackets() {
        val text = "x".repeat(EvenTextProtocol.MAX_PAYLOAD_BYTES * 255 + 1)
        assertFailsWith<IllegalArgumentException> {
            EvenTextProtocol.packets(EvenTextPage(text = text), syncSequence = 0)
        }
    }

    @Test
    fun matchesOfficialPageCountMath() {
        assertEquals(0, EvenTextProtocol.totalPages(0))
        assertEquals(1, EvenTextProtocol.totalPages(1))
        assertEquals(1, EvenTextProtocol.totalPages(5))
        assertEquals(2, EvenTextProtocol.totalPages(6))
        assertEquals(2, EvenTextProtocol.totalPages(10))
        assertEquals(3, EvenTextProtocol.totalPages(11))
    }

    @Test
    fun matchesOfficialCurrentPageMath() {
        assertEquals(1, EvenTextProtocol.currentPageForLine(0))
        assertEquals(2, EvenTextProtocol.currentPageForLine(1))
        assertEquals(2, EvenTextProtocol.currentPageForLine(4))
        assertEquals(2, EvenTextProtocol.currentPageForLine(5))
        assertEquals(3, EvenTextProtocol.currentPageForLine(6))
    }

    @Test
    fun paginatesTextByFiveLines() {
        val pages = EvenTextProtocol.paginateText("1\n2\n3\n4\n5\n6")
        assertEquals(listOf("1\n2\n3\n4\n5", "6"), pages)
    }

    @Test
    fun pinsEmptyTextPagination() {
        assertEquals(emptyList(), EvenTextProtocol.paginateText(""))
    }

    @Test
    fun rejectsMoreThanTwoHundredFiftyFiveLogicalDisplayPagesBeforePacketization() {
        val lineCount = EvenTextProtocol.MAX_LOGICAL_PAGES * EvenTextProtocol.LINES_PER_PAGE + 1
        val text = (1..lineCount).joinToString("\n") { "x" }

        assertFailsWith<GlassesError.Unsupported> {
            EvenTextProtocol.validatedDisplayPages(text)
        }
    }
}

class EvenMicProtocolTest {
    @Test
    fun buildsMicEnableAndDisableFrames() {
        assertContentEquals(byteArrayOf(0x0E, 0x01), EvenMicProtocol.controlFrame(enable = true))
        assertContentEquals(byteArrayOf(0x0E, 0x00), EvenMicProtocol.controlFrame(enable = false))
    }

    @Test
    fun parsesMicControlSuccessOnlyForMicCommand() {
        assertTrue(EvenMicProtocol.isControlSuccess(byteArrayOf(0x0E, 0xC9.toByte(), 0x01)))
        assertFalse(EvenMicProtocol.isControlSuccess(byteArrayOf(0x0E, 0xCA.toByte(), 0x01)))
        assertFalse(EvenMicProtocol.isControlSuccess(byteArrayOf(0x4E, 0xC9.toByte())))
    }

    @Test
    fun acceptsExactSizeMicNotificationAndExtractsLc3PayloadAndSequence() {
        val packet = byteArrayOf(0xF1.toByte(), 0x0A) +
            ByteArray(EvenMicProtocol.LC3_PAYLOAD_BYTES_PER_NOTIFICATION) { it.toByte() }
        val result = EvenMicProtocol.parseNotification(packet)

        assertTrue(result is EvenMicNotificationResult.Frame)
        assertEquals(10, result.frame.sequence)
        assertEquals(EvenMicProtocol.LC3_PAYLOAD_BYTES_PER_NOTIFICATION, result.frame.lc3Bytes.size)
        assertContentEquals(ByteArray(EvenMicProtocol.LC3_PAYLOAD_BYTES_PER_NOTIFICATION) { it.toByte() }, result.frame.lc3Bytes)
    }

    @Test
    fun rejectsTruncatedAndOversizedMicNotificationsAsMalformed() {
        assertTrue(EvenMicProtocol.parseNotification(byteArrayOf(0xF1.toByte(), 0x00, 0x11)) is EvenMicNotificationResult.Malformed)
        assertTrue(EvenMicProtocol.parseNotification(byteArrayOf(0xF1.toByte()) + ByteArray(99)) is EvenMicNotificationResult.Malformed)
        assertTrue(EvenMicProtocol.parseNotification(byteArrayOf(0xF1.toByte()) + ByteArray(202)) is EvenMicNotificationResult.Malformed)
    }

    @Test
    fun ignoresNonMicNotifications() {
        assertEquals(EvenMicNotificationResult.NotMic, EvenMicProtocol.parseNotification(byteArrayOf(0xF2.toByte(), 0x00, 0x11)))
        assertEquals(EvenMicNotificationResult.NotMic, EvenMicProtocol.parseNotification(ByteArray(0)))
    }
}

class EvenMicSequenceTrackerTest {
    @Test
    fun reconstructsMonotonicSequenceAcrossWraparound() {
        val tracker = EvenMicSequenceTracker()

        assertEquals(254L, tracker.next(254))
        assertEquals(255L, tracker.next(255))
        assertEquals(256L, tracker.next(0))
        assertEquals(257L, tracker.next(1))
    }

    @Test
    fun preservesSequenceGapAcrossWraparound() {
        val tracker = EvenMicSequenceTracker()

        assertEquals(255L, tracker.next(255))
        assertEquals(258L, tracker.next(2))
    }

    @Test
    fun skipsDuplicatesAndRewoundBytes() {
        val tracker = EvenMicSequenceTracker()

        assertEquals(10L, tracker.next(10))
        assertNull(tracker.next(10))
        assertNull(tracker.next(9))
        assertEquals(11L, tracker.next(11))
    }
}

class EvenStateEventsTest {
    @Test
    fun mapsSimpleTouchEventsToTapCounts() {
        assertEquals(1, EvenStateEvents.tapCount(byteArrayOf(0xF5.toByte(), 0x01)))
        assertEquals(2, EvenStateEvents.tapCount(byteArrayOf(0xF5.toByte(), 0x00)))
        assertEquals(3, EvenStateEvents.tapCount(byteArrayOf(0xF5.toByte(), 0x04)))
        assertEquals(3, EvenStateEvents.tapCount(byteArrayOf(0xF5.toByte(), 0x05)))
    }

    @Test
    fun ignoresNonTapStateEvents() {
        assertNull(EvenStateEvents.tapCount(byteArrayOf(0xF5.toByte(), 0x17)))
        assertNull(EvenStateEvents.tapCount(byteArrayOf(0x4E, 0x01)))
        assertNull(EvenStateEvents.tapCount(byteArrayOf(0xF5.toByte())))
    }
}
