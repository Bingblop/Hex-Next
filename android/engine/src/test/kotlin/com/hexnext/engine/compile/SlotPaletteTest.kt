package com.hexnext.engine.compile

import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SlotPaletteTest {
    private val theme = ThemeResolver.resolve(ThemeSource(0xFF3E91FF.toInt()))

    @Test
    fun `mirror maps tones around 50`() {
        assertEquals(100, SlotRole.mirror(900))
        assertEquals(500, SlotRole.mirror(500))
        assertEquals(600, SlotRole.mirror(400))
        assertEquals(900, SlotRole.mirror(100))
        assertEquals(200, SlotRole.mirror(800))
        assertEquals(900, SlotRole.mirror(50))
    }

    @Test
    fun `derived palette follows the documented roles`() {
        val p = SlotPalette.derive(theme)
        assertEquals(60, p.size)
        assertEquals(theme.dark.shade(PaletteRole.NEUTRAL1, 900), p["xx0"])
        assertEquals(theme.dark.shade(PaletteRole.ACCENT1, 500), p["xx1"])
        assertEquals(theme.dark.shade(PaletteRole.ACCENT2, 400), p["xx2"])
        assertEquals(theme.dark.shade(PaletteRole.NEUTRAL1, 100), p["xx3"])
        assertEquals(theme.dark.shade(PaletteRole.NEUTRAL2, 300), p["xx4"])
        assertEquals(theme.dark.shade(PaletteRole.ACCENT3, 400), p["xx5"])
        assertEquals(theme.dark.shade(PaletteRole.NEUTRAL1, 800), p["xx6"])
        assertEquals(p["xx6"], p["xx7"])
        assertEquals(theme.dark.shade(PaletteRole.NEUTRAL1, 50), p["xx8"])
        assertEquals(theme.dark.shade(PaletteRole.ACCENT1, 300), p["xx9"])
        assertEquals(theme.light.shade(PaletteRole.NEUTRAL1, 100), p["lxx0"])
        assertEquals(theme.light.shade(PaletteRole.ACCENT1, 500), p["lxx1"])
        assertEquals(p["xx1"], p["xy1"])
        assertEquals(p["lxx3"], p["lxz3"])
    }
}
