package com.xgglass.core

import android.content.Intent

/**
 * Generic bridge for SDK adapters that need the host app to launch an external activity
 * and return its result asynchronously.
 */
data class ExternalActivityResult(
    val resultCode: Int,
    val data: Intent?,
)

fun interface ExternalActivityBridge {
    suspend fun launch(intent: Intent): ExternalActivityResult
}
