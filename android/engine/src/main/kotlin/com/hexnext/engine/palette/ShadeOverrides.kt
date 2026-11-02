package com.hexnext.engine.palette

/**
 * Pins individual Monet shades and smooths the rest of the same role around them.
 *
 * For every role with at least one pin, each pinned shade takes the pinned colour exactly. The
 * CIELAB difference `pinned − generated` at each pin is spread over the unpinned shades of that
 * role by linear interpolation in tone space:
 * * between two pins, the correction blends from one pin's difference to the other's;
 * * between a pin and the end of the scale (shade 0, tone 100 / shade 1000, tone 0) it fades to
 *   zero at that end, so white and black stay put.
 *
 * Interpolating by tone (not by shade index) keeps the scale monotonic in L* as long as the pinned
 * colours themselves are in lightness order and inside the open range of their unpinned ends.
 * Out-of-gamut results are brought in by reducing Lab chroma ([ColorMath.argbFromLabClipped]).
 */
public object ShadeOverrides {
    /**
     * Applies [overrides] (keys `accent1_500` or `system_accent1_500`) to [shades] (the 65 system
     * keys). Unknown override keys are ignored. Returns a new map in [ShadeScale.ALL_KEYS] order
     * (plus any extra keys of [shades] after it).
     */
    public fun apply(shades: Map<String, Int>, overrides: Map<String, Int>): Map<String, Int> {
        if (overrides.isEmpty()) return shades
        val pins = HashMap<PaletteRole, MutableMap<Int, Int>>()
        for ((k, v) in overrides) {
            val (role, idx) = ShadeScale.parseKey(k) ?: continue
            pins.getOrPut(role) { sortedMapOf() }[idx] = v
        }
        val out = LinkedHashMap<String, Int>()
        for (role in PaletteRole.SHADE_ROLES) {
            val keys = ShadeScale.SHADES.map { ShadeScale.key(role, it) }
            val colors = keys.map { shades[it] }
            if (colors.any { it == null }) {
                keys.forEachIndexed { i, k -> colors[i]?.let { out[k] = it } }
                continue
            }
            val rolePins = pins[role]
            val result = if (rolePins.isNullOrEmpty()) colors.map { it!! } else smooth(colors.map { it!! }, rolePins)
            keys.forEachIndexed { i, k -> out[k] = result[i] }
        }
        for ((k, v) in shades) if (k !in out) out[k] = v
        return out
    }

    /** Smooths one role's 13 colours ([colors], lightest first) around [pins] (index → colour). */
    public fun smooth(colors: List<Int>, pins: Map<Int, Int>): List<Int> {
        require(colors.size == ShadeScale.SIZE) { "expected ${ShadeScale.SIZE} shades" }
        val tones = ShadeScale.TONES.map { it.toDouble() }
        val labs = colors.map { ColorMath.lab(it) }
        val deltas = pins.mapValues { (i, c) ->
            val p = ColorMath.lab(c)
            doubleArrayOf(p[0] - labs[i][0], p[1] - labs[i][1], p[2] - labs[i][2])
        }
        val pinIdx = pins.keys.sorted()
        return colors.indices.map { i ->
            pins[i]?.let { return@map it }
            val left = pinIdx.lastOrNull { it < i }
            val right = pinIdx.firstOrNull { it > i }
            val zero = doubleArrayOf(0.0, 0.0, 0.0)
            val d = when {
                left != null && right != null -> {
                    val w = (tones[left] - tones[i]) / (tones[left] - tones[right])
                    lerp(deltas.getValue(left), deltas.getValue(right), w)
                }
                left != null -> { // pin is lighter; fade to zero at tone 0
                    val w = (tones[left] - tones[i]) / (tones[left] - 0.0)
                    lerp(deltas.getValue(left), zero, w)
                }
                right != null -> { // pin is darker; fade to zero at tone 100
                    val w = (tones[right] - tones[i]) / (tones[right] - 100.0)
                    lerp(deltas.getValue(right), zero, w)
                }
                else -> zero
            }
            if (d.all { it == 0.0 }) {
                colors[i]
            } else {
                val l = labs[i]
                val argb = ColorMath.argbFromLabClipped(l[0] + d[0], l[1] + d[1], l[2] + d[2])
                ColorMath.withAlpha(argb, ColorMath.alpha(colors[i]))
            }
        }
    }

    private fun lerp(a: DoubleArray, b: DoubleArray, w: Double): DoubleArray =
        DoubleArray(3) { a[it] + (b[it] - a[it]) * w }
}
