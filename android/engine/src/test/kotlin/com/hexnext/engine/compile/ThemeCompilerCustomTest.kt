package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.layer
import com.hexnext.engine.compile.Fixtures.pack
import com.hexnext.engine.compile.Fixtures.table
import com.hexnext.engine.map.Skipped
import com.hexnext.engine.pack.Tint
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeCompilerCustomTest {
    private val settings = "com.android.settings"
    private val myFiles = "com.sec.android.app.myfiles"
    private val rules = OneUiRules.load(9)

    /** A One UI 9 Settings table: five dashboard rows, `display`/`connections`/`battery` with two densities, `tips`/`modes` default only. */
    private val settingsTable = table(
        settings, listOf("dashboard_tint"),
        mapOf(
            "sec_ic_settings_display" to listOf(480, 640),
            "sec_ic_settings_connections" to listOf(480, 640),
            "sec_ic_settings_battery" to listOf(480, 640),
            "sec_ic_settings_tips" to listOf(0),
            "sec_ic_settings_modes" to listOf(0),
            "sec_bg_settings" to listOf(480),
            "other_drawable" to listOf(0),
        ),
    )
    private val myFilesTable = table(myFiles, emptyList(), mapOf("home_category_images" to listOf(480, 640)))

    private val iconPack = pack(
        "icons-1",
        listOf(
            layer("settingsicon", "base", Tint(Tint.SLOT, slot = "xx1"), listOf("sec_ic_settings_display", "sec_ic_settings_connections")),
            layer("settingsicon_night", "base", Tint(Tint.SLOT, slot = "xx3"), listOf("sec_ic_settings_display")),
        ),
        palette = mapOf("xx1" to "#FF123456"),
    )
    private val packs = InMemoryPackResolver(listOf(iconPack))

    private val baseSpec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()), simplePackId = "icons-1")

    private fun device() = DeviceSnapshot(
        Fixtures.profile9,
        mapOf(
            settings to TargetSnapshot(settings, installed = true, table = settingsTable),
            myFiles to TargetSnapshot(myFiles, installed = true, table = myFilesTable),
        ),
        Fixtures.reference,
    )

    private fun compile(spec: ThemeSpec = baseSpec, packResolver: PackResolver = packs) =
        ThemeCompiler.compile(spec, device(), packResolver, rules) { 1L }

    private fun List<PlannedDrawableJob>.named(name: String) = filter { it.resourceName.substringAfter('/') == name }

    private val display = "$settings:drawable/sec_ic_settings_display"

    @Test
    fun `pack jobs cover the expected configurations before any override`() {
        val t = compile(baseSpec.copy(synthesizeMissingIcons = false)).target(settings)!!
        assertEquals(
            setOf("xxhdpi", "xxxhdpi", "night-xxhdpi", "night-xxxhdpi"),
            t.drawables.named("sec_ic_settings_display").map { it.configuration }.toSet(),
        )
        assertEquals(4, t.drawables.named("sec_ic_settings_connections").size)
        assertEquals(8, t.drawables.size)
    }

    @Test
    fun `custom icon replaces pack jobs and uses their configurations`() {
        val spec = baseSpec.copy(synthesizeMissingIcons = false, customIcons = mapOf(display to "mine-0a1b2c3d.png"))
        val result = compile(spec)
        val t = result.target(settings)!!
        val jobs = t.drawables.named("sec_ic_settings_display")
        assertEquals(setOf("xxhdpi", "xxxhdpi", "night-xxhdpi", "night-xxxhdpi"), jobs.map { it.configuration }.toSet())
        for (job in jobs) {
            assertEquals("mine-0a1b2c3d.png", job.custom)
            assertNull(job.synthesis)
            assertTrue(job.plan.isEmpty)
            assertFalse(job.ninePatch)
            assertEquals(CustomAssets.PACK_ID, job.source.packId)
            assertEquals(CustomAssets.CATEGORY_ICON, job.source.category)
            assertEquals("mine-0a1b2c3d.png", job.source.resourceName)
            assertEquals("settings_icons", job.elementId)
        }
        // The other pack icon is untouched.
        assertTrue(t.drawables.named("sec_ic_settings_connections").all { it.custom == null && it.source.packId == "icons-1" })
        assertEquals(8, t.drawables.size)
        assertTrue(result.events.any { it.target == settings && it.message == "custom drawable" && it.data["key"] == display })
    }

    @Test
    fun `custom icon without pack jobs uses the table densities and falls back to the default config`() {
        val spec = baseSpec.copy(
            synthesizeMissingIcons = false,
            customIcons = mapOf(
                "$settings:drawable/sec_ic_settings_battery" to "bat-00000001.png",
                "$settings:drawable/sec_ic_settings_tips" to "tips-00000002.png",
                "$settings:drawable/other_drawable" to "oth-00000003.png",
                "$myFiles:drawable/home_category_images" to "img-00000004.png",
            ),
        )
        val result = compile(spec)
        val t = result.target(settings)!!
        assertEquals(setOf("xxhdpi", "xxxhdpi"), t.drawables.named("sec_ic_settings_battery").map { it.configuration }.toSet())
        assertEquals("settings_icons", t.drawables.named("sec_ic_settings_battery").first().elementId)
        assertEquals(listOf(""), t.drawables.named("sec_ic_settings_tips").map { it.configuration })
        assertEquals("system_drawables", t.drawables.named("other_drawable").single().elementId)

        val m = result.target(myFiles)!!
        assertEquals(setOf("xxhdpi", "xxxhdpi"), m.drawables.map { it.configuration }.toSet())
        assertTrue(m.drawables.all { it.elementId == "my_files_icons" && it.custom == "img-00000004.png" })
    }

    @Test
    fun `custom background gets the backgrounds element and keys of other targets are ignored`() {
        val spec = baseSpec.copy(
            synthesizeMissingIcons = false,
            customBackgrounds = mapOf(
                "$settings:drawable/sec_bg_settings" to "wall-11223344.png",
                "android:drawable/sec_bg_settings" to "wall-11223344.png",
            ),
        )
        val t = compile(spec).target(settings)!!
        val bg = t.drawables.named("sec_bg_settings").single()
        assertEquals("xxhdpi", bg.configuration)
        assertEquals("backgrounds", bg.elementId)
        assertEquals(CustomAssets.CATEGORY_BACKGROUND, bg.source.category)
        assertEquals("wall-11223344.png", bg.custom)
        assertTrue(t.skipped.none { "android:" in it.name })
    }

    @Test
    fun `custom icon for a resource the target lacks is skipped`() {
        val key = "$settings:drawable/sec_ic_settings_not_there"
        val spec = baseSpec.copy(synthesizeMissingIcons = false, customIcons = mapOf(key to "x-00000000.png"))
        val result = compile(spec)
        val t = result.target(settings)!!
        assertTrue(Skipped(key, ThemeCompiler.REASON_NOT_IN_TARGET) in t.skipped)
        assertTrue(t.drawables.none { it.custom != null })
        assertTrue(result.events.any { it.message == "custom drawable not in target" && it.data["key"] == key })
    }

    @Test
    fun `synthesis fills only missing rows in the configurations the pack covers`() {
        val result = compile()
        val t = result.target(settings)!!
        val synthesised = t.drawables.filter { it.synthesis != null }
        // battery exists in the table and has no pack job; tips/modes exist too; display/connections are covered.
        assertEquals(setOf("sec_ic_settings_battery", "sec_ic_settings_tips", "sec_ic_settings_modes"), synthesised.map { it.resourceName.substringAfter('/') }.toSet())
        // Only the four configurations the pack produced, never the table's own "".
        assertEquals(setOf("xxhdpi", "xxxhdpi", "night-xxhdpi", "night-xxxhdpi"), synthesised.map { it.configuration }.toSet())
        assertEquals(12, synthesised.size)
        for (job in synthesised) {
            val s = job.synthesis!!
            assertEquals(job.resourceName.substringAfter("sec_ic_settings_"), s.glyph)
            assertEquals(display, s.reference.resourceName)
            assertEquals(job.configuration, s.reference.configuration)
            assertNull(s.reference.synthesis)
            assertTrue(job.plan.isEmpty)
            assertFalse(job.ninePatch)
            assertEquals("icons-1", job.source.packId)
            assertEquals(s.reference.source.category, job.source.category)
            assertEquals(job.resourceName.substringAfter('/'), job.source.resourceName)
            assertEquals("settings_icons", job.elementId)
            assertNull(job.custom)
        }
        // Rows missing from the table (e.g. galaxy_ai) are not planned.
        assertTrue(t.drawables.none { it.resourceName.endsWith("galaxy_ai") })
        assertEquals(8 + 12, t.drawables.size)
        assertTrue(result.events.any { it.message == "icon synthesised" && it.data["row"] == "battery" && it.data["reference"] == display })
    }

    @Test
    fun `synthesis prefers connections when display is missing and skips custom overrides`() {
        val noDisplay = pack("icons-2", listOf(layer("settingsicon", "base", Tint(Tint.SLOT, slot = "xx1"), listOf("sec_ic_settings_connections"))))
        val spec = baseSpec.copy(simplePackId = "icons-2", customIcons = mapOf("$settings:drawable/sec_ic_settings_battery" to "bat-00000001.png"))
        val t = compile(spec, InMemoryPackResolver(listOf(noDisplay))).target(settings)!!
        val synthesised = t.drawables.filter { it.synthesis != null }
        assertTrue(synthesised.isNotEmpty())
        assertTrue(synthesised.all { it.synthesis!!.reference.resourceName == "$settings:drawable/sec_ic_settings_connections" })
        // display is synthesised (pack lacks it); battery is the custom icon, never synthesised.
        assertTrue(synthesised.any { it.resourceName == display })
        assertTrue(t.drawables.named("sec_ic_settings_battery").all { it.custom != null })
    }

    @Test
    fun `synthesis is off when disabled, when the element is off, and without a pack`() {
        assertTrue(compile(baseSpec.copy(synthesizeMissingIcons = false)).target(settings)!!.drawables.none { it.synthesis != null })
        assertTrue(compile(baseSpec.copy(elementEnabled = mapOf("settings_icons" to false))).target(settings)!!.drawables.none { it.synthesis != null })
        assertTrue(compile(baseSpec.copy(simplePackId = null)).target(settings)!!.drawables.none { it.synthesis != null })
        assertTrue(compile(baseSpec.copy(simplePackId = "missing")).target(settings)!!.drawables.none { it.synthesis != null })
        // My Files never synthesises.
        assertTrue(compile().target(myFiles)!!.drawables.isEmpty())
    }

    @Test
    fun `content hash changes with the custom file and the glyph`() {
        val a = compile(baseSpec.copy(customIcons = mapOf(display to "a-00000000.png"))).target(settings)!!
        val b = compile(baseSpec.copy(customIcons = mapOf(display to "b-00000000.png"))).target(settings)!!
        assertNotEquals(a.contentHash, b.contentHash)
        assertEquals(a.contentHash, compile(baseSpec.copy(customIcons = mapOf(display to "a-00000000.png"))).target(settings)!!.contentHash)

        val plain = compile().target(settings)!!
        val off = compile(baseSpec.copy(synthesizeMissingIcons = false)).target(settings)!!
        assertNotEquals(plain.contentHash, off.contentHash)
        val job = plain.drawables.first { it.synthesis != null }
        val renamedGlyph = plain.drawables.map { if (it === job) it.copy(synthesis = it.synthesis!!.copy(glyph = "other")) else it }
        assertNotEquals(plain.contentHash, ThemeCompiler.contentHash(plain.colors, renamedGlyph))
        // Changing the reference's layers changes the hash too.
        val retinted = plain.drawables.map {
            if (it === job) it.copy(synthesis = it.synthesis!!.copy(reference = it.synthesis!!.reference.copy(plan = com.hexnext.engine.pack.CompositePlan(emptyList())))) else it
        }
        assertNotEquals(plain.contentHash, ThemeCompiler.contentHash(plain.colors, retinted))
        assertEquals(plain.contentHash, ThemeCompiler.contentHash(plain.colors, plain.drawables.reversed()))
    }

    @Test
    fun `old theme JSON without the new fields still parses`() {
        val old = """{"id":"t","name":"T","source":{"seedArgb":-16776961},"simplePackId":"icons-1","format":1}"""
        val spec = ThemeSpec.fromJson(old)
        assertEquals(emptyMap(), spec.customIcons)
        assertEquals(emptyMap(), spec.customBackgrounds)
        assertNull(spec.customFont)
        assertTrue(spec.synthesizeMissingIcons)
        assertEquals(1, spec.format)
        val roundTrip = ThemeSpec.fromJson(spec.copy(customFont = CustomFont("r.ttf", null, "R")).toJson())
        assertEquals(CustomFont("r.ttf", null, "R"), roundTrip.customFont)
    }
}
