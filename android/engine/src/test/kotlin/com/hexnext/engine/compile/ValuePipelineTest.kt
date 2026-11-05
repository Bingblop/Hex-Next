package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ArscReader
import com.hexnext.engine.arsc.ArscTestBuilder
import com.hexnext.engine.arsc.ArscTestBuilder.Entry
import com.hexnext.engine.arsc.ResourceValue
import com.hexnext.engine.frro.FrroReader
import com.hexnext.engine.frro.FrroSpec
import com.hexnext.engine.frro.FrroValue
import com.hexnext.engine.frro.FrroWriter
import com.hexnext.engine.map.PlannedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The non-colour value pipeline around [ValuePlanner]: spec, rules, arsc, frro and verification. */
class ValuePipelineTest {
    private val sysui = "com.android.systemui"

    @Test
    fun `a theme saved before values loads unchanged and new fields round trip`() {
        val old = ThemeSpec.default().toJson()
            .replace(Regex(""","(shapes|values|groupAlpha)":\{\}"""), "")
        assertFalse("shapes" in old)
        val loaded = ThemeSpec.fromJson(old)
        assertEquals(emptyMap(), loaded.shapes)
        assertEquals(emptyMap(), loaded.groupAlpha)
        assertFalse(loaded.hasValues())
        assertEquals("", loaded.valuesKey())

        val spec = ThemeSpec.default().copy(
            shapes = mapOf(ShapeControls.TILE_CORNER to ValueSetting.dimen(12.0)),
            values = mapOf("$sysui:bool/x" to ValueSetting.bool(true)),
            groupAlpha = mapOf("notification_bg" to 128),
        )
        val back = ThemeSpec.fromJson(spec.toJson())
        assertEquals(spec, back)
        assertTrue(back.hasValues())
        assertEquals(spec.valuesKey(), back.valuesKey())
        assertEquals(128, back.alphaFor(ColorGroup.NOTIFICATION_BG))
        assertEquals(255, back.alphaFor(ColorGroup.QS_PANEL_BG))
    }

    @Test
    fun `an unknown value kind from a newer build loads as UNKNOWN`() {
        val json = ThemeSpec.default().toJson().replace(""""shapes":{}""", """"shapes":{"tileCorner":{"kind":"WOBBLE","number":3.0}}""")
        val spec = ThemeSpec.fromJson(json)
        assertEquals(ValueKind.UNKNOWN, spec.shapes.getValue(ShapeControls.TILE_CORNER).kind)
    }

    @Test
    fun `alphaFor clamps and the panel falls back to qsPanelAlpha`() {
        val spec = ThemeSpec.default().copy(qsPanelAlpha = 180, groupAlpha = mapOf("notification_bg" to 999))
        assertEquals(180, spec.alphaFor(ColorGroup.QS_PANEL_BG))
        assertEquals(255, spec.alphaFor(ColorGroup.NOTIFICATION_BG))
        assertEquals(40, spec.copy(groupAlpha = mapOf("qs_panel_bg" to 40)).alphaFor(ColorGroup.QS_PANEL_BG))
    }

    @Test
    fun `One UI 9 rules carry well formed shape controls, older versions none`() {
        val qs = OneUiRules.load(9).quickSettings
        assertEquals(ShapeControls.QUICK_PANEL.toSet(), qs.values.keys)
        val name = Regex("""^[a-z][a-z0-9_.]*:(dimen|integer|bool)/[a-z0-9_]+$""")
        for ((id, rule) in qs.values) {
            assertTrue(rule.names.isNotEmpty(), id)
            rule.names.forEach { assertTrue(name.matches(it), "$id: $it") }
            assertTrue(rule.min!! < rule.max!!, id)
            assertTrue(rule.names.all { it.startsWith("$sysui:") || it.startsWith("android:") }, id)
        }
        assertEquals(ShapeControls.TILE_CORNER, qs.valueRuleFor("$sysui:dimen/qs_corner_radius")?.first)
        assertTrue(qs.allowsValue("$sysui:dimen/qs_corner_radius"))
        assertFalse(qs.allowsValue("$sysui:dimen/anything_else"))
        for (major in listOf(7, 8)) assertTrue(OneUiRules.load(major).quickSettings.values.isEmpty(), "One UI $major")
    }

    @Test
    fun `the arsc reader decodes floats`() {
        val b = ArscTestBuilder(sysui)
        b.typeNames += "dimen"
        b.typeSpec(1, 1)
        b.type(1, 1, b.config(), mapOf(0 to Entry.Simple(b.key("alpha"), 0x04, 0.5f.toRawBits())))
        val table = ArscReader.read(b.build())
        assertEquals(ResourceValue.FloatValue(0.5f), table.find("dimen/alpha")!!.values.single().value)
    }

    @Test
    fun `frro spec values keep their data types and survive a rewrite`() {
        val spec = FrroSpec(
            "com.android.shell", "HexNextValues_com_android_systemui", sysui,
            values = listOf(
                FrroValue("dimen/qs_corner_radius", ValueTypes.TYPE_DIMENSION, Dimens.encode(12.0, 1)),
                FrroValue("dimen/qs_corner_radius", ValueTypes.TYPE_DIMENSION, Dimens.encode(16.0, 1), "sw600dp-v13"),
                FrroValue("integer/cols", ValueTypes.TYPE_INT_DEC, 5),
                FrroValue("bool/flag", ValueTypes.TYPE_INT_BOOLEAN, ValueTypes.BOOL_TRUE),
            ),
        )
        val path = "/data/resource-cache/com.android.shell-HexNextValues_com_android_systemui-1.frro"
        val bytes = FrroWriter.write(spec, path)
        val file = FrroReader.read(bytes)
        val byName = file.entries.groupBy { it.resourceName }
        val corner = byName.getValue("$sysui:dimen/qs_corner_radius")
        assertEquals(listOf(null, "sw600dp-v13"), corner.map { it.configuration })
        assertTrue(corner.all { it.dataType == ValueTypes.TYPE_DIMENSION })
        assertEquals(ValueTypes.TYPE_INT_DEC to 5, byName.getValue("$sysui:integer/cols").single().let { it.dataType to it.data })
        assertEquals(ValueTypes.TYPE_INT_BOOLEAN, byName.getValue("$sysui:bool/flag").single().dataType)
        // A rewrite (as ThemeService does after commit) keeps non-colour entries byte for byte.
        assertTrue(bytes.contentEquals(FrroWriter.from(file, bytes).toByteArray(path)))
    }

    @Test
    fun `overlay lookup output is parsed per data type`() {
        val dimen = OverlayLookup.parseValue("com.android.systemui:dimen/qs_corner_radius -> 12.0dip", ValueTypes.TYPE_DIMENSION)!!
        assertTrue(Dimens.sameSize(dimen, Dimens.encode(12.0, 1)))
        assertEquals(ValueTypes.BOOL_TRUE, OverlayLookup.parseValue("res bool/flag -> true", ValueTypes.TYPE_INT_BOOLEAN))
        assertEquals(0, OverlayLookup.parseValue("res bool/flag -> false", ValueTypes.TYPE_INT_BOOLEAN))
        assertEquals(4, OverlayLookup.parseValue("integer/cols -> 0x4", ValueTypes.TYPE_INT_DEC))
        assertEquals(4, OverlayLookup.parseValue("integer/cols -> 4", ValueTypes.TYPE_INT_DEC))
        assertEquals(0.5f.toRawBits(), OverlayLookup.parseValue("dimen/a -> 0.5", ValueTypes.TYPE_FLOAT))
        assertNull(OverlayLookup.parseValue("Error: no such resource", ValueTypes.TYPE_DIMENSION))

        val planned = PlannedValue("$sysui:dimen/qs_corner_radius", ValueTypes.TYPE_DIMENSION, Dimens.encode(12.0, 1))
        assertEquals(true, OverlayLookup.matchesValue("-> 12.0dip", planned))
        assertEquals(false, OverlayLookup.matchesValue("-> 28.0dip", planned))
        assertNull(OverlayLookup.matchesValue("nothing", planned))
    }

    @Test
    fun `value outcomes adjust the target result`() {
        val ok = TargetOutcome(sysui, TargetResult.OK)
        assertEquals(TargetResult.OK, ok.withValues(2, true).result)
        assertEquals(TargetResult.PARTIAL, ok.withValues(2, false).result)
        assertEquals(TargetResult.PARTIAL, ok.withValues(0, null, failed = true).result)
        assertEquals(TargetResult.OK, TargetOutcome(sysui, TargetResult.EMPTY).withValues(1, null).result)
        assertEquals(TargetResult.EMPTY, TargetOutcome(sysui, TargetResult.EMPTY).withValues(0, null).result)
        assertEquals(2, ok.withValues(2, true).valueCount)
    }

    @Test
    fun `a failed value overlay on a target without colours is a failure`() {
        val empty = TargetOutcome(sysui, TargetResult.EMPTY)
        val failed = empty.withValues(0, null, "boom", failed = true)
        assertEquals(TargetResult.FAILED, failed.result)
        assertEquals("boom", failed.valueMessage)
        val report = ApplyReport("t", "T", 0, 1, listOf(failed))
        assertEquals(1, report.failedCount)
        assertEquals(0, report.skippedCount)
        assertFalse(report.success)
        // With the colour overlay in place the target is only partial.
        assertEquals(TargetResult.PARTIAL, TargetOutcome(sysui, TargetResult.OK).withValues(0, null, failed = true).result)
        assertEquals(TargetResult.PARTIAL, TargetOutcome(sysui, TargetResult.PARTIAL).withValues(0, null, failed = true).result)
    }

    @Test
    fun `no value probe when every value has a configuration variant`() {
        val a = PlannedValue("$sysui:dimen/a", ValueTypes.TYPE_DIMENSION, 0x1001, "")
        val aTablet = PlannedValue("$sysui:dimen/a", ValueTypes.TYPE_DIMENSION, 0x2001, "sw600dp-v13")
        val b = PlannedValue("$sysui:dimen/b", ValueTypes.TYPE_DIMENSION, 0x1001, "")
        val bLand = PlannedValue("$sysui:dimen/b", ValueTypes.TYPE_DIMENSION, 0x1801, "land")
        val target = CompiledTarget(sysui, "HexNext_x", emptyList(), emptyList(), emptyList(), 0, "h", values = listOf(a, aTablet, b, bLand))
        assertNull(OverlayLookup.valueProbe(target))
    }

    @Test
    fun `the probe prefers a value with only a default configuration`() {
        val a = PlannedValue("$sysui:dimen/a", ValueTypes.TYPE_DIMENSION, 0x1001, "")
        val aLand = PlannedValue("$sysui:dimen/a", ValueTypes.TYPE_DIMENSION, 0x1001, "land")
        val b = PlannedValue("$sysui:dimen/b", ValueTypes.TYPE_DIMENSION, 0x1001, "")
        val target = CompiledTarget(sysui, "HexNext_x", emptyList(), emptyList(), emptyList(), 0, "h", values = listOf(a, aLand, b))
        assertEquals(b, OverlayLookup.valueProbe(target))
    }

    @Test
    fun `snapshot mapper passes scanned values through`() {
        val values = mapOf("dimen/qs_corner_radius" to listOf(ScannedValue("", ValueTypes.TYPE_DIMENSION, 0x1c01)))
        val snapshot = SnapshotMapper.target(TargetScan(sysui, installed = true, values = values))
        assertEquals(values, snapshot.values)
    }
}
