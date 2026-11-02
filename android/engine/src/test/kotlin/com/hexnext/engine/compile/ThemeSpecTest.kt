package com.hexnext.engine.compile

import com.hexnext.engine.palette.ColorSpecVersion
import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.PaletteStyle
import com.hexnext.engine.palette.PitchBlack
import com.hexnext.engine.palette.ThemeAdjustments
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeSpecTest {
    private val full = ThemeSpec(
        id = "t1", name = "Night owl",
        source = ThemeSource(0xFF2E9E44.toInt(), 0xFF00FF00.toInt(), null, PaletteStyle.VIBRANT, ColorSpecVersion.SPEC_2025, 0.5),
        darkAdjustments = ThemeAdjustments(accentSaturation = 150, pitchBlack = PitchBlack.SURFACES, shadeOverrides = mapOf("accent1_500" to 0xFF102030.toInt())),
        lightAdjustments = ThemeAdjustments(tintedText = false, roleOverrides = mapOf("on_primary" to -1)),
        mode = ThemeMode.ADVANCED,
        simplePackId = "deluxe-4-2",
        elementPacks = mapOf("navigation_bar" to "dsp-2-5", "keyboard" to "deluxe-4-2"),
        elementEnabled = mapOf("aod" to false),
        elementOptions = mapOf("settings_icons" to mapOf("1sett" to "true")),
        targetEnabled = mapOf("com.samsung.android.app.notes" to false),
        colorPins = mapOf(
            "com.android.systemui:color/qs_tile_bg" to ColorPin.Shade(PaletteRole.ACCENT2, 300),
            "android:color/sec_x" to ColorPin.Fixed(0x80FF0000.toInt()),
        ),
        customIcons = mapOf("com.android.settings:drawable/sec_ic_settings_display" to "mine-0a1b2c3d.png"),
        customBackgrounds = mapOf("com.android.settings:drawable/sec_bg" to "wall-11223344.png"),
        customFont = CustomFont("inter-aabbccdd.ttf", "inter-bold-eeff0011.ttf", "Inter"),
        synthesizeMissingIcons = false,
    )

    @Test
    fun `json round trip keeps everything`() {
        val json = full.toJson()
        assertEquals(full, ThemeSpec.fromJson(json))
        assertTrue("\"type\":\"shade\"" in json)
        assertTrue("\"type\":\"fixed\"" in json)
        assertEquals(ThemeSpec.default(), ThemeSpec.fromJson(ThemeSpec.default().toJson()))
    }

    @Test
    fun `unknown keys are ignored and missing optionals default`() {
        val spec = ThemeSpec.fromJson("""{"id":"x","name":"X","source":{"seedArgb":-16776961},"future":1}""")
        assertEquals(ThemeMode.SIMPLE, spec.mode)
        assertNull(spec.simplePackId)
        assertEquals(ThemeSpec.FORMAT, spec.format)
        assertEquals(emptyMap(), spec.customIcons)
        assertEquals(emptyMap(), spec.customBackgrounds)
        assertNull(spec.customFont)
        assertTrue(spec.synthesizeMissingIcons)
        assertFailsWith<IllegalArgumentException> { ThemeSpec.fromJson("{}") }
        assertFailsWith<IllegalArgumentException> { ThemeSpec.fromJson("""{"id":"x","name":"X","source":{"seedArgb":1},"colorPins":{"a":{"type":"shade","role":"ERROR","shade":500}}}""") }
    }

    @Test
    fun `defaults enable everything`() {
        val d = ThemeSpec.default()
        assertTrue(d.isElementEnabled("navigation_bar"))
        assertTrue(d.isTargetEnabled("android"))
        assertEquals(ThemeSpec.DEFAULT_SEED, d.source.seedArgb)
        assertEquals(emptyList(), d.referencedPackIds)
        assertFalse(full.isElementEnabled("aod"))
        assertFalse(full.isTargetEnabled("com.samsung.android.app.notes"))
    }

    @Test
    fun `pack selection follows the mode`() {
        assertEquals("dsp-2-5", full.packFor("navigation_bar"))
        assertEquals("deluxe-4-2", full.packFor("dialer"))
        val simple = full.copy(mode = ThemeMode.SIMPLE)
        assertEquals("deluxe-4-2", simple.packFor("navigation_bar"))
        assertEquals(listOf("deluxe-4-2", "dsp-2-5"), full.referencedPackIds)
        assertEquals(listOf("deluxe-4-2"), simple.referencedPackIds)
    }

    @Test
    fun `custom asset fields round trip and helpers edit them`() {
        val json = full.toJson()
        assertTrue("\"customFont\"" in json && "\"synthesizeMissingIcons\":false" in json)
        val back = ThemeSpec.fromJson(json)
        assertEquals(full.customIcons, back.customIcons)
        assertEquals(full.customBackgrounds, back.customBackgrounds)
        assertEquals(full.customFont, back.customFont)
        assertFalse(back.synthesizeMissingIcons)

        val d = ThemeSpec.default()
        val key = "com.android.settings:drawable/sec_ic_settings_tips"
        val withIcon = d.withCustomIcon(key, "tips-12345678.png")
        assertEquals(mapOf(key to "tips-12345678.png"), withIcon.customIcons)
        assertEquals(d, withIcon.withCustomIcon(key, null))
        val withBg = d.withCustomBackground("android:drawable/bg", "b-00000000.png")
        assertEquals(mapOf("android:drawable/bg" to "b-00000000.png"), withBg.customBackgrounds)
        assertEquals(d, withBg.withCustomBackground("android:drawable/bg", null))
        val font = CustomFont("f-00000000.ttf", displayName = "F")
        assertEquals(font, d.withFont(font).customFont)
        assertNull(d.withFont(font).withFont(null).customFont)
        assertFalse(d.withSynthesizeMissingIcons(false).synthesizeMissingIcons)
        assertEquals("custom:", CustomAssets.ICON_PREFIX)
    }

    @Test
    fun `shade pins are validated`() {
        assertFailsWith<IllegalArgumentException> { ColorPin.Shade(PaletteRole.ERROR, 500) }
        assertFailsWith<IllegalArgumentException> { ColorPin.Shade(PaletteRole.ACCENT1, 550) }
    }
}
