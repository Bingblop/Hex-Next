package com.hexnext.engine.compile

import com.hexnext.engine.palette.ResolvedScheme
import com.hexnext.engine.palette.ResolvedTheme

/**
 * The system Material You palette every app outside One UI reads from `android` (Google apps and
 * any Material 3 app), set from the Hex Next theme the way ColorBlendr's framework overlay sets it:
 *
 * - the 65 shades `system_accent1_0` … `system_neutral2_1000`: the light shades by default and the
 *   dark ones under `night` (ColorBlendr rebuilds on a mode change; a qualifier does it once), plus
 *   the `<shade>_light` / `<shade>_dark` copies;
 * - every Material 3 role as `system_<role>_light` / `_dark` (`system_primary_light`,
 *   `system_surface_container_high_dark`, …), the palette key colours as
 *   `system_palette_key_color_<palette>_light` / `_dark`;
 * - the fixed roles as `system_<role>` (day/night) and `_light` / `_dark`;
 * - Android's own tokens (`system_control_activated_*`, `system_text_primary_inverse_*`, …), from
 *   the shades at the tones System UI uses;
 * - the Material token copies (`m3_sys_color_[dynamic_]<mode>_<role>`, `gm3_…`, `materialColor<Role>`)
 *   where the framework has them as plain colours (references already follow the system colours).
 *
 * Only names the device's `android` table has are kept ([has]), so an older framework without the
 * role colours still gets the shades.
 */
public object SystemPalette {
    /** (`color/<name>`, configuration `""` or `"night"`) → ARGB. */
    public fun colors(
        theme: ResolvedTheme,
        has: (String) -> Boolean,
        isReference: (String) -> Boolean = { false },
    ): Map<Pair<String, String>, Int> {
        val out = LinkedHashMap<Pair<String, String>, Int>()
        fun put(name: String, argb: Int?, config: String = "") {
            if (argb == null) return
            val typeName = "color/$name"
            if (has(typeName)) out[typeName to config] = argb
        }
        fun dayNight(name: String, light: Int?, dark: Int?) {
            put(name, light)
            put(name, dark, NIGHT)
        }

        // Shades.
        for ((key, argb) in theme.light.shades) {
            dayNight(key, argb, theme.dark.shades[key])
            put("${key}_light", argb)
            put("${key}_dark", theme.dark.shades[key])
        }

        // Material 3 roles.
        val light = system(theme.light)
        val dark = system(theme.dark)
        for ((role, argb) in light) {
            val darkArgb = dark[role]
            when {
                role.endsWith(KEY_SUFFIX) -> {
                    val palette = role.removeSuffix(KEY_SUFFIX)
                    put("system_palette_key_color_${palette}_light", argb)
                    put("system_palette_key_color_${palette}_dark", darkArgb)
                }
                FIXED in role -> {
                    dayNight("system_$role", argb, darkArgb)
                    put("system_${role}_light", argb)
                    put("system_${role}_dark", darkArgb)
                }
                else -> {
                    put("system_${role}_light", argb)
                    put("system_${role}_dark", darkArgb)
                }
            }
        }

        // Material token copies, where the framework keeps them as plain colours.
        for ((role, argb) in light) {
            if (role.endsWith(KEY_SUFFIX)) continue
            val darkArgb = dark[role]
            if (FIXED in role) {
                for (prefix in M3_PREFIXES) dayNight("$prefix$role", argb, darkArgb)
            } else {
                for (prefix in M3_PREFIXES) {
                    put("${prefix}light_$role", argb)
                    put("${prefix}dark_$role", darkArgb)
                }
            }
            for (prefix in PASCAL_PREFIXES) {
                val name = prefix + pascal(role)
                if (isReference("color/$name")) continue
                dayNight(name, argb, darkArgb)
            }
        }
        return out
    }

    /** The scheme's roles plus Android's own tokens (AOSP's dynamic colours, 2021 tones). */
    private fun system(scheme: ResolvedScheme): Map<String, Int> {
        val roles = LinkedHashMap(scheme.roles)
        val s = scheme.shades
        val night = scheme.isDark
        fun tone(palette: String, lightTone: Int, darkTone: Int): Int? =
            s["system_${palette}_${(100 - if (night) darkTone else lightTone) * 10}"]
        tone("accent1", 90, 30)?.let { roles.putIfAbsent("control_activated", it) }
        tone("neutral2", 30, 80)?.let { roles.putIfAbsent("control_normal", it) }
        roles.putIfAbsent("control_highlight", if (night) 0x33FFFFFF else 0x1F000000)
        tone("neutral1", 90, 10)?.let {
            roles.putIfAbsent("text_primary_inverse", it)
            roles.putIfAbsent("text_primary_inverse_disable_only", it)
            roles.putIfAbsent("text_secondary_and_tertiary_inverse_disabled", it)
            roles.putIfAbsent("text_hint_inverse", it)
        }
        tone("neutral2", 80, 30)?.let { roles.putIfAbsent("text_secondary_and_tertiary_inverse", it) }
        return roles
    }

    private fun pascal(snake: String): String =
        snake.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }

    private const val NIGHT = "night"
    private const val KEY_SUFFIX = "_palette_key_color"
    private const val FIXED = "_fixed"
    private val M3_PREFIXES = listOf("m3_sys_color_", "m3_sys_color_dynamic_", "gm3_sys_color_", "gm3_sys_color_dynamic_")
    private val PASCAL_PREFIXES = listOf("settingslib_materialColor", "materialColor")
}
