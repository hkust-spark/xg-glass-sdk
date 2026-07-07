package com.xgglass.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.Volatile

/**
 * Shared state, event, and connect lifecycle plumbing for device clients.
 */
@OptIn(ExperimentalAtomicApi::class)
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

    private val countNoSubscriberSuspendDrops = eventBufferOverflow == BufferOverflow.SUSPEND
    private val totalDroppedEvents = AtomicLong(0)
    private val unreportedDroppedEvents = AtomicLong(0)
    private val noSubscriberBufferedEvents = AtomicLong(0)

    private val connectMutex = Mutex()

    protected open val markConnectingOnConnect: Boolean = true
    protected open val rethrowConnectCancellation: Boolean = false

    /**
     * Total event emissions rejected since this client was created.
     *
     * The default [BufferOverflow.DROP_OLDEST] overflow mode means `tryEmit` never returns false;
     * losses as silently discarded oldest events are not observable via this counter.
     * This counter observes rejected emissions for [BufferOverflow.SUSPEND] overflow clients.
     * For no-subscriber [BufferOverflow.SUSPEND] clients, emissions beyond the base event buffer
     * capacity are treated as rejected because there is no collector available to drain them.
     */
    val droppedEventCount: Long
        get() = totalDroppedEvents.load()

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

    protected fun emitEvent(event: GlassesEvent): Boolean {
        val emitted = _events.tryEmit(event)
        if (countNoSubscriberSuspendDrops && _events.subscriptionCount.value == 0) {
            val buffered = noSubscriberBufferedEvents.fetchAndAdd(1)
            if (buffered >= EVENT_BUFFER_CAPACITY) {
                recordDroppedEvent()
                return false
            }
        } else {
            noSubscriberBufferedEvents.store(0)
        }
        if (!emitted) {
            recordDroppedEvent()
            return false
        }
        emitPendingDropWarning()
        return true
    }

    protected fun emitLog(message: String) {
        emitEvent(GlassesEvent.Log(message))
    }

    protected fun emitWarn(message: String) {
        emitEvent(GlassesEvent.Warning(message))
    }

    private fun recordDroppedEvent() {
        totalDroppedEvents.fetchAndAdd(1)
        unreportedDroppedEvents.fetchAndAdd(1)
    }

    private fun emitPendingDropWarning() {
        if (_events.subscriptionCount.value == 0) return
        val pending = unreportedDroppedEvents.load()
        if (pending <= 0L) return
        if (!unreportedDroppedEvents.compareAndSet(pending, 0L)) return

        val notice = GlassesEvent.Warning("$pending event(s) were dropped because the event buffer was full")
        if (!_events.tryEmit(notice)) {
            unreportedDroppedEvents.fetchAndAdd(pending)
        }
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY: Long = 64
    }
}
