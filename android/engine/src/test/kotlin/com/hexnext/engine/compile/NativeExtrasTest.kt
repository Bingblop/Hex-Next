package com.hexnext.engine.compile

import com.hexnext.engine.pack.PackOption
import com.hexnext.engine.pack.Tint
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeExtrasTest {
    private val trans = PackOption(
        PackOption.SELECT, "Notification transparency",
        keys = listOf("0notitrans", "3notitrans", "9notitrans", "unknownkey"),
        choices = listOf("Opaque", "30%", "90%", "Mystery"),
    )
    private val round = PackOption(PackOption.SELECT, "Notification corners", keys = listOf("0roundnoti", "6roundnoti"), choices = listOf("Square", ""))
    private val divider = PackOption(PackOption.SWITCH, "Tinted dividers", key = "uidivid")
    private val glass = PackOption(PackOption.SWITCH, "Glass panel", key = "gloglass")
    private val layered = PackOption(PackOption.SWITCH, "Layered key", key = "uione")
    private val green = PackOption(PackOption.SWITCH, "Green header", key = "green")

    private val pack = Fixtures.pack(
        "p1",
        listOf(Fixtures.layer("misc-uione", "base", Tint(Tint.NONE), listOf("bg"))),
    ).copy(options = listOf(trans, round, divider, glass, layered, green))

    private val resolved = { ThemeResolver.resolve(ThemeSource(0xFF2E9E44.toInt())) }
    private val packs = InMemoryPackResolver(listOf(pack))
    private fun spec() = ThemeSpec.default().copy(simplePackId = "p1")

    @Test
    fun `table loads and every effect is well formed`() {
        assertTrue(NativeExtras.ALL.size >= 4)
        for (extra in NativeExtras.ALL) {
            assertTrue(extra.keys.isNotEmpty(), extra.id)
            for ((key, choice) in extra.keys) for (e in choice.effects) {
                val forms = listOf(
                    e.group != null && (e.alpha != null || e.colour != null),
                    e.names != null && (e.colour != null || e.alpha != null),
                    e.shape != null && e.dp != null,
                )
                assertEquals(1, forms.count { it }, "$key: $e")
                e.group?.let { assertNotNull(ColorGroup.byId(it), "$key group $it") }
                e.names?.let { Regex(it) }
                e.shape?.let { assertTrue(it in ShapeControls.QUICK_PANEL, "$key shape $it") }
                e.colour?.let { assertNotNull(NativeExtras.token(it, spec(), resolved, night = false), "$key token $it") }
                e.alpha?.let { assertTrue(it in 0..255) }
            }
        }
    }

    @Test
    fun `options lists dead options with a One UI default, never a layer option`() {
        val options = NativeExtras.options(pack)
        val labels = options.map { it.live.option.label }
        assertEquals(listOf("Notification transparency", "Notification corners", "Tinted dividers", "Glass panel"), labels)
        val t = options[0].live.option
        assertEquals(listOf("hexnext_default_notification_cards", "0notitrans", "3notitrans", "9notitrans"), t.keys)
        assertEquals(listOf(NativeExtras.DEFAULT_CHOICE_LABEL, "Opaque", "30%", "90%"), t.choices)
        assertEquals(listOf("hexnext_default_notification_cards", "0roundnoti", "6roundnoti"), options[1].live.option.keys)
        assertEquals("6roundnoti", options[1].live.option.choices.last(), "a blank choice label falls back to the key")
        assertEquals(setOf("notifications"), options[0].live.elementIds)
        assertEquals("notification_cards", options[0].live.extra)
        // uione gates a layer here: it stays a layer option; green is restricted to another pack.
        assertTrue(PackOptions.live(pack).any { it.option.key == "uione" })
        assertTrue(options.none { it.live.option.key == "uione" || it.live.option.key == "green" })
        assertEquals(PackOptions.live(pack).size + options.size, PackOptions.all(pack).size)
    }

    @Test
    fun `value starts at the One UI default and follows the user's choice`() {
        val live = NativeExtras.options(pack)[0].live
        assertEquals("hexnext_default_notification_cards", PackOptions.value(spec(), pack, live))
        val chosen = spec().copy(elementOptions = mapOf("notifications" to mapOf("9notitrans" to "true")))
        assertEquals("9notitrans", PackOptions.value(chosen, pack, live))
        val sw = NativeExtras.options(pack).first { it.live.option.key == "uidivid" }.live
        assertEquals("false", PackOptions.value(spec(), pack, sw))
    }

    @Test
    fun `pack settings turn a choice on until the user touches it`() {
        val declared = pack.copy(settings = mapOf("hex:9notitrans" to JsonPrimitive("true"), "hex:gloglass" to JsonPrimitive("true")))
        val live = NativeExtras.options(declared)[0].live
        assertEquals("9notitrans", PackOptions.value(spec(), declared, live))
        val r = NativeExtras.resolve(spec(), InMemoryPackResolver(listOf(declared)), resolved)
        assertEquals(26, r.spec.groupAlpha["notification_bg"])
        assertEquals(150, r.spec.groupAlpha["qs_panel_bg"])
        // A select's first key is not assumed on from Pack.defaultOptions alone.
        assertTrue(NativeExtras.resolve(spec(), packs, resolved).applied.isEmpty())
    }

    @Test
    fun `resolve merges alpha, shape and name colours below the user's settings`() {
        val chosen = spec().copy(
            elementOptions = mapOf(
                "notifications" to mapOf("3notitrans" to "true", "6roundnoti" to "true"),
                "dialogs" to mapOf("uidivid" to "true"),
            ),
        )
        val r = NativeExtras.resolve(chosen, packs, resolved)
        assertEquals(178, r.spec.groupAlpha["notification_bg"])
        assertEquals(ValueSetting.dimen(38.0), r.spec.shapes[ShapeControls.NOTIFICATION_CORNER])
        val divider = r.colours.single()
        assertTrue(divider.matches("android", "list_divider_color"))
        assertFalse(divider.matches("android", "accent"))
        assertEquals(0x80FFFFFF.toInt(), divider.apply(0x80123456.toInt(), night = true), "colour replaced, alpha kept")
        assertEquals(3, r.applied.size)

        val user = chosen.copy(
            groupAlpha = mapOf("notification_bg" to 240),
            shapes = mapOf(ShapeControls.NOTIFICATION_CORNER to ValueSetting.dimen(4.0)),
        )
        val kept = NativeExtras.resolve(user, packs, resolved)
        assertEquals(240, kept.spec.groupAlpha["notification_bg"])
        assertEquals(ValueSetting.dimen(4.0), kept.spec.shapes[ShapeControls.NOTIFICATION_CORNER])
    }

    @Test
    fun `glass leaves a user panel alpha alone and disabled elements get nothing`() {
        val chosen = spec().copy(elementOptions = mapOf("quick_settings_panel" to mapOf("gloglass" to "true")), qsPanelAlpha = 200)
        val r = NativeExtras.resolve(chosen, packs, resolved)
        assertNull(r.spec.groupAlpha["qs_panel_bg"])
        assertEquals(90, r.spec.groupAlpha["qs_tile_active"])
        val off = chosen.copy(elementEnabled = mapOf("quick_settings_panel" to false))
        assertTrue(NativeExtras.resolve(off, packs, resolved).applied.isEmpty())
    }

    @Test
    fun `tokens resolve, unknown tokens are null`() {
        assertEquals(0xFFFFFFFF.toInt(), NativeExtras.token("white", spec(), resolved, night = false))
        assertEquals(0xFF4CAF50.toInt(), NativeExtras.token("#FF4CAF50", spec(), resolved, night = true))
        assertNotNull(NativeExtras.token("primary", spec(), resolved, night = true))
        assertNull(NativeExtras.token("chartreuse", spec(), resolved, night = false))
    }

    @Test
    fun `compile applies a native notification alpha and corner`() {
        val chosen = spec().copy(
            source = ThemeSource(0xFF2E9E44.toInt()),
            elementOptions = mapOf("notifications" to mapOf("3notitrans" to "true")),
        )
        val sysui = "com.android.systemui"
        val snap = TargetSnapshot(sysui, installed = true, table = Fixtures.table(sysui, listOf("qp_notification_background_color")))
        val device = DeviceSnapshot(Fixtures.profile9, mapOf(sysui to snap), Fixtures.reference)
        val rules = OneUiRules.load(9)
        val plain = ThemeCompiler.compile(spec().copy(source = chosen.source), device, packs, rules)
        val result = ThemeCompiler.compile(chosen, device, packs, rules)
        assertTrue(result.events.any { it.message == "native extras" })
        val before = plain.target(sysui)?.colors.orEmpty().associateBy { it.resourceName }
        val after = result.target(sysui)?.colors.orEmpty().associateBy { it.resourceName }
        val name = "$sysui:color/qp_notification_background_color"
        assertEquals(178, after.getValue(name).argb ushr 24)
        assertEquals(255, before.getValue(name).argb ushr 24)
    }
}
