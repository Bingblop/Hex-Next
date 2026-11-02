package com.hexnext.engine.palette

import com.hexnext.engine.mcu.hct.Hct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteUnitsTest {
    @Test
    fun shadeScale() {
        assertEquals(65, ShadeScale.ALL_KEYS.size)
        assertEquals("system_accent1_0", ShadeScale.ALL_KEYS.first())
        assertEquals("system_neutral2_1000", ShadeScale.ALL_KEYS.last())
        assertEquals(50, ShadeScale.toneOf(500))
        assertEquals(10, ShadeScale.shadeOf(99))
        assertEquals(PaletteRole.NEUTRAL2 to 7, ShadeScale.parseKey("neutral2_500"))
        assertEquals(PaletteRole.ACCENT3 to 0, ShadeScale.parseKey("system_accent3_0"))
        assertNull(ShadeScale.parseKey("accent1_550"))
        assertNull(ShadeScale.parseKey("error_500"))
    }

    @Test
    fun curves() {
        assertEquals(0.0, PaletteTuning.backgroundTone(6.0, 0), 1e-9)
        assertEquals(1.0, PaletteTuning.backgroundTone(9.0, 0), 1e-9)
        assertEquals(90.0, PaletteTuning.backgroundTone(95.0, 0), 1e-9)
        assertEquals(75.0, PaletteTuning.backgroundTone(85.0, 0), 1e-9)
        assertEquals(20.0, PaletteTuning.backgroundTone(10.0, 200), 1e-9)
        assertEquals(15.0, PaletteTuning.backgroundTone(10.0, 150), 1e-9)
        assertEquals(42.0, PaletteTuning.backgroundTone(42.0, 100), 1e-9)
        assertEquals(0.0, PaletteTuning.vividToneShift(50.0))
        assertEquals(3.0, PaletteTuning.vividToneShift(55.0), 1e-9)
        assertEquals(6.0, PaletteTuning.vividToneShift(80.0))
        assertEquals(3.0, PaletteTuning.vividToneShift(95.0), 1e-9)
        assertEquals(0.0, PaletteTuning.vividToneShift(100.0))
    }

    @Test
    fun chromaFactors() {
        val adj = ThemeAdjustments(accentSaturation = 50, backgroundSaturation = 150, accurateShades = false)
        assertEquals(0.625, PaletteTuning.chromaFactor(PaletteRole.ACCENT1, PaletteStyle.TONAL_SPOT, adj), 1e-9)
        assertEquals(1.5, PaletteTuning.chromaFactor(PaletteRole.NEUTRAL1, PaletteStyle.TONAL_SPOT, adj), 1e-9)
        assertEquals(1.0, PaletteTuning.chromaFactor(PaletteRole.NEUTRAL1, PaletteStyle.RAINBOW, adj), 1e-9)
        assertEquals(0.625, PaletteTuning.chromaFactor(PaletteRole.ACCENT1, PaletteStyle.RAINBOW, adj), 1e-9)
        assertEquals(1.0, PaletteTuning.chromaFactor(PaletteRole.ACCENT1, PaletteStyle.MONOCHROME, adj), 1e-9)
    }

    @Test
    fun gamutClipKeepsHueAndTone() {
        val argb = ColorMath.clipToGamut(140.0, 150.0, 50.0)
        val hct = Hct.fromInt(argb)
        assertEquals(50.0, hct.tone, 0.5)
        assertEquals(140.0, hct.hue, 2.0)
        assertTrue(hct.chroma < 150.0 && hct.chroma > 20.0)
        val inGamut = ColorMath.clipToGamut(250.0, 20.0, 60.0)
        assertEquals(20.0, Hct.fromInt(inGamut).chroma, 1.0)
    }

    @Test
    fun shadeOverridePinsExactlyAndStaysMonotonic() {
        val shades = ThemeResolver.resolve(ThemeSource(0xFF3F7DD6.toInt())).light.shades
        for (pin in listOf(0xFF4C8AE8.toInt(), 0xFF1F4F99.toInt())) {
            val out = ShadeOverrides.apply(shades, mapOf("accent1_500" to pin, "system_accent1_800" to 0xFF10305A.toInt()))
            assertEquals(pin, out["system_accent1_500"])
            assertEquals(0xFF10305A.toInt(), out["system_accent1_800"])
            val ls = ShadeScale.SHADES.map { ColorMath.lstar(out.getValue("system_accent1_$it")) }
            for (i in 1 until ls.size) assertTrue(ls[i] < ls[i - 1] + 0.3, "L* $ls")
            // neighbours moved toward the pin, the ends did not
            assertTrue(out["system_accent1_400"] != shades["system_accent1_400"])
            assertEquals(shades["system_accent1_0"], out["system_accent1_0"])
            assertEquals(shades["system_accent1_1000"], out["system_accent1_1000"])
            // other roles untouched
            for (k in ShadeScale.ALL_KEYS.filter { !it.startsWith("system_accent1_") }) assertEquals(shades[k], out[k])
        }
    }

    @Test
    fun presets() {
        assertEquals(24, ColorPresets.ALL.size)
        assertEquals(24, ColorPresets.ALL.map { it.name }.toSet().size)
        assertTrue(ColorPresets.ALL.all { ColorMath.alpha(it.argb) == 255 })
        assertEquals(0xFF009688.toInt(), ColorPresets.byName("teal")?.argb)
    }
}
