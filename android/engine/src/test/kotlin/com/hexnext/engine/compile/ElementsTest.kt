package com.hexnext.engine.compile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElementsTest {
    private val catalogCategories: Set<String> by lazy {
        val file = listOf("../../docs/plugin-catalog/catalog.json", "docs/plugin-catalog/catalog.json")
            .map(::File).first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("resource_union").jsonObject.keys
    }

    @Test
    fun `every catalog category maps explicitly to an element`() {
        assertTrue(catalogCategories.size > 500)
        val unmapped = catalogCategories.filter { Elements.resolveCategory(it) == null }
        assertEquals(emptyList(), unmapped)
    }

    @Test
    fun `listed plugin categories map to the expected elements`() {
        val expected = mapOf(
            "settingsicon" to "settings_icons", "settingsicon_alt-1sett" to "settings_icons",
            "myfilesicon" to "my_files_icons", "misc-myfiles" to "my_files_icons", "misc_alt-myfiles" to "my_files_icons",
            "navbar" to "navigation_bar", "navbar_land#p" to "navigation_bar", "control" to "controls",
            "dialer" to "dialer", "dialer_night" to "dialer", "dialpad" to "dialpad", "dialpad_nightHp" to "dialpad",
            "keyboard" to "keyboard", "keyboard_night" to "keyboard", "home" to "launcher_home",
            "badge" to "launcher_badges", "lockscreenHr" to "lock_screen", "search" to "launcher_home",
            "background" to "backgrounds", "box" to "dialogs", "buttons" to "dialogs", "indicator" to "status_bar",
            "drawable-xxhdpi-v26" to "system_drawables", "drawable-night-xxhdpi-v26" to "system_drawables",
            "misc-notibgHr" to "notifications", "lockscreen-volpanelHr" to "volume_panel", "misc-qsmb1" to "quick_settings_panel",
            "qs" to "quick_settings_tiles", "misc-chat" to "misc", "misc-note-notibgHr" to "notifications", "ui_messages-bubnw" to "misc",
        )
        for ((category, element) in expected) assertEquals(element, Elements.resolveCategory(category)?.id, category)
    }

    @Test
    fun `unknown categories fall back to misc`() {
        assertNull(Elements.resolveCategory("brand_new_thing"))
        assertEquals(Elements.MISC, Elements.elementForCategory("brand_new_thing").id)
    }

    @Test
    fun `required elements exist with their targets`() {
        val required = listOf(
            "settings_icons", "settings_dashboard", "navigation_bar", "status_bar", "quick_settings_tiles",
            "quick_settings_panel", "notifications", "volume_panel", "power_menu", "lock_screen", "keyboard", "dialer",
            "dialpad", "in_call", "contacts", "my_files_icons", "my_files", "gallery", "clock", "calculator", "calendar",
            "device_care", "launcher_home", "launcher_badges", "aod", "smart_capture", "backgrounds", "dialogs", "misc",
        )
        for (id in required) assertNotNull(Elements.byId(id), id)
        assertEquals(Elements.ALL.size, Elements.ALL.map { it.id }.distinct().size)
        assertEquals(listOf("com.android.systemui", "android"), Elements.byId("navigation_bar")!!.targets)
        assertEquals(listOf("com.samsung.android.honeyboard"), Elements.byId("keyboard")!!.targets)
        assertEquals(listOf("com.samsung.android.incallui"), Elements.byId("in_call")!!.targets)
        assertEquals(QuickSettingsPolicy.COLOURS_ONLY, Elements.byId("quick_settings_tiles")!!.quickSettingsPolicy)
        assertEquals(QuickSettingsPolicy.NONE, Elements.byId("quick_settings_panel")!!.quickSettingsPolicy)
        assertTrue(Elements.misc.anyTarget)
    }

    @Test
    fun `every rule target has at least one targeted element`() {
        for (pkg in OneUiRules.load(9).activeTargets) {
            assertTrue(Elements.forTarget(pkg).any { !it.anyTarget }, pkg)
        }
        assertTrue(Elements.forTarget("com.example").all { it.anyTarget })
    }

    @Test
    fun `category names parse suffixes and tokens`() {
        val a = CategoryName.parse("dialpad_nightHp")
        assertEquals("dialpad", a.base)
        assertTrue(a.night)
        assertEquals(listOf("Hp"), a.tokens)

        val b = CategoryName.parse("drawable-night-xxhdpi-v26-note")
        assertEquals("drawable", b.base)
        assertTrue(b.night)
        assertEquals(listOf("xxhdpi", "v26", "note"), b.tokens)
        assertEquals(listOf("note"), b.optionTokens)

        val c = CategoryName.parse("settingsicon_alt-1sett")
        assertFalse(c.night)
        assertEquals(listOf("alt", "1sett"), c.optionTokens)
        assertEquals(listOf("settingsicon-alt", "settingsicon-1sett", "settingsicon"), c.lookupKeys)

        val d = CategoryName.parse("navbar_land#p")
        assertEquals(listOf("land", "p"), d.tokens)
        assertEquals(listOf("p"), d.optionTokens)

        assertEquals(CategoryName.parse("misc-myfiles"), CategoryName.parse("misc/myfiles").copy(raw = "misc-myfiles"))
        assertEquals(emptyList(), CategoryName.parse("drawable-xxhdpi-v26").optionTokens)
    }
}
