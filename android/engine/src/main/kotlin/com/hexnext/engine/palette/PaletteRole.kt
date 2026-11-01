package com.hexnext.engine.palette

import com.hexnext.engine.mcu.dynamiccolor.DynamicScheme
import com.hexnext.engine.mcu.palettes.TonalPalette

/**
 * A tonal palette of a scheme. The first five are Android's Monet palettes
 * (`system_accent1_*` … `system_neutral2_*`); [ERROR] has no system shades.
 */
public enum class PaletteRole(
    /** Name used in Android resource names (`system_<name>_<shade>`), `null` for [ERROR]. */
    public val systemName: String?,
) {
    /** Primary palette. */
    ACCENT1("accent1"),

    /** Secondary palette. */
    ACCENT2("accent2"),

    /** Tertiary palette. */
    ACCENT3("accent3"),

    /** Neutral palette (surfaces, text). */
    NEUTRAL1("neutral1"),

    /** Neutral-variant palette (outlines, variants). */
    NEUTRAL2("neutral2"),

    /** Error palette. */
    ERROR(null);

    /** True for [NEUTRAL1] and [NEUTRAL2]. */
    public val isNeutral: Boolean get() = this == NEUTRAL1 || this == NEUTRAL2

    /** True for [ACCENT1]..[ACCENT3]. */
    public val isAccent: Boolean get() = this == ACCENT1 || this == ACCENT2 || this == ACCENT3

    /** The MCU palette of [scheme] backing this role. */
    public fun palette(scheme: DynamicScheme): TonalPalette = when (this) {
        ACCENT1 -> scheme.primaryPalette
        ACCENT2 -> scheme.secondaryPalette
        ACCENT3 -> scheme.tertiaryPalette
        NEUTRAL1 -> scheme.neutralPalette
        NEUTRAL2 -> scheme.neutralVariantPalette
        ERROR -> scheme.errorPalette
    }

    public companion object {
        /** The five roles that have Android system shades, in canonical order. */
        public val SHADE_ROLES: List<PaletteRole> = listOf(ACCENT1, ACCENT2, ACCENT3, NEUTRAL1, NEUTRAL2)

        /** Role for a system name such as `"accent1"`, or `null`. */
        public fun fromSystemName(name: String): PaletteRole? = SHADE_ROLES.firstOrNull { it.systemName == name }

        /** The role whose palette in [scheme] is [palette] (identity), or `null`. */
        public fun ofPalette(scheme: DynamicScheme, palette: TonalPalette): PaletteRole? =
            entries.firstOrNull { it.palette(scheme) === palette }
    }
}

/** Android's 13 Monet shades and their HCT tones. */
public object ShadeScale {
    /** Shade numbers, lightest first. */
    public val SHADES: List<Int> = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

    /** HCT tones matching [SHADES] index for index. */
    public val TONES: List<Int> = listOf(100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0)

    /** Number of shades per role (13). */
    public val SIZE: Int get() = SHADES.size

    /** All 65 system keys (`system_accent1_0` … `system_neutral2_1000`) in canonical order. */
    public val ALL_KEYS: List<String> = PaletteRole.SHADE_ROLES.flatMap { r -> SHADES.map { key(r, it) } }

    /** Tone of an Android shade number; throws for unknown shades. */
    public fun toneOf(shade: Int): Int {
        val i = SHADES.indexOf(shade)
        require(i >= 0) { "unknown shade $shade" }
        return TONES[i]
    }

    /** Shade number of an HCT tone listed in [TONES]; throws otherwise. */
    public fun shadeOf(tone: Int): Int {
        val i = TONES.indexOf(tone)
        require(i >= 0) { "tone $tone is not a shade tone" }
        return SHADES[i]
    }

    /** Index (0..12) of an Android shade number, or -1. */
    public fun indexOf(shade: Int): Int = SHADES.indexOf(shade)

    /** System key `system_<role>_<shade>`; [role] must have a [PaletteRole.systemName]. */
    public fun key(role: PaletteRole, shade: Int): String {
        val n = requireNotNull(role.systemName) { "$role has no system shades" }
        return "system_${n}_$shade"
    }

    /**
     * Parses `system_accent1_500` or the short form `accent1_500` to (role, shade index),
     * or returns `null` for anything else.
     */
    public fun parseKey(key: String): Pair<PaletteRole, Int>? {
        val k = key.removePrefix("system_")
        val sep = k.lastIndexOf('_')
        if (sep <= 0) return null
        val role = PaletteRole.fromSystemName(k.substring(0, sep)) ?: return null
        val shade = k.substring(sep + 1).toIntOrNull() ?: return null
        val idx = indexOf(shade)
        return if (idx < 0) null else role to idx
    }
}
