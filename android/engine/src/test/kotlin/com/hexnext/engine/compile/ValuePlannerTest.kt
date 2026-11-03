package com.hexnext.engine.compile

import com.hexnext.engine.map.PlannedValue
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValuePlannerTest {
    private val sysui = "com.android.systemui"
    private val rules = OneUiRules.load(9).copy(renamed = emptyMap())
    private val tileCorner = "$sysui:dimen/qs_corner_radius"

    private fun dimen(dp: Double, config: String = "") = ScannedValue(config, ValueTypes.TYPE_DIMENSION, Dimens.encode(dp, 1))

    private val values: Map<String, List<ScannedValue>> = mapOf(
        "dimen/qs_corner_radius" to listOf(dimen(28.0), dimen(32.0, "sw600dp-v13")),
        "dimen/rounded_slider_height" to listOf(dimen(48.0)),
        "dimen/notification_corner_radius" to listOf(ScannedValue("", ValueTypes.TYPE_REFERENCE, 0x7f070009, 0x7f070009)),
        "integer/quick_settings_num_columns" to listOf(ScannedValue("", ValueTypes.TYPE_INT_DEC, 4)),
        "bool/config_showActivity" to listOf(ScannedValue("", ValueTypes.TYPE_INT_BOOLEAN, 0)),
    )

    private fun snap(v: Map<String, List<ScannedValue>> = values) =
        TargetSnapshot(sysui, installed = true, table = Fixtures.table(sysui, listOf("qs_tile_round_background_on")), values = v)

    private fun spec(shapes: Map<String, ValueSetting> = emptyMap(), extra: Map<String, ValueSetting> = emptyMap()) =
        ThemeSpec.default().copy(shapes = shapes, values = extra)

    @Test
    fun `every device configuration of a present name is written, absent candidates skipped`() {
        val plan = ValuePlanner.plan(sysui, snap(), spec(mapOf(ShapeControls.TILE_CORNER to ValueSetting.dimen(12.0))), rules)
        assertEquals(listOf("", "sw600dp-v13"), plan.values.map { it.configuration })
        assertTrue(plan.values.all { it.resourceName == tileCorner && it.dataType == ValueTypes.TYPE_DIMENSION })
        assertTrue(plan.values.all { Dimens.sameSize(it.data, Dimens.encode(12.0, 1)) })
        assertEquals(setOf(ShapeControls.TILE_CORNER), plan.controls)
        // The other candidates of the control are absent on this phone: skipped, never written.
        val absent = plan.skipped.filter { it.reason == ValuePlanner.REASON_NOT_IN_TARGET }.map { it.name }
        assertTrue("$sysui:dimen/common_tile_default_active_tile_corner_radius" in absent)
        assertTrue(plan.values.none { it.resourceName in absent })
    }

    @Test
    fun `scale multiplies each configuration's own value, and an unresolved reference is skipped`() {
        val plan = ValuePlanner.plan(
            sysui, snap(),
            spec(mapOf(ShapeControls.TILE_CORNER to ValueSetting.scale(0.5), ShapeControls.NOTIFICATION_CORNER to ValueSetting.scale(2.0))),
            rules,
        )
        val corner = plan.values.filter { it.resourceName == tileCorner }.associate { it.configuration to Dimens.value(it.data) }
        assertEquals(14.0, corner.getValue(""), 1e-6)
        assertEquals(16.0, corner.getValue("sw600dp-v13"), 1e-6)
        assertTrue(plan.skipped.any { it.name == "$sysui:dimen/notification_corner_radius" && it.reason == ValuePlanner.REASON_UNRESOLVED })
    }

    @Test
    fun `values are clamped to the rule's range and stock values dropped`() {
        val clamped = ValuePlanner.plan(sysui, snap(), spec(mapOf(ShapeControls.SLIDER_THICKNESS to ValueSetting.dimen(500.0))), rules)
        assertEquals(1, clamped.clamped)
        assertEquals(64.0, Dimens.value(clamped.values.single().data), 1e-6)
        val stock = ValuePlanner.plan(sysui, snap(), spec(mapOf(ShapeControls.SLIDER_THICKNESS to ValueSetting.dimen(48.0))), rules)
        assertTrue(stock.values.isEmpty())
    }

    @Test
    fun `advanced values need the allowlist and a matching type`() {
        val listed = rules.copy(quickSettings = rules.quickSettings.copy(valueNames = listOf("$sysui:integer/quick_settings_num_columns", "$sysui:bool/config_showActivity")))
        val plan = ValuePlanner.plan(
            sysui, snap(),
            spec(
                extra = mapOf(
                    "$sysui:integer/quick_settings_num_columns" to ValueSetting.integer(5),
                    "$sysui:bool/config_showActivity" to ValueSetting.bool(true),
                    "$sysui:dimen/not_listed" to ValueSetting.dimen(3.0),
                ),
            ),
            listed,
        )
        assertEquals(5, plan.values.single { it.dataType == ValueTypes.TYPE_INT_DEC }.data)
        assertEquals(ValueTypes.BOOL_TRUE, plan.values.single { it.dataType == ValueTypes.TYPE_INT_BOOLEAN }.data)
        assertTrue(plan.skipped.any { it.name == "$sysui:dimen/not_listed" && it.reason == ValuePlanner.REASON_VALUE_POLICY })

        val wrongType = ValuePlanner.plan(
            sysui, snap(),
            spec(extra = mapOf("$sysui:integer/quick_settings_num_columns" to ValueSetting.bool(true))),
            listed,
        )
        assertTrue(wrongType.values.isEmpty())
        assertEquals(ValuePlanner.REASON_VALUE_TYPE, wrongType.skipped.single().reason)
    }

    @Test
    fun `an unknown kind from a newer build is skipped, not fatal`() {
        val plan = ValuePlanner.plan(sysui, snap(), spec(mapOf(ShapeControls.TILE_CORNER to ValueSetting(ValueKind.UNKNOWN, 3.0))), rules)
        assertTrue(plan.values.isEmpty())
        assertTrue(plan.skipped.any { it.reason == ValuePlanner.REASON_VALUE_TYPE })
    }

    @Test
    fun `no value lists in the scan means re-scan, other targets and versions get nothing`() {
        val noScan = ValuePlanner.plan(sysui, snap(emptyMap()), spec(mapOf(ShapeControls.TILE_CORNER to ValueSetting.dimen(10.0))), rules)
        assertTrue(noScan.values.isEmpty())
        assertTrue(noScan.skipped.all { it.reason == ValuePlanner.REASON_RESCAN })
        val settings = TargetSnapshot("com.android.settings", installed = true, values = values)
        assertEquals(ValuePlanner.Plan.EMPTY, ValuePlanner.plan("com.android.settings", settings, spec(mapOf(ShapeControls.TILE_CORNER to ValueSetting.dimen(10.0))), rules))
        assertEquals(ValuePlanner.Plan.EMPTY, ValuePlanner.plan(sysui, snap(), spec(mapOf(ShapeControls.TILE_CORNER to ValueSetting.dimen(10.0))), OneUiRules.load(8)))
    }

    private fun device(v: Map<String, List<ScannedValue>> = values) =
        DeviceSnapshot(Fixtures.profile9, mapOf(sysui to snap(v)), Fixtures.reference)

    @Test
    fun `compiled values go to a separate overlay and never change the colour hash`() {
        val base = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()))
        val packs = InMemoryPackResolver(emptyList())
        val plain = ThemeCompiler.compile(base, device(), packs, rules).target(sysui)!!
        val shaped = ThemeCompiler.compile(base.copy(shapes = mapOf(ShapeControls.TILE_CORNER to ValueSetting.dimen(8.0))), device(), packs, rules)
        val target = shaped.target(sysui)!!
        assertEquals(plain.contentHash, target.contentHash)
        assertEquals(plain.colors, target.colors)
        assertEquals("HexNextValues_com_android_systemui", target.valueOverlayName)
        assertEquals(2, target.values.size)
        assertNotEquals(plain.valueHash, target.valueHash)
        assertFalse(plain.hasValues)
        assertTrue(shaped.events.any { it.message == "values planned" && it.data["count"] == "2" })
        // Disabled targets get nothing.
        val off = ThemeCompiler.compile(base.copy(shapes = target.values.let { mapOf(ShapeControls.TILE_CORNER to ValueSetting.dimen(8.0)) }, targetEnabled = mapOf(sysui to false)), device(), packs, rules)
        assertNull(off.target(sysui))
    }

    @Test
    fun `available controls reflect the scan`() {
        val controls = ValuePlanner.availableControls(device(), rules)
        assertEquals(ShapeControls.QUICK_PANEL.toSet(), controls.keys)
        val tile = controls.getValue(ShapeControls.TILE_CORNER)
        assertTrue(tile.available && tile.scanned)
        assertEquals(listOf(tileCorner), tile.presentNames)
        assertEquals(28.0, tile.stock!!, 1e-6)
        assertFalse(controls.getValue(ShapeControls.PANEL_CORNER).available)
        // notification_corner_radius is a System UI name (confirmed on One UI 9), so it was scanned.
        assertTrue(controls.getValue(ShapeControls.NOTIFICATION_CORNER).scanned)
        val none = ValuePlanner.availableControls(device(emptyMap()), rules)
        assertTrue(none.values.none { it.available || it.scanned })
    }

    @Test
    fun `value hash is order independent`() {
        val a = PlannedValue(tileCorner, ValueTypes.TYPE_DIMENSION, 0x801, "")
        val b = PlannedValue(tileCorner, ValueTypes.TYPE_DIMENSION, 0x801, "land")
        assertEquals(ValuePlanner.valueHash(listOf(a, b)), ValuePlanner.valueHash(listOf(b, a)))
    }
}
