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
    NOTIFICATION_BG("notification_bg", "Notification background", "Notification cards and the notification shade", ColorGroupSection.QUICK_PANEL, PaletteRole.NEUTRAL1, 10, 800),
    DIALOG("dialog", "Dialogs and popups", "Dialogs, popup menus and bottom sheets", ColorGroupSection.SYSTEM, PaletteRole.NEUTRAL1, 10, 800),
    KEYBOARD_BG("keyboard_bg", "Keyboard background", "Samsung Keyboard backdrop", ColorGroupSection.APPS, PaletteRole.NEUTRAL2, 50, 900),
    KEYBOARD_KEY("keyboard_key", "Keyboard keys", "Samsung Keyboard key caps", ColorGroupSection.APPS, PaletteRole.NEUTRAL1, 10, 800),
    DIALER("dialer", "Dialer and in-call", "Phone dialpad, call buttons and the in-call screen", ColorGroupSection.APPS, PaletteRole.ACCENT1, 600, 200),
    LOCK_SCREEN("lock_screen", "Lock screen and AOD clock", "Lock screen and Always On Display clock and text", ColorGroupSection.SYSTEM, PaletteRole.ACCENT1, 100, 100),
    SYSTEM_BARS("system_bars", "Navigation and status bar", "Navigation bar and status bar backgrounds", ColorGroupSection.SYSTEM, PaletteRole.NEUTRAL1, 50, 900),
    ;

    /** The Auto colour of this group in [theme], for night ([night] true) or day. */
    public fun auto(theme: ResolvedTheme, night: Boolean): Int =
        theme.scheme(night).shade(autoRole, if (night) autoDarkShade else autoLightShade)

    public companion object {
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

/** The colour [group] shows: the user colour, else Auto from [theme]. */
public fun ThemeSpec.effectiveGroupColor(group: ColorGroup, theme: ResolvedTheme, night: Boolean): Int =
    groupColor(group, night) ?: group.auto(theme, night)

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
