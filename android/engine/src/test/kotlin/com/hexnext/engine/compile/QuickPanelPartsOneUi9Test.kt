package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ArscReader
import com.hexnext.engine.arsc.ArscTestBuilder
import com.hexnext.engine.compile.Fixtures.semwt
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.palette.ColorMath
import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ShadeReference
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The One UI 9 Quick panel parts beyond the tiles: panel and detail backdrops, tile icons, panel
 * text, the media player and the panel notifications. Names are from the owner's SM-S948U1
 * (System UI colour list and the Theme Park Quick panel overlay on that phone); the APK values
 * below are stand-ins, since the device data carries names and SemWT values only.
 */
class QuickPanelPartsOneUi9Test {
    private val sysui = "com.android.systemui"
    private val rules = OneUiRules.load(9).copy(renamed = emptyMap())
    private val spec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()))
    private val resolved = ThemeResolver.resolve(spec.source, spec.darkAdjustments, spec.lightAdjustments)

    /** SemWT values from the device (day, night). */
    private val semwtValues: Map<String, Pair<Int, Int>> = linkedMapOf(
        "qs_tile_round_background_on" to (0xFF008393.toInt() to 0xFF00BCD2.toInt()),
        "qs_tile_round_background_dim" to (0x80D6E5E9.toInt() to 0x80253335.toInt()),
        "qs_tile_container_bg" to (0x33DCE4E5 to 0x33151D1E),
        "qs_tile_icon_on_dim_tint_color" to (0xFFF6FDFF.toInt() to 0xFFF6FDFF.toInt()),
        "subscreen_qs_tile_round_background_on" to (0xFF19B9E5.toInt() to 0xFF19B9E5.toInt()),
    )

    /** Names only the APK defines (SemWT does not set them), with a stand-in literal. */
    private val apkOnly: Map<String, Int> = linkedMapOf(
        "sec_panel_background_color" to 0xCCF2F2F2.toInt(),
        "sec_qs_detail_bg_color" to 0xF2FFFFFF.toInt(),
        "qs_tile_icon_off_tint_color" to 0xFF000000.toInt(),
        "animated_brightness_sun_icon_color" to 0xFFFFFFFF.toInt(),
        "shade_header_clock_color" to 0xFF000000.toInt(),
        "qs_tile_label" to 0xFF000000.toInt(),
        "qs_tile_sub_label" to 0x99000000.toInt(),
        "sec_qs_media_player_background_color" to 0xFFFFFFFF.toInt(),
        "media_primary_text" to 0xFF000000.toInt(),
        "qp_notification_content_color" to 0xFF5F5F5F.toInt(),
        // Not exact names: left alone on Auto.
        "qs_tile_layout_container_bg" to 0x33FFFFFF,
        "qs_tile_search_background_color" to 0xFF3A3A3D.toInt(),
    )

    private val table = run {
        val all = semwtValues.keys.map { it to 0xFF000000.toInt() } + apkOnly.toList()
        val b = ArscTestBuilder(sysui)
        b.typeNames += listOf("color")
        b.typeSpec(1, all.size)
        b.type(1, all.size, b.config(), all.withIndex().associate { (i, e) -> i to ArscTestBuilder.Entry.Simple(b.key(e.first), 0x1c, e.second) })
        ArscReader.read(b.build())
    }

    private val device = DeviceSnapshot(
        Fixtures.profile9,
        mapOf(
            sysui to TargetSnapshot(
                sysui,
                installed = true,
                table = table,
                semwt = semwt(sysui, semwtValues.flatMap { (n, v) -> listOf(Triple(n, v.first, ""), Triple(n, v.second, "night")) }),
            ),
        ),
        Fixtures.reference,
    )

    private fun compile(s: ThemeSpec) = ThemeCompiler.compile(s, device, InMemoryPackResolver(emptyList()), rules) { 7L }

    private fun CompiledTarget.color(name: String, night: Boolean): PlannedColor? =
        colors.firstOrNull { it.resourceName == "$sysui:color/$name" && it.configuration == (if (night) "night" else "") }

    private fun ThemeSpec.both(g: ColorGroup, argb: Int) = withGroupColor(g, false, argb).withGroupColor(g, true, argb)

    private fun samsungAlpha(name: String, night: Boolean): Int =
        ColorMath.alpha(apkOnly[name] ?: semwtValues.getValue(name).let { if (night) it.second else it.first })

    @Test
    fun `each visible Quick panel part has its group`() {
        val parts = mapOf(
            "qs_tile_round_background_on_stroke" to ColorGroup.QS_TILE_ACTIVE,
            "qs_tile_round_background_off" to ColorGroup.QS_TILE_INACTIVE,
            "qs_tile_round_background_off_stroke" to ColorGroup.QS_TILE_INACTIVE,
            "qs_edit_tile_round_background_available" to ColorGroup.QS_TILE_INACTIVE,
            "sec_panel_background_color" to ColorGroup.QS_PANEL_BG,
            "open_theme_qp_bg_color" to ColorGroup.QS_PANEL_BG,
            "sec_brightness_mirror_background_color" to ColorGroup.QS_PANEL_BG,
            "sec_qs_detail_bg_color" to ColorGroup.QS_PANEL_BG,
            "qs_tile_icon_on_dim_tint_color" to ColorGroup.QS_TILE_ICON_ON,
            "animated_brightness_sun_icon_color" to ColorGroup.QS_TILE_ICON_ON,
            "qs_tile_icon_off_tint_color" to ColorGroup.QS_TILE_ICON_OFF,
            "qs_edit_panel_tile_icon_color" to ColorGroup.QS_TILE_ICON_OFF,
            "qs_tile_label" to ColorGroup.QS_TEXT,
            "shade_header_clock_color" to ColorGroup.QS_TEXT,
            "sec_qs_header_tint_color" to ColorGroup.QS_TEXT,
            "qs_multisim_carrier_name_color" to ColorGroup.QS_TEXT,
            "qs_page_indicator_tint_color_selected" to ColorGroup.QS_TEXT,
            "sec_qs_detail_primary_text_color" to ColorGroup.QS_TEXT,
            "sec_qs_page_indicator_tint_color" to ColorGroup.QS_TEXT,
            "qs_edit_content_text_color" to ColorGroup.QS_TEXT,
            "qs_edit_panel_available_background_color" to ColorGroup.QS_PANEL_BG,
            "qs_edit_panel_active_tile_icon_color" to ColorGroup.QS_TILE_ICON_ON,
            "qs_edit_panel_available_tile_icon_color" to ColorGroup.QS_TILE_ICON_OFF,
            "sec_qs_media_player_background_color" to ColorGroup.NOTIFICATION_BG,
            "qp_notification_background_color" to ColorGroup.NOTIFICATION_BG,
            "media_primary_text" to ColorGroup.TEXT_PRIMARY,
            "qp_notification_title_color" to ColorGroup.TEXT_PRIMARY,
            "media_secondary_text" to ColorGroup.TEXT_SECONDARY,
            "open_theme_notification_content_text_color" to ColorGroup.TEXT_SECONDARY,
        )
        val accent = ShadeReference(Fixtures.reference).infer(Fixtures.ref("system_accent1_600"))
        for ((name, group) in parts) {
            // Exact names skip the tone check: the same group whatever the colour is.
            assertEquals(group, ColorGroupMatcher.classify(sysui, "color/$name", null, rules), name)
            assertEquals(group, ColorGroupMatcher.classify(sysui, "color/$name", accent, rules), name)
            assertTrue(rules.allowsSystemUiColour(name), name)
        }
        // Quick panel groups never leave System UI: elsewhere the generic rules decide.
        assertEquals(ColorGroup.ICON_TINT, ColorGroupMatcher.classify("com.android.settings", "color/qs_tile_icon_off_tint_color", null, rules))
    }

    @Test
    fun `names the System UI pattern misses are allowed only as exact names, and an allowlist still wins`() {
        for (name in listOf("shade_header_text_color", "media_primary_text", "media_secondary_text", "open_theme_qp_bg_color")) {
            assertFalse(rules.quickSettings.allowsColour(name), name)
            assertTrue(rules.allowsSystemUiColour(name), name)
        }
        assertFalse(rules.allowsSystemUiColour("hexnext_test_unlisted_color"))
        val listed = rules.copy(quickSettings = rules.quickSettings.copy(colours = listOf("qs_tile_round_background_on")))
        assertTrue(listed.allowsSystemUiColour("qs_tile_round_background_on"))
        assertFalse(listed.allowsSystemUiColour("shade_header_text_color"))
    }

    @Test
    fun `on Auto every exact part takes the colour the preview shows, from SemWT or the APK, with Samsung's alpha`() {
        val t = compile(spec).target(sysui)!!
        val exact = rules.colorGroupFamilies.getValue("systemui").exact
            .flatMap { (id, names) -> names.map { it to ColorGroup.byId(id)!! } }.toMap()
        for (name in semwtValues.keys + apkOnly.keys) {
            val group = exact[name]
            for (night in listOf(false, true)) {
                val written = t.color(name, night)
                if (group == null) {
                    // Not a verified part: nothing is added from the APK on Auto.
                    assertNull(written, "$name night=$night")
                    continue
                }
                val expected = ColorMath.withAlpha(spec.effectiveGroupColor(group, resolved, night), samsungAlpha(name, night))
                assertEquals(expected, assertNotNull(written, "$name night=$night").argb, "$name night=$night")
            }
        }
        // The ink groups read on their base: dark ink on the light day panel, light ink at night.
        assertEquals(resolved.light.shade(PaletteRole.NEUTRAL1, 900), spec.effectiveGroupColor(ColorGroup.QS_TEXT, resolved, false))
        assertEquals(resolved.dark.shade(PaletteRole.NEUTRAL1, 10), spec.effectiveGroupColor(ColorGroup.QS_TEXT, resolved, true))
        val event = compile(spec).events.single { it.target == sysui && it.message == "group colours" }
        assertEquals("20", event.data["fromApk"])
    }

    @Test
    fun `tile icons follow a custom tile colour unless the icons have their own`() {
        val pale = 0xFFFFF59D.toInt()
        val deep = 0xFF1A237E.toInt()
        val dark = resolved.light.shade(PaletteRole.NEUTRAL1, 900)
        val light = resolved.light.shade(PaletteRole.NEUTRAL1, 10)

        val onPale = compile(spec.both(ColorGroup.QS_TILE_ACTIVE, pale)).target(sysui)!!
        assertEquals(dark, onPale.color("qs_tile_icon_on_dim_tint_color", false)!!.argb)
        assertEquals(dark, onPale.color("animated_brightness_sun_icon_color", false)!!.argb)

        val onDeep = compile(spec.both(ColorGroup.QS_TILE_ACTIVE, deep)).target(sysui)!!
        assertEquals(light, onDeep.color("qs_tile_icon_on_dim_tint_color", false)!!.argb)

        // Off icons follow the off tiles, panel text the panel.
        val offDeep = compile(spec.both(ColorGroup.QS_TILE_INACTIVE, deep).both(ColorGroup.QS_PANEL_BG, deep)).target(sysui)!!
        assertEquals(light, offDeep.color("qs_tile_icon_off_tint_color", false)!!.argb)
        assertEquals(light, offDeep.color("shade_header_clock_color", false)!!.argb)

        // The preview agrees.
        val s = spec.both(ColorGroup.QS_TILE_ACTIVE, pale)
        assertEquals(dark, s.effectiveGroupColors(resolved, false).getValue(ColorGroup.QS_TILE_ICON_ON))

        // A custom icon colour wins over the ink.
        val red = 0xFFE53935.toInt()
        val own = compile(s.both(ColorGroup.QS_TILE_ICON_ON, red)).target(sysui)!!
        assertEquals(red, own.color("qs_tile_icon_on_dim_tint_color", false)!!.argb)
        assertEquals(red, own.color("qs_tile_icon_on_dim_tint_color", true)!!.argb)
    }

    @Test
    fun `the panel opacity reaches the whole panel backdrop and detail views, not the tiles`() {
        val t = compile(spec.copy(qsPanelAlpha = 0x99)).target(sysui)!!
        for (name in listOf("sec_panel_background_color", "sec_qs_detail_bg_color")) {
            assertTrue(ColorMath.alpha(t.color(name, false)!!.argb) < samsungAlpha(name, false), name)
        }
        assertEquals(0xFF, ColorMath.alpha(t.color("qs_tile_round_background_on", false)!!.argb))
        assertEquals(0xFF, ColorMath.alpha(t.color("qs_tile_icon_off_tint_color", false)!!.argb))
    }

    @Test
    fun `group counts include the APK-only parts`() {
        val counts = ColorGroupMatcher.groupCounts(device, rules)
        assertEquals(GroupCount(2, 1), counts[ColorGroup.QS_TILE_ICON_ON])
        assertEquals(GroupCount(1, 1), counts[ColorGroup.QS_TILE_ICON_OFF])
        assertEquals(GroupCount(3, 1), counts[ColorGroup.QS_TEXT])
        assertEquals(GroupCount(3, 1), counts[ColorGroup.QS_PANEL_BG])
    }
}
