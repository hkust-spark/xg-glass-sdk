package com.xgglass.core

internal expect class NonSuspendingLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> NonSuspendingLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
