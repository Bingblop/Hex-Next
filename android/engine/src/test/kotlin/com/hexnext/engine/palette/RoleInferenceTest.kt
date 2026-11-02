package com.hexnext.engine.palette

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoleInferenceTest {
    private val reference = ThemeResolver.resolve(ThemeSource(0xFF6750A4.toInt())).light.shades
    private val target = ThemeResolver.resolve(ThemeSource(0xFF2E9E44.toInt(), style = PaletteStyle.VIBRANT))

    @Test
    fun recoversEveryReferenceShade() {
        for (key in ShadeScale.ALL_KEYS) {
            val observed = reference.getValue(key)
            val got = assertNotNull(RoleInference.infer(observed, reference))
            assertEquals(0.0, got.deltaE, 1e-9, key)
            // Identical colours (white/black ends) resolve to the first equal key; others exactly.
            val firstEqual = ShadeScale.ALL_KEYS.first { reference[it] == observed }
            assertEquals(firstEqual, got.key, key)
            if (ShadeScale.ALL_KEYS.count { reference[it] == observed } == 1) assertEquals(key, got.key)
        }
    }

    @Test
    fun recolorKeepsAlpha() {
        val observed = (reference.getValue("system_accent1_600") and 0x00FFFFFF) or (0x7F shl 24)
        val inferred = assertNotNull(RoleInference.infer(observed, reference))
        assertEquals(0x7F, inferred.alpha)
        assertEquals(PaletteRole.ACCENT1, inferred.role)
        assertEquals(600, inferred.shade)
        val out = assertNotNull(RoleInference.recolor(observed, reference, target, isDark = true))
        assertEquals((target.dark.shades.getValue("system_accent1_600") and 0x00FFFFFF) or (0x7F shl 24), out)
    }

    @Test
    fun farColoursStayFixed() {
        val orange = 0xFFFF6A00.toInt()
        val d = assertNotNull(RoleInference.infer(orange, reference)).deltaE
        assert(d > 6.0)
        assertNull(RoleInference.recolor(orange, reference, target, isDark = false))
        assertNotNull(RoleInference.recolor(orange, reference, target, isDark = false, maxDeltaE = d + 1))
        assertNull(RoleInference.infer(orange, emptyMap()))
    }
}
