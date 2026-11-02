package com.hexnext.engine.compile

import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ResolvedTheme
import com.hexnext.engine.palette.ShadeScale
import kotlin.math.abs

/**
 * Role of a pack palette slot digit: [role] at [darkShade] in the dark scheme, and at
 * [lightShade] in the light scheme (the `l`-prefixed slot).
 *
 * [lightShade] mirrors the dark shade's tone (`100 - tone`, nearest shade, the darker one on a
 * tie) so a dark background slot becomes a light background in light mode.
 */
public data class SlotRole(val role: PaletteRole, val darkShade: Int) {
    /** Mirrored light-mode shade. */
    public val lightShade: Int get() = mirror(darkShade)

    public companion object {
        /** Shade whose tone is closest to `100 - tone(shade)`; on a tie the darker shade. */
        public fun mirror(shade: Int): Int {
            val target = 100 - ShadeScale.toneOf(shade)
            return ShadeScale.SHADES.withIndex()
                .minWith(compareBy<IndexedValue<Int>>({ abs(ShadeScale.TONES[it.index] - target) }, { ShadeScale.TONES[it.index] == 0 }, { -it.index }))
                .value
        }
    }
}

/**
 * Derives a pack palette (slot → ARGB) from a [ResolvedTheme], so packs follow the user's colours.
 *
 * Slot digits 0–9 map to roles as listed in [ROLES]. The prefixes [PREFIXES] (`xx`, `xy`, `xz`)
 * share the mapping; dark slots (`xx1`) come from the dark scheme, light slots (`lxx1`) from the
 * light scheme. Other slot families (`sx`, `lz*`, `xg` …) are left to the pack palette, and fixed
 * (`H…`) tints are never touched.
 */
public object SlotPalette {
    /** Slot prefixes derived from the theme. */
    public val PREFIXES: List<String> = listOf("xx", "xy", "xz")

    /** Digit → role. */
    public val ROLES: List<SlotRole> = listOf(
        SlotRole(PaletteRole.NEUTRAL1, 900), // 0 dark background
        SlotRole(PaletteRole.ACCENT1, 500), // 1 primary accent
        SlotRole(PaletteRole.ACCENT2, 400), // 2 secondary accent
        SlotRole(PaletteRole.NEUTRAL1, 100), // 3 foreground / text
        SlotRole(PaletteRole.NEUTRAL2, 300), // 4 variant / outline
        SlotRole(PaletteRole.ACCENT3, 400), // 5 tertiary accent
        SlotRole(PaletteRole.NEUTRAL1, 800), // 6 surface
        SlotRole(PaletteRole.NEUTRAL1, 800), // 7 surface (second)
        SlotRole(PaletteRole.NEUTRAL1, 50), // 8 highlight
        SlotRole(PaletteRole.ACCENT1, 300), // 9 light accent
    )

    /** The derived palette for [theme]: `xx0`…`xz9` and `lxx0`…`lxz9`. */
    public fun derive(theme: ResolvedTheme): Map<String, Int> = buildMap {
        for (prefix in PREFIXES) {
            ROLES.forEachIndexed { digit, slot ->
                put("$prefix$digit", theme.dark.shade(slot.role, slot.darkShade))
                put("l$prefix$digit", theme.light.shade(slot.role, slot.lightShade))
            }
        }
    }
}
