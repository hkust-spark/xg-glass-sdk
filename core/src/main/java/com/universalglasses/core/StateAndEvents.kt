package com.universalglasses.core

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val error: GlassesError) : ConnectionState()
}

sealed class GlassesEvent {
    data class Log(val message: String) : GlassesEvent()
    data class Warning(val message: String) : GlassesEvent()
    data class Tap(val count: Int) : GlassesEvent()
}
