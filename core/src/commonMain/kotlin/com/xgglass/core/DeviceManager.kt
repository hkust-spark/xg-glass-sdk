package com.xgglass.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phone-side manager for glasses whose app runs on the glasses.
 */
interface DeviceManager {
    val model: GlassesModel
    val state: StateFlow<DeviceManagerState>
    val events: Flow<GlassesEvent>

    /** Install or update the on-glasses app and prepare the device. */
    suspend fun install(): Result<Unit>

    /** Push user-configured settings to the on-glasses app. */
    suspend fun pushSettings(settings: Map<String, String>): Result<Unit>

    /** Release resources. Safe to call multiple times. */
    suspend fun close()
}

/** Installation lifecycle state for a [DeviceManager]. */
sealed class DeviceManagerState {
    /** No install or settings push is currently running. */
    data object Idle : DeviceManagerState()

    /** The manager is installing or updating the on-glasses app. */
    data object Installing : DeviceManagerState()

    /** The on-glasses app is installed and ready for settings or launch. */
    data object Installed : DeviceManagerState()

    /** The last manager operation failed with [error]. */
    data class Error(val error: GlassesError) : DeviceManagerState()
}
