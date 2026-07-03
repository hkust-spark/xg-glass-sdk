package com.xgglass.core

/**
 * Connection state for a glasses client.
 *
 * New subtypes may be added in minor releases; clients should include an `else`
 * branch in `when` expressions.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val error: GlassesError) : ConnectionState()
}

/**
 * Events emitted by a glasses client.
 *
 * New subtypes may be added in minor releases; clients should include an `else`
 * branch in `when` expressions.
 */
sealed class GlassesEvent {
    data class Log(val message: String) : GlassesEvent()
    data class Warning(val message: String) : GlassesEvent()
    data class Tap(val count: Int) : GlassesEvent()
}
