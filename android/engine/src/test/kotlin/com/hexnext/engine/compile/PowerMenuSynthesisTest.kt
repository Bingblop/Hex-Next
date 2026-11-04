package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.layer
import com.hexnext.engine.compile.Fixtures.pack
import com.hexnext.engine.compile.Fixtures.table
import com.hexnext.engine.pack.Tint
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Power menu buttons a pack has no art for are drawn in its style from its Power off icon. */
class PowerMenuSynthesisTest {
    private val sysui = "com.android.systemui"
    private val rules = OneUiRules.load(9)
    private val sysuiTable = table(
        sysui, emptyList(),
        mapOf(
            "tw_ic_do_poweroff" to listOf(480),
            "tw_ic_do_restart" to listOf(480),
            "tw_ic_do_emergencysos" to listOf(480),
            "tw_ic_do_medicalinfo" to listOf(480),
        ),
    )

    private fun compile(packIcons: List<String>, synthesize: Boolean = true): List<PlannedDrawableJob> {
        val p = pack("power-1", listOf(layer("powermenu", "base", Tint(Tint.SLOT, slot = "xx1"), packIcons)), palette = mapOf("xx1" to "#FF00A0B0"))
        val spec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()), simplePackId = "power-1", synthesizeMissingIcons = synthesize)
        val device = DeviceSnapshot(Fixtures.profile9, mapOf(sysui to TargetSnapshot(sysui, installed = true, table = sysuiTable)), Fixtures.reference)
        return ThemeCompiler.compile(spec, device, InMemoryPackResolver(listOf(p)), rules) { 1L }.target(sysui)?.drawables.orEmpty()
    }

    @Test
    fun `missing Emergency call and Medical info art is synthesised from Power off`() {
        val jobs = compile(listOf("tw_ic_do_poweroff", "tw_ic_do_restart"))
        val synthesised = jobs.filter { it.synthesis != null }
        assertTrue(synthesised.isNotEmpty(), jobs.map { it.resourceName }.toString())
        assertEquals(setOf("tw_ic_do_emergencysos", "tw_ic_do_medicalinfo"), synthesised.map { it.resourceName.substringAfter('/') }.toSet())
        for (job in synthesised) {
            assertEquals("$sysui:drawable/tw_ic_do_poweroff", job.synthesis!!.reference.resourceName)
            assertEquals(job.configuration, job.synthesis!!.reference.configuration)
        }
        assertEquals(setOf("emergency_sos", "medical_info"), synthesised.map { it.synthesis!!.glyph }.toSet())
    }

    @Test
    fun `pack art wins, Restart stands in for Power off, and the switch turns it off`() {
        val full = compile(listOf("tw_ic_do_poweroff", "tw_ic_do_restart", "tw_ic_do_emergencysos", "tw_ic_do_medicalinfo"))
        assertTrue(full.none { it.synthesis != null })
        val restartOnly = compile(listOf("tw_ic_do_restart")).filter { it.synthesis != null }
        assertTrue(restartOnly.isNotEmpty())
        assertTrue(restartOnly.all { it.synthesis!!.reference.resourceName == "$sysui:drawable/tw_ic_do_restart" })
        assertTrue(compile(listOf("tw_ic_do_poweroff"), synthesize = false).none { it.synthesis != null })
        assertTrue(compile(emptyList()).none { it.synthesis != null })
    }
}
