package com.hexnext.engine.compile

import com.hexnext.engine.device.MappedResource
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.palette.ColorMath
import com.hexnext.engine.palette.ResolvedTheme
import com.hexnext.engine.palette.RoleInference
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource

/**
 * Recolours every colour of a SemWT mapping from one accent seed, the way [ThemeCompiler] does for
 * a full theme: each Samsung value is matched to its nearest shade in the system palette that was
 * active at scan time ([RoleInference.infer]) and replaced by the same role and shade of the
 * palette resolved from the seed (the light scheme for the default configuration, the dark scheme
 * for `night`), keeping the observed alpha. Unlike the compiler, no colour is left fixed: a colour
 * too far from any shade still takes its nearest shade, so the whole mapping follows the accent.
 *
 * Used by the Settings test overlay.
 */
public object AccentRecolor {
    /** The FRRO configuration of night values. */
    public const val NIGHT: String = SnapshotMapper.NIGHT

    /**
     * Planned colours for every colour mapping of [mapping] in [targetPackage].
     *
     * @param reference the scan-time system shades (`system_accent1_500` → ARGB).
     * @param includeNight also plan a `night` value per resource (from the mapping's night value,
     *   else its default value, recoloured with the dark scheme).
     * @param forceSeed `type/name`s set to the opaque seed in every configuration instead
     *   (a resource that must be unmistakable in a screenshot); they are added when missing.
     */
    public fun recolor(
        targetPackage: String,
        mapping: List<MappedResource>,
        reference: Map<String, Int>,
        seedArgb: Int,
        includeNight: Boolean,
        forceSeed: Collection<String> = emptyList(),
    ): List<PlannedColor> {
        val theme = ThemeResolver.resolve(ThemeSource(seedArgb or OPAQUE))
        val out = LinkedHashMap<Pair<String, String>, PlannedColor>()
        val seen = HashSet<String>()
        for (m in mapping) {
            if (m.type != "color") continue
            val day = m.defaultColor ?: continue
            val name = m.typeAndName
            if (!seen.add(name)) continue
            val resource = "$targetPackage:$name"
            out[resource to ""] = PlannedColor(resource, shadeFor(day, reference, theme, isDark = false, seedArgb), "")
            if (includeNight) {
                val night = m.nightColor ?: day
                out[resource to NIGHT] = PlannedColor(resource, shadeFor(night, reference, theme, isDark = true, seedArgb), NIGHT)
            }
        }
        for (name in forceSeed) {
            val resource = "$targetPackage:$name"
            out[resource to ""] = PlannedColor(resource, seedArgb or OPAQUE, "")
            if (includeNight) out[resource to NIGHT] = PlannedColor(resource, seedArgb or OPAQUE, NIGHT)
        }
        return out.values.toList()
    }

    /** The resolved shade [observed] maps to; the seed (with the observed alpha) when [reference] is empty. */
    internal fun shadeFor(observed: Int, reference: Map<String, Int>, theme: ResolvedTheme, isDark: Boolean, seedArgb: Int): Int {
        val match = RoleInference.infer(observed, reference)
            ?: return ColorMath.withAlpha(seedArgb, ColorMath.alpha(observed))
        val shade = theme.scheme(isDark).shades[match.key] ?: seedArgb
        return ColorMath.withAlpha(shade, match.alpha)
    }

    private const val OPAQUE: Int = 0xFF000000.toInt()
}
