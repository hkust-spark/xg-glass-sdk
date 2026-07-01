package com.universalglasses.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlassesErrorTest {
    @Test
    fun `sealed error subtypes expose useful messages`() {
        // Arrange
        val timeout = GlassesError.Timeout("capturePhoto")
        val unsupported = GlassesError.Unsupported("display text")
        val transport = GlassesError.Transport("socket closed")

        // Act / Assert
        assertNotNull(timeout.message)
        assertTrue(timeout.message!!.contains("capturePhoto"))
        assertEquals("Unsupported: display text", unsupported.message)
        assertEquals("socket closed", transport.message)
    }
}
