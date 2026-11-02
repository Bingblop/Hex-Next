package com.hexnext.engine.compile

/**
 * Constants and [ThemeSpec] helpers for user-imported assets (custom icons, backgrounds, fonts).
 *
 * Keys of [ThemeSpec.customIcons] / [ThemeSpec.customBackgrounds] are `pkg:drawable/name`; values
 * are file names under the app's `custom/<icons|backgrounds>` directory. The compiler turns an
 * entry into a [PlannedDrawableJob] with [PlannedDrawableJob.custom] set and
 * [PlannedDrawableJob.source] = `DrawableSource(PACK_ID, CATEGORY_ICON | CATEGORY_BACKGROUND, file)`.
 */
public object CustomAssets {
    /** Prefix marking a custom asset in UI-facing identifiers (`custom:<file>`). */
    public const val ICON_PREFIX: String = "custom:"

    /** [com.hexnext.engine.map.DrawableSource.packId] of every custom job. */
    public const val PACK_ID: String = "custom"

    /** [com.hexnext.engine.map.DrawableSource.category] of a custom icon job. */
    public const val CATEGORY_ICON: String = "custom-icon"

    /** [com.hexnext.engine.map.DrawableSource.category] of a custom background job. */
    public const val CATEGORY_BACKGROUND: String = "custom-background"
}

/** This spec with the icon of [key] (`pkg:drawable/name`) replaced by [file]; null removes the override. Pure. */
public fun ThemeSpec.withCustomIcon(key: String, file: String?): ThemeSpec =
    copy(customIcons = if (file == null) customIcons - key else customIcons + (key to file))

/** This spec with the background of [key] (`pkg:drawable/name`) replaced by [file]; null removes the override. Pure. */
public fun ThemeSpec.withCustomBackground(key: String, file: String?): ThemeSpec =
    copy(customBackgrounds = if (file == null) customBackgrounds - key else customBackgrounds + (key to file))

/** This spec with [font] as the system font; null restores the system default. Pure. */
public fun ThemeSpec.withFont(font: CustomFont?): ThemeSpec = copy(customFont = font)

/** This spec with icon synthesis for missing dashboard rows switched on or off. Pure. */
public fun ThemeSpec.withSynthesizeMissingIcons(enabled: Boolean): ThemeSpec = copy(synthesizeMissingIcons = enabled)
