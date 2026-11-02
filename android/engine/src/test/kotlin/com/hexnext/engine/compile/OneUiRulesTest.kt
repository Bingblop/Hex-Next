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
}
