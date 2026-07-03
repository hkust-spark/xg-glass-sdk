package com.xgglass.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Shared state, event, and connect lifecycle plumbing for device clients.
 */
abstract class BaseGlassesClient(
    initialCapabilities: DeviceCapabilities,
    eventBufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST,
) : GlassesClient {
    private val defaultCapabilities: DeviceCapabilities = initialCapabilities

    @Volatile
    private var currentCapabilities: DeviceCapabilities = initialCapabilities
    override val capabilities: DeviceCapabilities
        get() = currentCapabilities

    protected val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    protected val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = eventBufferOverflow,
    )
    override val events: Flow<GlassesEvent> = _events

    private val connectMutex = Mutex()

    protected open val markConnectingOnConnect: Boolean = true
    protected open val rethrowConnectCancellation: Boolean = false

    final override suspend fun connect(): Result<Unit> = connectMutex.withLock {
        if (shouldShortCircuitConnect(_state.value)) {
            return Result.success(Unit)
        }

        val preflight = beforeConnect()
        if (preflight != null) {
            return preflight
        }

        if (markConnectingOnConnect) {
            _state.value = ConnectionState.Connecting
        }

        return try {
            doConnect()
            _state.value = ConnectionState.Connected
            Result.success(Unit)
        } catch (ce: CancellationException) {
            _state.value = ConnectionState.Disconnected
            if (rethrowConnectCancellation) {
                throw ce
            }
            Result.failure(ce)
        } catch (e: Exception) {
            val err = mapConnectError(e)
            _state.value = ConnectionState.Error(err)
            Result.failure(err)
        }
    }

    protected open fun shouldShortCircuitConnect(state: ConnectionState): Boolean {
        return state is ConnectionState.Connected || state is ConnectionState.Connecting
    }

    protected open suspend fun beforeConnect(): Result<Unit>? = null

    protected open fun mapConnectError(error: Exception): GlassesError {
        return (error as? GlassesError)
            ?: GlassesError.Transport("${this::class.simpleName ?: "GlassesClient"} connect failed: ${error.message}", error)
    }

    protected abstract suspend fun doConnect()

    /** Refine the reported capabilities (e.g. after the real device model is known). Thread-safe. */
    protected fun updateCapabilities(transform: (DeviceCapabilities) -> DeviceCapabilities) {
        currentCapabilities = transform(currentCapabilities)
    }

    /** Restore the initial (pre-connect) capabilities. Call on disconnect. */
    protected fun resetCapabilities() {
        currentCapabilities = defaultCapabilities
    }

    protected fun emitLog(message: String) {
        _events.tryEmit(GlassesEvent.Log(message))
    }

    protected fun emitWarn(message: String) {
        _events.tryEmit(GlassesEvent.Warning(message))
    }
}
