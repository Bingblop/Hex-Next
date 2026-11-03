package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.map.DrawableSource
import com.hexnext.engine.map.ResourceMapper
import com.hexnext.engine.mcu.hct.Hct
import com.hexnext.engine.pack.CompositePlan
import com.hexnext.engine.pack.CompositeStep
import com.hexnext.engine.palette.ColorMath
import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ResolvedTheme
import kotlin.math.abs
import kotlin.math.max

/**
 * Applies [IconColorStyle] to icon composite plans. [ThemeCompiler] calls these hooks; it never
 * styles icons itself.
 *
 * How each style treats the three kinds of icon:
 *
 * | Style | Pack icons ([style]) | Synthesised icons ([styleReference]) | Built-in icons ([builtInJobs]) |
 * |---|---|---|---|
 * | PACK_ORIGINAL | pack palette, untouched | copy the pack reference | per-row hue badge, white glyph |
 * | ACCENT | derived accent palette, untouched | copy the reference | accent1 badge, white glyph |
 * | MULTICOLOR | accent layers take the row hue | per-row hue | per-row hue badge, white glyph |
 * | DUOTONE | 1st accent layer accent1, later accent3 | same | accent1 badge, accent3 glyph |
 * | GRADIENT | MULTICOLOR + gradient to hue + 40° | same | gradient badge, white glyph |
 *
 * Only layers whose slot is an accent slot (see [accentSlotDigit]) ever change, so neutral,
 * fixed and untinted layers keep the pack's own contrast design. Row hues follow stock One UI's
 * multicolour Settings list (connections blue, sound teal-green, notifications orange …).
 *
 * Every function is pure and never touches System UI: [ICON_ELEMENTS] are Settings and My Files
 * elements only, and [builtInJobs] returns nothing for any other package.
 */
public object IconColors {
    /** Elements whose drawables are icons the style applies to. Never System UI elements. */
    public val ICON_ELEMENTS: Set<String> = setOf("settings_icons", "my_files_icons")

    /** [DrawableSource.packId] and [DrawableSource.category] of every built-in badge job. */
    public const val BUILT_IN_ID: String = "builtin"

    /** Lowest HCT chroma MULTICOLOR / GRADIENT give an accent layer, so a muted seed still reads as colour. */
    public const val MIN_CHROMA: Double = 48.0

    /** Hue step from the start to the end of a GRADIENT tint or badge, in degrees. */
    public const val GRADIENT_SHIFT: Double = 40.0

    /** HCT tone of a built-in badge fill in dark mode. */
    public const val BADGE_TONE_NIGHT: Double = 66.0

    /** HCT tone of a built-in badge fill in light mode. */
    public const val BADGE_TONE_DAY: Double = 55.0

    /** HCT chroma of a built-in badge fill (reduced only where sRGB cannot reach it). */
    public const val BADGE_CHROMA: Double = 56.0

    /**
     * A restyled colour this close (CIE76) to the current one counts as already styled and is
     * kept bit for bit; it makes [style] idempotent despite HCT round-trip rounding.
     */
    private const val SAME_COLOUR_DELTA_E: Double = 2.5

    private const val SETTINGS_PKG = "com.android.settings"
    private const val MYFILES_PKG = "com.sec.android.app.myfiles"
    private const val SETTINGS_ICONS = "settings_icons"
    private const val MY_FILES_ICONS = "my_files_icons"
    private const val MYFILES_CATEGORY_PREFIX = "home_category_"
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Accent slot of the pack palette: optional `l` (light) + `xx`/`xy`/`xz` + one digit. */
    private val ACCENT_SLOT = Regex("^l?(xx|xy|xz)([0-9])$")

    /**
     * Settings dashboard row (the part after `sec_ic_settings_`) → HCT hue, after stock One UI's
     * multicolour Settings list. Every row of `oneui/<major>.json` `dashboardRows` is listed and
     * no two share a hue, so neighbouring rows never look alike.
     */
    public val ROW_HUES: Map<String, Double> = mapOf(
        "connections" to 250.0, // blue
        "multi_devices" to 222.0, // sky blue
        "sound" to 160.0, // teal-green
        "notifications" to 55.0, // orange
        "display" to 95.0, // yellow
        "wallpaper" to 350.0, // pink
        "themes" to 335.0, // rose
        "homescreen" to 280.0, // violet-blue
        "lockscreen" to 20.0, // coral
        "biometrics_and_security" to 292.0, // violet
        "cloud_and_account" to 210.0, // cyan
        "google" to 262.0, // Google blue
        "location" to 185.0, // teal
        "accessibility" to 142.0, // green
        "galaxy_ai" to 312.0, // purple
        "digitalwellbeing" to 128.0, // leaf green
        "devicecare" to 150.0, // emerald
        "battery" to 135.0, // battery green
        "software_update" to 302.0, // purple-violet
        "about" to 240.0, // grey-blue
        "applications" to 232.0, // steel blue
        "advanced_feature" to 72.0, // amber-orange
        "general_device_management" to 245.0, // slate blue
        "emergency" to 25.0, // red
        "user_manual" to 84.0, // amber
        "modes" to 172.0, // aqua
        "tips" to 102.0, // lemon
    )

    /** My Files category (`home_category_<key>`, singular and plural forms) → glyph key and HCT hue. */
    private class MyFilesCategory(val glyph: String, val hue: Double)

    private val MY_FILES_CATEGORIES: Map<String, MyFilesCategory> = run {
        val images = MyFilesCategory("myfiles_images", 350.0) // pink
        val videos = MyFilesCategory("myfiles_videos", 300.0) // purple
        val audio = MyFilesCategory("myfiles_audio", 60.0) // orange
        val documents = MyFilesCategory("myfiles_documents", 95.0) // yellow
        val downloads = MyFilesCategory("myfiles_downloads", 255.0) // blue
        val apk = MyFilesCategory("myfiles_apk", 140.0) // green
        mapOf(
            "image" to images, "images" to images,
            "video" to videos, "videos" to videos,
            "audio" to audio,
            "document" to documents, "documents" to documents,
            "download" to downloads, "downloads" to downloads,
            "apk" to apk, "apks" to apk,
        )
    }

    /** Hues of My Files categories without built-in art (compressed, favourites …), for [rowHue]. */
    private val EXTRA_MY_FILES_HUES: Map<String, Double> = mapOf(
        "compressed" to 30.0,
        "favorites" to 80.0,
        "favourites" to 80.0,
        "recent" to 200.0,
        "trash" to 25.0,
    )

    /** True when [elementId] must be planned with the pack palette only (no derived theme palette). */
    public fun keepsPackPalette(style: IconColorStyle, elementId: String): Boolean =
        style == IconColorStyle.PACK_ORIGINAL && elementId in ICON_ELEMENTS

    /**
     * [plan] restyled for [style]. Only steps of [ICON_ELEMENTS] whose slot is an accent slot may
     * change; returns [plan] unchanged for other elements, [IconColorStyle.PACK_ORIGINAL] and
     * [IconColorStyle.ACCENT]. Must be idempotent (styling a styled plan changes nothing).
     *
     * - MULTICOLOR: the accent tint keeps its HCT tone and alpha and takes [rowHue] with chroma at
     *   least [MIN_CHROMA].
     * - DUOTONE: the first accent step takes accent1 and later ones accent3, at the shade the slot
     *   digit names in [SlotPalette.ROLES] in `theme.scheme(night)` (the light shade for an
     *   `l` slot or in day mode).
     * - GRADIENT: MULTICOLOR plus [CompositeStep.gradientEndArgb] at hue + [GRADIENT_SHIFT].
     */
    public fun style(
        style: IconColorStyle,
        elementId: String,
        plan: CompositePlan,
        resourceName: String,
        night: Boolean,
        theme: ResolvedTheme,
    ): CompositePlan {
        if (elementId !in ICON_ELEMENTS) return plan
        if (style == IconColorStyle.PACK_ORIGINAL || style == IconColorStyle.ACCENT) return plan
        if (plan.steps.none { accentSlotDigit(it.slot) != null && it.tintArgb != null }) return plan
        val hue = rowHue(resourceName)
        var accentIndex = 0
        val steps = plan.steps.map { step ->
            val tint = step.tintArgb ?: return@map step
            val slot = step.slot ?: return@map step
            val digit = accentSlotDigit(slot) ?: return@map step
            val restyled = when (style) {
                IconColorStyle.MULTICOLOR -> step.copy(tintArgb = rehue(tint, hue), gradientEndArgb = null)
                IconColorStyle.GRADIENT -> {
                    val start = rehue(tint, hue)
                    step.copy(tintArgb = start, gradientEndArgb = gradientEnd(start, hue))
                }
                IconColorStyle.DUOTONE -> {
                    val role = if (accentIndex == 0) PaletteRole.ACCENT1 else PaletteRole.ACCENT3
                    val slotRole = SlotPalette.ROLES[digit]
                    val light = !night || slot.startsWith("l")
                    val shade = if (light) slotRole.lightShade else slotRole.darkShade
                    val argb = theme.scheme(!light).shade(role, shade)
                    step.copy(tintArgb = ColorMath.withAlpha(argb, ColorMath.alpha(tint)), gradientEndArgb = null)
                }
                IconColorStyle.PACK_ORIGINAL, IconColorStyle.ACCENT -> step
            }
            accentIndex++
            restyled
        }
        return if (steps == plan.steps) plan else plan.copy(steps = steps)
    }

    /**
     * The synthesis reference for row [resourceName]: [reference] with its plan restyled for that
     * row, so each synthesised icon takes its own hue under MULTICOLOR / GRADIENT.
     */
    public fun styleReference(
        style: IconColorStyle,
        reference: PlannedDrawableJob,
        resourceName: String,
        night: Boolean,
        theme: ResolvedTheme,
    ): PlannedDrawableJob {
        val element = if (reference.elementId in ICON_ELEMENTS) reference.elementId else SETTINGS_ICONS
        val plan = style(style, element, reference.plan, resourceName, night, theme)
        return if (plan === reference.plan) reference else reference.copy(plan = plan)
    }

    /**
     * The HCT hue (0 … 360) MULTICOLOR gives [resourceName] (a `pkg:drawable/name` or bare name).
     *
     * Settings rows (`sec_ic_settings_<row>` or the bare row key) and My Files categories
     * (`home_category_<category>`) use their stock One UI colour; any other name is hashed onto a
     * 12-step colour wheel, so the same name always gets the same hue.
     */
    public fun rowHue(resourceName: String): Double {
        val key = rowKey(resourceName)
        ROW_HUES[key]?.let { return it }
        if (key.startsWith(MYFILES_CATEGORY_PREFIX)) {
            val category = key.removePrefix(MYFILES_CATEGORY_PREFIX)
            MY_FILES_CATEGORIES[category]?.let { return it.hue }
            EXTRA_MY_FILES_HUES[category]?.let { return it }
        }
        MY_FILES_CATEGORIES[key]?.let { return it.hue }
        // String.hashCode is specified by the JLS, so this is stable across runs and devices.
        return Math.floorMod(key.hashCode(), WHEEL_STEPS) * (360.0 / WHEEL_STEPS) + WHEEL_OFFSET
    }

    private const val WHEEL_STEPS = 12
    private const val WHEEL_OFFSET = 15.0

    /** `pkg:drawable/sec_ic_settings_display` → `display`; `home_category_images` stays as it is. */
    private fun rowKey(resourceName: String): String {
        val name = resourceName.substringAfter(':').substringAfterLast('/')
        return name.removePrefix(ThemeCompiler.DASHBOARD_ICON_PREFIX)
    }

    /**
     * Built-in icon jobs ([PlannedDrawableJob.badge]) for target [pkg] when [ThemeSpec.builtInIcons]
     * is on: one per Settings dashboard row ([OneUiRules.dashboardRows]) and My Files category the
     * [table] defines, in every density configuration of that drawable, day and night. Skips
     * resources in [overridden] (custom icons) and keys already in [planned] (pack and synthesis
     * jobs win; the compiler also never replaces an existing key). Never returns System UI jobs.
     * Colours follow [ThemeSpec.iconStyle]; PACK_ORIGINAL draws as MULTICOLOR.
     *
     * Built-in icons stand in for a missing pack: nothing is returned for an element that is
     * switched off (the user keeps Samsung's icons) or that has a pack chosen (the pack's own art
     * and, for Settings, icon synthesis cover it), so pack and built-in art never mix.
     */
    public fun builtInJobs(
        spec: ThemeSpec,
        pkg: String,
        table: ResourceTable,
        rules: OneUiRules,
        planned: Set<Pair<String, String>>,
        overridden: Set<String>,
        theme: ResolvedTheme,
    ): List<PlannedDrawableJob> {
        val (elementId, icons) = when (pkg) {
            SETTINGS_PKG -> SETTINGS_ICONS to rules.dashboardRows.distinct().map { row ->
                BuiltInIcon(ThemeCompiler.DASHBOARD_ICON_PREFIX + row, row, rowHue(row))
            }
            MYFILES_PKG -> MY_FILES_ICONS to myFilesIcons(table)
            else -> return emptyList()
        }
        if (!spec.isElementEnabled(elementId) || spec.packFor(elementId) != null) return emptyList()
        val out = ArrayList<PlannedDrawableJob>()
        val badges = HashMap<Boolean, HashMap<String, IconBadge>>()
        for (icon in icons) {
            val resourceName = "$pkg:drawable/${icon.name}"
            if (resourceName in overridden) continue
            val entry = table.find("drawable", icon.name) ?: continue
            val densities = ResourceMapper.pngDensities(entry.values.map { it.resConfig.densityQualifier })
            for (density in densities) {
                for (night in DAY_AND_NIGHT) {
                    val config = ThemeCompiler.configFor(night, density)
                    if ((resourceName to config) in planned) continue
                    val badge = badges.getOrPut(night) { HashMap() }.getOrPut(icon.glyph) {
                        badge(spec.iconStyle, icon.glyph, icon.hue, night, theme)
                    }
                    out += PlannedDrawableJob(
                        resourceName, config, CompositePlan(emptyList()), ninePatch = false,
                        source = DrawableSource(BUILT_IN_ID, BUILT_IN_ID, icon.name), elementId = elementId, badge = badge,
                    )
                }
            }
        }
        return out
    }

    private val DAY_AND_NIGHT = listOf(false, true)

    /** One built-in icon: target drawable [name], glyph key and hue. */
    private class BuiltInIcon(val name: String, val glyph: String, val hue: Double)

    /** My Files category drawables of [table] that have built-in art, in table order, one per glyph. */
    private fun myFilesIcons(table: ResourceTable): List<BuiltInIcon> {
        val seen = HashSet<String>()
        val out = ArrayList<BuiltInIcon>()
        for (entry in table.entries) {
            if (entry.type != "drawable" || !entry.name.startsWith(MYFILES_CATEGORY_PREFIX)) continue
            val category = MY_FILES_CATEGORIES[entry.name.removePrefix(MYFILES_CATEGORY_PREFIX)] ?: continue
            if (!seen.add(entry.name)) continue
            out += BuiltInIcon(entry.name, category.glyph, category.hue)
        }
        return out
    }

    /**
     * The badge colours of built-in glyph [glyph] (row hue [hue]) for [style] in day or [night]
     * mode. Public so the app preview draws exactly what apply fabricates.
     */
    public fun badge(style: IconColorStyle, glyph: String, hue: Double, night: Boolean, theme: ResolvedTheme): IconBadge {
        val tone = if (night) BADGE_TONE_NIGHT else BADGE_TONE_DAY
        return when (style) {
            IconColorStyle.PACK_ORIGINAL, IconColorStyle.MULTICOLOR ->
                IconBadge(glyph, ColorMath.clipToGamut(hue, BADGE_CHROMA, tone), null, WHITE)
            IconColorStyle.GRADIENT -> IconBadge(
                glyph,
                ColorMath.clipToGamut(hue, BADGE_CHROMA, tone),
                ColorMath.clipToGamut(normalise(hue + GRADIENT_SHIFT), BADGE_CHROMA, tone),
                WHITE,
            )
            IconColorStyle.ACCENT -> IconBadge(glyph, atTone(theme.scheme(night).shade(PaletteRole.ACCENT1, 500), tone), null, WHITE)
            IconColorStyle.DUOTONE -> {
                val scheme = theme.scheme(night)
                // A dark glyph on the light night badge, a near-white tinted glyph on the day badge.
                val glyphTone = if (night) DUOTONE_GLYPH_TONE_NIGHT else DUOTONE_GLYPH_TONE_DAY
                IconBadge(
                    glyph,
                    atTone(scheme.shade(PaletteRole.ACCENT1, 500), tone),
                    null,
                    atTone(scheme.shade(PaletteRole.ACCENT3, 500), glyphTone),
                )
            }
        }
    }

    /** The badge of built-in icon [resourceName] (`sec_ic_settings_<row>` or a My Files category); null without built-in art. */
    public fun badgeFor(style: IconColorStyle, resourceName: String, night: Boolean, theme: ResolvedTheme): IconBadge? {
        val name = resourceName.substringAfter(':').substringAfterLast('/')
        if (name.startsWith(MYFILES_CATEGORY_PREFIX)) {
            val category = MY_FILES_CATEGORIES[name.removePrefix(MYFILES_CATEGORY_PREFIX)] ?: return null
            return badge(style, category.glyph, category.hue, night, theme)
        }
        val row = name.removePrefix(ThemeCompiler.DASHBOARD_ICON_PREFIX)
        return badge(style, row, rowHue(row), night, theme)
    }

    /** My Files category drawables with built-in art, in preview order: images, videos, audio, documents, downloads, apk. */
    public val MY_FILES_PREVIEW: List<String> = listOf(
        "home_category_image", "home_category_video", "home_category_audio",
        "home_category_documents", "home_category_download", "home_category_apk",
    )

    private const val DUOTONE_GLYPH_TONE_NIGHT = 18.0
    private const val DUOTONE_GLYPH_TONE_DAY = 96.0

    /** [argb]'s hue and chroma (at least a little colour) at [tone]. */
    private fun atTone(argb: Int, tone: Double): Int {
        val hct = Hct.fromInt(argb)
        return ColorMath.clipToGamut(hct.hue, max(hct.chroma, MIN_ACCENT_BADGE_CHROMA), tone)
    }

    private const val MIN_ACCENT_BADGE_CHROMA = 24.0

    /** Digit (0–9) of an accent slot such as `xx1` / `lxy5` whose role is an accent; null otherwise. */
    internal fun accentSlotDigit(slot: String?): Int? {
        val match = ACCENT_SLOT.matchEntire(slot ?: return null) ?: return null
        val digit = match.groupValues[2].toInt()
        return digit.takeIf { SlotPalette.ROLES[it].role in ACCENT_ROLES }
    }

    private val ACCENT_ROLES = setOf(PaletteRole.ACCENT1, PaletteRole.ACCENT2, PaletteRole.ACCENT3)

    /** [tint] with HCT hue [hue], its own tone and alpha, chroma at least [MIN_CHROMA]; [tint] itself when that is the same colour. */
    private fun rehue(tint: Int, hue: Double): Int {
        val hct = Hct.fromInt(tint)
        val target = ColorMath.clipToGamut(hue, max(MIN_CHROMA, hct.chroma), hct.tone)
        if (ColorMath.deltaE76(target, tint) < SAME_COLOUR_DELTA_E) return tint
        return ColorMath.withAlpha(target, ColorMath.alpha(tint))
    }

    /** The gradient end for a restyled [start] tint: hue + [GRADIENT_SHIFT] at the same tone, chroma and alpha. */
    private fun gradientEnd(start: Int, hue: Double): Int {
        val hct = Hct.fromInt(start)
        val end = ColorMath.clipToGamut(normalise(hue + GRADIENT_SHIFT), max(MIN_CHROMA, hct.chroma), hct.tone)
        return ColorMath.withAlpha(end, ColorMath.alpha(start))
    }

    private fun normalise(hue: Double): Double = ((hue % 360.0) + 360.0) % 360.0

    /** Smallest angle between two hues, in degrees (0 … 180). */
    internal fun hueDistance(a: Double, b: Double): Double {
        val d = abs(normalise(a) - normalise(b))
        return if (d > 180.0) 360.0 - d else d
    }
}
