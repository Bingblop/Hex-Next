package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.layer
import com.hexnext.engine.compile.Fixtures.pack
import com.hexnext.engine.compile.Fixtures.ref
import com.hexnext.engine.compile.Fixtures.semwt
import com.hexnext.engine.compile.Fixtures.table
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.map.ResourceMapper
import com.hexnext.engine.map.Skipped
import com.hexnext.engine.pack.Tint
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

class ThemeCompilerTest {
    private val sysui = "com.android.systemui"
    private val settings = "com.android.settings"
    private val orange = 0xFFFF6A00.toInt()

    private val baseSpec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()))
    private val resolved = ThemeResolver.resolve(baseSpec.source)

    private val sysuiTable = table(
        sysui,
        listOf("qs_tile_bg", "quick_panel_bg", "notification_bg", "wallpaper_tint", "lock_icon_color", "panel_extra"),
        mapOf("ic_sysbar_back" to listOf(480), "qs_tile_shape" to listOf(480)),
    )
    private val sysuiSemwt = semwt(
        sysui,
        listOf(
            Triple("qs_tile_bg", ref("system_accent1_500"), ""),
            Triple("qs_tile_bg", ref("system_neutral1_900"), "night"),
            Triple("notification_bg", (ref("system_neutral2_100") and 0x00FFFFFF) or (0x80 shl 24), ""),
            Triple("wallpaper_tint", ref("system_accent2_300"), ""),
            Triple("lock_icon_color", orange, ""),
            Triple("qs_removed_in_9", ref("system_accent1_200"), ""),
        ),
    )
    private val settingsTable = table(
        settings,
        listOf("sec_widget_round_and_bgcolor", "dashboard_tint"),
        mapOf("ic_settings_wifi" to listOf(480, 640), "ic_settings_display" to listOf(0), "account_radio_check" to listOf(480)),
    )
    private val settingsSemwt = semwt(settings, listOf(Triple("sec_widget_round_and_bgcolor", ref("system_neutral1_50"), "")))

    private fun device(extra: Map<String, TargetSnapshot> = emptyMap()) = DeviceSnapshot(
        Fixtures.profile9,
        mapOf(
            sysui to TargetSnapshot(sysui, installed = true, table = sysuiTable, semwt = sysuiSemwt, versionCode = 36),
            settings to TargetSnapshot(settings, installed = true, table = settingsTable, semwt = settingsSemwt),
            "com.sec.android.gallery3d" to TargetSnapshot("com.sec.android.gallery3d", installed = true, debloated = true, table = settingsTable),
            "com.samsung.android.calendar" to TargetSnapshot("com.samsung.android.calendar", installed = false),
            "com.samsung.android.lool" to TargetSnapshot("com.samsung.android.lool", installed = true, table = null),
            "com.samsung.android.messaging" to TargetSnapshot("com.samsung.android.messaging", installed = true, table = settingsTable),
        ) + extra,
        Fixtures.reference,
    )

    private val noPacks = InMemoryPackResolver(emptyList())

    private val iconPack = pack(
        "icons-1",
        listOf(
            layer("settingsicon", "base", Tint(Tint.SLOT, slot = "xx1"), listOf("ic_settings_wifi", "ic_settings_display", "ic_settings_gone")),
            layer("settingsicon", "first", Tint(Tint.FIXED, argb = "#80FF0000"), listOf("ic_settings_wifi")),
            layer("settingsicon_night", "base", Tint(Tint.SLOT, slot = "xx3"), listOf("ic_settings_wifi")),
            layer("settingsicon-1sett", "base", Tint(Tint.SLOT, slot = "xx5"), listOf("ic_settings_display")),
            layer("control", "base", Tint(Tint.SLOT, slot = "xx2"), listOf("account_radio_check")),
            layer("drawable-xxhdpi-v26", "base", Tint(Tint.SLOT, slot = "xx4"), listOf("account_radio_check", "ic_sysbar_back", "absent_everywhere")),
            layer("navbar", "base", Tint(Tint.SLOT, slot = "xx1"), listOf("ic_sysbar_back")),
            layer("qs", "base", Tint(Tint.SLOT, slot = "xx1"), listOf("qs_tile_shape")),
        ),
        palette = mapOf("xx1" to "#FF123456", "sx1" to "#FF654321"),
    )

    private fun compile(spec: ThemeSpec = baseSpec, packs: PackResolver = noPacks, rules: OneUiRules = OneUiRules.load(9), dev: DeviceSnapshot = device()) =
        ThemeCompiler.compile(spec, dev, packs, rules) { 42L }

    private fun CompiledTarget.color(name: String, config: String = ""): PlannedColor? =
        colors.firstOrNull { it.resourceName == "$packageName:color/$name" && it.configuration == config }

    private fun CompiledTarget.reason(name: String): String? = skipped.firstOrNull { it.name == name }?.reason

    @Test
    fun `recolours SemWT colours by inferred role per mode`() {
        val t = assertNotNull(compile().target(sysui))
        assertEquals("HexNext_com_android_systemui", t.overlayName)
        assertEquals(resolved.light.shade(PaletteRole.ACCENT1, 500), t.color("qs_tile_bg")!!.argb)
        assertEquals(resolved.dark.shade(PaletteRole.NEUTRAL1, 900), t.color("qs_tile_bg", "night")!!.argb)
        val notif = (resolved.light.shade(PaletteRole.NEUTRAL2, 100) and 0x00FFFFFF) or (0x80 shl 24)
        assertEquals(notif, t.color("notification_bg")!!.argb)
        assertEquals(36L, t.versionCode)
    }

    @Test
    fun `unmatched colours stay fixed and are reported`() {
        val result = compile()
        val t = result.target(sysui)!!
        assertNull(t.color("lock_icon_color"))
        assertEquals(ThemeCompiler.REASON_FIXED, t.reason("$sysui:color/lock_icon_color"))
        assertTrue(result.events.any { it.target == sysui && it.message == "colours kept fixed" && it.data["count"] == "1" })
        assertTrue(result.events.all { it.timestamp == 42L })
    }

    @Test
    fun `quick settings policy drops non panel System UI colours`() {
        val t = compile().target(sysui)!!
        assertNull(t.color("wallpaper_tint"))
        assertEquals(ThemeCompiler.REASON_QS_POLICY, t.reason("$sysui:color/wallpaper_tint"))
        // With the policy off the colour is kept.
        val off = compile(rules = OneUiRules.load(9).copy(quickSettingsColoursOnly = false)).target(sysui)!!
        assertNotNull(off.color("wallpaper_tint"))
        // Other targets are never filtered.
        assertNotNull(compile().target(settings)!!.color("sec_widget_round_and_bgcolor"))
    }

    @Test
    fun `names missing from the target table are skipped`() {
        val t = compile().target(sysui)!!
        assertTrue(t.colors.none { "qs_removed_in_9" in it.resourceName })
        assertEquals(ResourceMapper.REASON_NOT_IN_TARGET, t.reason("$sysui:color/qs_removed_in_9"))
    }

    @Test
    fun `pins win over inference and policy still applies`() {
        val spec = baseSpec.copy(
            colorPins = mapOf(
                "$sysui:color/qs_tile_bg" to ColorPin.Shade(PaletteRole.ACCENT3, 200),
                "$sysui:color/lock_icon_color" to ColorPin.Fixed(0xFF00FF00.toInt()),
                "$sysui:color/panel_extra" to ColorPin.Shade(PaletteRole.NEUTRAL2, 600),
                "$sysui:color/wallpaper_tint" to ColorPin.Fixed(0xFF0000FF.toInt()),
                "$sysui:color/qs_not_a_resource" to ColorPin.Fixed(1),
                "$settings:color/dashboard_tint" to ColorPin.Fixed(0xFF111111.toInt()),
            ),
        )
        val t = compile(spec).target(sysui)!!
        assertEquals(resolved.light.shade(PaletteRole.ACCENT3, 200), t.color("qs_tile_bg")!!.argb)
        assertEquals(resolved.dark.shade(PaletteRole.ACCENT3, 200), t.color("qs_tile_bg", "night")!!.argb)
        assertEquals(0xFF00FF00.toInt(), t.color("lock_icon_color")!!.argb)
        assertNull(t.reason("$sysui:color/lock_icon_color"))
        assertEquals(resolved.light.shade(PaletteRole.NEUTRAL2, 600), t.color("panel_extra")!!.argb)
        assertEquals(resolved.dark.shade(PaletteRole.NEUTRAL2, 600), t.color("panel_extra", "night")!!.argb)
        assertNull(t.color("wallpaper_tint"))
        assertEquals(ResourceMapper.REASON_NOT_IN_TARGET, t.reason("$sysui:color/qs_not_a_resource"))
        assertTrue(t.colors.none { "dashboard_tint" in it.resourceName })
        val s = compile(spec).target(settings)!!
        assertEquals(0xFF111111.toInt(), s.color("dashboard_tint")!!.argb)
    }

    @Test
    fun `renamed resources are applied before lookup`() {
        val dev = device(
            mapOf(
                sysui to TargetSnapshot(
                    sysui, installed = true, table = sysuiTable,
                    semwt = semwt(sysui, listOf(Triple("qs_tile_bg_old", ref("system_accent1_500"), ""))),
                ),
            ),
        )
        val rules = OneUiRules.load(9).copy(renamed = mapOf("color/qs_tile_bg_old" to "color/qs_tile_bg"))
        val t = compile(dev = dev, rules = rules).target(sysui)!!
        assertEquals(resolved.light.shade(PaletteRole.ACCENT1, 500), t.color("qs_tile_bg")!!.argb)
        assertEquals(1, t.semwtCoverage)
        val without = compile(dev = dev).target(sysui)!!
        assertNull(without.color("qs_tile_bg"))
    }

    @Test
    fun `unusable targets are skipped with a reason`() {
        val result = compile(baseSpec.copy(targetEnabled = mapOf(settings to false)))
        val skipped = result.skippedTargets.associate { it.name to it.reason }
        assertEquals(ThemeCompiler.REASON_DEBLOATED, skipped["com.sec.android.gallery3d"])
        assertEquals(ThemeCompiler.REASON_NOT_INSTALLED, skipped["com.samsung.android.calendar"])
        assertEquals(ThemeCompiler.REASON_NO_TABLE, skipped["com.samsung.android.lool"])
        assertEquals(ThemeCompiler.REASON_DEPRECATED, skipped["com.samsung.android.messaging"])
        assertEquals(ThemeCompiler.REASON_DISABLED, skipped[settings])
        assertEquals(ThemeCompiler.REASON_NOT_SCANNED, skipped["android"])
        assertEquals(listOf(sysui), result.targets.map { it.packageName })
        assertTrue(result.events.any { it.target == "com.sec.android.gallery3d" && it.data["reason"] == "debloated" })
    }

    @Test
    fun `drawables are planned per density with night categories and twins`() {
        val spec = baseSpec.copy(simplePackId = "icons-1")
        val t = compile(spec, InMemoryPackResolver(listOf(iconPack))).target(settings)!!
        val jobs = t.drawables.associateBy { it.resourceName.substringAfter('/') + "@" + it.configuration }
        val wifi = "com.android.settings:drawable/ic_settings_wifi"

        val day = jobs.getValue("ic_settings_wifi@xxhdpi")
        assertEquals(wifi, day.resourceName)
        assertEquals("settings_icons", day.elementId)
        val light = SlotPalette.derive(resolved)
        assertEquals(listOf(light.getValue("lxx1"), 0x80FF0000.toInt()), day.plan.steps.map { it.tintArgb })
        assertEquals(day.plan, jobs.getValue("ic_settings_wifi@xxxhdpi").plan)
        assertFalse(day.ninePatch)

        val night = jobs.getValue("ic_settings_wifi@night-xxhdpi")
        assertEquals("settingsicon_night", night.source.category)
        assertEquals(listOf(light.getValue("xx3")), night.plan.steps.map { it.tintArgb })
        assertTrue("ic_settings_wifi@night-xxxhdpi" in jobs)

        // Default-config drawable, and a night twin from the day category with dark tints.
        assertEquals(listOf(light.getValue("lxx1")), jobs.getValue("ic_settings_display@").plan.steps.map { it.tintArgb })
        val twin = jobs.getValue("ic_settings_display@night")
        assertEquals("settingsicon", twin.source.category)
        assertEquals(listOf(light.getValue("xx1")), twin.plan.steps.map { it.tintArgb })

        // Targeted element (controls) wins over the generic framework category.
        assertEquals("controls", jobs.getValue("account_radio_check@xxhdpi").elementId)

        assertEquals(ResourceMapper.REASON_NOT_IN_TARGET, t.reason("$settings:drawable/ic_settings_gone"))
        // Generic categories do not report names other targets own.
        assertNull(t.reason("$settings:drawable/absent_everywhere"))
        assertEquals(8, t.drawables.size)
    }

    @Test
    fun `option tokens select variant categories`() {
        val spec = baseSpec.copy(simplePackId = "icons-1", elementOptions = mapOf("settings_icons" to mapOf("1sett" to "true")))
        val t = compile(spec, InMemoryPackResolver(listOf(iconPack))).target(settings)!!
        val display = t.drawables.single { it.resourceName.endsWith("ic_settings_display") && it.configuration == "" }
        assertEquals("settingsicon-1sett", display.source.category)
        assertEquals(SlotPalette.derive(resolved).getValue("lxx5"), display.plan.steps.single().tintArgb)
    }

    @Test
    fun `pack palette option keeps pack colours`() {
        val spec = baseSpec.copy(simplePackId = "icons-1", elementOptions = mapOf("settings_icons" to mapOf("palette" to "pack")))
        val t = compile(spec, InMemoryPackResolver(listOf(iconPack))).target(settings)!!
        val twin = t.drawables.single { it.resourceName.endsWith("ic_settings_display") && it.configuration == "night" }
        assertEquals(0xFF123456.toInt(), twin.plan.steps.single().tintArgb)
    }

    @Test
    fun `quick settings tiles build no drawables but navbar does`() {
        val spec = baseSpec.copy(simplePackId = "icons-1")
        val t = compile(spec, InMemoryPackResolver(listOf(iconPack))).target(sysui)!!
        assertTrue(t.drawables.none { it.resourceName.endsWith("qs_tile_shape") })
        assertEquals(ThemeCompiler.REASON_QS_POLICY, t.reason("$sysui:drawable/qs_tile_shape"))
        val back = t.drawables.filter { it.resourceName == "$sysui:drawable/ic_sysbar_back" }
        assertEquals(setOf("xxhdpi", "night-xxhdpi"), back.map { it.configuration }.toSet())
        assertTrue(back.all { it.elementId == "navigation_bar" })
    }

    @Test
    fun `advanced mode picks packs per element and disabled elements build nothing`() {
        val other = pack("other", listOf(layer("settingsicon", "base", Tint(Tint.NONE), listOf("ic_settings_display"))))
        val packs = InMemoryPackResolver(listOf(iconPack, other))
        val spec = baseSpec.copy(
            mode = ThemeMode.ADVANCED, simplePackId = "icons-1",
            elementPacks = mapOf("settings_icons" to "other"),
            elementEnabled = mapOf("controls" to false, "system_drawables" to false),
        )
        val t = compile(spec, packs).target(settings)!!
        assertEquals(setOf("other"), t.drawables.map { it.source.packId }.toSet())
        assertEquals(listOf(null), t.drawables.first().plan.steps.map { it.tintArgb })
        assertTrue(t.drawables.none { it.resourceName.endsWith("account_radio_check") })
    }

    @Test
    fun `entry names prefer nine patches`() {
        val packs = InMemoryPackResolver(
            listOf(iconPack),
            mapOf("icons-1" to setOf("layers/once/base/xx1/navbar/ic_sysbar_back.9.png", "layers/once/base/xx1/navbar/ic_sysbar_back.png")),
        )
        val t = compile(baseSpec.copy(simplePackId = "icons-1"), packs).target(sysui)!!
        val back = t.drawables.first { it.resourceName.endsWith("ic_sysbar_back") }
        assertTrue(back.ninePatch)
    }

    @Test
    fun `missing packs are reported`() {
        val result = compile(baseSpec.copy(simplePackId = "nope"))
        val t = result.target(settings)!!
        assertEquals(ThemeCompiler.REASON_PACK_MISSING, t.reason("nope"))
        assertEquals(1, t.skipped.count { it.name == "nope" })
        assertTrue(result.events.any { it.message == "pack not available" })
    }

    @Test
    fun `content hash is stable and content sensitive`() {
        val spec = baseSpec.copy(simplePackId = "icons-1")
        val packs = InMemoryPackResolver(listOf(iconPack))
        val a = compile(spec, packs)
        val b = compile(spec, packs)
        assertEquals(a.targets.map { it.contentHash }, b.targets.map { it.contentHash })
        assertEquals(64, a.targets.first().contentHash.length)
        val c = compile(spec.copy(source = ThemeSource(0xFFE91E63.toInt())), packs)
        assertNotEquals(a.target(settings)!!.contentHash, c.target(settings)!!.contentHash)
        val t = a.target(sysui)!!
        assertEquals(t.contentHash, ThemeCompiler.contentHash(t.colors.reversed(), t.drawables.reversed()))
        assertNotEquals(t.contentHash, ThemeCompiler.contentHash(t.colors.drop(1), t.drawables))
    }

    @Test
    fun `semwt coverage counts distinct overridden resources`() {
        // qs_tile_bg (2 configs) and notification_bg are planned and in SemWT.
        assertEquals(2, compile().target(sysui)!!.semwtCoverage)
        assertEquals(1, compile().target(settings)!!.semwtCoverage)
    }

    @Test
    fun `overlay names and config helpers`() {
        assertEquals("HexNext_android", ThemeCompiler.overlayName("android"))
        assertEquals("night-xxhdpi", ThemeCompiler.configFor(true, "xxhdpi"))
        assertEquals("night", ThemeCompiler.configFor(true, ""))
        assertEquals("", ThemeCompiler.configFor(false, ""))
        assertTrue(ThemeCompiler.isNight("night-xxhdpi-v26"))
        assertFalse(ThemeCompiler.isNight("notnight"))
    }

    @Test
    fun `targets missing from the rules are still compiled after the rule targets`() {
        val extra = "com.example.extra"
        val dev = device(mapOf(extra to TargetSnapshot(extra, installed = true, table = table(extra, listOf("x")))))
        assertEquals(listOf(sysui, settings, extra), compile(dev = dev).targets.map { it.packageName })
    }

    @Test
    fun `skipped entries are distinct`() {
        val t = compile().target(sysui)!!
        assertEquals(t.skipped, t.skipped.distinct())
        assertTrue(Skipped("$sysui:color/qs_removed_in_9", ResourceMapper.REASON_NOT_IN_TARGET) in t.skipped)
    }
}
