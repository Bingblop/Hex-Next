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
            assertTrue(rules.quickSettingsColoursOnly)
            assertEquals(expectedTargets, rules.activeTargets)
        }
        assertFailsWith<IllegalArgumentException> { OneUiRules.load(6) }
    }

    @Test
    fun `profiles pick their rules and fall back to the newest`() {
        fun profile(major: Int?) = OneUiProfile(major?.let { OneUiVersion(it, 0, 35, 0) }, "")
        assertEquals(8, OneUiRules.forProfile(profile(8)).major)
        assertEquals(9, OneUiRules.forProfile(profile(12)).major)
        assertEquals(9, OneUiRules.forProfile(profile(null)).major)
    }

    @Test
    fun `parse renames and deprecation`() {
        val rules = OneUiRules.parse(
            """{"targets":["a","b"],"deprecated":["b"],"quickSettingsColoursOnly":false,"renamed":{"color/old":"color/new"},"x":1}""",
        )
        assertEquals(listOf("a"), rules.activeTargets)
        assertFalse(rules.quickSettingsColoursOnly)
        assertEquals("color/new", rules.rename("color/old"))
        assertEquals("color/other", rules.rename("color/other"))
        assertFailsWith<IllegalArgumentException> { OneUiRules.parse("[1]") }
    }
}
