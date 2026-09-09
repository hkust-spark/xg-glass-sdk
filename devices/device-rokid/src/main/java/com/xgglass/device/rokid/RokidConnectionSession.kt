package com.xgglass.device.rokid

import kotlinx.coroutines.CompletableDeferred

/** One connection lifetime. Retired callbacks cannot modify a replacement connection. */
internal class RokidConnectionSession {
    private val lock = Any()
    private data class Status(val open: Boolean = true, val bluetooth: Boolean = false, val wifi: Boolean = false)
    @Volatile private var status = Status()
    private var bluetoothAttempt = 0L
    private val pending = mutableSetOf<CompletableDeferred<*>>()

    // Readiness is an immutable snapshot so microphone callbacks never need to
    // acquire the connection lock while holding their own recorder lock.
    val isOpen: Boolean get() = status.open
    val bluetoothReady: Boolean get() = status.let { it.open && it.bluetooth }
    val wifiReady: Boolean get() = status.let { it.open && it.wifi }
    val isReady: Boolean get() = status.let { it.open && it.bluetooth && it.wifi }

    fun <T> newWaiter(): CompletableDeferred<T> = synchronized(lock) {
        CompletableDeferred<T>().also { waiter ->
            if (status.open) {
                pending.add(waiter)
                waiter.invokeOnCompletion { synchronized(lock) { pending.remove(waiter) } }
            } else {
                waiter.completeExceptionally(IllegalStateException("Rokid connection is closed"))
            }
        }
    }

    fun nextBluetoothAttempt(): Long = synchronized(lock) {
        status = status.copy(bluetooth = false)
        ++bluetoothAttempt
    }

    fun retireBluetoothAttempt(attempt: Long) = synchronized(lock) {
        if (attempt == bluetoothAttempt) bluetoothAttempt++
    }

    fun bluetoothCallback(attempt: Long, action: () -> Unit) = synchronized(lock) {
        if (status.open && attempt == bluetoothAttempt) action()
    }

    fun callback(action: () -> Unit) = synchronized(lock) {
        if (status.open) action()
    }

    fun markBluetoothReady() = synchronized(lock) { if (status.open) status = status.copy(bluetooth = true) }
    fun markWifiReady() = synchronized(lock) { if (status.open) status = status.copy(wifi = true) }

    /** Success publication and disconnect must have one ordering, not a check-then-write race. */
    fun publishIfReady(publish: () -> Unit): Boolean = synchronized(lock) {
        if (!isReady) return@synchronized false
        publish()
        true
    }

    /** Completes every pending handshake immediately on loss, cancellation, or explicit disconnect. */
    fun close(cause: Exception): Boolean {
        val waiters = synchronized(lock) {
            if (!status.open) return false
            status = Status(open = false)
            pending.toList().also { pending.clear() }
        }
        waiters.forEach { it.completeExceptionally(cause) }
        return true
    }
}
