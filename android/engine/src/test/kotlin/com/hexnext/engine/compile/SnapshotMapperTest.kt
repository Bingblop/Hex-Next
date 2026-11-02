package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.ref
import com.hexnext.engine.device.OneUi9Facts
import com.hexnext.engine.device.OverlayDump
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotMapperTest {
    private val settingsDump: OverlayDump = OverlayDump.parse(
        checkNotNull(javaClass.getResourceAsStream("/overlay_dump_settings_oneui9.txt")) { "fixture missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) },
    )!!

    private fun hex(argb: Int) = "0x%08x".format(argb)

    /** A SemWT dump whose colours are shades of the scan-time reference palette. */
    private fun syntheticDump(pkg: String, state: String = "STATE_ENABLED"): OverlayDump = OverlayDump.parse(
        """
        android:SemWT_$pkg:0 {
          mPackageName...........: android
          mOverlayName...........: SemWT_$pkg
          mTargetPackageName.....: $pkg
          mTargetOverlayableName.: 
          mState.................: $state
          mIsEnabled.............: true
          mPriority..............: 2147483647
          mIsFabricated..........: true
        }
        IDMAP OF android:SemWT_$pkg
        Mapping:
            0x7f060001 -> color ${hex(ref("system_accent1_600"))}    0x7f060001 -> color ${hex(ref("system_accent1_200"))} (color/accent_text)
            0x7f060002 -> color ${hex(ref("system_neutral1_50"))} (color/background)
            0x7f050003 -> dimension 0x00001801 (dimen/corner)
        """.trimIndent(),
    )!!

    @Test
    fun `semwt overlay is rebuilt from the dump with night values`() {
        val semwt = SnapshotMapper.semwtFromDump(settingsDump)
        assertEquals("com.android.settings", semwt.targetPackage)
        assertEquals("SemWT_com.android.settings", semwt.name)
        assertEquals("android", semwt.packageName)
        val colours = settingsDump.entries.count { it.defaultColor != null } + settingsDump.entries.count { it.nightColor != null }
        assertEquals(colours, semwt.entries.size)
        assertTrue(semwt.entries.all { it.dataType == SnapshotMapper.TYPE_INT_COLOR_ARGB8 })
        val day = semwt.entries.first { it.resourceName == "com.android.settings:color/actionbar_text_color" && it.configuration == null }
        val night = semwt.entries.first { it.resourceName == "com.android.settings:color/actionbar_text_color" && it.configuration == "night" }
        assertEquals(0xff0b4d5f.toInt(), day.data)
        assertEquals(0xff6ed4ef.toInt(), night.data)
    }

    @Test
    fun `dump table keeps ids and both configurations`() {
        val table = SnapshotMapper.tableFromDump(settingsDump)
        assertEquals("com.android.settings", table.packageName)
        assertEquals(0x7f, table.packageId)
        val entry = assertNotNull(table.find("color/actionbar_text_color"))
        assertEquals(0x7f060020, entry.id)
        assertEquals(listOf("", "night"), entry.values.map { it.config })
        assertEquals(settingsDump.entries.map { it.typeAndName }.distinct().size, table.entries.size)
        assertNull(table.find("color/sec_search_view_hint_text_color"), "names the idmap could not find are not in the table")
    }

    @Test
    fun `dump only targets compile colour only themes without packs`() {
        val pkg = "com.sec.android.app.myfiles"
        val snapshot = SnapshotMapper.snapshot(
            Fixtures.profile9,
            listOf(TargetScan(pkg, installed = true, dump = syntheticDump(pkg))),
            Fixtures.reference,
        )
        val target = snapshot.targets.getValue(pkg)
        assertEquals(TargetAvailability.AVAILABLE, SnapshotMapper.availability(target))
        val spec = ThemeSpec.default().copy(simplePackId = "not-installed")
        val compiled = ThemeCompiler.compile(spec, snapshot, InMemoryPackResolver(emptyList()), clock = { 1L })
        val out = assertNotNull(compiled.target(pkg))
        assertEquals("HexNext_com_sec_android_app_myfiles", out.overlayName)
        assertTrue(out.drawables.isEmpty())
        val configs = out.colors.filter { it.resourceName == "$pkg:color/accent_text" }.map { it.configuration }.sorted()
        assertEquals(listOf("", "night"), configs)
        assertTrue(out.colors.any { it.resourceName == "$pkg:color/background" && it.configuration == "" })
        assertFalse(out.colors.any { it.resourceName.contains("dimen/") })
    }

    @Test
    fun `debloated targets are marked and skipped`() {
        val scans = listOf(
            TargetScan("com.samsung.android.app.notes", installed = false, debloated = true),
            TargetScan("com.samsung.android.calendar", installed = false, dump = syntheticDump("com.samsung.android.calendar", OneUi9Facts.STATE_MISSING_TARGET)),
            TargetScan("com.sec.android.app.clockpackage", installed = false),
            TargetScan("com.sec.android.app.popupcalculator", installed = true),
        )
        val snapshot = SnapshotMapper.snapshot(Fixtures.profile9, scans, Fixtures.reference)
        val notes = snapshot.targets.getValue("com.samsung.android.app.notes")
        assertTrue(!notes.installed && notes.debloated)
        assertEquals(TargetAvailability.DEBLOATED, SnapshotMapper.availability(notes))
        assertEquals(TargetAvailability.DEBLOATED, SnapshotMapper.availability(snapshot.targets.getValue("com.samsung.android.calendar")))
        assertEquals(TargetAvailability.NOT_INSTALLED, SnapshotMapper.availability(snapshot.targets.getValue("com.sec.android.app.clockpackage")))
        assertEquals(TargetAvailability.NO_RESOURCES, SnapshotMapper.availability(snapshot.targets.getValue("com.sec.android.app.popupcalculator")))

        val compiled = ThemeCompiler.compile(ThemeSpec.default(), snapshot, InMemoryPackResolver(emptyList()), clock = { 1L })
        assertTrue(compiled.targets.isEmpty())
        val outcomes = ApplyReport.skippedOutcomes(compiled).associateBy { it.target }
        assertEquals(TargetResult.SKIPPED_DEBLOATED, outcomes.getValue("com.samsung.android.app.notes").result)
        assertEquals(TargetResult.SKIPPED_DEBLOATED, outcomes.getValue("com.samsung.android.calendar").result)
        assertEquals(TargetResult.SKIPPED, outcomes.getValue("com.sec.android.app.clockpackage").result)
        assertEquals(ThemeCompiler.REASON_NO_TABLE, outcomes.getValue("com.sec.android.app.popupcalculator").message)
    }

    @Test
    fun `installed wins over a stale missing target state`() {
        val scan = TargetScan("com.android.settings", installed = true, semwtState = OneUi9Facts.STATE_MISSING_TARGET)
        assertFalse(SnapshotMapper.isDebloated(scan))
    }

    @Test
    fun `arsc table is extended by dump names it lacks`() {
        val pkg = "com.sec.android.app.myfiles"
        val arsc = Fixtures.table(pkg, listOf("accent_text", "only_in_arsc"))
        val snapshot = SnapshotMapper.target(TargetScan(pkg, installed = true, table = arsc, dump = syntheticDump(pkg)))
        val table = assertNotNull(snapshot.table)
        assertNotNull(table.find("color/only_in_arsc"))
        assertNotNull(table.find("color/background"))
        assertNotNull(table.find("dimen/corner"))
        assertEquals(arsc.find("color/accent_text"), table.find("color/accent_text"), "arsc entries win")
        assertNotNull(snapshot.semwt)
    }

    @Test
    fun `a table of another package is ignored`() {
        val pkg = "com.sec.android.app.myfiles"
        val other = Fixtures.table("com.other", listOf("x"))
        val snapshot = SnapshotMapper.target(TargetScan(pkg, installed = true, table = other))
        assertNull(snapshot.table)
    }

    @Test
    fun `frro fallback gives semwt and table when the dump has no mapping`() {
        val pkg = "com.android.settings"
        val frro = Fixtures.semwt(pkg, listOf(Triple("accent_text", ref("system_accent1_600"), ""), Triple("accent_text", ref("system_accent1_200"), "night")))
        val emptyDump = settingsDump.copy(entries = emptyList())
        val snapshot = SnapshotMapper.target(TargetScan(pkg, installed = true, dump = emptyDump, semwtFrro = frro))
        assertEquals(frro, snapshot.semwt)
        val entry = assertNotNull(snapshot.table?.find("color/accent_text"))
        assertEquals(listOf("", "night"), entry.values.map { it.config })
    }

    @Test
    fun `real SM-S948U1 scan marks removed apps with a missing-target SemWT as debloated`() {
        val report = Json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream("/app_scan_report_sm_s948u1_trimmed.json")) { "fixture missing" }
                .use { it.readBytes().toString(Charsets.UTF_8) },
        ).jsonObject
        fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        val semwtState = report.getValue("semWt").jsonArray.map { it.jsonObject }
            .associate { it.str("target").orEmpty() to it.str("state") }
        val scans = report.getValue("packages").jsonArray.map { it.jsonObject }.map { p ->
            val pkg = p.str("pkg").orEmpty()
            TargetScan(
                packageName = pkg,
                installed = p.getValue("installed").jsonPrimitive.boolean,
                debloated = p.getValue("debloated").jsonPrimitive.boolean,
                semwtState = p.str("semwtState") ?: semwtState[pkg],
            )
        }
        val snapshot = SnapshotMapper.snapshot(Fixtures.profile9, scans, Fixtures.reference)
        val removed = listOf(
            "com.sec.android.app.clockpackage",
            "com.sec.android.app.popupcalculator",
            "com.samsung.android.calendar",
            "com.samsung.android.messaging",
        )
        for (pkg in removed) {
            val t = snapshot.targets.getValue(pkg)
            assertFalse(t.installed, pkg)
            assertTrue(t.debloated, pkg)
            assertEquals(TargetAvailability.DEBLOATED, SnapshotMapper.availability(t), pkg)
        }
        // Notes left no trace in this report (its SemWT dump failed and nothing else listed it):
        // the scanner's pm -u / system APK checks decide; the mapper alone sees "not installed".
        assertEquals(TargetAvailability.NOT_INSTALLED, SnapshotMapper.availability(snapshot.targets.getValue("com.samsung.android.app.notes")))
        assertTrue(snapshot.targets.getValue("com.android.settings").installed)
        assertFalse(snapshot.targets.getValue("com.android.settings").debloated)

        val compiled = ThemeCompiler.compile(ThemeSpec.default(), snapshot, InMemoryPackResolver(emptyList()), clock = { 1L })
        val outcomes = ApplyReport.skippedOutcomes(compiled).associateBy { it.target }
        // Messaging is deprecated on One UI 9, which wins over debloated.
        for (pkg in removed - "com.samsung.android.messaging") {
            assertEquals(TargetResult.SKIPPED_DEBLOATED, outcomes.getValue(pkg).result, pkg)
        }
    }
}
