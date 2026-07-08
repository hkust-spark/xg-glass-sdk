package com.xgglass.device.inmo.runtime

import android.content.Intent
import android.os.BatteryManager
import kotlin.math.roundToInt

internal object InmoRuntimeBatteryPolicy {
    fun percentFromIntent(intent: Intent?): Int? {
        if (intent == null || intent.action != Intent.ACTION_BATTERY_CHANGED) return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return ((level.toDouble() / scale.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    fun percentFromManager(manager: BatteryManager?): Int? {
        val value = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return null
        if (value < 0) return null
        return value.coerceIn(0, 100)
    }

    fun shouldEmit(previous: Int?, next: Int): Boolean = previous == null || kotlin.math.abs(next - previous) >= 1
}
