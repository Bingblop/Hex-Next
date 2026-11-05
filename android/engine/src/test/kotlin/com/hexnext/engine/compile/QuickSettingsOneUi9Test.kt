package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.semwt
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.palette.ColorMath
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quick Settings on One UI 9, built from the System UI SemWT names the owner's SM-S948U1 reported
 * (apply logs of Nov 1 2026: `colours kept fixed` on `com.android.systemui`). The logs carry names
 * only, so the values below are stand-ins in Samsung's style: fixed colours that are not palette
 * shades (white active tiles, translucent off tiles and card, a yellow torch bar).
 */
class QuickSettingsOneUi9Test {
    private val sysui = "com.android.systemui"
    private val rules = OneUiRules.load(9).copy(renamed = emptyMap())
    private val red = 0xFFE53935.toInt()
    private val blue = 0xFF1E3AE5.toInt()

    private val spec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()))
    private val resolved = ThemeResolver.resolve(spec.source, spec.darkAdjustments, spec.lightAdjustments)

    /** name → (day, night) Samsung value. */
    private val samsung: Map<String, Pair<Int, Int>> = linkedMapOf(
        "qs_tile_round_background_on" to (0xFFFDFDFD.toInt() to 0xFFF6F6F6.toInt()),
        "qs_tile_round_background_dim" to (0x29FFFFFF to 0x24FFFFFF),
        "qs_tile_container_bg" to (0x66FFFFFF to 0x1FFFFFFF),
        "qs_tile_search_background_color" to (0xFF3A3A3D.toInt() to 0xFF2B2B2E.toInt()),
        "qs_flashlight_seekbar_progress_color" to (0xFFFFC94D.toInt() to 0xFFFFC94D.toInt()),
        "qs_flashlight_seekbar_tickmark_color" to (0x66FFFFFF to 0x66FFFFFF),
        "qs_flashlight_thumb_stroke_color" to (0x1A000000 to 0x1A000000),
        "qp_notification_primary_color" to (0xFF3E6FD8.toInt() to 0xFF8AB4F8.toInt()),
        "open_theme_noti_header_color" to (0xFF5F6368.toInt() to 0xFFBDC1C6.toInt()),
        "sec_wifi_icon_gray_color" to (0xFF8C8C8C.toInt() to 0xFF8C8C8C.toInt()),
        // Synthetic: a System UI name neither the regex nor the One UI 9 list allows.
        "hexnext_test_unlisted_color" to (0xFF123456.toInt() to 0xFF654321.toInt()),
    )

    private val exact = mapOf(
        "qs_tile_round_background_on" to ColorGroup.QS_TILE_ACTIVE,
        "qs_tile_round_background_dim" to ColorGroup.QS_TILE_INACTIVE,
        "qs_tile_container_bg" to ColorGroup.QS_PANEL_BG,
    )
    private val untouchable = listOf(
        "qs_tile_search_background_color", "qs_flashlight_seekbar_progress_color", "qs_flashlight_seekbar_tickmark_color",
        "qs_flashlight_thumb_stroke_color",
    )

    private val device = DeviceSnapshot(
        Fixtures.profile9,
        mapOf(
            sysui to TargetSnapshot(
                sysui,
                installed = true,
                table = Fixtures.table(sysui, samsung.keys.toList()),
                semwt = semwt(sysui, samsung.flatMap { (n, v) -> listOf(Triple(n, v.first, ""), Triple(n, v.second, "night")) }),
            ),
        ),
        Fixtures.reference,
    )

    private fun compile(s: ThemeSpec) = ThemeCompiler.compile(s, device, InMemoryPackResolver(emptyList()), rules) { 7L }

    private fun CompiledTarget.color(name: String, night: Boolean): PlannedColor? =
        colors.firstOrNull { it.resourceName == "$sysui:color/$name" && it.configuration == (if (night) "night" else "") }

    private fun samsungValue(name: String, night: Boolean) = samsung.getValue(name).let { if (night) it.second else it.first }

    private fun ThemeSpec.both(g: ColorGroup, argb: Int) = withGroupColor(g, false, argb).withGroupColor(g, true, argb)

    @Test
    fun `Auto writes each Quick panel group's Auto colour onto the real names, day and night`() {
        val result = compile(spec)
        val t = result.target(sysui)!!
        for ((name, group) in exact) {
            for (night in listOf(false, true)) {
                val expected = ColorMath.withAlpha(group.auto(resolved, night), ColorMath.alpha(samsungValue(name, night)))
                assertEquals(expected, t.color(name, night)?.argb, "$name night=$night")
                // The Studio preview shows the same colour (its alpha aside).
                assertEquals(spec.effectiveGroupColors(resolved, night).getValue(group) and 0xFFFFFF, expected and 0xFFFFFF, "$name preview")
            }
            // No longer frozen at Samsung's value.
            assertTrue(t.skipped.none { it.name.startsWith("$sysui:color/$name") }, name)
        }
        val event = result.events.single { it.target == sysui && it.message == "group colours" }
        assertEquals("6", event.data["count"])
        for (group in exact.values) assertEquals("2", event.data[group.id], group.id)
    }

    @Test
    fun `custom Quick panel colours reach the real names, keeping Samsung's alpha`() {
        val custom = spec.both(ColorGroup.QS_TILE_ACTIVE, red).both(ColorGroup.QS_TILE_INACTIVE, blue)
            .withGroupColor(ColorGroup.QS_PANEL_BG, false, blue).withGroupColor(ColorGroup.QS_PANEL_BG, true, red)
        val t = compile(custom).target(sysui)!!
        for (night in listOf(false, true)) {
            assertEquals(ColorMath.withAlpha(red, 0xFF), t.color("qs_tile_round_background_on", night)!!.argb)
            assertEquals(ColorMath.withAlpha(blue, ColorMath.alpha(samsungValue("qs_tile_round_background_dim", night))), t.color("qs_tile_round_background_dim", night)!!.argb)
            val panel = if (night) red else blue
            assertEquals(ColorMath.withAlpha(panel, ColorMath.alpha(samsungValue("qs_tile_container_bg", night))), t.color("qs_tile_container_bg", night)!!.argb)
        }
        // The panel opacity slider applies to the tile-grid card too: never more solid than Samsung's.
        val see = compile(custom.copy(qsPanelAlpha = 0x80)).target(sysui)!!
        assertEquals(blue and 0xFFFFFF, see.color("qs_tile_container_bg", false)!!.argb and 0xFFFFFF)
        assertTrue(ColorMath.alpha(see.color("qs_tile_container_bg", false)!!.argb) <= 0x66)
    }

    @Test
    fun `tile search and the torch popup keep Samsung's colours, whatever groups are set`() {
        var all = spec
        for (g in ColorGroup.ALL) all = all.both(g, red)
        val auto = compile(spec).target(sysui)!!
        val custom = compile(all).target(sysui)!!
        for (name in untouchable) {
            for (night in listOf(false, true)) {
                // Role inference may still follow the palette (as before); no group colour ever lands here.
                val value = assertNotNull(auto.color(name, night), name).argb
                assertEquals(value, custom.color(name, night)?.argb, "$name night=$night")
                assertTrue(value != red, name)
            }
        }
        // A fixed torch colour that is no palette shade stays exactly Samsung's.
        assertEquals(samsungValue("qs_flashlight_seekbar_progress_color", false), custom.color("qs_flashlight_seekbar_progress_color", false)?.argb)
    }

    @Test
    fun `verified names the old regex froze are allowed, unknown names keep the safe fallback`() {
        val t = compile(spec).target(sysui)!!
        for (name in listOf("open_theme_noti_header_color", "sec_wifi_icon_gray_color", "qp_notification_primary_color")) {
            assertTrue(t.skipped.none { it.name.startsWith("$sysui:color/$name") && it.reason == ThemeCompiler.REASON_QS_POLICY }, name)
        }
        // A name outside the policy is copied verbatim (SemWT is switched off on apply) and reported.
        for (night in listOf(false, true)) {
            assertEquals(samsungValue("hexnext_test_unlisted_color", night), t.color("hexnext_test_unlisted_color", night)?.argb)
        }
        assertEquals(
            ThemeCompiler.REASON_QS_POLICY,
            t.skipped.single { it.name == "$sysui:color/hexnext_test_unlisted_color" }.reason,
        )
        // Colour-only: never a System UI drawable.
        assertTrue(t.drawables.isEmpty())
    }

    @Test
    fun `apply verification probes the active tile in the current mode`() {
        val t = compile(spec).target(sysui)!!
        val day = assertNotNull(OverlayLookup.probe(t, night = false))
        assertEquals("$sysui:color/qs_tile_round_background_on" to "", day.resourceName to day.configuration)
        val night = assertNotNull(OverlayLookup.probe(t, night = true))
        assertEquals("$sysui:color/qs_tile_round_background_on" to "night", night.resourceName to night.configuration)
        // Mode unknown: every System UI colour has a night variant, so nothing is safe to probe.
        assertNull(OverlayLookup.probe(t))
    }

    @Test
    fun `group counts show what each Quick panel group reaches`() {
        val counts = ColorGroupMatcher.groupCounts(device, rules)
        assertEquals(GroupCount(1, 1), counts[ColorGroup.QS_TILE_ACTIVE])
        assertEquals(GroupCount(1, 1), counts[ColorGroup.QS_TILE_INACTIVE])
        assertEquals(GroupCount(1, 1), counts[ColorGroup.QS_PANEL_BG])
    }
}
