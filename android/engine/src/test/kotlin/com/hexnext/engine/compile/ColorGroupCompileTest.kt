package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ArscReader
import com.hexnext.engine.arsc.ArscTestBuilder
import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.compile.Fixtures.ref
import com.hexnext.engine.compile.Fixtures.semwt
import com.hexnext.engine.compile.Fixtures.table
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.palette.ColorMath
import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorGroupCompileTest {
    private val settings = "com.android.settings"
    private val sysui = "com.android.systemui"
    private val red = 0xFFE53935.toInt()
    private val blue = 0xFF1E3AE5.toInt()
    private val green = 0xFF2E7D32.toInt()

    private val baseSpec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()), iconStyle = IconColorStyle.ACCENT)
    private val resolved = ThemeResolver.resolve(baseSpec.source)
    private val rules9 = OneUiRules.load(9).copy(renamed = emptyMap())

    private fun withAlpha(argb: Int, alpha: Int) = ColorMath.withAlpha(argb, alpha)

    // Real One UI 9 Settings names (from the SemWT idmap dump), with values from the scan palette.
    private val settingsSemwt = semwt(
        settings,
        listOf(
            Triple("sesl_switch_track_on_color_light", ref("system_accent1_600"), ""),
            Triple("sesl_switch_track_on_color_light", ref("system_accent1_200"), "night"),
            Triple("sesl_switchbar_on_background_color_light", withAlpha(ref("system_accent1_700"), 0x1a), ""),
            Triple("sesl_switchbar_on_background_color_light", withAlpha(ref("system_accent1_200"), 0x1a), "night"),
            Triple("sesl_switch_thumb_on_color", ref("system_neutral1_10"), ""),
            Triple("sesl_switch_thumb_on_color", ref("system_neutral1_10"), "night"),
            Triple("sesl_round_and_bgcolor_light", ref("system_neutral1_50"), ""),
            Triple("sesl_round_and_bgcolor_light", ref("system_neutral1_1000"), "night"),
            Triple("sesl_background_color_light", ref("system_neutral1_10"), ""),
            Triple("sesl_background_color_light", ref("system_neutral2_900"), "night"),
            Triple("sesl_action_bar_text_color_title_light", ref("system_neutral1_900"), ""),
            Triple("sesl_action_bar_text_color_title_light", ref("system_neutral1_50"), "night"),
            Triple("sec_widget_relative_link_text_color", ref("system_accent1_600"), ""),
            Triple("sesl_btn_background_color_light", ref("system_accent1_100"), ""),
        ),
    )

    /** A table whose colours are literals in the default configuration: SemWT names black, [literals] as given. */
    private fun tableWith(pkg: String, semwtNames: List<String>, vararg literals: Pair<String, Int>): ResourceTable {
        val all = semwtNames.map { it to 0xFF000000.toInt() } + literals
        val b = ArscTestBuilder(pkg)
        b.typeNames += listOf("color")
        b.typeSpec(1, all.size)
        b.type(1, all.size, b.config(), all.withIndex().associate { (i, e) -> i to ArscTestBuilder.Entry.Simple(b.key(e.first), 0x1c, e.second) })
        return ArscReader.read(b.build())
    }

    private val settingsTable = tableWith(
        settings,
        listOf(
            "sesl_switch_track_on_color_light", "sesl_switchbar_on_background_color_light", "sesl_switch_thumb_on_color",
            "sesl_round_and_bgcolor_light", "sesl_background_color_light", "sesl_action_bar_text_color_title_light",
            "sec_widget_relative_link_text_color", "sesl_btn_background_color_light",
        ),
        // Only in the APK table (literal colours in the default configuration), not set by SemWT.
        "sec_suggestion_card_background_color" to 0xFFFCFCFC.toInt(), "sesl_switch_track_on_color_dark" to 0xFF3E91FF.toInt(),
        "sec_error_card_background_color" to 0xFFFCFCFC.toInt(), "sec_app_name_text_color" to 0xFF000000.toInt(),
    )

    private val sysuiTable = table(
        sysui,
        listOf("qs_tile_bg", "quick_panel_bg", "shade_bg", "qs_tile_round_background_on", "wallpaper_tint", "qs_background_dark"),
    )
    private val sysuiSemwt = semwt(
        sysui,
        listOf(
            Triple("qs_tile_bg", ref("system_accent1_500"), ""),
            Triple("quick_panel_bg", withAlpha(ref("system_neutral1_100"), 0xE0), ""),
            Triple("quick_panel_bg", withAlpha(ref("system_neutral1_900"), 0xE0), "night"),
            Triple("shade_bg", ref("system_neutral1_900"), "night"),
            Triple("qs_tile_round_background_on", ref("system_accent1_600"), ""),
            Triple("wallpaper_tint", ref("system_accent2_300"), ""),
        ),
    )

    private fun device(settingsSnapshot: TargetSnapshot = TargetSnapshot(settings, installed = true, table = settingsTable, semwt = settingsSemwt)) =
        DeviceSnapshot(
            Fixtures.profile9,
            mapOf(
                settings to settingsSnapshot,
                sysui to TargetSnapshot(sysui, installed = true, table = sysuiTable, semwt = sysuiSemwt),
            ),
            Fixtures.reference,
        )

    private val noPacks = InMemoryPackResolver(emptyList())

    private fun compile(spec: ThemeSpec, rules: OneUiRules = rules9, dev: DeviceSnapshot = device()) =
        ThemeCompiler.compile(spec, dev, noPacks, rules) { 7L }

    private fun CompiledTarget.color(name: String, config: String = ""): PlannedColor? =
        colors.firstOrNull { it.resourceName == "$packageName:color/$name" && it.configuration == config }

    private fun ThemeSpec.group(g: ColorGroup, light: Int?, dark: Int?) = withGroupColor(g, false, light).withGroupColor(g, true, dark)

    private val semwtNames = listOf(
        "sesl_switch_track_on_color_light", "sesl_switchbar_on_background_color_light", "sesl_switch_thumb_on_color",
        "sesl_round_and_bgcolor_light", "sesl_background_color_light", "sesl_action_bar_text_color_title_light",
        "sec_widget_relative_link_text_color", "sesl_btn_background_color_light",
    )

    @Test
    fun `switch colours reach switch names per mode and leave everything else on inference`() {
        val stock = compile(baseSpec).target(settings)!!
        val t = compile(baseSpec.group(ColorGroup.SWITCH, red, blue)).target(settings)!!
        assertEquals(red, t.color("sesl_switch_track_on_color_light")!!.argb)
        assertEquals(blue, t.color("sesl_switch_track_on_color_light", "night")!!.argb)
        // The tinted switch bar keeps Samsung's alpha.
        assertEquals(withAlpha(red, 0x1a), t.color("sesl_switchbar_on_background_color_light")!!.argb)
        assertEquals(withAlpha(blue, 0x1a), t.color("sesl_switchbar_on_background_color_light", "night")!!.argb)
        // The white thumb drawn on the track is not the switch fill.
        assertEquals(stock.color("sesl_switch_thumb_on_color"), t.color("sesl_switch_thumb_on_color"))
        // Every other SemWT name is exactly what role inference gives.
        for (n in semwtNames.filterNot { it.startsWith("sesl_switch") }) {
            for (config in listOf("", "night")) assertEquals(stock.color(n, config), t.color(n, config), "$n [$config]")
        }
        val ev = compile(baseSpec.group(ColorGroup.SWITCH, red, blue)).events.single { it.target == settings && it.message == "group colours" }
        // Four SemWT keys plus the APK-only dark track in both configurations.
        assertEquals("6", ev.data["count"])
        assertEquals("2", ev.data["fromApk"])
        assertEquals("6", ev.data["switch"])
    }

    @Test
    fun `a group colour beats inference in day and night separately`() {
        val stock = compile(baseSpec).target(settings)!!
        val dayOnly = compile(baseSpec.group(ColorGroup.BACKGROUND, red, null)).target(settings)!!
        assertEquals(red, dayOnly.color("sesl_round_and_bgcolor_light")!!.argb)
        assertEquals(resolved.dark.shade(PaletteRole.NEUTRAL1, 1000), dayOnly.color("sesl_round_and_bgcolor_light", "night")!!.argb)
        val nightOnly = compile(baseSpec.group(ColorGroup.BACKGROUND, null, blue)).target(settings)!!
        assertEquals(resolved.light.shade(PaletteRole.NEUTRAL1, 50), nightOnly.color("sesl_round_and_bgcolor_light")!!.argb)
        assertEquals(blue, nightOnly.color("sesl_round_and_bgcolor_light", "night")!!.argb)
        // sesl_background_color is the card colour (neutral1 10 / neutral2 900): Surface, not Background.
        assertEquals(stock.color("sesl_background_color_light"), dayOnly.color("sesl_background_color_light"))
        val cards = compile(baseSpec.group(ColorGroup.SURFACE, green, blue)).target(settings)!!
        assertEquals(green, cards.color("sesl_background_color_light")!!.argb)
        assertEquals(blue, cards.color("sesl_background_color_light", "night")!!.argb)
    }

    @Test
    fun `text and accent groups reach their names`() {
        val t = compile(baseSpec.group(ColorGroup.TEXT_PRIMARY, green, red).group(ColorGroup.ACCENT, blue, blue)).target(settings)!!
        assertEquals(green, t.color("sesl_action_bar_text_color_title_light")!!.argb)
        assertEquals(red, t.color("sesl_action_bar_text_color_title_light", "night")!!.argb)
        assertEquals(blue, t.color("sec_widget_relative_link_text_color")!!.argb)
        // Text groups never reach APK-only names: SemWT marks which texts are themable.
        assertNull(t.color("sec_app_name_text_color"))
    }

    @Test
    fun `a pale tonal button stays a pale container`() {
        val t = compile(baseSpec.group(ColorGroup.BUTTON, red, red)).target(settings)!!
        val v = t.color("sesl_btn_background_color_light")!!.argb
        assertNotEquals(red, v)
        assertTrue(ColorMath.lstar(v) > 80)
    }

    @Test
    fun `a pin beats a group`() {
        val spec = baseSpec.group(ColorGroup.SWITCH, red, blue).copy(
            colorPins = mapOf(
                "$settings:color/sesl_switch_track_on_color_light" to ColorPin.Fixed(green),
                "$settings:color/sesl_switch_track_on_color_dark" to ColorPin.Shade(PaletteRole.ACCENT3, 400),
            ),
        )
        val result = compile(spec)
        val t = result.target(settings)!!
        assertEquals(green, t.color("sesl_switch_track_on_color_light")!!.argb)
        assertEquals(green, t.color("sesl_switch_track_on_color_light", "night")!!.argb)
        assertEquals(resolved.light.shade(PaletteRole.ACCENT3, 400), t.color("sesl_switch_track_on_color_dark")!!.argb)
        assertEquals(resolved.dark.shade(PaletteRole.ACCENT3, 400), t.color("sesl_switch_track_on_color_dark", "night")!!.argb)
        assertEquals(withAlpha(red, 0x1a), t.color("sesl_switchbar_on_background_color_light")!!.argb)
        // Pinned keys are not counted as reached by the group.
        assertEquals("2", result.events.single { it.target == settings && it.message == "group colours" }.data["count"])
    }

    @Test
    fun `Auto changes nothing`() {
        val stock = compile(baseSpec)
        val autoEntries = baseSpec.copy(colorGroups = mapOf("switch" to GroupColor(), "background" to GroupColor(null, null)))
        val roundTrip = baseSpec.group(ColorGroup.SWITCH, red, blue).group(ColorGroup.SWITCH, null, null)
        val unknownId = baseSpec.copy(colorGroups = mapOf("future_group" to GroupColor(red, red)))
        for (spec in listOf(autoEntries, roundTrip, baseSpec.withAllGroupsAuto(), unknownId)) {
            val c = compile(spec)
            for (pkg in listOf(settings, sysui)) assertEquals(stock.target(pkg)!!.contentHash, c.target(pkg)!!.contentHash, pkg)
            assertTrue(c.events.none { it.message == "group colours" })
        }
        assertTrue(roundTrip.colorGroups.isEmpty())
    }

    @Test
    fun `fixed colours take a group colour`() {
        val orange = 0xFFFF6A00.toInt()
        val semwt = semwt(settings, listOf(Triple("sesl_switch_track_on_color_light", orange, "")))
        val dev = device(TargetSnapshot(settings, installed = true, table = settingsTable, semwt = semwt))
        val stock = compile(baseSpec, dev = dev).target(settings)!!
        assertEquals(orange, stock.color("sesl_switch_track_on_color_light")!!.argb)
        assertEquals(ThemeCompiler.REASON_FIXED, stock.skipped.single { it.name == "$settings:color/sesl_switch_track_on_color_light" }.reason)
        val t = compile(baseSpec.group(ColorGroup.SWITCH, red, blue), dev = dev).target(settings)!!
        assertEquals(red, t.color("sesl_switch_track_on_color_light")!!.argb)
        assertTrue(t.skipped.none { it.reason == ThemeCompiler.REASON_FIXED })
    }

    @Test
    fun `Quick panel group colours still obey the Quick Settings policy`() {
        val spec = baseSpec.group(ColorGroup.QS_TILE_ACTIVE, red, red).group(ColorGroup.QS_PANEL_BG, blue, blue)
        val t = compile(spec).target(sysui)!!
        assertEquals(red, t.color("qs_tile_bg")!!.argb)
        assertEquals(withAlpha(blue, 0xE0), t.color("quick_panel_bg")!!.argb)
        assertEquals(withAlpha(blue, 0xE0), t.color("quick_panel_bg", "night")!!.argb)
        // shade_bg is a panel backdrop by name, but the pattern policy does not allow it: Samsung's value stays.
        assertEquals(ref("system_neutral1_900"), t.color("shade_bg", "night")!!.argb)
        assertEquals(ThemeCompiler.REASON_QS_POLICY, t.skipped.single { it.name == "$sysui:color/shade_bg [night]" }.reason)

        // With an allowlist, a tile name outside it is refused exactly as before groups existed.
        val listed = rules9.copy(quickSettings = rules9.quickSettings.copy(colours = listOf("qs_tile_bg", "quick_panel_bg")))
        val l = compile(spec, rules = listed).target(sysui)!!
        assertEquals(red, l.color("qs_tile_bg")!!.argb)
        assertEquals(ref("system_accent1_600"), l.color("qs_tile_round_background_on")!!.argb)
        assertEquals(ThemeCompiler.REASON_QS_POLICY, l.skipped.single { it.name == "$sysui:color/qs_tile_round_background_on" }.reason)
        // APK-only System UI names outside the policy are never added.
        assertNull(l.color("qs_background_dark"))
        assertNull(l.color("qs_background_dark", "night"))
        // Inside the pattern policy, an APK-only panel name is reached.
        assertEquals(blue, t.color("qs_background_dark", "night")!!.argb)
    }

    @Test
    fun `qsPanelAlpha is written onto Quick panel backgrounds, Auto or custom`() {
        val auto = compile(baseSpec.copy(qsPanelAlpha = 0x66)).target(sysui)!!
        assertEquals(withAlpha(resolved.light.shade(PaletteRole.NEUTRAL1, 100), 0x66), auto.color("quick_panel_bg")!!.argb)
        assertEquals(withAlpha(resolved.dark.shade(PaletteRole.NEUTRAL1, 900), 0x66), auto.color("quick_panel_bg", "night")!!.argb)
        // Tiles are not the panel.
        assertEquals(resolved.light.shade(PaletteRole.ACCENT1, 500), auto.color("qs_tile_bg")!!.argb)
        val custom = compile(baseSpec.copy(qsPanelAlpha = 0x70).group(ColorGroup.QS_PANEL_BG, blue, red)).target(sysui)!!
        assertEquals(withAlpha(blue, 0x70), custom.color("quick_panel_bg")!!.argb)
        assertEquals(withAlpha(red, 0x70), custom.color("quick_panel_bg", "night")!!.argb)
        // Below the floor the panel keeps a readable alpha; 0 never makes it fully transparent.
        val floor = ThemeSpec.MIN_QS_PANEL_ALPHA
        val clear = compile(baseSpec.copy(qsPanelAlpha = 0).group(ColorGroup.QS_PANEL_BG, blue, red)).target(sysui)!!
        assertEquals(withAlpha(blue, floor), clear.color("quick_panel_bg")!!.argb)
        assertEquals(withAlpha(red, floor), clear.color("quick_panel_bg", "night")!!.argb)
        // 255 leaves Samsung's alpha.
        assertEquals(compile(baseSpec).target(sysui)!!.contentHash, compile(baseSpec.copy(qsPanelAlpha = 255)).target(sysui)!!.contentHash)
    }

    @Test
    fun `group colours reach APK-only names in both modes`() {
        val t = compile(baseSpec.group(ColorGroup.SURFACE, green, blue).group(ColorGroup.SWITCH, red, blue)).target(settings)!!
        assertEquals(green, t.color("sec_suggestion_card_background_color")!!.argb)
        assertEquals(blue, t.color("sec_suggestion_card_background_color", "night")!!.argb)
        // A _dark name takes the dark colour in both configurations.
        assertEquals(blue, t.color("sesl_switch_track_on_color_dark")!!.argb)
        assertEquals(blue, t.color("sesl_switch_track_on_color_dark", "night")!!.argb)
        // Status colours keep their meaning.
        assertNull(t.color("sec_error_card_background_color"))
    }

    @Test
    fun `a day-only group colour keeps the APK literal at night on APK-only names`() {
        val t = compile(baseSpec.group(ColorGroup.SURFACE, green, null)).target(settings)!!
        assertEquals(green, t.color("sec_suggestion_card_background_color")!!.argb)
        // The single default literal would otherwise carry the day colour into dark mode.
        assertEquals(0xFFFCFCFC.toInt(), t.color("sec_suggestion_card_background_color", "night")!!.argb)
    }

    @Test
    fun `inventory planned values equal the compiled values`() {
        val spec = baseSpec.group(ColorGroup.SWITCH, red, blue).group(ColorGroup.QS_PANEL_BG, blue, red).copy(
            qsPanelAlpha = 0x99,
            colorPins = mapOf(
                "$settings:color/sesl_round_and_bgcolor_light" to ColorPin.Fixed(green),
                "$settings:color/not_in_table" to ColorPin.Fixed(green),
                "$sysui:color/wallpaper_tint" to ColorPin.Fixed(green),
            ),
        )
        val dev = device()
        val compiled = compile(spec, dev = dev)
        for (pkg in listOf(settings, sysui)) {
            val rows = ColorGroupMatcher.inventory(dev.targets.getValue(pkg), dev.systemShades, spec, rules9)
            val planned = rows.filter { it.planned != null }.map { Triple(it.resourceName, it.configuration, it.planned!!) }.toSet()
            val written = compiled.target(pkg)!!.colors.map { Triple(it.resourceName, it.configuration, it.argb) }.toSet()
            assertEquals(written, planned, pkg)
            // Sorted by name, then configuration, one row per key.
            assertEquals(rows.sortedWith(compareBy({ it.resourceName }, { it.configuration })), rows)
            assertEquals(rows.size, rows.map { it.resourceName to it.configuration }.distinct().size)
        }
        val rows = ColorGroupMatcher.inventory(dev.targets.getValue(settings), dev.systemShades, spec, rules9)
        val track = rows.single { it.resourceName == "$settings:color/sesl_switch_track_on_color_light" && it.configuration == "night" }
        assertEquals(ref("system_accent1_200"), track.observed)
        assertEquals(ColorGroup.SWITCH, track.group)
        assertEquals(PaletteRole.ACCENT1, track.inferred!!.role)
        assertEquals(blue, track.planned)
        val pinned = rows.single { it.resourceName == "$settings:color/sesl_round_and_bgcolor_light" && it.configuration == "" }
        assertEquals(ColorPin.Fixed(green), pinned.pin)
        assertEquals(green, pinned.planned)
        // APK-only colours are listed with their literal; untouched ones are left stock.
        val appName = rows.single { it.resourceName == "$settings:color/sec_app_name_text_color" }
        assertEquals(0xFF000000.toInt(), appName.observed)
        assertEquals(ColorGroup.TEXT_PRIMARY, appName.group)
        assertNull(appName.planned)
        // A pin on a name the table lacks is listed but cannot be written.
        val missing = rows.single { it.resourceName == "$settings:color/not_in_table" }
        assertEquals(ColorPin.Fixed(green), missing.pin)
        assertNull(missing.planned)
        // A disabled target writes nothing.
        val off = spec.copy(targetEnabled = mapOf(settings to false))
        assertTrue(ColorGroupMatcher.inventory(dev.targets.getValue(settings), dev.systemShades, off, rules9).all { it.planned == null })
    }

    @Test
    fun `inventory reports night-only APK literals as the original day value`() {
        val name = "sesl_round_and_bgcolor_light"
        val original = 0xFF717171.toInt()
        val b = ArscTestBuilder(settings)
        b.typeNames += "color"
        b.typeSpec(1, 1)
        b.type(1, 1, b.config(uiMode = 0x20), mapOf(0 to ArscTestBuilder.Entry.Simple(b.key(name), 0x1c, original)))
        val target = TargetSnapshot(settings, installed = true, table = ArscReader.read(b.build()))
        val spec = baseSpec.group(ColorGroup.BACKGROUND, red, blue)
        val rows = ColorGroupMatcher.inventory(target, Fixtures.reference, spec, rules9)
        val day = rows.single { it.resourceName == "$settings:color/$name" && it.configuration == "" }
        val night = rows.single { it.resourceName == "$settings:color/$name" && it.configuration == "night" }
        assertEquals(original, day.observed)
        assertEquals(original, night.observed)
        assertEquals(red, day.planned)
    }

    @Test
    fun `group counts list reached resources and packages`() {
        val counts = ColorGroupMatcher.groupCounts(device(), rules9)
        // Settings: two SemWT track/bar names plus the APK-only dark track.
        assertEquals(GroupCount(3, 1), counts[ColorGroup.SWITCH])
        // System UI: quick_panel_bg and the APK-only qs_background_dark; shade_bg is kept by the policy.
        assertEquals(GroupCount(2, 1), counts[ColorGroup.QS_PANEL_BG])
        assertEquals(GroupCount(2, 1), counts[ColorGroup.QS_TILE_ACTIVE])
        assertNotNull(counts[ColorGroup.BACKGROUND])
        assertFalse(ColorGroup.DIALER in counts)
        assertEquals(ColorGroup.ALL.filter { it in counts }, counts.keys.toList())
    }

    @Test
    fun `inventory is fast on a full-size app`() {
        val names = (0 until 1500).map { i ->
            listOf("sesl_switch_track_${i}_color", "sec_card_${i}_background_color", "sec_item_${i}_text_color", "sec_misc_${i}_color")[i % 4]
        }
        val shades = listOf("system_accent1_600", "system_neutral1_10", "system_neutral1_900", "system_accent2_300")
        val big = semwt(settings, names.flatMapIndexed { i, n -> listOf(Triple(n, ref(shades[i % 4]), ""), Triple(n, ref(shades[(i + 1) % 4]), "night")) })
        val target = TargetSnapshot(settings, installed = true, table = table(settings, names), semwt = big)
        val spec = baseSpec.group(ColorGroup.SWITCH, red, blue).group(ColorGroup.SURFACE, green, blue)
        ColorGroupMatcher.inventory(target, Fixtures.reference, spec, rules9)
        val start = System.nanoTime()
        val rows = ColorGroupMatcher.inventory(target, Fixtures.reference, spec, rules9)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(3000, rows.size)
        assertTrue(ms < 3000, "inventory took $ms ms")
    }
}
