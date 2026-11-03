package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.layer
import com.hexnext.engine.compile.Fixtures.pack
import com.hexnext.engine.compile.Fixtures.table
import com.hexnext.engine.mcu.hct.Hct
import com.hexnext.engine.pack.Tint
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [IconColorStyle] end to end through [ThemeCompiler.compile]. */
class IconStyleCompileTest {
    private val settings = "com.android.settings"
    private val myFiles = "com.sec.android.app.myfiles"
    private val sysui = "com.android.systemui"
    private val rules = OneUiRules.load(9)

    private val packAccent = 0xFF123456.toInt()
    private val packBase = 0xFF0A0A0A.toInt()

    private val settingsTable = table(
        settings, listOf("dashboard_tint"),
        mapOf(
            "sec_ic_settings_display" to listOf(480, 640),
            "sec_ic_settings_connections" to listOf(480, 640),
            "sec_ic_settings_battery" to listOf(480, 640),
            "sec_ic_settings_sound" to listOf(480),
            "sec_ic_settings_tips" to listOf(0),
            "other_drawable" to listOf(0),
        ),
    )
    private val myFilesTable = table(
        myFiles, emptyList(),
        mapOf(
            "home_category_image" to listOf(480),
            "home_category_video" to listOf(480),
            "home_category_compressed" to listOf(480),
            "file_type_pdf" to listOf(480),
        ),
    )
    private val sysuiTable = table(
        sysui, listOf("qs_tile_bg"),
        mapOf("sec_ic_settings_display" to listOf(480), "home_category_image" to listOf(480), "qs_tile_shape" to listOf(480)),
    )

    private val iconPack = pack(
        "icons-1",
        listOf(
            layer("settingsicon", "base", Tint(Tint.SLOT, slot = "xx0"), listOf("sec_ic_settings_display", "sec_ic_settings_connections")),
            layer("settingsicon", "first", Tint(Tint.SLOT, slot = "xx1"), listOf("sec_ic_settings_display", "sec_ic_settings_connections")),
            layer("settingsicon", "second", Tint(Tint.FIXED, argb = "#FFFFFFFF"), listOf("sec_ic_settings_display", "sec_ic_settings_connections")),
        ),
        palette = mapOf("xx0" to "#FF0A0A0A", "xx1" to "#FF123456"),
    )
    private val packs = InMemoryPackResolver(listOf(iconPack))

    private val baseSpec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()), simplePackId = "icons-1")

    private fun device() = DeviceSnapshot(
        Fixtures.profile9,
        mapOf(
            settings to TargetSnapshot(settings, installed = true, table = settingsTable),
            myFiles to TargetSnapshot(myFiles, installed = true, table = myFilesTable),
            sysui to TargetSnapshot(sysui, installed = true, table = sysuiTable),
        ),
        Fixtures.reference,
    )

    private fun compile(spec: ThemeSpec = baseSpec, packResolver: PackResolver = packs) =
        ThemeCompiler.compile(spec, device(), packResolver, rules) { 1L }

    private fun hue(argb: Int) = Hct.fromInt(argb).hue

    private fun packJobs(t: CompiledTarget) = t.drawables.filter { it.custom == null && it.synthesis == null && it.badge == null }

    @Test
    fun `the default style keeps the pack's own tints`() {
        assertEquals(IconColorStyle.PACK_ORIGINAL, baseSpec.iconStyle)
        val t = compile().target(settings)!!
        val jobs = packJobs(t)
        assertEquals(8, jobs.size)
        for (job in jobs) {
            assertEquals(listOf(packBase, packAccent, 0xFFFFFFFF.toInt()), job.plan.steps.map { it.tintArgb }, job.toString())
            assertTrue(job.plan.steps.all { it.gradientEndArgb == null })
        }
        // A pack is chosen, so no built-in badges mix with the pack art.
        assertTrue(t.drawables.none { it.badge != null })
        // Synthesised references carry the pack look too.
        val synth = t.drawables.filter { it.synthesis != null }
        assertTrue(synth.isNotEmpty())
        assertTrue(synth.all { it.synthesis!!.reference.plan.steps[1].tintArgb == packAccent })
    }

    @Test
    fun `accent style tints from the theme instead`() {
        val t = compile(baseSpec.copy(iconStyle = IconColorStyle.ACCENT)).target(settings)!!
        val derived = SlotPalette.derive(com.hexnext.engine.palette.ThemeResolver.resolve(baseSpec.source))
        for (job in packJobs(t)) {
            val night = ThemeCompiler.isNight(job.configuration)
            assertEquals(derived.getValue(if (night) "xx1" else "lxx1"), job.plan.steps[1].tintArgb)
        }
    }

    @Test
    fun `multicolour gives rows distinct hues and changes the hash`() {
        val plain = compile().target(settings)!!
        val multi = compile(baseSpec.copy(iconStyle = IconColorStyle.MULTICOLOR)).target(settings)!!
        assertNotEquals(plain.contentHash, multi.contentHash)

        val jobs = packJobs(multi)
        fun accentOf(row: String, config: String) =
            jobs.first { it.resourceName == "$settings:drawable/sec_ic_settings_$row" && it.configuration == config }.plan.steps[1].tintArgb!!
        for (config in listOf("xxhdpi", "night-xxhdpi")) {
            val display = accentOf("display", config)
            val connections = accentOf("connections", config)
            assertNotEquals(display, connections)
            assertTrue(IconColors.hueDistance(hue(display), IconColors.rowHue("display")) < 6.0)
            assertTrue(IconColors.hueDistance(hue(connections), IconColors.rowHue("connections")) < 6.0)
        }
        // Neutral and fixed layers keep the pack design.
        assertTrue(jobs.all { it.plan.steps[0].tintArgb != packAccent && it.plan.steps[2].tintArgb == 0xFFFFFFFF.toInt() })

        // Synthesised rows each get their own reference colour.
        val synth = multi.drawables.filter { it.synthesis != null && it.configuration == "night-xxhdpi" }
        val byRow = synth.associate { it.synthesis!!.glyph to it.synthesis!!.reference.plan.steps[1].tintArgb!! }
        assertEquals(setOf("battery", "sound", "tips"), byRow.keys)
        assertEquals(byRow.size, byRow.values.distinct().size, byRow.toString())
        for ((row, argb) in byRow) assertTrue(IconColors.hueDistance(hue(argb), IconColors.rowHue(row)) < 6.0, "$row: ${hue(argb)}")
        // The reference stays the display icon, only its colours change.
        assertTrue(synth.all { it.synthesis!!.reference.resourceName == "$settings:drawable/sec_ic_settings_display" })
    }

    @Test
    fun `gradient sets gradient ends that change the hash`() {
        val multi = compile(baseSpec.copy(iconStyle = IconColorStyle.MULTICOLOR)).target(settings)!!
        val gradient = compile(baseSpec.copy(iconStyle = IconColorStyle.GRADIENT)).target(settings)!!
        assertNotEquals(multi.contentHash, gradient.contentHash)
        assertTrue(packJobs(gradient).all { it.plan.steps[1].gradientEndArgb != null && it.plan.steps[0].gradientEndArgb == null })
    }

    @Test
    fun `built-in icons cover dashboard rows and my files categories when there is no pack`() {
        val result = compile(baseSpec.copy(simplePackId = null))
        val s = result.target(settings)!!
        assertTrue(s.drawables.all { it.badge != null }, s.drawables.toString())
        assertEquals(
            setOf("display", "connections", "battery", "sound", "tips"),
            s.drawables.map { it.badge!!.glyph }.toSet(),
        )
        assertEquals(setOf("xxhdpi", "xxxhdpi", "night-xxhdpi", "night-xxxhdpi"), s.drawables.filter { it.resourceName.endsWith("display") }.map { it.configuration }.toSet())
        assertEquals(setOf("xxhdpi", "night-xxhdpi"), s.drawables.filter { it.resourceName.endsWith("sound") }.map { it.configuration }.toSet())
        assertEquals(2, s.drawables.count { it.resourceName.endsWith("tips") })
        for (job in s.drawables) {
            assertTrue(job.plan.isEmpty)
            assertEquals("settings_icons", job.elementId)
            assertEquals(IconColors.BUILT_IN_ID, job.source.packId)
            assertEquals(job.resourceName.substringAfter('/'), job.source.resourceName)
            assertNull(job.custom)
            assertNull(job.synthesis)
            assertEquals(0xFFFFFFFF.toInt(), job.badge!!.glyphArgb)
        }
        // Colourful: every row its own fill.
        val fills = s.drawables.filter { it.configuration == "night-xxhdpi" }.associate { it.badge!!.glyph to it.badge!!.fillArgb }
        assertEquals(fills.size, fills.values.distinct().size, fills.toString())

        val m = result.target(myFiles)!!
        assertEquals(setOf("myfiles_images", "myfiles_videos"), m.drawables.map { it.badge?.glyph }.toSet())
        assertTrue(m.drawables.all { it.elementId == "my_files_icons" })
        assertEquals(4, m.drawables.size)

        // Never for System UI, even when its table has the same names.
        assertTrue(result.target(sysui)!!.drawables.isEmpty())
        assertTrue(IconColors.builtInJobs(baseSpec.copy(simplePackId = null), sysui, sysuiTable, rules, emptySet(), emptySet(), com.hexnext.engine.palette.ThemeResolver.resolve(baseSpec.source)).isEmpty())
    }

    @Test
    fun `built-in icons respect the switch, disabled elements, custom icons and styles`() {
        val noPack = baseSpec.copy(simplePackId = null)
        assertTrue(compile(noPack.copy(builtInIcons = false)).targets.all { t -> t.drawables.none { it.badge != null } })
        assertTrue(compile(noPack.copy(elementEnabled = mapOf("settings_icons" to false))).target(settings)!!.drawables.none { it.badge != null })

        val key = "$settings:drawable/sec_ic_settings_display"
        val custom = compile(noPack.copy(customIcons = mapOf(key to "mine-00000001.png"))).target(settings)!!
        assertTrue(custom.drawables.filter { it.resourceName == key }.all { it.custom != null && it.badge == null })
        assertTrue(custom.drawables.any { it.badge != null })

        val accent = compile(noPack.copy(iconStyle = IconColorStyle.ACCENT)).target(settings)!!
        assertEquals(1, accent.drawables.filter { it.configuration == "night-xxhdpi" }.map { it.badge!!.fillArgb }.distinct().size)
        val gradient = compile(noPack.copy(iconStyle = IconColorStyle.GRADIENT)).target(settings)!!
        assertTrue(gradient.drawables.all { it.badge!!.fillEndArgb != null })
        val multi = compile(noPack.copy(iconStyle = IconColorStyle.MULTICOLOR)).target(settings)!!
        val original = compile(noPack).target(settings)!!
        assertEquals(multi.contentHash, original.contentHash)
        assertNotEquals(multi.contentHash, gradient.contentHash)
        assertNotEquals(multi.contentHash, accent.contentHash)
        val duo = compile(noPack.copy(iconStyle = IconColorStyle.DUOTONE)).target(settings)!!
        assertNotNull(duo.drawables.firstOrNull()?.badge)
        assertTrue(duo.drawables.all { it.badge!!.glyphArgb != 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `a missing pack for my files does not block settings packs and vice versa`() {
        // Advanced mode: Settings has a pack, My Files has none, so only My Files gets built-ins.
        val spec = baseSpec.copy(mode = ThemeMode.ADVANCED, simplePackId = null, elementPacks = mapOf("settings_icons" to "icons-1"))
        val result = compile(spec)
        assertTrue(result.target(settings)!!.drawables.none { it.badge != null })
        assertTrue(result.target(myFiles)!!.drawables.isNotEmpty())
        assertTrue(result.target(myFiles)!!.drawables.all { it.badge != null })
    }
}
