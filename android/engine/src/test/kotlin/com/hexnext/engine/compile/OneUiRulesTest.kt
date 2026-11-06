package com.hexnext.engine.compile

import com.hexnext.engine.device.OneUiProfile
import com.hexnext.engine.device.OneUiVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneUiRulesTest {
    private val expectedTargets = listOf(
        "android", "com.android.systemui", "com.android.settings", "com.sec.android.app.launcher",
        "com.samsung.android.honeyboard", "com.samsung.android.dialer", "com.samsung.android.incallui",
        "com.samsung.android.app.contacts", "com.sec.android.app.myfiles", "com.sec.android.gallery3d",
        "com.sec.android.app.clockpackage", "com.sec.android.app.popupcalculator", "com.samsung.android.calendar",
        "com.samsung.android.lool", "com.samsung.android.app.aodservice", "com.samsung.android.app.smartcapture",
        "com.samsung.android.app.notes", "com.samsung.android.app.reminder",
        "com.sec.android.app.sbrowser", "com.samsung.android.email.provider", "com.sec.android.app.music", "com.sec.android.app.voicenote",
        "com.sec.android.app.shealth",
    )

    @Test
    fun `bundled rule files load`() {
        for (major in 7..9) {
            val rules = OneUiRules.load(major)
            assertEquals(major, rules.major)
            assertEquals(expectedTargets, rules.targets)
            assertTrue("com.samsung.android.messaging" in rules.deprecated)
            assertTrue(rules.deprecated.none { it in rules.activeTargets })
            assertEquals(expectedTargets, rules.activeTargets)
            assertEquals(27, rules.dashboardRows.size)
            assertEquals(rules.dashboardRows.distinct(), rules.dashboardRows)
            assertTrue("display" in rules.dashboardRows && "galaxy_ai" in rules.dashboardRows && "tips" in rules.dashboardRows)
        }
        assertFailsWith<IllegalArgumentException> { OneUiRules.load(6) }
    }

    @Test
    fun `One UI 9 copies the pack dialog and popup art onto its real dialog drawables`() {
        val copies = OneUiRules.load(9).drawableCopies
        assertTrue("drawable/sesl_dialog_background" in copies.getValue("drawable/dialog_"))
        assertTrue("drawable/dialog_background_material" in copies.getValue("drawable/dialog_"))
        assertTrue("drawable/sesl_menu_popup_background_dark" in copies.getValue("drawable/popup_"))
    }

    @Test
    fun `One UI 9 rewires the legacy names other packs use where Willemite had none`() {
        // Pack audit of 2026-11-05 (docs/device/oneui9-pack-rewiring-audit-20261105.md): names that
        // 8-22 of the 44 packs ship under legacy or slot-template names, which no One UI 9 app has.
        val rules = OneUiRules.load(9)
        val none = { _: String -> false }
        // Status bar Wi-Fi icons, which the Quick panel policy lets through as sec_ic_wifi_signal_*.
        for (level in 0..4) {
            assertEquals("drawable/sec_ic_wifi_signal_$level", rules.renameFor("drawable/sec_quickpannel_wifi_signal_$level", none))
            assertEquals("drawable/sec_ic_wifi_signal_lock_$level", rules.renameFor("drawable/sec_quickpannel_wifi_signal_lock_$level", none))
            assertTrue(rules.quickSettings.allowsOutside(rules.renameFor("drawable/sec_quickpannel_wifi_signal_$level", none)))
        }
        // The legacy `buttons` layer: action bar back and overflow glyphs per theme.
        assertEquals("drawable/sesl_ic_menu_overflow_dark", rules.renameFor("drawable/menu_dark", none))
        assertEquals("drawable/sesl_ic_menu_overflow_light", rules.renameFor("drawable/menu_light", none))
        assertEquals("drawable/sesl_ic_ab_back_dark", rules.renameFor("drawable/back_dark", none))
        assertEquals("drawable/sesl_ic_ab_back_light", rules.renameFor("drawable/back_light", none))
        // Dialer Wi-Fi calling keypad icon, now split per carrier.
        assertEquals("drawable/phone_keypad_wificall_ic_comm", rules.renameFor("drawable/phone_keypad_wificall", none))
        assertTrue("drawable/phone_keypad_wificall_ic_vzw" in rules.drawableCopies.getValue("drawable/phone_keypad_wificall"))
        // Slot-template names (trailing underscore) like Willemite's dialog_ and popup_.
        assertEquals("drawable/sesl_index_bar_bg", rules.renameFor("drawable/sesl_index_bar_bg_", none))
        assertTrue("drawable/sem_toast_frame_mtrl" in rules.drawableCopies.getValue("drawable/sem_toast_frame_mtrl_"))
        assertTrue("drawable/tw_spinner_mtrl_am_alpha" in rules.drawableCopies.getValue("drawable/tw_spinner_mtrl_am_alpha_"))
        // Un-suffixed sesl names that One UI 9 splits into dark and light.
        for (name in listOf("sesl_ic_ab_back", "sesl_ic_menu_overflow", "sesl_tablayout_subtab_indicator_background")) {
            val copies = rules.drawableCopies.getValue("drawable/$name")
            assertTrue("drawable/${name}_dark" in copies, name)
            assertTrue("drawable/${name}_light" in copies, name)
        }
        assertEquals(3, rules.drawableCopies.getValue("drawable/sec_ic_settings_coverscreen").size)
        // A target that still has the legacy name keeps it: the fallback never overrides exact artwork.
        assertEquals("drawable/menu_dark", rules.renameFor("drawable/menu_dark") { it == "drawable/menu_dark" })
    }

    @Test
    fun `One UI 9 builds power menu and status bar icons outside the Quick panel policy`() {
        val qs = OneUiRules.load(9).quickSettings
        assertTrue(qs.allowsOutside("drawable/tw_ic_do_poweroff"))
        assertTrue(qs.allowsOutside("drawable/sec_ic_wifi_signal_3"))
        assertTrue(qs.allowsOutside("drawable/stat_sys_data_bluetooth"))
        assertFalse(qs.allowsOutside("drawable/sec_quick_panel_tile_bg"))
    }

    @Test
    fun `every bundled version has a colours-only Quick Settings policy for its own layout`() {
        for (major in 7..9) {
            val qs = OneUiRules.load(major).quickSettings
            assertEquals("oneui$major", qs.layout, "layout of One UI $major")
            assertEquals("One UI $major", qs.layoutLabel)
            assertFalse(qs.drawablesAllowed, "One UI $major must not build System UI drawables")
            assertTrue(qs.coloursOnly)
            assertTrue(qs.filterColours)
            assertEquals(qs.colours.distinct(), qs.colours)
            // An empty allowlist means the pattern filter; a non-empty one must be exact names.
            if (qs.colours.isEmpty()) {
                assertTrue(qs.allowsColour("qs_tile_bg"))
                assertFalse(qs.allowsColour("wallpaper_tint"))
            } else {
                assertTrue(qs.colours.all { it.isNotBlank() && ' ' !in it })
            }
        }
    }

    @Test
    fun `profiles pick their rules and fall back to the newest`() {
        fun profile(major: Int?) = OneUiProfile(major?.let { OneUiVersion(it, 0, 35, 0) }, "")
        assertEquals(8, OneUiRules.forProfile(profile(8)).major)
        assertEquals(9, OneUiRules.forProfile(profile(12)).major)
        assertEquals(9, OneUiRules.forProfile(profile(null)).major)
        assertEquals("oneui9", OneUiRules.forProfile(profile(null)).quickSettings.layout)
    }

    @Test
    fun `parse renames and deprecation`() {
        val rules = OneUiRules.parse(
            """{"major":8,"targets":["a","b"],"deprecated":["b"],"quickSettingsColoursOnly":false,"renamed":{"color/old":"color/new"},"x":1}""",
        )
        assertEquals(listOf("a"), rules.activeTargets)
        assertEquals("color/new", rules.rename("color/old"))
        assertEquals("color/other", rules.rename("color/other"))
        assertEquals(emptyList(), rules.dashboardRows)
        // The retired boolean is ignored; the policy defaults to colours only for the file's major.
        assertEquals(QuickSettingsRules.default(8), rules.quickSettings)
        assertEquals("oneui8", rules.quickSettings.layout)
        assertFalse(rules.quickSettings.drawablesAllowed)
        assertFailsWith<IllegalArgumentException> { OneUiRules.parse("[1]") }
    }

    @Test
    fun `parse quick settings block`() {
        val rules = OneUiRules.parse(
            """{"major":9,"quickSettings":{"layout":"oneui9","colours":["qs_tile_bg","color/quick_panel_bg"],"drawablesAllowed":true}}""",
        )
        val qs = rules.quickSettings
        assertEquals("oneui9", qs.layout)
        assertTrue(qs.drawablesAllowed)
        assertFalse(qs.coloursOnly)
        assertTrue(qs.allowsColour("qs_tile_bg"))
        assertTrue(qs.allowsColour("color/qs_tile_bg"))
        assertTrue(qs.allowsColour("quick_panel_bg"))
        // Pattern matches are not enough once an allowlist is given.
        assertFalse(qs.allowsColour("qs_other_tile"))
        assertFalse(qs.allowsColour("wallpaper_tint"))
    }

    @Test
    fun `quick settings rules helpers`() {
        val default = QuickSettingsRules()
        assertEquals("", default.layout)
        assertEquals("One UI", default.layoutLabel)
        assertTrue(default.coloursOnly)
        assertTrue(default.allowsColour("notification_bg"))
        assertFalse(default.allowsColour("accent_device_default"))
        assertEquals("oneui7", default.withDefaultLayout(7).layout)
        assertEquals("oneui7", QuickSettingsRules(layout = "oneui7").withDefaultLayout(9).layout)
        assertEquals("custom", QuickSettingsRules(layout = "custom").layoutLabel)
        assertEquals("oneui", QuickSettingsRules(layout = "oneui").layoutLabel)
        assertEquals("oneui12", QuickSettingsRules.layoutFor(12))
        val unfiltered = QuickSettingsRules(filterColours = false)
        assertTrue(unfiltered.allowsColour("anything_at_all"))
    }

    @Test
    fun `every bundled version has valid colour group rules`() {
        for (major in 7..9) {
            val rules = OneUiRules.load(major)
            // Every group except the app-specific ones and the ink groups drawn on another group
            // (reached by device-verified exact names only) has generic name rules.
            val appOnly = setOf(ColorGroup.KEYBOARD_BG, ColorGroup.KEYBOARD_KEY, ColorGroup.KEYBOARD_FUNCTION_KEY, ColorGroup.KEYBOARD_LABEL, ColorGroup.DIALER, ColorGroup.LOCK_SCREEN, ColorGroup.CALCULATOR_KEYS, ColorGroup.GALLERY_MENU, ColorGroup.VOICE_RECORDER_BUTTON) +
                ColorGroup.ALL.filter { it.contrastOn != null }
            assertEquals(ColorGroup.ALL.filterNot { it in appOnly }.map { it.id }.toSet(), rules.colorGroups.keys, "One UI $major")
            val patterns = rules.colorGroups.values.flatten() + rules.colorGroupFamilies.values.flatMap { it.groups.values.flatten() }
            assertTrue(patterns.isNotEmpty())
            for (p in patterns) Regex(p) // throws on an invalid pattern
            for (family in rules.colorGroupFamilies.values) {
                for (id in family.groups.keys + family.remap.keys + family.remap.values) assertTrue(ColorGroup.byId(id) != null, "unknown group $id")
            }
            for ((pkg, family) in rules.colorGroupPackages) {
                assertTrue(family in rules.colorGroupFamilies, "$pkg -> $family")
                assertTrue(pkg in rules.targets, pkg)
            }
            // Inactive tiles are tried before active ones ("inactive" contains "active").
            val order = rules.colorGroups.keys.toList()
            assertTrue(order.indexOf("qs_tile_inactive") < order.indexOf("qs_tile_active"))
        }
        // The versions share one rule set until a release renames its colours.
        assertEquals(OneUiRules.load(9).colorGroups, OneUiRules.load(8).colorGroups)
        // Exact names are device-verified per release (One UI 9 only so far); the patterns are shared.
        fun patterns(major: Int) = OneUiRules.load(major).colorGroupFamilies.mapValues { it.value.copy(exact = emptyMap()) }
        assertEquals(patterns(9), patterns(7))
        assertEquals(patterns(9), patterns(8))
    }

    @Test
    fun `One UI 9 Quick Settings names are allowed and every exact name is a System UI rule`() {
        val rules = OneUiRules.load(9)
        val qs = rules.quickSettings
        val exact = rules.colorGroupFamilies.getValue("systemui").exact
        assertEquals(
            setOf(
                "qs_tile_active", "qs_tile_inactive", "qs_panel_bg", "qs_tile_icon_on", "qs_tile_icon_off", "qs_text",
                "notification_bg", "text_primary", "text_secondary", "accent",
            ),
            exact.keys,
        )
        // A name belongs to one group only.
        val all = exact.values.flatten()
        assertEquals(all.size, all.toSet().size)
        for ((id, names) in exact) {
            val group = ColorGroup.byId(id)
            assertTrue(group != null, "unknown group $id")
            for (name in names) {
                assertTrue(rules.allowsSystemUiColour(name), name)
                assertEquals(group, ColorGroupMatcher.classify("com.android.systemui", "color/$name", null, rules), name)
            }
        }
        // Verified names the old regex froze are now allowed; 7 and 8 stay on the regex alone.
        assertTrue(qs.extraColours.isNotEmpty())
        for (name in qs.extraColours) {
            assertFalse(ThemeCompiler.QS_COLOUR_NAMES.containsMatchIn(name), "$name is already allowed by the regex")
            assertTrue(qs.allowsColour(name), name)
            assertTrue(qs.allowsColour("color/$name"), name)
        }
        assertFalse(qs.allowsColour("some_unknown_systemui_color"))
        for (major in listOf(7, 8)) assertTrue(OneUiRules.load(major).quickSettings.extraColours.isEmpty(), "One UI $major")
        // With an explicit list, extra names still count.
        val listed = QuickSettingsRules(colours = listOf("qs_tile_bg"), extraColours = listOf("color/open_theme_noti_header_color"))
        assertTrue(listed.allowsColour("open_theme_noti_header_color"))
        assertTrue(listed.allowsColour("qs_tile_bg"))
        assertFalse(listed.allowsColour("qs_other"))
    }

    @Test
    fun `colour group rules parse with defaults`() {
        val rules = OneUiRules.parse(
            """{"major": 9, "colorGroups": {"switch": ["toggle"], "future_group": ["x"]},
               "colorGroupPackages": {"p": "f"}, "colorGroupFamilies": {"f": {"groups": {"dialer": ["call"]}, "remap": {"button": "dialer"}}}}""",
        )
        assertEquals(listOf("switch", "future_group"), rules.colorGroups.keys.toList())
        assertEquals(ColorGroupFamily(mapOf("dialer" to listOf("call")), mapOf("button" to "dialer")), rules.colorGroupFamilies["f"])
        // Unknown group ids are ignored; package rules come first and remap generic results.
        assertEquals(ColorGroup.SWITCH, ColorGroupMatcher.classify("q", "color/my_toggle", null, rules))
        assertEquals(ColorGroup.DIALER, ColorGroupMatcher.classify("p", "color/call_end", null, rules))
        assertEquals(ColorGroup.SWITCH, ColorGroupMatcher.classify("p", "color/my_toggle", null, rules))
        assertEquals(null, ColorGroupMatcher.classify("q", "color/x", null, rules))
        val empty = OneUiRules.parse("""{"major": 9}""")
        assertTrue(empty.colorGroups.isEmpty() && empty.colorGroupPackages.isEmpty() && empty.colorGroupFamilies.isEmpty())
    }

    @Test
    fun `One UI 9 keeps the go-to-top drawables Carnelian crashed Settings and My Files with`() {
        val r = OneUiRules.load(9)
        assertTrue(r.keepsDrawable("com.android.settings:drawable/sesl_go_to_top_light"))
        assertTrue(r.keepsDrawable("com.sec.android.app.myfiles:drawable/sesl_go_to_top_dark"))
        assertTrue(r.keepsDrawable("android:drawable/sem_go_to_top_light"))
        assertTrue(r.keepsDrawable("drawable/sem_list_go_to_top_dark"))
        assertFalse(r.keepsDrawable("com.android.settings:drawable/sec_ic_settings_display"))
        assertFalse(r.keepsDrawable("com.android.settings:drawable/sesl_switch_thumb_on"))
    }
}
