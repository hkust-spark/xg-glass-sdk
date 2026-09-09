package com.xgglass.device.rokid

import com.google.gson.JsonParser
import com.rokid.cxr.client.utils.ValueUtil.CxrStatus
import com.xgglass.core.GlassesError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RokidDisplayControllerTest {
    private class Scheduler : RokidDisplayScheduler {
        var now = 1000L
        val tasks = mutableListOf<Runnable>()
        override fun nowMillis() = now
        override fun postDelayed(task: Runnable, delayMs: Long) { tasks += task }
        override fun cancel(task: Runnable) { tasks.remove(task) }
        fun flush() {
            now += 350
            val pending = tasks.toList()
            tasks.clear()
            pending.forEach(Runnable::run)
        }
    }

    private class Transport : RokidDisplayTransport {
        val displayed = mutableListOf<String>()
        var openStatus = CxrStatus.REQUEST_SUCCEED
        var updateStatus = CxrStatus.REQUEST_SUCCEED
        var closes = 0
        override fun open(layout: String): CxrStatus {
            if (openStatus == CxrStatus.REQUEST_SUCCEED) {
                displayed += JsonParser.parseString(layout).asJsonObject["children"].asJsonArray[0]
                    .asJsonObject["props"].asJsonObject["text"].asString
            }
            return openStatus
        }
        override fun update(update: String): CxrStatus {
            if (updateStatus == CxrStatus.REQUEST_SUCCEED) {
                displayed += JsonParser.parseString(update).asJsonArray[0]
                    .asJsonObject["props"].asJsonObject["text"].asString
            }
            return updateStatus
        }
        override fun close() { closes++ }
    }

    @Test fun rapidAppendsKeepEveryChunk() {
        val transport = Transport()
        val scheduler = Scheduler()
        val controller = RokidDisplayController(transport = transport, scheduler = scheduler)
        controller.showText("A", force = false)
        controller.showText("B", force = false, append = true)
        controller.showText("C", force = false, append = true)
        assertEquals("ABC", controller.lastText)
        scheduler.flush()
        assertEquals(listOf("A", "ABC"), transport.displayed)
    }

    @Test fun forcedReplacementCancelsOlderQueuedUpdate() {
        val transport = Transport()
        val scheduler = Scheduler()
        val controller = RokidDisplayController(transport = transport, scheduler = scheduler)
        controller.showText("old", force = false)
        controller.showText("queued", force = false)
        val stale = scheduler.tasks.single()
        controller.showText("new", force = true)
        stale.run() // A scheduler that already dequeued the old callback must also be safe.
        scheduler.flush()
        assertEquals(listOf("old", "new"), transport.displayed)
    }

    @Test fun closeCancelsPendingCallbacksAndResetsAppendDocument() {
        val transport = Transport()
        val scheduler = Scheduler()
        val controller = RokidDisplayController(transport = transport, scheduler = scheduler)
        controller.showText("old", force = false)
        controller.showText("pending", force = false)
        val stale = scheduler.tasks.single()
        controller.close()
        stale.run()
        assertEquals(listOf("old"), transport.displayed)
        assertTrue(scheduler.tasks.isEmpty())
        controller.showText("fresh", force = false, append = true)
        assertEquals(listOf("old", "fresh"), transport.displayed)
        assertEquals(1, transport.closes)
    }

    @Test fun synchronousVendorFailureReachesCallerAndWaitingKeepsLegacyAcceptance() {
        val transport = Transport()
        val controller = RokidDisplayController(transport = transport, scheduler = Scheduler())
        transport.openStatus = CxrStatus.REQUEST_FAILED
        assertFailsWith<GlassesError.Transport> { controller.showText("failed", force = true) }
        assertEquals("", controller.lastText)
        transport.openStatus = CxrStatus.REQUEST_WAITING
        controller.showText("accepted", force = true)
        assertEquals("accepted", controller.lastText)
    }

    @Test fun failedQueuedUpdateReportsFailureAndKeepsDesiredDocument() {
        val transport = Transport()
        val scheduler = Scheduler()
        val failures = mutableListOf<Exception>()
        val controller = RokidDisplayController(transport = transport, scheduler = scheduler, onAsyncFailure = failures::add)
        controller.showText("A", force = false)
        controller.showText("B", force = false, append = true)
        transport.updateStatus = CxrStatus.REQUEST_FAILED
        transport.openStatus = CxrStatus.REQUEST_FAILED
        scheduler.flush()
        assertEquals(1, failures.size)
        assertEquals("AB", controller.lastText)
        transport.openStatus = CxrStatus.REQUEST_SUCCEED
        controller.showText("C", force = true, append = true)
        assertEquals(listOf("A", "ABC"), transport.displayed)
    }

    @Test fun failedUpdateCanReopenView() {
        val transport = Transport()
        val controller = RokidDisplayController(transport = transport, scheduler = Scheduler())
        controller.showText("A", force = true)
        transport.updateStatus = CxrStatus.REQUEST_FAILED
        controller.showText("B", force = true)
        assertEquals(listOf("A", "B"), transport.displayed)
    }

    @Test fun failedForcedUpdateDoesNotDiscardAnAlreadyAcceptedAppend() {
        val transport = Transport()
        val scheduler = Scheduler()
        val controller = RokidDisplayController(transport = transport, scheduler = scheduler)
        controller.showText("A", force = false)
        controller.showText("B", force = false, append = true)
        transport.updateStatus = CxrStatus.REQUEST_FAILED
        transport.openStatus = CxrStatus.REQUEST_FAILED
        assertFailsWith<GlassesError> { controller.showText("C", force = true) }
        transport.updateStatus = CxrStatus.REQUEST_SUCCEED
        transport.openStatus = CxrStatus.REQUEST_SUCCEED
        scheduler.flush()
        assertEquals(listOf("A", "AB"), transport.displayed)
    }

    @Test fun queuedUpdateCannotUseADisconnectedTransport() {
        val transport = Transport()
        val scheduler = Scheduler()
        var connected = true
        val failures = mutableListOf<Exception>()
        val controller = RokidDisplayController(
            transport = transport, scheduler = scheduler,
            isConnected = { connected }, onAsyncFailure = failures::add,
        )
        controller.showText("A", force = false)
        controller.showText("B", force = false)
        connected = false
        scheduler.flush()
        assertEquals(listOf("A"), transport.displayed)
        assertEquals(1, failures.size)
        assertEquals<Exception>(GlassesError.NotConnected, failures.single())
    }
}
