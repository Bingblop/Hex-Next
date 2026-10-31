package com.hexnext.themer.theme

import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeFidelity
import com.google.android.material.color.utilities.SchemeFruitSalad
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeNeutral
import com.google.android.material.color.utilities.SchemeRainbow
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant
import com.google.android.material.color.utilities.TonalPalette

/**
 * Palette styles. [systemUiName] is the matching `theme_style` value System UI understands,
 * used when only the seed color can be handed to System UI (non-root Shizuku).
 */
enum class PaletteStyle(val label: String, val systemUiName: String) {
    TONAL_SPOT("Tonal spot", "TONAL_SPOT"),
    VIBRANT("Vibrant", "VIBRANT"),
    EXPRESSIVE("Expressive", "EXPRESSIVE"),
    FIDELITY("Fidelity", "CONTENT"),
    CONTENT("Content", "CONTENT"),
    NEUTRAL("Neutral", "SPRITZ"),
    MONOCHROME("Monochrome", "MONOCHROMATIC"),
    RAINBOW("Rainbow", "RAINBOW"),
    FRUIT_SALAD("Fruit salad", "FRUIT_SALAD");

    fun scheme(seed: Int, dark: Boolean): DynamicScheme {
        val hct = Hct.fromInt(seed)
        return when (this) {
            TONAL_SPOT -> SchemeTonalSpot(hct, dark, 0.0)
            VIBRANT -> SchemeVibrant(hct, dark, 0.0)
            EXPRESSIVE -> SchemeExpressive(hct, dark, 0.0)
            FIDELITY -> SchemeFidelity(hct, dark, 0.0)
            CONTENT -> SchemeContent(hct, dark, 0.0)
            NEUTRAL -> SchemeNeutral(hct, dark, 0.0)
            MONOCHROME -> SchemeMonochrome(hct, dark, 0.0)
            RAINBOW -> SchemeRainbow(hct, dark, 0.0)
            FRUIT_SALAD -> SchemeFruitSalad(hct, dark, 0.0)
        }
    }
}

/** The five Monet tonal groups exposed by the framework as `android:color/system_<group>_<shade>`. */
enum class ToneGroup(val resourcePrefix: String, val label: String) {
    ACCENT1("system_accent1", "Primary"),
    ACCENT2("system_accent2", "Secondary"),
    ACCENT3("system_accent3", "Tertiary"),
    NEUTRAL1("system_neutral1", "Neutral"),
    NEUTRAL2("system_neutral2", "Neutral variant"),
}

data class ThemeSpec(
    val seed: Int,
    val style: PaletteStyle,
    /** Paints dark-theme backgrounds pure black, for AMOLED screens. */
    val pureBlack: Boolean,
)

/** One fabricated-overlay value: `<package>:color/<name>` = [argb]. */
data class ColorEntry(val name: String, val argb: Int) {
    val resourceName get() = if (":" in name) name else "android:color/$name"
}

class MonetPalette private constructor(
    val spec: ThemeSpec,
    /** Colors per group, ordered like [SHADES]. */
    val shades: Map<ToneGroup, IntArray>,
    private val light: DynamicScheme,
    private val dark: DynamicScheme,
) {

    /** The 65 `system_accentN_*` / `system_neutralN_*` shade colors (Android 12+). */
    fun shadeEntries(): List<ColorEntry> = ToneGroup.entries.flatMap { group ->
        val colors = shades.getValue(group)
        SHADES.mapIndexed { i, shade -> ColorEntry("${group.resourcePrefix}_$shade", colors[i]) }
    }

    /**
     * The Material 3 role colors (`system_primary_light`, `system_surface_container_dark`, ...)
     * that Android 14+ apps read directly. Kept separate from the shades so a framework that lacks
     * one of these names can't take the shade overlay down with it.
     */
    fun roleEntries(): List<ColorEntry> {
        val out = ArrayList<ColorEntry>(ROLES.size * 2 + FIXED_ROLES.size)
        for ((name, role) in ROLES) {
            out += ColorEntry("system_${name}_light", role(COLORS).getArgb(light))
            out += ColorEntry("system_${name}_dark", darkRole(name, role(COLORS).getArgb(dark)))
        }
        for ((name, role) in FIXED_ROLES) {
            out += ColorEntry("system_$name", role(COLORS).getArgb(light))
        }
        return out
    }

    private fun darkRole(name: String, argb: Int): Int =
        if (spec.pureBlack && name in PURE_BLACK_ROLES) BLACK else argb

    companion object {
        /** Framework shade suffixes, lightest to darkest. */
        val SHADES = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

        private const val BLACK = 0xFF000000.toInt()

        private val COLORS = MaterialDynamicColors()

        /** Maps a framework shade to an HCT tone, as System UI's ColorScheme does. */
        fun toneForShade(shade: Int): Int = when (shade) {
            0 -> 100
            10 -> 99
            50 -> 95
            else -> 100 - shade / 10
        }

        fun generate(spec: ThemeSpec): MonetPalette {
            val light = spec.style.scheme(spec.seed, dark = false)
            val dark = spec.style.scheme(spec.seed, dark = true)
            val palettes: Map<ToneGroup, TonalPalette> = mapOf(
                ToneGroup.ACCENT1 to light.primaryPalette,
                ToneGroup.ACCENT2 to light.secondaryPalette,
                ToneGroup.ACCENT3 to light.tertiaryPalette,
                ToneGroup.NEUTRAL1 to light.neutralPalette,
                ToneGroup.NEUTRAL2 to light.neutralVariantPalette,
            )
            val shades = palettes.mapValues { (group, palette) ->
                IntArray(SHADES.size) { i ->
                    val shade = SHADES[i]
                    if (spec.pureBlack && group == ToneGroup.NEUTRAL1 && shade >= 900) BLACK
                    else palette.tone(toneForShade(shade))
                }
            }
            return MonetPalette(spec, shades, light, dark)
        }

        private val ROLES: List<Pair<String, (MaterialDynamicColors) -> com.google.android.material.color.utilities.DynamicColor>> = listOf(
            "primary" to { it.primary() },
            "on_primary" to { it.onPrimary() },
            "primary_container" to { it.primaryContainer() },
            "on_primary_container" to { it.onPrimaryContainer() },
            "secondary" to { it.secondary() },
            "on_secondary" to { it.onSecondary() },
            "secondary_container" to { it.secondaryContainer() },
            "on_secondary_container" to { it.onSecondaryContainer() },
            "tertiary" to { it.tertiary() },
            "on_tertiary" to { it.onTertiary() },
            "tertiary_container" to { it.tertiaryContainer() },
            "on_tertiary_container" to { it.onTertiaryContainer() },
            "error" to { it.error() },
            "on_error" to { it.onError() },
            "error_container" to { it.errorContainer() },
            "on_error_container" to { it.onErrorContainer() },
            "background" to { it.background() },
            "on_background" to { it.onBackground() },
            "surface" to { it.surface() },
            "surface_dim" to { it.surfaceDim() },
            "surface_bright" to { it.surfaceBright() },
            "surface_container_lowest" to { it.surfaceContainerLowest() },
            "surface_container_low" to { it.surfaceContainerLow() },
            "surface_container" to { it.surfaceContainer() },
            "surface_container_high" to { it.surfaceContainerHigh() },
            "surface_container_highest" to { it.surfaceContainerHighest() },
            "on_surface" to { it.onSurface() },
            "surface_variant" to { it.surfaceVariant() },
            "on_surface_variant" to { it.onSurfaceVariant() },
            "outline" to { it.outline() },
            "outline_variant" to { it.outlineVariant() },
            "control_activated" to { it.controlActivated() },
            "control_normal" to { it.controlNormal() },
            "control_highlight" to { it.controlHighlight() },
            "text_primary_inverse" to { it.textPrimaryInverse() },
            "text_secondary_and_tertiary_inverse" to { it.textSecondaryAndTertiaryInverse() },
            "text_primary_inverse_disable_only" to { it.textPrimaryInverseDisableOnly() },
            "text_secondary_and_tertiary_inverse_disabled" to { it.textSecondaryAndTertiaryInverseDisabled() },
            "text_hint_inverse" to { it.textHintInverse() },
            "palette_key_color_primary" to { it.primaryPaletteKeyColor() },
            "palette_key_color_secondary" to { it.secondaryPaletteKeyColor() },
            "palette_key_color_tertiary" to { it.tertiaryPaletteKeyColor() },
        )

        private val FIXED_ROLES: List<Pair<String, (MaterialDynamicColors) -> com.google.android.material.color.utilities.DynamicColor>> = listOf(
            "primary_fixed" to { it.primaryFixed() },
            "primary_fixed_dim" to { it.primaryFixedDim() },
            "on_primary_fixed" to { it.onPrimaryFixed() },
            "on_primary_fixed_variant" to { it.onPrimaryFixedVariant() },
            "secondary_fixed" to { it.secondaryFixed() },
            "secondary_fixed_dim" to { it.secondaryFixedDim() },
            "on_secondary_fixed" to { it.onSecondaryFixed() },
            "on_secondary_fixed_variant" to { it.onSecondaryFixedVariant() },
            "tertiary_fixed" to { it.tertiaryFixed() },
            "tertiary_fixed_dim" to { it.tertiaryFixedDim() },
            "on_tertiary_fixed" to { it.onTertiaryFixed() },
            "on_tertiary_fixed_variant" to { it.onTertiaryFixedVariant() },
        )

        private val PURE_BLACK_ROLES = setOf(
            "background",
            "surface",
            "surface_dim",
            "surface_container_lowest",
            "surface_container_low",
        )
    }
}

/** `#RRGGBB` for display and for System UI's seed-color setting. */
fun Int.toHex(): String = String.format("#%06X", this and 0xFFFFFF)

/** Parses `#RRGGBB`, `RRGGBB` or `#AARRGGBB`; returns an opaque ARGB int or null. */
fun parseHexColor(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    return (0xFF000000L or (value and 0xFFFFFFL)).toInt()
}
