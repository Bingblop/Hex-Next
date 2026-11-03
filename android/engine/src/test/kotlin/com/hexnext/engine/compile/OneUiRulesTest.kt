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
            val appOnly = setOf(ColorGroup.KEYBOARD_BG, ColorGroup.KEYBOARD_KEY, ColorGroup.DIALER, ColorGroup.LOCK_SCREEN) +
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
                "notification_bg", "text_primary", "text_secondary",
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
}
