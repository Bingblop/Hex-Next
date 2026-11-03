package com.hexnext.engine.compile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValueNamesTest {
    @Test
    fun `three column lines, default config and every value form`() {
        val parsed = ValueNames.parse(
            "dimen",
            listOf(
                "qs_corner_radius\tdefault\t28.0dp",
                "qs_corner_radius\tsw600dp-v13\t32.0dp",
                "alpha\tdefault\t0.5f",
                "old_float\tdefault\tOther(dataType=4, data=1056964608)",
                "ref\tdefault\t@0x7f070001",
            ).joinToString("\n"),
        )
        assertEquals(0, parsed.dropped)
        val corner = parsed.values.getValue("dimen/qs_corner_radius")
        assertEquals(listOf("", "sw600dp-v13"), corner.map { it.config })
        assertEquals(ValueTypes.TYPE_DIMENSION, corner[0].dataType)
        assertEquals(28.0, Dimens.value(corner[0].data), 1e-6)
        assertEquals(0.5f, Float.fromBits(parsed.values.getValue("dimen/alpha").single().data))
        assertEquals(0.5f, Float.fromBits(parsed.values.getValue("dimen/old_float").single().data))
        val ref = parsed.values.getValue("dimen/ref").single()
        assertFalse(ref.resolved)
        assertEquals(0x7f070001, ref.refId)
    }

    @Test
    fun `a fourth column resolves a reference`() {
        val ref = ValueNames.parse("dimen", "slider\tland\t@0x7f070002\t44.0dp").values.getValue("dimen/slider").single()
        assertTrue(ref.resolved)
        assertEquals("land", ref.config)
        assertEquals(0x7f070002, ref.refId)
        assertEquals(44.0, Dimens.value(ref.data), 1e-6)
    }

    @Test
    fun `bools and integers`() {
        val bools = ValueNames.parse("bool", "a\tdefault\ttrue\nb\tnight-v8\tfalse").values
        assertEquals(ValueTypes.BOOL_TRUE, bools.getValue("bool/a").single().data)
        assertEquals(0, bools.getValue("bool/b").single().data)
        assertEquals("night-v8", bools.getValue("bool/b").single().config)
        val ints = ValueNames.parse("integer", "cols\tdefault\t4\nneg\tdefault\t-2").values
        assertEquals(ValueTypes.TYPE_INT_DEC, ints.getValue("integer/cols").single().dataType)
        assertEquals(4, ints.getValue("integer/cols").single().data)
        assertEquals(-2, ints.getValue("integer/neg").single().data)
    }

    @Test
    fun `malformed lines are dropped and counted`() {
        val parsed = ValueNames.parse("dimen", "ok\tdefault\t4.0dp\nno tabs here\nbad\tdefault\tgarbage\n\ttoo\tmany\tcolumns\there\n")
        assertEquals(setOf("dimen/ok"), parsed.values.keys)
        assertEquals(3, parsed.dropped)
    }
}
