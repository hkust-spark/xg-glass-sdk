package com.xgglass.core

import kotlin.test.Test
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
}
