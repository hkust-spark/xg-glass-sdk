package com.xgglass.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StateAndEventsTest {
    @Test
    fun `long press is a singleton glasses event`() {
        assertSame(GlassesEvent.LongPress, GlassesEvent.LongPress)
    }

    @Test
    fun `long press capability defaults to false and can be enabled`() {
        assertFalse(DeviceCapabilities().supportsLongPressEvents)
        assertTrue(DeviceCapabilities(supportsLongPressEvents = true).supportsLongPressEvents)
    }

    @Test
    fun `battery level carries percent value`() {
        assertEquals(42, GlassesEvent.BatteryLevel(42).percent)
    }

    @Test
    fun `battery capability defaults to false and can be enabled`() {
        assertFalse(DeviceCapabilities().supportsBatteryEvents)
        assertTrue(DeviceCapabilities(supportsBatteryEvents = true).supportsBatteryEvents)
    }
}
