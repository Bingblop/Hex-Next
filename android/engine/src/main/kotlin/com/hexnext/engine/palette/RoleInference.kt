package com.hexnext.engine.palette

/**
 * Where an observed colour sits in a reference Monet palette.
 *
 * @property role palette role of the nearest shade.
 * @property shadeIndex index 0..12 into [ShadeScale.SHADES].
 * @property alpha alpha (0..255) of the observed colour.
 * @property deltaE CIE76 distance between the observed colour (RGB only) and that shade.
 */
public data class InferredColor(
    val role: PaletteRole,
    val shadeIndex: Int,
    val alpha: Int,
    val deltaE: Double,
) {
    /** Android shade number (0, 10, 50 … 1000). */
    public val shade: Int get() = ShadeScale.SHADES[shadeIndex]

    /** System key, e.g. `system_accent1_500`. */
    public val key: String get() = ShadeScale.key(role, shade)
}

/**
 * Re-derives colours of Samsung's `SemWT_*` overlays from the user's palette: each observed colour
 * is matched to the nearest shade (CIE76 in CIELAB) of the system palette that was active when the
 * device was scanned, then replaced by the same role/shade of the [ResolvedTheme].
 *
 * Several shades can be identical (every role's shade 0 is white and shade 1000 black). Ties are
 * broken deterministically by [ShadeScale.ALL_KEYS] order (accent1 first, lightest shade first),
 * so the first equal shade wins.
 */
public object RoleInference {
    /** Default maximum ΔE for a match to be recoloured (PALETTE.md: worse matches stay fixed). */
    public const val DEFAULT_MAX_DELTA_E: Double = 6.0

    /**
     * Nearest shade of [reference] (system keys → colour; missing keys are skipped) to [observed].
     * Returns `null` when [reference] has none of the 65 keys.
     */
    public fun infer(observed: Int, reference: Map<String, Int>): InferredColor? {
        val lab = ColorMath.lab(observed)
        var best: InferredColor? = null
        for (role in PaletteRole.SHADE_ROLES) {
            ShadeScale.SHADES.forEachIndexed { i, shade ->
                val ref = reference[ShadeScale.key(role, shade)] ?: return@forEachIndexed
                val d = ColorMath.deltaE76(lab, ColorMath.lab(ref))
                if (best == null || d < best!!.deltaE) {
                    best = InferredColor(role, i, ColorMath.alpha(observed), d)
                }
            }
        }
        return best
    }

    /**
     * The [resolved] theme's colour for the role/shade [observed] matches in [reference], carrying
     * the observed alpha; `null` when there is no match within [maxDeltaE] (the colour stays fixed).
     */
    public fun recolor(
        observed: Int,
        reference: Map<String, Int>,
        resolved: ResolvedTheme,
        isDark: Boolean,
        maxDeltaE: Double = DEFAULT_MAX_DELTA_E,
    ): Int? {
        val match = infer(observed, reference) ?: return null
        if (match.deltaE > maxDeltaE) return null
        val target = resolved.scheme(isDark).shades[match.key] ?: return null
        return ColorMath.withAlpha(target, match.alpha)
    }
}
