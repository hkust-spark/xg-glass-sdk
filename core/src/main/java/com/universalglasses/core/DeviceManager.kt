package com.universalglasses.core

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

sealed class DeviceManagerState {
    data object Idle : DeviceManagerState()
    data object Installing : DeviceManagerState()
    data object Installed : DeviceManagerState()
    data class Error(val error: GlassesError) : DeviceManagerState()
}
