package com.xgglass.device.rayneo.runtime

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

class RayNeoRuntimeBatteryPolicyTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun batteryChangedIntentMapsLevelAndScaleToPercent() {
        val intent = batteryIntent(level = 3, scale = 4)

        assertEquals(75, RayNeoRuntimeBatteryPolicy.percentFromIntent(intent))
    }

    @Test
    fun batteryChangedIntentClampsPercentAndRejectsInvalidValues() {
        assertEquals(100, RayNeoRuntimeBatteryPolicy.percentFromIntent(batteryIntent(level = 101, scale = 100)))
        assertNull(RayNeoRuntimeBatteryPolicy.percentFromIntent(batteryIntent(level = -1, scale = 100)))
        assertNull(RayNeoRuntimeBatteryPolicy.percentFromIntent(batteryIntent(level = 10, scale = 0)))
        assertNull(RayNeoRuntimeBatteryPolicy.percentFromIntent(mockk<Intent> { every { action } returns "other" }))
    }

    @Test
    fun batteryManagerCapacityMapsToClampedPercent() {
        val manager = mockk<BatteryManager>()
        every { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 34
        assertEquals(34, RayNeoRuntimeBatteryPolicy.percentFromManager(manager))

        every { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 255
        assertEquals(100, RayNeoRuntimeBatteryPolicy.percentFromManager(manager))

        every { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns -1
        assertNull(RayNeoRuntimeBatteryPolicy.percentFromManager(manager))
    }

    @Test
    fun emitsOnlyInitialOrMeaningfulOnePercentChanges() {
        assertTrue(RayNeoRuntimeBatteryPolicy.shouldEmit(previous = null, next = 80))
        assertFalse(RayNeoRuntimeBatteryPolicy.shouldEmit(previous = 80, next = 80))
        assertTrue(RayNeoRuntimeBatteryPolicy.shouldEmit(previous = 80, next = 81))
        assertTrue(RayNeoRuntimeBatteryPolicy.shouldEmit(previous = 80, next = 79))
    }

    private fun batteryIntent(level: Int, scale: Int): Intent = mockk {
        every { action } returns Intent.ACTION_BATTERY_CHANGED
        every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns level
        every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns scale
    }
}
