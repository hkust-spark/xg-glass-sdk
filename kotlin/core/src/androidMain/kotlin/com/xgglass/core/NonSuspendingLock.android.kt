package com.xgglass.core

import java.util.concurrent.locks.ReentrantLock

internal actual class NonSuspendingLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun lock() {
        delegate.lock()
    }

    actual fun unlock() {
        delegate.unlock()
    }
}
