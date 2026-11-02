package com.hexnext.engine.palette

/**
 * Pitch black (dark theme only).
 *
 * * Neutral surface roles ([SURFACE_ROLES], plus [CONTAINER_ROLES] for
 *   [PitchBlack.SURFACES_AND_CONTAINERS]) get tone `max(0, tone − surfaceTone) × 1.15`, where
 *   `surfaceTone` is the dark `surface` tone of the scheme's spec (6 for 2021, 4 for 2025+). The
 *   remap is applied to the scheme tone before the background-lightness curve (both curves keep
 *   tone 0 at 0, so black stays black).
 * * `system_neutral1_900` becomes opaque black.
 */
public object PitchBlackRule {
    /** Roles treated as surfaces. */
    public val SURFACE_ROLES: Set<String> = setOf("background", "surface", "surface_dim", "surface_bright")

    /** Container roles, included only with [PitchBlack.SURFACES_AND_CONTAINERS]. */
    public val CONTAINER_ROLES: Set<String> = setOf(
        "surface_container_lowest", "surface_container_low", "surface_container",
        "surface_container_high", "surface_container_highest",
    )

    /** Tone multiplier above the surface tone. */
    public const val TONE_SCALE: Double = 1.15

    /** The shade forced to black. */
    public const val BLACK_SHADE_KEY: String = "system_neutral1_900"

    private const val BLACK: Int = 0xFF000000.toInt()

    /** Whether role [name] is remapped in this mode. */
    public fun appliesTo(name: String, isDark: Boolean, mode: PitchBlack): Boolean = when {
        !isDark || mode == PitchBlack.OFF -> false
        name in SURFACE_ROLES -> true
        name in CONTAINER_ROLES -> mode == PitchBlack.SURFACES_AND_CONTAINERS
        else -> false
    }

    /** Remapped tone `max(0, tone − surfaceTone) × 1.15`, capped at 100. */
    public fun tone(tone: Double, surfaceTone: Double): Double =
        ((tone - surfaceTone).coerceAtLeast(0.0) * TONE_SCALE).coerceAtMost(100.0)

    /** [shades] with [BLACK_SHADE_KEY] set to black when the rule is active. */
    public fun applyToShades(shades: Map<String, Int>, isDark: Boolean, mode: PitchBlack): Map<String, Int> {
        if (!isDark || mode == PitchBlack.OFF) return shades
        return LinkedHashMap(shades).apply { this[BLACK_SHADE_KEY] = BLACK }
    }
}

/**
 * Tinted text off: text roles become pure white when their L* ≥ 50, otherwise pure black,
 * keeping their alpha. Text roles are those named `on_*`, `inverse_on_surface` and the
 * Android `text_*` roles.
 */
public object TintlessTextRule {
    /** Whether [name] is a text role. */
    public fun isTextRole(name: String): Boolean =
        name.startsWith("on_") || name == "inverse_on_surface" || name.startsWith("text_")

    /** Pure white or black for [argb] by lightness, alpha kept. */
    public fun neutralize(argb: Int): Int {
        val rgb = if (ColorMath.lstar(argb) >= 50.0) 0xFFFFFF else 0x000000
        return (argb and 0xFF000000.toInt()) or rgb
    }

    /** [roles] with text roles neutralised when [tintedText] is false. */
    public fun apply(roles: Map<String, Int>, tintedText: Boolean): Map<String, Int> {
        if (tintedText) return roles
        return roles.mapValuesTo(LinkedHashMap()) { (k, v) -> if (isTextRole(k)) neutralize(v) else v }
    }
}
