package com.xgglass.core

/**
 * Error types returned inside [Result] by [GlassesClient] operations.
 *
 * SDK methods do not throw except [kotlin.coroutines.cancellation.CancellationException].
 * New subtypes may be added in minor releases; use an `else` branch when matching.
 */
sealed class GlassesError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The client is not connected; call [GlassesClient.connect] first or reconnect after loss. */
    data object NotConnected : GlassesError("Not connected")

    /** A required runtime permission is missing; exact permissions depend on the device module. */
    data object PermissionDenied : GlassesError("Required permissions not granted")

    /** A previous operation is still in flight; retry after it completes. */
    data object Busy : GlassesError("Device is busy")

    /** The named [operation] exceeded its deadline; retrying is reasonable. */
    data class Timeout(val operation: String) : GlassesError("Timeout: $operation")

    /**
     * Device- or link-level failure.
     *
     * [detail] is diagnostic text for logs, not end-user UI. [cause] carries the underlying
     * throwable when available.
     */
    class Transport(val detail: String, cause: Throwable? = null) : GlassesError(detail, cause)

    /** This device/capability combination cannot perform the operation; check [DeviceCapabilities] first. */
    data class Unsupported(val detail: String) : GlassesError("Unsupported: $detail")
}
