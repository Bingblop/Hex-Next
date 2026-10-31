package com.hexnext.themer.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetPaletteTest {

    private val blue = ThemeSpec(seed = 0xFF3E7BFA.toInt(), style = PaletteStyle.TONAL_SPOT, pureBlack = false)

    @Test
    fun shadesMapToSystemUiTones() {
        val tones = MonetPalette.SHADES.map(MonetPalette::toneForShade)
        assertEquals(listOf(100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0), tones)
    }

    @Test
    fun everyFrameworkShadeIsCovered() {
        val entries = MonetPalette.generate(blue).shadeEntries()
        assertEquals(65, entries.size)
        assertEquals(entries.size, entries.map { it.name }.toSet().size)
        assertTrue(entries.any { it.resourceName == "android:color/system_accent1_500" })
        assertTrue(entries.any { it.resourceName == "android:color/system_neutral2_1000" })
    }

    @Test
    fun shadeEndsAreWhiteAndBlack() {
        val accent = MonetPalette.generate(blue).shades.getValue(ToneGroup.ACCENT1)
        assertEquals(0xFFFFFFFF.toInt(), accent.first())
        assertEquals(0xFF000000.toInt(), accent.last())
    }

    @Test
    fun pureBlackDarkensNeutralBackgrounds() {
        val palette = MonetPalette.generate(blue.copy(pureBlack = true))
        val neutral = palette.shades.getValue(ToneGroup.NEUTRAL1)
        assertEquals(0xFF000000.toInt(), neutral[MonetPalette.SHADES.indexOf(900)])
        val background = palette.roleEntries().first { it.name == "system_background_dark" }
        assertEquals(0xFF000000.toInt(), background.argb)
    }

    @Test
    fun roleEntriesHaveLightAndDarkVariants() {
        val names = MonetPalette.generate(blue).roleEntries().map { it.name }.toSet()
        assertTrue("system_primary_light" in names)
        assertTrue("system_primary_dark" in names)
        assertTrue("system_surface_container_highest_dark" in names)
        assertTrue("system_primary_fixed" in names)
    }

    @Test
    fun everyStyleGenerates() {
        PaletteStyle.entries.forEach { style ->
            assertEquals(65, MonetPalette.generate(blue.copy(style = style)).shadeEntries().size)
        }
    }

    @Test
    fun hexParsing() {
        assertEquals(0xFF3E7BFA.toInt(), parseHexColor("#3e7bfa"))
        assertEquals(0xFF3E7BFA.toInt(), parseHexColor("803E7BFA"))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("zzzzzz"))
        assertEquals("#3E7BFA", 0xFF3E7BFA.toInt().toHex())
    }

    @Test
    fun oneUiVersionFormatting() {
        assertEquals("One UI 8.0", formatOneUi("80000"))
        assertEquals("One UI 6.1", formatOneUi("60100"))
        assertNull(formatOneUi(""))
    }
}
