package com.xgglass.core

import platform.Foundation.NSRecursiveLock

internal actual class NonSuspendingLock actual constructor() {
    private val delegate = NSRecursiveLock()

    actual fun lock() {
        delegate.lock()
    }

    actual fun unlock() {
        delegate.unlock()
    }
}
