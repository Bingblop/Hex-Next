package com.hexnext.engine.palette

import com.hexnext.engine.mcu.hct.Hct
import com.hexnext.engine.mcu.utils.ColorUtils
import kotlin.math.abs
import kotlin.math.sqrt

/** CIELAB / HCT helpers built on Material Color Utilities' colour maths. */
public object ColorMath {
    private const val ALPHA_MASK: Int = 0xFF000000.toInt()
    private const val RGB_MASK: Int = 0x00FFFFFF

    /** Chroma tolerance of an HCT round trip accepted by [clipToGamut]. */
    public const val CHROMA_TOLERANCE: Double = 1.0

    /** Tone tolerance of an HCT round trip accepted by [clipToGamut]. */
    public const val TONE_TOLERANCE: Double = 0.5

    /** CIELAB (D65) of the RGB part of [argb]: `[L*, a*, b*]`. */
    public fun lab(argb: Int): DoubleArray = ColorUtils.labFromArgb(argb)

    /** L* of [argb]. */
    public fun lstar(argb: Int): Double = ColorUtils.lstarFromArgb(argb)

    /** CIE76 colour difference of the RGB parts of two colours (alpha ignored). */
    public fun deltaE76(a: Int, b: Int): Double = deltaE76(lab(a), lab(b))

    /** CIE76 difference of two Lab triples. */
    public fun deltaE76(a: DoubleArray, b: DoubleArray): Double {
        val dl = a[0] - b[0]
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** [argb] with its alpha replaced by [alpha] (0..255). */
    public fun withAlpha(argb: Int, alpha: Int): Int = (argb and RGB_MASK) or (alpha.coerceIn(0, 255) shl 24)

    /** Alpha (0..255) of [argb]. */
    public fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    /**
     * The colour with HCT [hue] and [tone] and the largest chroma ≤ [chroma] that is in the sRGB
     * gamut. Hue and tone are kept; only chroma is reduced. A candidate is accepted when its HCT
     * round trip reproduces the chroma within [CHROMA_TOLERANCE] and the tone within
     * [TONE_TOLERANCE]; otherwise chroma is binary-searched downward. Returns an opaque colour.
     */
    public fun clipToGamut(hue: Double, chroma: Double, tone: Double): Int {
        val t = tone.coerceIn(0.0, 100.0)
        val c = chroma.coerceAtLeast(0.0)
        candidate(hue, c, t)?.let { return it }
        var lo = 0.0
        var hi = c
        var best = Hct.from(hue, 0.0, t).toInt()
        repeat(SEARCH_STEPS) {
            val mid = (lo + hi) / 2
            val got = candidate(hue, mid, t)
            if (got != null) {
                best = got
                lo = mid
            } else {
                hi = mid
            }
        }
        return best or ALPHA_MASK
    }

    private const val SEARCH_STEPS = 18

    private fun candidate(hue: Double, chroma: Double, tone: Double): Int? {
        val argb = Hct.from(hue, chroma, tone).toInt()
        val back = Hct.fromInt(argb)
        val ok = abs(back.chroma - chroma) <= CHROMA_TOLERANCE && abs(back.tone - tone) <= TONE_TOLERANCE
        return if (ok) argb or ALPHA_MASK else null
    }

    /**
     * Converts Lab to an opaque ARGB colour, bringing out-of-gamut values in by scaling a* and b*
     * toward zero (keeping L* and the Lab hue angle) until the conversion round-trips within
     * ΔE 1.
     */
    public fun argbFromLabClipped(l: Double, a: Double, b: Double): Int {
        val lc = l.coerceIn(0.0, 100.0)
        fun conv(s: Double): Int? {
            val argb = ColorUtils.argbFromLab(lc, a * s, b * s)
            return if (deltaE76(lab(argb), doubleArrayOf(lc, a * s, b * s)) <= 1.0) argb else null
        }
        conv(1.0)?.let { return it or ALPHA_MASK }
        var lo = 0.0
        var hi = 1.0
        var best = ColorUtils.argbFromLab(lc, 0.0, 0.0)
        repeat(SEARCH_STEPS) {
            val mid = (lo + hi) / 2
            val got = conv(mid)
            if (got != null) {
                best = got
                lo = mid
            } else {
                hi = mid
            }
        }
        return best or ALPHA_MASK
    }
}
