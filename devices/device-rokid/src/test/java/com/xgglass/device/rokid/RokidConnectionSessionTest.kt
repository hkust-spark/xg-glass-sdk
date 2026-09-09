package com.xgglass.device.rokid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RokidConnectionSessionTest {
    @Test fun lossAfterConnectionClearsBothTransportsAndIgnoresLateCallbacks() {
        val session = RokidConnectionSession()
        session.markBluetoothReady()
        session.markWifiReady()
        assertTrue(session.isReady)
        assertTrue(session.close(IllegalStateException("Bluetooth lost")))
        session.callback { session.markWifiReady() }
        assertFalse(session.bluetoothReady)
        assertFalse(session.wifiReady)
        assertFalse(session.isReady)
        assertFalse(session.close(IllegalStateException("duplicate loss")))
    }

    @Test fun disconnectInterruptsAllPendingHandshakes() = runTest {
        val session = RokidConnectionSession()
        val wifi = session.newWaiter<Unit>()
        val photo = session.newWaiter<String>()
        session.close(CancellationException("explicit disconnect"))
        assertTrue(wifi.isCompleted)
        assertTrue(photo.isCompleted)
        assertFailsWith<CancellationException> { wifi.await() }
        assertFailsWith<CancellationException> { photo.await() }
    }

    @Test fun failedCachedAttemptCannotBreakSuccessfulRetry() {
        val session = RokidConnectionSession()
        val cachedAttempt = session.nextBluetoothAttempt()
        session.retireBluetoothAttempt(cachedAttempt)
        val scanAttempt = session.nextBluetoothAttempt()
        session.bluetoothCallback(scanAttempt) { session.markBluetoothReady() }
        session.bluetoothCallback(cachedAttempt) { session.close(IllegalStateException("late cached failure")) }
        assertTrue(session.bluetoothReady)
    }

    @Test fun retiredConnectionCannotRunCallbacksInNewConnection() {
        val old = RokidConnectionSession()
        val current = RokidConnectionSession()
        old.close(CancellationException("replaced"))
        var calls = 0
        old.callback { calls++ }
        current.callback { calls++ }
        assertEquals(1, calls)
        assertTrue(current.isOpen)
    }

    @Test fun repeatedAndCancelledVendorCompletionsAreHarmless() = runTest {
        val session = RokidConnectionSession()
        val result = session.newWaiter<String>()
        assertTrue(result.complete("first"))
        assertFalse(result.complete("duplicate"))
        assertFalse(result.completeExceptionally(IllegalStateException("late failure")))
        assertEquals("first", result.await())
        val cancelled = session.newWaiter<String>()
        cancelled.cancel()
        session.callback { assertFalse(cancelled.complete("late photo")) }
        session.close(CancellationException("cleanup"))
    }
}
