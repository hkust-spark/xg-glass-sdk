package com.xgglass.core.android

import org.junit.Test
import kotlin.test.assertContentEquals

class Pcm8Test {
    @Test
    fun `signed silence and extrema map to Android unsigned PCM`() {
        val signed = byteArrayOf(-128, -1, 0, 1, 127)
        val unsigned = convertPcm8Signedness(signed)
        assertContentEquals(byteArrayOf(0, 127, -128, -127, -1), unsigned)
        assertContentEquals(byteArrayOf(-128, -1, 0, 1, 127), signed)
        assertContentEquals(signed, convertPcm8Signedness(unsigned))
    }
}
