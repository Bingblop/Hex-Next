package com.hexnext.themer.ui.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationTest {
    @Test
    fun `four tabs in order`() {
        assertEquals(listOf("Theme", "Packs", "Library", "Settings"), Destination.entries.map { it.label })
    }

    @Test
    fun `every colour and icon sub-tab has one Theme section`() {
        for (tab in ColoursTab.entries) assertEquals(tab, ThemeSection.of(tab).colours)
        for (tab in IconsTab.entries) assertEquals(tab, ThemeSection.of(tab).icons)
        assertEquals(1 + ColoursTab.entries.size + IconsTab.entries.size, ThemeSection.entries.size)
    }

    @Test
    fun `lab page names of the five-tab shell still open the matching page`() {
        assertEquals(Destination.THEME to ThemeSection.OVERVIEW, destinationFor("STUDIO").let { it.first to it.second })
        assertEquals(Destination.PACKS, destinationFor("SKINS").first)
        assertEquals(Destination.THEME to ThemeSection.COLOURS, destinationFor("COLOURS").let { it.first to it.second })
        assertEquals(Destination.THEME to ThemeSection.ICONS, destinationFor("ICONS").let { it.first to it.second })
        assertEquals(Destination.SETTINGS to SettingsSection.APPS, destinationFor("SYSTEM").let { it.first to it.third })
    }

    @Test
    fun `new names and sections, unknown falls back to Theme`() {
        assertEquals(Destination.LIBRARY, destinationFor("LIBRARY").first)
        assertEquals(Destination.THEME to ThemeSection.FONTS, destinationFor("THEME:FONTS").let { it.first to it.second })
        assertEquals(Destination.SETTINGS to SettingsSection.TOOLS, destinationFor("SETTINGS:TOOLS").let { it.first to it.third })
        assertEquals(Destination.THEME to ThemeSection.OVERVIEW, destinationFor(null).let { it.first to it.second })
        assertEquals(Destination.THEME, destinationFor("NOPE").first)
    }
}
