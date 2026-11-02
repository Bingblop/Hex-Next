package com.hexnext.engine.compile

import com.hexnext.engine.device.OverlayDump
import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ShadeScale
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccentRecolorTest {
    private val pkg = "com.android.settings"
    private val dump: OverlayDump = OverlayDump.parse(
        checkNotNull(javaClass.getResourceAsStream("/overlay_dump_settings_oneui9.txt")) { "fixture missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) },
    )!!
    private val seed = 0xFFE53935.toInt()
    private val theme = ThemeResolver.resolve(ThemeSource(seed))

    @Test
    fun `every colour of the mapping is recoloured in both configurations`() {
        val colors = dump.entries.filter { it.type == "color" && it.defaultColor != null }.map { it.typeAndName }.distinct()
        assertTrue(colors.isNotEmpty())
        val out = AccentRecolor.recolor(pkg, dump.entries, Fixtures.reference, seed, includeNight = true)
        assertEquals(colors.size * 2, out.size)
        assertEquals(colors.map { "$pkg:$it" }.toSet(), out.map { it.resourceName }.toSet())
        assertEquals(setOf("", "night"), out.map { it.configuration }.toSet())
        val shades = (theme.light.shades.values + theme.dark.shades.values).map { it and 0xFFFFFF }.toSet()
        assertTrue(out.all { (it.argb and 0xFFFFFF) in shades }, "every value is a shade of the seed palette")
        assertTrue(out.none { it.resourceName.contains("dimen/") })
    }

    @Test
    fun `a reference shade maps to the same role and shade of the seed palette`() {
        val key = ShadeScale.key(PaletteRole.ACCENT1, 600)
        val mapped = AccentRecolor.shadeFor(Fixtures.ref(key) and 0x80FFFFFF.toInt(), Fixtures.reference, theme, isDark = false, seed)
        assertEquals(theme.light.shades.getValue(key) and 0xFFFFFF, mapped and 0xFFFFFF)
        assertEquals(0x80, mapped ushr 24)
    }

    @Test
    fun `forced resources take the seed and day only without night`() {
        val out = AccentRecolor.recolor(pkg, dump.entries, Fixtures.reference, seed, includeNight = false, forceSeed = listOf("color/sec_widget_round_and_bgcolor"))
        assertTrue(out.all { it.configuration.isEmpty() })
        assertEquals(seed, out.single { it.resourceName == "$pkg:color/sec_widget_round_and_bgcolor" }.argb)
    }
}
