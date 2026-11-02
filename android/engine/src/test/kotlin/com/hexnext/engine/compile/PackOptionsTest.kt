package com.hexnext.engine.compile

import com.hexnext.engine.pack.PackOption
import com.hexnext.engine.pack.Tint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackOptionsTest {
    private val none = Tint(Tint.NONE)

    private val iconStyle = PackOption(PackOption.SELECT, "Settings icon style", keys = listOf("1sett", "2sett", "3sett"), choices = listOf("Flat", "Tinted", "Multicolour"))
    private val background = PackOption(PackOption.SWITCH, " Add background ", key = "addbackground")
    private val dead = PackOption(PackOption.SWITCH, "Does nothing", key = "nothing")
    private val blank = PackOption(PackOption.SWITCH, "   ", key = "trasnpa")

    private val pack = Fixtures.pack(
        "p1",
        listOf(
            Fixtures.layer("settingsicon-1sett", "base", none, listOf("ic_a")),
            Fixtures.layer("settingsicon-2sett", "base", none, listOf("ic_a")),
            Fixtures.layer("pref-2sett", "base", none, listOf("bg")),
            Fixtures.layer("misc-wabubble", "base", none, listOf("bubble")),
            Fixtures.layer("navbar-addbackground", "base", none, listOf("nav_bg")),
            Fixtures.layer("dialpad_nightHp-trasnpa", "base", none, listOf("key")),
            Fixtures.layer("navbar-xxhdpi", "base", none, listOf("nav_a")),
        ),
    ).copy(options = listOf(iconStyle, background, dead, blank, background))

    @Test
    fun `live keeps gated options in pack order, drops dead ones and duplicates`() {
        val live = PackOptions.live(pack)
        assertEquals(listOf("Settings icon style", "Add background", "trasnpa"), live.map { it.option.label })
        assertEquals(setOf("settings_icons", "settings_dashboard"), live[0].elementIds)
        assertEquals(setOf("navigation_bar"), live[1].elementIds)
        assertEquals(setOf("dialpad"), live[2].elementIds)
        assertEquals(listOf("1sett", "2sett", "3sett"), live[0].keys)
        assertEquals(listOf("addbackground"), live[1].keys)
        assertTrue(live.none { it.option.key == "nothing" })
    }

    @Test
    fun `select with a single live key is still live`() {
        val p = Fixtures.pack("p2", listOf(Fixtures.layer("settingsicon-3sett", "base", none, listOf("ic"))))
            .copy(options = listOf(iconStyle))
        val live = PackOptions.live(p)
        assertEquals(1, live.size)
        assertEquals(setOf("settings_icons"), live.single().elementIds)
        assertTrue(PackOptions.live(Fixtures.pack("p3", emptyList()).copy(options = listOf(iconStyle))).isEmpty())
    }

    @Test
    fun `value defaults to the first select key and switch off`() {
        val spec = ThemeSpec.default()
        assertEquals("1sett", PackOptions.value(spec, pack, iconStyle))
        assertEquals("false", PackOptions.value(spec, pack, background))
        assertEquals("false", PackOptions.value(spec, pack, background, "navigation_bar"))
    }

    @Test
    fun `value reads the element options over the pack defaults`() {
        val spec = ThemeSpec.default().copy(
            elementOptions = mapOf(
                "settings_icons" to mapOf("1sett" to "false", "3sett" to "true"),
                "navigation_bar" to mapOf("addbackground" to "true"),
            ),
        )
        assertEquals("3sett", PackOptions.value(spec, pack, iconStyle))
        assertEquals("3sett", PackOptions.value(spec, pack, iconStyle, "settings_icons"))
        assertEquals("1sett", PackOptions.value(spec, pack, iconStyle, "settings_dashboard"))
        assertEquals("true", PackOptions.value(spec, pack, background))
    }

    @Test
    fun `withOption writes every gated element and turns the other select keys off`() {
        val live = PackOptions.live(pack)
        val select = live[0]
        val switch = live[1]
        val spec = ThemeSpec.default()
            .copy(elementOptions = mapOf("settings_icons" to mapOf("palette" to "pack")))
            .withOption(pack, select, "2sett")
            .withOption(pack, switch, "true")
        val expectedSelect = mapOf("1sett" to "false", "2sett" to "true", "3sett" to "false")
        assertEquals(expectedSelect + ("palette" to "pack"), spec.elementOptions["settings_icons"])
        assertEquals(expectedSelect, spec.elementOptions["settings_dashboard"])
        assertEquals(mapOf("addbackground" to "true"), spec.elementOptions["navigation_bar"])
        assertEquals("2sett", PackOptions.value(spec, pack, iconStyle))
        assertEquals("true", PackOptions.value(spec, pack, background))

        val one = spec.withOption(pack, select, "3sett", setOf("settings_dashboard"))
        assertEquals("2sett", PackOptions.value(one, pack, iconStyle, "settings_icons"))
        assertEquals("3sett", PackOptions.value(one, pack, iconStyle, "settings_dashboard"))

        val off = spec.withOption(pack, switch, "false")
        assertEquals(mapOf("addbackground" to "false"), off.elementOptions["navigation_bar"])
        assertEquals(spec, spec.withOption(pack, switch, "true"))
    }

    @Test
    fun `element pack and enabled helpers`() {
        val spec = ThemeSpec.default().withElementPack("keyboard", "p1").withElementEnabled("aod", false)
        assertEquals(mapOf("keyboard" to "p1"), spec.elementPacks)
        assertFalse(spec.isElementEnabled("aod"))
        assertTrue(spec.isElementEnabled("keyboard"))
        val reset = spec.withElementPack("keyboard", null).withElementEnabled("aod", true)
        assertNull(reset.elementPacks["keyboard"])
        assertEquals(emptyMap(), reset.elementEnabled)
    }

    @Test
    fun `covered elements follow registry order`() {
        assertEquals(
            listOf("settings_icons", "settings_dashboard", "navigation_bar", "dialpad", "misc"),
            PackOptions.coveredElements(pack).map { it.id },
        )
        assertEquals(emptyList(), PackOptions.coveredElements(Fixtures.pack("empty", emptyList())))
    }

    @Test
    fun `options survive a json round trip`() {
        val live = PackOptions.live(pack)
        val spec = ThemeSpec.default().withOption(pack, live[0], "3sett").withOption(pack, live[1], "true")
            .withElementPack("navigation_bar", "p1").withElementEnabled("misc", false)
        val back = ThemeSpec.fromJson(spec.toJson())
        assertEquals(spec, back)
        assertEquals("3sett", PackOptions.value(back, pack, iconStyle))
        assertEquals("true", PackOptions.value(back, pack, background))
    }

    @Test
    fun `pack options parse from pack json`() {
        val json = """{"id":"x","name":"X","options":[{"type":"select","label":"Style","keys":["a","b"],"choices":["A","B"]},{"type":"switch","label":"Flag","key":"f"}],
            "layers":[{"pass":"once","stage":"base","category":"navbar-b","tint":{"kind":"none"},"path":"l","resources":["r"]}]}"""
        val p = com.hexnext.engine.pack.PackReader.parse(json)
        val live = PackOptions.live(p)
        assertEquals(1, live.size)
        assertEquals("Style", live.single().option.label)
        assertEquals("a", PackOptions.value(ThemeSpec.default(), p, live.single().option))
    }
}
