package com.hexnext.engine.compile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DimensTest {
    @Test
    fun `whole numbers use radix 23p0 as aapt2 writes them`() {
        assertEquals(0x00001001, Dimens.encode(16f, DimenUnit.DP))
        assertEquals(0x00001000, Dimens.encode(16f, DimenUnit.PX))
        assertEquals(0x00000c02, Dimens.encode(12f, DimenUnit.SP))
        assertEquals(0x00000001, Dimens.encode(0f, DimenUnit.DP))
    }

    @Test
    fun `fractions pick the smallest radix that holds them`() {
        val half = Dimens.encode(0.5f, DimenUnit.DP)
        assertEquals(3, (half shr 4) and 0x3, "0p23 for a value below 1")
        assertEquals(0.5, Dimens.value(half), 1e-6)
        val threeHalf = Dimens.encode(3.5f, DimenUnit.PX)
        assertEquals(2, (threeHalf shr 4) and 0x3, "8p15 for a small fraction")
        assertEquals(3.5, Dimens.value(threeHalf), 1e-6)
        val big = Dimens.encode(300.25f, DimenUnit.DP)
        assertEquals(300.25, Dimens.value(big), 1e-3)
    }

    @Test
    fun `negative values round trip`() {
        val raw = Dimens.encode(-4f, DimenUnit.DP)
        assertEquals(-4.0, Dimens.value(raw), 1e-6)
        assertEquals("-4.0dp", Dimens.format(raw))
    }

    @Test
    fun `format matches the scanner's old output`() {
        // The scanner used to print (raw & 0xFFFFFF00) * RADIX_MULTS[radix] in float.
        for (raw in listOf(0x00001001, 0x00000c02, 0x00002001, Dimens.encode(3.5f, DimenUnit.PX), Dimens.encode(0.5f, DimenUnit.DP))) {
            val old = (raw and 0xFFFFFF00.toInt()).toFloat() * floatArrayOf(1f / (1 shl 8), 1f / (1 shl 15), 1f / (1 shl 23), 1f / (1L shl 31).toFloat())[(raw shr 4) and 3]
            val unit = arrayOf("px", "dp", "sp", "pt", "in", "mm")[raw and 0xF]
            assertEquals("$old$unit", Dimens.format(raw))
        }
        assertEquals("16.0dp", Dimens.format(0x00001001))
    }

    @Test
    fun `parse reads scanner text and dip`() {
        assertEquals(0x00001001, Dimens.parse("16.0dp"))
        assertEquals(0x00001001, Dimens.parse("16dip"))
        assertTrue(Dimens.sameSize(Dimens.parse("3.5px")!!, Dimens.encode(3.5f, DimenUnit.PX)))
        assertEquals(0x00000c02, Dimens.parse("12sp"))
        assertNull(Dimens.parse("12"))
        assertNull(Dimens.parse("true"))
    }

    @Test
    fun `sameSize ignores the radix but not the unit`() {
        assertTrue(Dimens.sameSize(0x00001001, Dimens.encode(16.0, 1)))
        assertFalse(Dimens.sameSize(0x00001001, 0x00001000))
        assertFalse(Dimens.sameSize(0x00001001, 0x00001101))
    }
}
