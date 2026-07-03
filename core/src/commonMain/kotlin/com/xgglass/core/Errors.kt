package com.xgglass.core

sealed class GlassesError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object NotConnected : GlassesError("Not connected")
    data object PermissionDenied : GlassesError("Required permissions not granted")
    data object Busy : GlassesError("Device is busy")
    data class Timeout(val operation: String) : GlassesError("Timeout: $operation")
    class Transport(val detail: String, cause: Throwable? = null) : GlassesError(detail, cause)
    data class Unsupported(val detail: String) : GlassesError("Unsupported: $detail")
}
