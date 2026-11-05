package com.hexnext.engine.compile

import com.hexnext.engine.mcu.hct.Hct
import com.hexnext.engine.pack.CompositePlan
import com.hexnext.engine.pack.CompositeStep
import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IconColorsTest {
    private val theme = ThemeResolver.resolve(ThemeSource(0xFF2E9E44.toInt()))
    private val derived = SlotPalette.derive(theme)
    private val settings = "settings_icons"
    private val display = "com.android.settings:drawable/sec_ic_settings_display"
    private val connections = "com.android.settings:drawable/sec_ic_settings_connections"

    /** A typical pack icon: neutral badge, accent glyph, light accent shine, fixed and untinted layers. */
    private fun plan(night: Boolean = true): CompositePlan {
        val p = if (night) "" else "l"
        return CompositePlan(
            listOf(
                CompositeStep("a/base.png", derived.getValue("${p}xx0"), slot = "xx0"),
                CompositeStep("a/glyph.png", derived.getValue("${p}xx1"), slot = "xx1"),
                CompositeStep("a/shine.png", (derived.getValue("${p}xx9") and 0x00FFFFFF) or (0x80 shl 24), slot = "xx9"),
                CompositeStep("a/second.png", derived.getValue("${p}xy5"), slot = "xy5"),
                CompositeStep("a/fixed.png", 0xFFFF0000.toInt()),
                CompositeStep("a/raw.png", null),
                CompositeStep("a/fg.png", derived.getValue("${p}xx3"), slot = "xx3"),
            ),
        )
    }

    private fun hue(argb: Int) = Hct.fromInt(argb).hue
    private fun tone(argb: Int) = Hct.fromInt(argb).tone

    @Test
    fun `accent slots are recognised and neutral slots are not`() {
        assertEquals(1, IconColors.accentSlotDigit("xx1"))
        assertEquals(5, IconColors.accentSlotDigit("lxy5"))
        assertEquals(9, IconColors.accentSlotDigit("xz9"))
        assertEquals(2, IconColors.accentSlotDigit("xx2"))
        assertNull(IconColors.accentSlotDigit("xx0"))
        assertNull(IconColors.accentSlotDigit("xx3"))
        assertNull(IconColors.accentSlotDigit("lxx8"))
        assertNull(IconColors.accentSlotDigit("sx1"))
        assertNull(IconColors.accentSlotDigit("xx10"))
        assertNull(IconColors.accentSlotDigit("Hffffff"))
        assertNull(IconColors.accentSlotDigit(null))
    }

    @Test
    fun `multicolour changes only accent slot steps`() {
        val before = plan()
        val after = IconColors.style(IconColorStyle.MULTICOLOR, settings, before, display, true, theme)
        assertEquals(before.steps.size, after.steps.size)
        for ((b, a) in before.steps.zip(after.steps)) {
            assertEquals(b.layerPath, a.layerPath)
            assertEquals(b.slot, a.slot)
            if (IconColors.accentSlotDigit(b.slot) == null) assertEquals(b, a, "non-accent step ${b.layerPath} changed")
            else assertNotEquals(b.tintArgb, a.tintArgb, "accent step ${b.layerPath} kept its tint")
        }
    }

    @Test
    fun `multicolour keeps tone and alpha and takes the row hue with enough chroma`() {
        for (night in listOf(true, false)) {
            val before = plan(night)
            val after = IconColors.style(IconColorStyle.MULTICOLOR, settings, before, display, night, theme)
            for ((b, a) in before.steps.zip(after.steps)) {
                if (IconColors.accentSlotDigit(b.slot) == null) continue
                val bt = b.tintArgb!!
                val at = a.tintArgb!!
                assertTrue(abs(tone(bt) - tone(at)) < 1.0, "tone ${tone(bt)} → ${tone(at)} for ${b.slot}")
                assertEquals(bt ushr 24, at ushr 24, "alpha of ${b.slot}")
                assertTrue(IconColors.hueDistance(hue(at), IconColors.rowHue(display)) < 6.0, "hue ${hue(at)} for ${b.slot}")
                assertNull(a.gradientEndArgb)
            }
        }
    }

    @Test
    fun `row hues are distinct across dashboard rows`() {
        val rows = OneUiRules.load(9).dashboardRows
        assertEquals(27, rows.size)
        val hues = rows.map { IconColors.rowHue("com.android.settings:drawable/${ThemeCompiler.DASHBOARD_ICON_PREFIX}$it") }
        assertEquals(rows.size, hues.distinct().size, "rows sharing a hue: ${rows.zip(hues)}")
        // Every row is curated, none falls back to the hash wheel.
        assertTrue(rows.all { it in IconColors.ROW_HUES }, rows.filterNot { it in IconColors.ROW_HUES }.toString())
        // Stock One UI colours of the leading rows.
        assertEquals(250.0, IconColors.rowHue("connections"))
        assertEquals(160.0, IconColors.rowHue("sec_ic_settings_sound"))
        assertEquals(95.0, IconColors.rowHue("drawable/sec_ic_settings_display"))
        // The leading rows are visibly apart.
        val leading = listOf("connections", "sound", "notifications", "display", "wallpaper", "lockscreen", "biometrics_and_security", "cloud_and_account")
        for (i in leading.indices) for (j in i + 1 until leading.size) {
            val d = IconColors.hueDistance(IconColors.rowHue(leading[i]), IconColors.rowHue(leading[j]))
            assertTrue(d >= 20.0, "${leading[i]} and ${leading[j]} are only $d° apart")
        }
        // Styled rows really differ.
        val a = IconColors.style(IconColorStyle.MULTICOLOR, settings, plan(), display, true, theme).steps[1].tintArgb
        val b = IconColors.style(IconColorStyle.MULTICOLOR, settings, plan(), connections, true, theme).steps[1].tintArgb
        assertNotEquals(a, b)
    }

    @Test
    fun `my files categories and unknown names get stable hues`() {
        assertEquals(350.0, IconColors.rowHue("com.sec.android.app.myfiles:drawable/home_category_image"))
        assertEquals(350.0, IconColors.rowHue("home_category_images"))
        assertEquals(300.0, IconColors.rowHue("home_category_videos"))
        assertEquals(60.0, IconColors.rowHue("home_category_audio"))
        assertEquals(95.0, IconColors.rowHue("home_category_documents"))
        assertEquals(140.0, IconColors.rowHue("home_category_apk"))
        assertEquals(255.0, IconColors.rowHue("home_category_download"))
        val unknown = IconColors.rowHue("sec_ic_settings_brand_new_row")
        assertEquals(unknown, IconColors.rowHue("com.android.settings:drawable/sec_ic_settings_brand_new_row"))
        assertTrue(unknown in 0.0..360.0)
        assertEquals(0.0, (unknown - 15.0) % 30.0, "on the 12-step wheel: $unknown")
    }

    @Test
    fun `styling is idempotent for every style`() {
        for (style in IconColorStyle.entries) {
            for (night in listOf(true, false)) {
                for (row in listOf(display, connections, "com.android.settings:drawable/sec_ic_settings_wallpaper", "x:drawable/unknown_row")) {
                    val once = IconColors.style(style, settings, plan(night), row, night, theme)
                    val twice = IconColors.style(style, settings, once, row, night, theme)
                    assertEquals(once, twice, "$style night=$night $row")
                }
            }
        }
    }

    @Test
    fun `pack original and accent are no-ops and other elements are never styled`() {
        val p = plan()
        assertSame(p, IconColors.style(IconColorStyle.PACK_ORIGINAL, settings, p, display, true, theme))
        assertSame(p, IconColors.style(IconColorStyle.ACCENT, settings, p, display, true, theme))
        assertSame(p, IconColors.style(IconColorStyle.ACCENT, "my_files_icons", p, display, true, theme))
        for (style in IconColorStyle.entries) {
            assertSame(p, IconColors.style(style, "qs_tiles", p, display, true, theme), "$style")
            assertSame(p, IconColors.style(style, "system_drawables", p, display, true, theme), "$style")
        }
        val noAccent = CompositePlan(listOf(CompositeStep("a.png", 0xFF101010.toInt(), slot = "xx0"), CompositeStep("b.png", null)))
        assertSame(noAccent, IconColors.style(IconColorStyle.MULTICOLOR, settings, noAccent, display, true, theme))
    }

    @Test
    fun `gradient end is set only for gradient`() {
        for (style in IconColorStyle.entries) {
            val styled = IconColors.style(style, settings, plan(), display, true, theme)
            val withEnd = styled.steps.filter { it.gradientEndArgb != null }
            if (style == IconColorStyle.GRADIENT) {
                assertEquals(styled.steps.filter { IconColors.accentSlotDigit(it.slot) != null }.map { it.layerPath }, withEnd.map { it.layerPath })
                for (s in withEnd) {
                    val d = IconColors.hueDistance(hue(s.gradientEndArgb!!), hue(s.tintArgb!!))
                    assertTrue(d in 25.0..55.0, "gradient spans $d°")
                    assertEquals(s.tintArgb!! ushr 24, s.gradientEndArgb!! ushr 24)
                    assertTrue(abs(tone(s.gradientEndArgb!!) - tone(s.tintArgb!!)) < 1.0)
                }
            } else {
                assertTrue(withEnd.isEmpty(), "$style set a gradient end")
            }
        }
        // MULTICOLOR over a gradient plan drops the end again.
        val gradient = IconColors.style(IconColorStyle.GRADIENT, settings, plan(), display, true, theme)
        assertTrue(IconColors.style(IconColorStyle.MULTICOLOR, settings, gradient, display, true, theme).steps.all { it.gradientEndArgb == null })
    }

    @Test
    fun `duotone uses accent1 first and accent3 after at the slot shade`() {
        for (night in listOf(true, false)) {
            val styled = IconColors.style(IconColorStyle.DUOTONE, settings, plan(night), display, night, theme)
            val scheme = theme.scheme(night)
            val glyph = styled.steps[1] // xx1, first accent step
            val shine = styled.steps[2] // xx9
            val second = styled.steps[3] // xy5
            val r1 = SlotPalette.ROLES[1]
            val r9 = SlotPalette.ROLES[9]
            val r5 = SlotPalette.ROLES[5]
            fun shade(r: SlotRole) = if (night) r.darkShade else r.lightShade
            assertEquals(scheme.shade(PaletteRole.ACCENT1, shade(r1)), glyph.tintArgb)
            assertEquals((scheme.shade(PaletteRole.ACCENT3, shade(r9)) and 0x00FFFFFF) or (0x80 shl 24), shine.tintArgb)
            assertEquals(scheme.shade(PaletteRole.ACCENT3, shade(r5)), second.tintArgb)
            assertEquals(plan(night).steps[0], styled.steps[0])
        }
    }

    @Test
    fun `style reference restyles the reference plan for the target row`() {
        val ref = PlannedDrawableJob(display, "xxhdpi", plan(), false, com.hexnext.engine.map.DrawableSource("p", "settingsicon", "sec_ic_settings_display"), settings)
        val forSound = IconColors.styleReference(IconColorStyle.MULTICOLOR, ref, "com.android.settings:drawable/sec_ic_settings_sound", true, theme)
        assertTrue(IconColors.hueDistance(hue(forSound.plan.steps[1].tintArgb!!), 160.0) < 6.0)
        assertEquals(ref.resourceName, forSound.resourceName)
        assertSame(ref, IconColors.styleReference(IconColorStyle.PACK_ORIGINAL, ref, "x:drawable/sec_ic_settings_sound", true, theme))
    }

    @Test
    fun `badges follow the style`() {
        val multi = IconColors.badge(IconColorStyle.MULTICOLOR, "display", 95.0, night = true, theme = theme)
        assertEquals(multi, IconColors.badge(IconColorStyle.PACK_ORIGINAL, "display", 95.0, night = true, theme = theme))
        assertEquals(0xFFFFFFFF.toInt(), multi.glyphArgb)
        assertNull(multi.fillEndArgb)
        assertTrue(IconColors.hueDistance(hue(multi.fillArgb), 95.0) < 6.0)
        assertTrue(abs(tone(multi.fillArgb) - IconColors.BADGE_TONE_NIGHT) < 1.0)
        val day = IconColors.badge(IconColorStyle.MULTICOLOR, "display", 95.0, night = false, theme = theme)
        assertTrue(abs(tone(day.fillArgb) - IconColors.BADGE_TONE_DAY) < 1.0)

        val gradient = IconColors.badge(IconColorStyle.GRADIENT, "display", 95.0, night = true, theme = theme)
        assertNotNull(gradient.fillEndArgb)
        assertTrue(IconColors.hueDistance(hue(gradient.fillEndArgb!!), 135.0) < 6.0)

        val accent = IconColors.badge(IconColorStyle.ACCENT, "display", 95.0, night = true, theme = theme)
        val accentHue = hue(theme.dark.shade(PaletteRole.ACCENT1, 500))
        assertTrue(IconColors.hueDistance(hue(accent.fillArgb), accentHue) < 6.0)
        assertEquals(0xFFFFFFFF.toInt(), accent.glyphArgb)

        val duo = IconColors.badge(IconColorStyle.DUOTONE, "display", 95.0, night = false, theme = theme)
        assertNotEquals(0xFFFFFFFF.toInt(), duo.glyphArgb)
        assertTrue(tone(duo.glyphArgb) > 90.0)

        assertEquals("myfiles_images", IconColors.badgeFor(IconColorStyle.MULTICOLOR, "home_category_image", true, theme)?.glyph)
        assertEquals("display", IconColors.badgeFor(IconColorStyle.MULTICOLOR, display, true, theme)?.glyph)
        assertNull(IconColors.badgeFor(IconColorStyle.MULTICOLOR, "home_category_compressed", true, theme))
    }
}
