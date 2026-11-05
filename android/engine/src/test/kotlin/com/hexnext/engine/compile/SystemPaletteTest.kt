package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.table
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Apps outside One UI follow the Hex Next theme through the system Material You palette. */
class SystemPaletteTest {
    private val names = listOf(
        "system_accent1_600", "system_neutral1_900",
        "system_primary_light", "system_primary_dark",
        "system_surface_container_high_dark", "system_on_surface_light",
        "system_primary_fixed", "system_palette_key_color_primary_light",
        "system_control_activated_light", "materialColorPrimary",
        "config_unrelated_colour",
    )
    private val source = ThemeSource(0xFF3E91FF.toInt())

    private fun compile(systemPalette: Boolean = true): Map<Pair<String, String>, Int> {
        val spec = ThemeSpec.default().copy(source = source, systemPalette = systemPalette)
        val device = DeviceSnapshot(Fixtures.profile9, mapOf("android" to TargetSnapshot("android", installed = true, table = table("android", names))), Fixtures.reference)
        val target = ThemeCompiler.compile(spec, device, InMemoryPackResolver(emptyList()), OneUiRules.load(9)) { 1L }.target("android")
        return target?.colors.orEmpty().associate { (it.resourceName.substringAfter(':') to it.configuration) to it.argb }
    }

    @Test
    fun `android carries the shades and Material 3 roles from the Hex Next theme`() {
        val theme = ThemeResolver.resolve(source)
        val colors = compile()
        assertEquals(theme.light.shades["system_accent1_600"], colors["color/system_accent1_600" to ""])
        assertEquals(theme.dark.shades["system_accent1_600"], colors["color/system_accent1_600" to "night"])
        assertEquals(theme.light.roles["primary"], colors["color/system_primary_light" to ""])
        assertEquals(theme.dark.roles["primary"], colors["color/system_primary_dark" to ""])
        assertEquals(theme.dark.roles["surface_container_high"], colors["color/system_surface_container_high_dark" to ""])
        assertEquals(theme.light.roles["primary_fixed"], colors["color/system_primary_fixed" to ""])
        assertEquals(theme.light.roles["primary_palette_key_color"], colors["color/system_palette_key_color_primary_light" to ""])
        assertEquals(theme.light.shades["system_accent1_100"], colors["color/system_control_activated_light" to ""])
        assertEquals(theme.dark.roles["primary"], colors["color/materialColorPrimary" to "night"])
        assertTrue(("color/config_unrelated_colour" to "") !in colors)
    }

    @Test
    fun `the switch leaves the system palette alone`() {
        val colors = compile(systemPalette = false)
        assertTrue(colors.keys.none { it.first.startsWith("color/system_") })
    }
}
