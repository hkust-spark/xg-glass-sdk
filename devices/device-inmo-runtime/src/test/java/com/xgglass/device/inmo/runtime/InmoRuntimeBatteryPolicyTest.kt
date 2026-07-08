package com.xgglass.device.inmo.runtime

import android.content.Intent
import android.os.BatteryManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InmoRuntimeBatteryPolicyTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun batteryChangedIntentMapsLevelAndScaleToPercent() {
        val intent = batteryIntent(level = 45, scale = 50)

        assertEquals(90, InmoRuntimeBatteryPolicy.percentFromIntent(intent))
    }

    @Test
    fun batteryChangedIntentClampsPercentAndRejectsInvalidValues() {
        assertEquals(100, InmoRuntimeBatteryPolicy.percentFromIntent(batteryIntent(level = 200, scale = 100)))
        assertNull(InmoRuntimeBatteryPolicy.percentFromIntent(batteryIntent(level = -1, scale = 100)))
        assertNull(InmoRuntimeBatteryPolicy.percentFromIntent(batteryIntent(level = 10, scale = 0)))
        assertNull(InmoRuntimeBatteryPolicy.percentFromIntent(mockk<Intent> { every { action } returns "other" }))
    }

    @Test
    fun batteryManagerCapacityMapsToClampedPercent() {
        val manager = mockk<BatteryManager>()
        every { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 72
        assertEquals(72, InmoRuntimeBatteryPolicy.percentFromManager(manager))

        every { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 123
        assertEquals(100, InmoRuntimeBatteryPolicy.percentFromManager(manager))

        every { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns -1
        assertNull(InmoRuntimeBatteryPolicy.percentFromManager(manager))
    }

    @Test
    fun emitsOnlyInitialOrMeaningfulOnePercentChanges() {
        assertTrue(InmoRuntimeBatteryPolicy.shouldEmit(previous = null, next = 50))
        assertFalse(InmoRuntimeBatteryPolicy.shouldEmit(previous = 50, next = 50))
        assertTrue(InmoRuntimeBatteryPolicy.shouldEmit(previous = 50, next = 51))
        assertTrue(InmoRuntimeBatteryPolicy.shouldEmit(previous = 50, next = 49))
    }

    private fun batteryIntent(level: Int, scale: Int): Intent = mockk {
        every { action } returns Intent.ACTION_BATTERY_CHANGED
        every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns level
        every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns scale
    }
}
