package com.hexnext.engine.palette

import com.hexnext.engine.mcu.hct.Hct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeResolverTest {
    private val seed = 0xFF3F7DD6.toInt()
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun assertMonotonic(s: ResolvedScheme, label: String) {
        for (role in PaletteRole.SHADE_ROLES) {
            val ls = ShadeScale.SHADES.map { ColorMath.lstar(s.shade(role, it)) }
            for (i in 1 until ls.size) {
                assertTrue(ls[i] <= ls[i - 1] + 0.5, "$label $role L* not decreasing at ${ShadeScale.SHADES[i]}: $ls")
            }
        }
    }

    @Test
    fun allStylesSpecsAndModesProduce65MonotonicShades() {
        for (style in PaletteStyle.entries) for (spec in ColorSpecVersion.entries) {
            val theme = ThemeResolver.resolve(ThemeSource(seed, style = style, spec = spec))
            for (s in listOf(theme.light, theme.dark)) {
                val label = "$style/$spec/${if (s.isDark) "dark" else "light"}"
                assertEquals(ShadeScale.ALL_KEYS, s.shades.keys.toList(), label)
                assertMonotonic(s, label)
                for (r in listOf("primary", "on_primary", "surface", "on_surface", "surface_container_high")) {
                    assertTrue(r in s.roles, "$label missing $r")
                }
            }
        }
    }

    @Test
    fun rolesMatchMcuWithoutAdjustments() {
        val src = ThemeSource(seed)
        val scheme = PaletteStyle.TONAL_SPOT.buildScheme(seed, isDark = true)
        val mdc = com.hexnext.engine.mcu.dynamiccolor.MaterialDynamicColors()
        val dark = ThemeResolver.resolve(src).dark
        assertEquals(mdc.primary().getArgb(scheme), dark.roles["primary"])
        assertEquals(mdc.surfaceContainerHigh().getArgb(scheme), dark.roles["surface_container_high"])
        assertEquals(6.0, dark.surfaceTone, 0.6)
    }

    @Test
    fun isDeterministic() {
        val src = ThemeSource(seed, 0xFF00AA55.toInt(), 0xFFAA3300.toInt(), PaletteStyle.EXPRESSIVE, ColorSpecVersion.SPEC_2025, 0.3)
        val adj = ThemeAdjustments(accentSaturation = 140, backgroundLightness = 60, accurateShades = false)
        assertEquals(ThemeResolver.resolve(src, adj, adj), ThemeResolver.resolve(src, adj, adj))
    }

    @Test
    fun secondaryAndTertiarySeedsSetHues() {
        val green = 0xFF2E9E44.toInt()
        val red = 0xFFD03030.toInt()
        val s = ThemeResolver.resolve(ThemeSource(seed, secondaryArgb = green, tertiaryArgb = red)).light
        val hue = { argb: Int -> Hct.fromInt(argb).hue }
        assertEquals(hue(green), hue(s.shade(PaletteRole.ACCENT2, 500)), 3.0)
        assertEquals(hue(red), hue(s.shade(PaletteRole.ACCENT3, 500)), 3.0)
    }

    private fun labChroma(argb: Int): Double = ColorMath.lab(argb).let { kotlin.math.hypot(it[1], it[2]) }

    @Test
    fun zeroAccentSaturationGivesGreyAccents() {
        val s = ThemeResolver.resolveScheme(ThemeSource(seed, style = PaletteStyle.VIBRANT), false, ThemeAdjustments(accentSaturation = 0))
        for (role in listOf(PaletteRole.ACCENT1, PaletteRole.ACCENT2, PaletteRole.ACCENT3)) {
            for (shade in ShadeScale.SHADES) assertTrue(labChroma(s.shade(role, shade)) < 1.5, "$role $shade")
        }
        assertTrue(labChroma(s.roles.getValue("primary")) < 1.5)
        assertTrue(labChroma(s.roles.getValue("primary_container")) < 1.5)
    }

    @Test
    fun monochromeIgnoresSaturationAndVividAddsChroma() {
        val src = ThemeSource(seed, style = PaletteStyle.MONOCHROME)
        assertEquals(
            ThemeResolver.resolveScheme(src, true, ThemeAdjustments()).shades,
            ThemeResolver.resolveScheme(src, true, ThemeAdjustments(accentSaturation = 200, backgroundSaturation = 0)).shades,
        )
        val normal = ThemeResolver.resolveScheme(ThemeSource(seed), false, ThemeAdjustments())
        val vivid = ThemeResolver.resolveScheme(ThemeSource(seed), false, ThemeAdjustments(accurateShades = false))
        assertTrue(Hct.fromInt(vivid.shade(PaletteRole.ACCENT1, 400)).chroma > Hct.fromInt(normal.shade(PaletteRole.ACCENT1, 400)).chroma)
        assertTrue(ColorMath.lstar(vivid.shade(PaletteRole.ACCENT1, 300)) < ColorMath.lstar(normal.shade(PaletteRole.ACCENT1, 300)) - 4)
    }

    @Test
    fun backgroundLightnessDarkensAndBrightensNeutrals() {
        val src = ThemeSource(seed)
        val l = { level: Int ->
            ColorMath.lstar(ThemeResolver.resolveScheme(src, true, ThemeAdjustments(backgroundLightness = level)).shade(PaletteRole.NEUTRAL1, 900))
        }
        assertTrue(l(0) < l(100) - 3, "darkest ${l(0)} vs ${l(100)}")
        assertTrue(l(200) > l(100) + 3, "brightest ${l(200)} vs ${l(100)}")
        val dark = ThemeResolver.resolveScheme(src, true, ThemeAdjustments(backgroundLightness = 0))
        assertMonotonic(dark, "lightness 0")
    }

    @Test
    fun pitchBlack() {
        val src = ThemeSource(seed)
        val surfaces = ThemeResolver.resolveScheme(src, true, ThemeAdjustments(pitchBlack = PitchBlack.SURFACES))
        assertEquals(black, surfaces.shade(PaletteRole.NEUTRAL1, 900))
        assertEquals(black, surfaces.roles["surface"])
        assertEquals(black, surfaces.roles["background"])
        val normalHigh = ThemeResolver.resolveScheme(src, true, ThemeAdjustments()).roles["surface_container_high"]
        assertEquals(normalHigh, surfaces.roles["surface_container_high"])
        val all = ThemeResolver.resolveScheme(src, true, ThemeAdjustments(pitchBlack = PitchBlack.SURFACES_AND_CONTAINERS))
        assertTrue(ColorMath.lstar(all.roles.getValue("surface_container_high")) < ColorMath.lstar(normalHigh!!))
        assertEquals(0.0, all.surfaceTone, 0.5)
        // 2025 spec: surface tone 4 is the base.
        val s25 = ThemeResolver.resolveScheme(src.copy(spec = ColorSpecVersion.SPEC_2025), true, ThemeAdjustments(pitchBlack = PitchBlack.SURFACES))
        assertEquals(black, s25.roles["surface"])
        // light mode unaffected
        val light = ThemeResolver.resolveScheme(src, false, ThemeAdjustments(pitchBlack = PitchBlack.SURFACES))
        assertEquals(ThemeResolver.resolveScheme(src, false, ThemeAdjustments()), light)
        assertEquals(0.0, PitchBlackRule.tone(5.0, 6.0))
        assertEquals((12.0 - 6.0) * 1.15, PitchBlackRule.tone(12.0, 6.0), 1e-9)
    }

    @Test
    fun tintlessText() {
        for (dark in listOf(true, false)) {
            val s = ThemeResolver.resolveScheme(ThemeSource(seed), dark, ThemeAdjustments(tintedText = false))
            assertEquals(if (dark) white else black, s.roles["on_surface"])
            assertEquals(if (dark) white else black, s.roles["on_background"])
            assertTrue(s.roles.getValue("on_primary") == white || s.roles.getValue("on_primary") == black)
        }
        assertEquals(0x80FFFFFF.toInt(), TintlessTextRule.neutralize(0x80EEEEEE.toInt()))
        assertEquals(0x40000000, TintlessTextRule.neutralize(0x40202020))
    }

    @Test
    fun roleOverridesWin() {
        val pin = 0xFF123456.toInt()
        val s = ThemeResolver.resolveScheme(ThemeSource(seed), true, ThemeAdjustments(roleOverrides = mapOf("primary" to pin)))
        assertEquals(pin, s.roles["primary"])
    }

    @Test
    fun shadeOverrideThroughResolver() {
        val pin = 0xFF2A6FD0.toInt()
        val s = ThemeResolver.resolveScheme(ThemeSource(seed), false, ThemeAdjustments(shadeOverrides = mapOf("accent1_500" to pin)))
        assertEquals(pin, s.shade(PaletteRole.ACCENT1, 500))
        assertMonotonic(s, "override")
    }
}
