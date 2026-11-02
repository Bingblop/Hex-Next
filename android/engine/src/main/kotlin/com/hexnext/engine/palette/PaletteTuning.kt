package com.hexnext.engine.palette

/**
 * The per-palette adjustments of the Hex Next colour engine (docs/PALETTE.md):
 * saturation (chroma factor), vivid tone shift, background-lightness tone curves and gamut
 * clipping.
 *
 * Which adjustment reaches which role:
 * * accents: accent saturation; vivid mode (×1.25 chroma, tone shift) applies to accents only,
 *   so neutrals stay neutral;
 * * neutrals: background saturation and background lightness;
 * * [PaletteStyle.MONOCHROME] ignores both saturations; [PaletteStyle.RAINBOW] neutrals ignore
 *   background saturation (they are greyscale by design);
 * * [PaletteRole.ERROR] is never tuned, so errors stay recognisable.
 */
public object PaletteTuning {
    /** Chroma multiplier of vivid mode. */
    public const val VIVID_CHROMA: Double = 1.25

    /** Maximum tone reduction of vivid mode. */
    public const val VIVID_MAX_SHIFT: Double = 6.0

    /** Width of the taper of the vivid shift near tone 50 and tone 100. */
    public const val VIVID_TAPER: Double = 10.0

    /** "Darkest" background curve control points (tone → tone). */
    public val DARKEST_CURVE: List<Pair<Double, Double>> =
        listOf(0.0 to 0.0, 6.0 to 0.0, 12.0 to 2.0, 90.0 to 80.0, 100.0 to 100.0)

    /** "Brightest" background curve control points (tone → tone). */
    public val BRIGHTEST_CURVE: List<Pair<Double, Double>> =
        listOf(0.0 to 0.0, 10.0 to 20.0, 88.0 to 98.0, 100.0 to 100.0)

    /** Chroma factor applied to [role]'s palette chroma. */
    public fun chromaFactor(role: PaletteRole, style: PaletteStyle, adj: ThemeAdjustments): Double {
        if (style == PaletteStyle.MONOCHROME || role == PaletteRole.ERROR) return 1.0
        return if (role.isNeutral) {
            if (style == PaletteStyle.RAINBOW) 1.0 else adj.backgroundSaturation.coerceIn(0, 200) / 100.0
        } else {
            val base = adj.accentSaturation.coerceIn(0, 200) / 100.0
            if (adj.accurateShades) base else base * VIVID_CHROMA
        }
    }

    /**
     * Vivid tone shift: 0 up to tone 50, rising linearly to [VIVID_MAX_SHIFT] at tone 60,
     * constant to tone 90, falling linearly to 0 at tone 100. Returns the amount to subtract.
     */
    public fun vividToneShift(tone: Double): Double = when {
        tone <= 50.0 -> 0.0
        tone < 50.0 + VIVID_TAPER -> VIVID_MAX_SHIFT * (tone - 50.0) / VIVID_TAPER
        tone <= 100.0 - VIVID_TAPER -> VIVID_MAX_SHIFT
        tone < 100.0 -> VIVID_MAX_SHIFT * (100.0 - tone) / VIVID_TAPER
        else -> 0.0
    }

    /** Piecewise-linear interpolation through [points] (sorted by x), clamped at the ends. */
    public fun piecewise(points: List<Pair<Double, Double>>, x: Double): Double {
        if (x <= points.first().first) return points.first().second
        for (i in 1 until points.size) {
            val (x1, y1) = points[i]
            if (x <= x1) {
                val (x0, y0) = points[i - 1]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return points.last().second
    }

    /**
     * Background-lightness tone mapping: blends [tone] toward [DARKEST_CURVE] (level < 100) or
     * [BRIGHTEST_CURVE] (level > 100) by `|100 - level| / 100` (clamped to 0..1).
     */
    public fun backgroundTone(tone: Double, level: Int): Double {
        if (level == 100) return tone
        val w = (kotlin.math.abs(100 - level) / 100.0).coerceIn(0.0, 1.0)
        val curve = if (level < 100) DARKEST_CURVE else BRIGHTEST_CURVE
        return tone + (piecewise(curve, tone) - tone) * w
    }

    /** Final tone for a colour of [role] whose scheme tone is [tone]. */
    public fun mapTone(role: PaletteRole?, tone: Double, adj: ThemeAdjustments): Double = when {
        role == null || role == PaletteRole.ERROR -> tone
        role.isNeutral -> backgroundTone(tone, adj.backgroundLightness)
        !adj.accurateShades -> tone - vividToneShift(tone)
        else -> tone
    }

    /** In-gamut colour of HCT ([hue], [chroma], [tone]) — see [ColorMath.clipToGamut]. */
    public fun clip(hue: Double, chroma: Double, tone: Double): Int = ColorMath.clipToGamut(hue, chroma, tone)
}
