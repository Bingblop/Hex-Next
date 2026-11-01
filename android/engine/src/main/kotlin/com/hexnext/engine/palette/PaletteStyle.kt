package com.hexnext.engine.palette

import com.hexnext.engine.mcu.dynamiccolor.ColorSpec.SpecVersion
import com.hexnext.engine.mcu.dynamiccolor.DynamicScheme
import com.hexnext.engine.mcu.dynamiccolor.DynamicScheme.Platform
import com.hexnext.engine.mcu.dynamiccolor.Variant
import com.hexnext.engine.mcu.hct.Hct
import com.hexnext.engine.mcu.scheme.SchemeCmf
import com.hexnext.engine.mcu.scheme.SchemeContent
import com.hexnext.engine.mcu.scheme.SchemeExpressive
import com.hexnext.engine.mcu.scheme.SchemeFidelity
import com.hexnext.engine.mcu.scheme.SchemeFruitSalad
import com.hexnext.engine.mcu.scheme.SchemeMonochrome
import com.hexnext.engine.mcu.scheme.SchemeNeutral
import com.hexnext.engine.mcu.scheme.SchemeRainbow
import com.hexnext.engine.mcu.scheme.SchemeTonalSpot
import com.hexnext.engine.mcu.scheme.SchemeVibrant

/**
 * Material colour specification generation used to derive palettes and role tones.
 *
 * MCU falls back to an older spec for variants a newer spec does not define (for example
 * Fidelity under 2025 resolves with the 2021 rules); that fallback is MCU's and is kept as-is.
 */
public enum class ColorSpecVersion(
    /** The vendored MCU spec version this maps to. */
    public val mcu: SpecVersion,
) {
    /** Material You (Android 12–15). */
    SPEC_2021(SpecVersion.SPEC_2021),

    /** Material 3 Expressive (Android 16). */
    SPEC_2025(SpecVersion.SPEC_2025),

    /** 2026 refresh (adds CMF). */
    SPEC_2026(SpecVersion.SPEC_2026),
}

/**
 * Palette style ("Monet style"). Each style builds an MCU [DynamicScheme] from a seed colour.
 *
 * [CMF] is backed by the vendored `SchemeCmf`, which only exists for the 2026 spec: CMF always
 * builds with [ColorSpecVersion.SPEC_2026], whatever spec is requested.
 */
public enum class PaletteStyle(
    /** The MCU variant this style produces. */
    public val variant: Variant,
) {
    /** Default Android style: calm primary, low-chroma secondary. */
    TONAL_SPOT(Variant.TONAL_SPOT),

    /** High-chroma primary, colourful roles. */
    VIBRANT(Variant.VIBRANT),

    /** Playful, hue-rotated primary. */
    EXPRESSIVE(Variant.EXPRESSIVE),

    /** Near-greyscale ("Neutral" in MCU). */
    SPRITZ(Variant.NEUTRAL),

    /** Chromatic accents, greyscale neutrals. */
    RAINBOW(Variant.RAINBOW),

    /** Hue-rotated accents. */
    FRUIT_SALAD(Variant.FRUIT_SALAD),

    /** Pure greyscale. */
    MONOCHROME(Variant.MONOCHROME),

    /** Primary matches the seed colour exactly. */
    FIDELITY(Variant.FIDELITY),

    /** Like Fidelity with analogous tertiary. */
    CONTENT(Variant.CONTENT),

    /** Colour-Material-Finish scheme (2026 only, see class docs). */
    CMF(Variant.CMF);

    /**
     * Builds the MCU scheme for [seedArgb].
     *
     * @param extraSources additional source colours; only [CMF] uses the first one (as its
     *   tertiary source), other styles ignore them.
     */
    public fun buildScheme(
        seedArgb: Int,
        isDark: Boolean,
        contrast: Double = 0.0,
        spec: ColorSpecVersion = ColorSpecVersion.SPEC_2021,
        extraSources: List<Int> = emptyList(),
    ): DynamicScheme {
        val hct = Hct.fromInt(seedArgb or OPAQUE)
        val c = contrast.coerceIn(-1.0, 1.0)
        val s = spec.mcu
        val p = Platform.PHONE
        return when (this) {
            TONAL_SPOT -> SchemeTonalSpot(hct, isDark, c, s, p)
            VIBRANT -> SchemeVibrant(hct, isDark, c, s, p)
            EXPRESSIVE -> SchemeExpressive(hct, isDark, c, s, p)
            SPRITZ -> SchemeNeutral(hct, isDark, c, s, p)
            RAINBOW -> SchemeRainbow(hct, isDark, c, s, p)
            FRUIT_SALAD -> SchemeFruitSalad(hct, isDark, c, s, p)
            MONOCHROME -> SchemeMonochrome(hct, isDark, c, s, p)
            FIDELITY -> SchemeFidelity(hct, isDark, c, s, p)
            CONTENT -> SchemeContent(hct, isDark, c, s, p)
            CMF -> SchemeCmf(
                listOf(hct) + extraSources.map { Hct.fromInt(it or OPAQUE) },
                isDark, c, SpecVersion.SPEC_2026, p,
            )
        }
    }

    private companion object {
        const val OPAQUE: Int = 0xFF000000.toInt()
    }
}
