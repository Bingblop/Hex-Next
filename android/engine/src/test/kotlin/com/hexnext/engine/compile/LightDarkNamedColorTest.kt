package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ArscReader
import com.hexnext.engine.arsc.ArscTestBuilder
import com.hexnext.engine.compile.Fixtures.ref
import com.hexnext.engine.compile.Fixtures.semwt
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A colour whose name ends in `_light` or `_dark` keeps that mode's group colour in both
 * configurations, so a "light card" stays light at night and a "dark card" stays dark by day.
 */
class LightDarkNamedColorTest {
    @Test
    fun `light and dark named colours keep their own mode's group colour`() {
        val pkg = "com.android.settings"
        val b = ArscTestBuilder(pkg)
        b.typeNames += listOf("color")
        b.typeSpec(1, 2)
        val k0 = b.key("sec_foo_background_color_light"); val k1 = b.key("sec_bar_card_dark")
        b.type(1, 2, b.config(), mapOf(0 to ArscTestBuilder.Entry.Simple(k0, 0x1c, ref("system_neutral1_50")), 1 to ArscTestBuilder.Entry.Simple(k1, 0x1c, ref("system_neutral1_10"))))
        b.type(1, 2, b.config(uiMode = 0x20), mapOf(0 to ArscTestBuilder.Entry.Simple(k0, 0x1c, ref("system_neutral1_1000")), 1 to ArscTestBuilder.Entry.Simple(k1, 0x1c, ref("system_neutral1_900"))))
        val table = ArscReader.read(b.build())
        val sem = semwt(pkg, listOf(Triple("other_text_color", ref("system_neutral1_900"), "")))
        val dev = DeviceSnapshot(Fixtures.profile9, mapOf(pkg to TargetSnapshot(pkg, installed = true, table = table, semwt = sem)), Fixtures.reference)
        val spec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()))
            .withGroupColor(ColorGroup.BACKGROUND, false, 0xFFEEEEEE.toInt()).withGroupColor(ColorGroup.BACKGROUND, true, 0xFF111111.toInt())
            .withGroupColor(ColorGroup.SURFACE, false, 0xFFFFFFFF.toInt()).withGroupColor(ColorGroup.SURFACE, true, 0xFF222222.toInt())
        val t = ThemeCompiler.compile(spec, dev, InMemoryPackResolver(emptyList()), OneUiRules.load(9)) { 7L }
        val got = t.targets.single().colors.associate { "${it.resourceName}|${it.configuration}" to it.argb }
        val bg = "$pkg:color/sec_foo_background_color_light"
        val card = "$pkg:color/sec_bar_card_dark"
        assertEquals(
            mapOf(
                "$bg|" to 0xFFEEEEEE.toInt(), "$bg|night" to 0xFFEEEEEE.toInt(),
                "$card|" to 0xFF222222.toInt(), "$card|night" to 0xFF222222.toInt(),
            ),
            got.filterKeys { it.startsWith(bg) || it.startsWith(card) },
        )
    }
}
