package com.hexnext.engine.compile

import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ResolvedTheme

/** Where a [ColorGroup] is shown in the Colour Studio. */
public enum class ColorGroupSection(public val label: String) {
    CORE("Core"),
    TEXT_AND_ICONS("Text and icons"),
    CONTROLS("Controls"),
    QUICK_PANEL("Quick panel"),
    APPS("Apps"),
    SYSTEM("System"),
}

/**
 * A One UI element colour group: a set of colour resources, across every Samsung target, that the
 * user colours as one ("Switches", "Quick panel tiles: on"). Which resources belong to a group is decided
 * by `ColorGroupMatcher`; this type is only the catalogue.
 *
 * [id] is persisted in [ThemeSpec.colorGroups] and must never change; constants may be added but
 * not removed or renamed.
 *
 * Auto (no user colour) is [autoRole] at [autoLightShade] in day configurations and
 * [autoDarkShade] in night ones, from the resolved theme.
 *
 * @property quickSettings true for groups that only reach System UI Quick Settings colours, which
 *   are always colour-only.
 * @property contrastOn for a group drawn on top of another (tile icons, panel text): Auto is not
 *   [autoRole] but the palette's dark ink (neutral1 900) or light ink (neutral1 10), whichever
 *   reads better on that group's effective colour, so the icons follow a custom tile colour.
 *   [autoRole] and its shades are then only the ink used on the base group's own Auto colour.
 */
public enum class ColorGroup(
    public val id: String,
    public val label: String,
    public val description: String,
    public val section: ColorGroupSection,
    public val autoRole: PaletteRole,
    public val autoLightShade: Int,
    public val autoDarkShade: Int,
    public val quickSettings: Boolean = false,
    public val contrastOn: ColorGroup? = null,
) {
    ACCENT("accent", "Accent", "Highlights, links, selected items and progress", ColorGroupSection.CORE, PaletteRole.ACCENT1, 600, 200),
    BACKGROUND("background", "Background", "Screen backgrounds of Settings and Samsung apps", ColorGroupSection.CORE, PaletteRole.NEUTRAL1, 50, 1000),
    SURFACE("surface", "Surface and cards", "Rounded cards, list containers and app bars", ColorGroupSection.CORE, PaletteRole.NEUTRAL1, 10, 900),
    TEXT_PRIMARY("text_primary", "Primary text", "Titles and main labels", ColorGroupSection.TEXT_AND_ICONS, PaletteRole.NEUTRAL1, 900, 50),
    TEXT_SECONDARY("text_secondary", "Secondary text", "Summaries, hints and captions", ColorGroupSection.TEXT_AND_ICONS, PaletteRole.NEUTRAL2, 700, 200),
    ICON_TINT("icon_tint", "Icon tint", "Tinted vector icons in toolbars and lists", ColorGroupSection.TEXT_AND_ICONS, PaletteRole.ACCENT2, 600, 200),
    SWITCH("switch", "Switches and toggles", "Switch thumbs and tracks, checkboxes, radio buttons", ColorGroupSection.CONTROLS, PaletteRole.ACCENT1, 600, 300),
    BUTTON("button", "Buttons", "Filled and text buttons", ColorGroupSection.CONTROLS, PaletteRole.ACCENT1, 600, 200),
    SLIDER("slider", "Brightness and volume sliders", "Seek bars, brightness and volume sliders", ColorGroupSection.CONTROLS, PaletteRole.ACCENT1, 500, 300),
    QS_PANEL_BG("qs_panel_bg", "Quick panel background", "Quick panel and expanded detail backdrop", ColorGroupSection.QUICK_PANEL, PaletteRole.NEUTRAL1, 100, 900, quickSettings = true),
    QS_TILE_ACTIVE("qs_tile_active", "Quick panel tiles: on", "Tiles that are switched on", ColorGroupSection.QUICK_PANEL, PaletteRole.ACCENT1, 500, 300, quickSettings = true),
    QS_TILE_INACTIVE("qs_tile_inactive", "Quick panel tiles: off", "Tiles that are switched off", ColorGroupSection.QUICK_PANEL, PaletteRole.NEUTRAL2, 100, 800, quickSettings = true),
    QS_TILE_ICON_ON(
        "qs_tile_icon_on", "Quick panel icons: on", "Icons on tiles that are switched on and on the brightness slider",
        ColorGroupSection.QUICK_PANEL, PaletteRole.NEUTRAL1, 10, 900, quickSettings = true, contrastOn = QS_TILE_ACTIVE,
    ),
    QS_TILE_ICON_OFF(
        "qs_tile_icon_off", "Quick panel icons: off", "Icons on tiles that are switched off",
        ColorGroupSection.QUICK_PANEL, PaletteRole.NEUTRAL1, 900, 10, quickSettings = true, contrastOn = QS_TILE_INACTIVE,
    ),
    QS_TEXT(
        "qs_text", "Quick panel text", "Clock, date, carrier, header icons, tile labels and detail titles",
        ColorGroupSection.QUICK_PANEL, PaletteRole.NEUTRAL1, 900, 10, quickSettings = true, contrastOn = QS_PANEL_BG,
    ),
    NOTIFICATION_BG("notification_bg", "Notification background", "Notification cards and the notification shade", ColorGroupSection.QUICK_PANEL, PaletteRole.NEUTRAL1, 10, 800),
    DIALOG("dialog", "Dialogs and popups", "Dialogs, popup menus and bottom sheets", ColorGroupSection.SYSTEM, PaletteRole.NEUTRAL1, 10, 800),
    KEYBOARD_BG("keyboard_bg", "Keyboard background", "Samsung Keyboard backdrop", ColorGroupSection.APPS, PaletteRole.NEUTRAL2, 50, 900),
    KEYBOARD_KEY("keyboard_key", "Keyboard keys", "Samsung Keyboard key caps", ColorGroupSection.APPS, PaletteRole.NEUTRAL1, 10, 800),
    DIALER("dialer", "Dialer and in-call", "Phone dialpad, call buttons and the in-call screen", ColorGroupSection.APPS, PaletteRole.ACCENT1, 600, 200),
    LOCK_SCREEN("lock_screen", "Lock screen and AOD clock", "Lock screen and Always On Display clock and text", ColorGroupSection.SYSTEM, PaletteRole.ACCENT1, 100, 100),
    SYSTEM_BARS("system_bars", "Navigation and status bar", "Navigation bar and status bar backgrounds", ColorGroupSection.SYSTEM, PaletteRole.NEUTRAL1, 50, 900),
    ;

    /**
     * The Auto colour of this group in [theme], for night ([night] true) or day, with every other
     * group on Auto. For a [contrastOn] group that is the ink on the base group's Auto colour;
     * [ThemeSpec.effectiveGroupColor] uses the base group's effective colour instead.
     */
    public fun auto(theme: ResolvedTheme, night: Boolean): Int {
        val base = contrastOn ?: return theme.scheme(night).shade(autoRole, if (night) autoDarkShade else autoLightShade)
        return inkOn(theme, night, inkBackground(base.auto(theme, night), night))
    }

    /**
     * The colour this group's ink actually sits on when its base is [baseColor]. One UI 9 draws the
     * Quick panel background see-through over the blurred, dimmed shade, and the header clock, date,
     * buttons and big-tile labels sit right on that shade, so [QS_TEXT] is judged against the panel
     * colour blended over the shade's dim, not against the opaque panel colour (which picked dark
     * text on a dark shade for a light panel colour). Only at night: the day shade's frosting
     * depends on the wallpaper, and the opaque colour is the better guess there.
     */
    internal fun inkBackground(baseColor: Int, night: Boolean): Int =
        if (night && contrastOn == QS_PANEL_BG) blend(baseColor, NIGHT_SHADE_DIM, PANEL_OPACITY) else baseColor

    public companion object {
        /**
         * The palette ink for night or day that reads better on [background] (its alpha ignored):
         * neutral1 900 or neutral1 10, by WCAG contrast.
         */
        public fun inkOn(theme: ResolvedTheme, night: Boolean, background: Int): Int {
            val scheme = theme.scheme(night)
            val dark = scheme.shade(PaletteRole.NEUTRAL1, DARK_INK_SHADE)
            val light = scheme.shade(PaletteRole.NEUTRAL1, LIGHT_INK_SHADE)
            return if (contrast(dark, background) >= contrast(light, background)) dark else light
        }

        /** Every group, in Studio order. */
        public val ALL: List<ColorGroup> = entries

        /** The group with [id], null when unknown (for example written by a newer build). */
        public fun byId(id: String): ColorGroup? = entries.firstOrNull { it.id == id }

        /** Groups in [section], in Studio order. */
        public fun inSection(section: ColorGroupSection): List<ColorGroup> = entries.filter { it.section == section }
    }
}

/** The user colour of [group] for night or day, null for Auto. */
public fun ThemeSpec.groupColor(group: ColorGroup, night: Boolean): Int? = colorGroups[group.id]?.forNight(night)

/**
 * The colour [group] shows: the user colour, else Auto from [theme]. A [ColorGroup.contrastOn]
 * group on Auto is the ink that reads on its base group's effective colour (custom or Auto).
 */
public fun ThemeSpec.effectiveGroupColor(group: ColorGroup, theme: ResolvedTheme, night: Boolean): Int {
    groupColor(group, night)?.let { return it }
    val base = group.contrastOn ?: return group.auto(theme, night)
    return ColorGroup.inkOn(theme, night, group.inkBackground(effectiveGroupColor(base, theme, night), night))
}

/** Every group's effective colour for night or day (for previews). */
public fun ThemeSpec.effectiveGroupColors(theme: ResolvedTheme, night: Boolean): Map<ColorGroup, Int> =
    ColorGroup.ALL.associateWith { effectiveGroupColor(it, theme, night) }

/**
 * Sets [group]'s colour for night ([night] true) or day; null returns that mode to Auto. A group
 * with both modes on Auto is removed from [ThemeSpec.colorGroups]. Colours are stored opaque.
 */
public fun ThemeSpec.withGroupColor(group: ColorGroup, night: Boolean, argb: Int?): ThemeSpec {
    val value = argb?.let { it or OPAQUE_ALPHA }
    val current = colorGroups[group.id] ?: GroupColor()
    val next = if (night) current.copy(dark = value) else current.copy(light = value)
    return copy(colorGroups = if (next.isAuto) colorGroups - group.id else colorGroups + (group.id to next))
}

/** Returns every group, in both modes, to Auto. */
public fun ThemeSpec.withAllGroupsAuto(): ThemeSpec = copy(colorGroups = emptyMap())

private const val OPAQUE_ALPHA: Int = 0xFF000000.toInt()
private const val DARK_INK_SHADE: Int = 900
private const val LIGHT_INK_SHADE: Int = 10

/** How much of the Quick panel background colour shows over the shade (One UI 9 blur panel). */
private const val PANEL_OPACITY: Double = 0.35
private const val NIGHT_SHADE_DIM: Int = 0xFF000000.toInt()

/** [top] at [opacity] over opaque [under]; the result is opaque. */
private fun blend(top: Int, under: Int, opacity: Double): Int {
    fun ch(shift: Int): Int {
        val t = (top shr shift) and 0xFF
        val u = (under shr shift) and 0xFF
        return Math.round(t * opacity + u * (1 - opacity)).toInt().coerceIn(0, 255)
    }
    return OPAQUE_ALPHA or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** WCAG contrast ratio of two colours, alpha ignored. */
private fun contrast(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** WCAG relative luminance of an sRGB colour. */
private fun luminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
