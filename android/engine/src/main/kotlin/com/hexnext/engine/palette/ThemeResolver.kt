package com.hexnext.engine.palette

import com.hexnext.engine.mcu.dynamiccolor.DynamicColor
import com.hexnext.engine.mcu.dynamiccolor.DynamicScheme
import com.hexnext.engine.mcu.dynamiccolor.MaterialDynamicColors
import com.hexnext.engine.mcu.hct.Hct
import com.hexnext.engine.mcu.palettes.TonalPalette
import java.util.Optional

/**
 * One mode (light or dark) of a resolved theme.
 *
 * @property shades the 65 Monet shades keyed `system_accent1_0` … `system_neutral2_1000`, in
 *   [ShadeScale.ALL_KEYS] order.
 * @property roles every Material role MCU defines for the scheme's spec, keyed by snake_case name
 *   (`primary`, `on_primary`, `surface_container_high`, …), in MCU's enumeration order.
 * @property surfaceTone HCT tone of the final (tuned) `surface` role.
 */
public data class ResolvedScheme(
    val isDark: Boolean,
    val shades: Map<String, Int>,
    val roles: Map<String, Int>,
    val surfaceTone: Double,
) {
    /** Shade of [role] at Android shade number [shade]. */
    public fun shade(role: PaletteRole, shade: Int): Int = shades.getValue(ShadeScale.key(role, shade))
}

/** A theme resolved for both modes. */
public data class ResolvedTheme(
    val light: ResolvedScheme,
    val dark: ResolvedScheme,
) {
    /** [dark] when [isDark], else [light]. */
    public fun scheme(isDark: Boolean): ResolvedScheme = if (isDark) dark else light
}

/**
 * Builds [ResolvedTheme]s: MCU scheme → tuned palettes → shades and roles → rules → pins.
 *
 * Per mode the pipeline is:
 * 1. build the style's MCU scheme, replacing secondary/tertiary hues when seeds are given;
 * 2. rebuild it with each palette's chroma scaled by [PaletteTuning.chromaFactor];
 * 3. shades: palette hue/chroma at the shade tone mapped by [PaletteTuning.mapTone], gamut-clipped;
 * 4. roles: MCU resolves each role's tone (contrast-aware) on the tuned scheme; pitch black remaps
 *    surface tones, then [PaletteTuning.mapTone] applies; colours whose tone is unchanged keep
 *    MCU's exact output;
 * 5. [PitchBlackRule] on shades, then [ShadeOverrides];
 * 6. [TintlessTextRule] on roles, then role overrides.
 *
 * The result depends only on the inputs (no caches or clocks are consulted), so equal inputs give
 * equal outputs.
 */
public object ThemeResolver {
    /** Resolves light and dark modes of [source] with their own adjustments. */
    public fun resolve(
        source: ThemeSource,
        darkAdjustments: ThemeAdjustments = ThemeAdjustments(),
        lightAdjustments: ThemeAdjustments = ThemeAdjustments(),
    ): ResolvedTheme = ResolvedTheme(
        light = resolveScheme(source, isDark = false, adjustments = lightAdjustments),
        dark = resolveScheme(source, isDark = true, adjustments = darkAdjustments),
    )

    /** The MCU scheme for [source] in one mode with chroma adjustments applied (no tone mapping). */
    public fun tunedScheme(source: ThemeSource, isDark: Boolean, adjustments: ThemeAdjustments): DynamicScheme {
        val extra = listOfNotNull(source.tertiaryArgb)
        val base = source.style.buildScheme(source.seedArgb, isDark, source.contrast, source.spec, extra)
        fun tuned(role: PaletteRole, hue: Double, chroma: Double): TonalPalette =
            TonalPalette.fromHueAndChroma(hue, chroma * PaletteTuning.chromaFactor(role, source.style, adjustments))

        val p = base.primaryPalette
        val s = base.secondaryPalette
        val t = base.tertiaryPalette
        val secondaryHue = source.secondaryArgb?.let { Hct.fromInt(it).hue } ?: s.hue
        val tertiaryHue = source.tertiaryArgb?.let { Hct.fromInt(it).hue } ?: t.hue
        return DynamicScheme(
            base.sourceColorHctList,
            base.variant,
            isDark,
            base.contrastLevel,
            base.platform,
            base.specVersion,
            tuned(PaletteRole.ACCENT1, p.hue, p.chroma),
            tuned(PaletteRole.ACCENT2, secondaryHue, s.chroma),
            tuned(PaletteRole.ACCENT3, tertiaryHue, t.chroma),
            tuned(PaletteRole.NEUTRAL1, base.neutralPalette.hue, base.neutralPalette.chroma),
            tuned(PaletteRole.NEUTRAL2, base.neutralVariantPalette.hue, base.neutralVariantPalette.chroma),
            Optional.of(base.errorPalette),
        )
    }

    /** Resolves one mode. */
    public fun resolveScheme(source: ThemeSource, isDark: Boolean, adjustments: ThemeAdjustments): ResolvedScheme {
        val scheme = tunedScheme(source, isDark, adjustments)
        val mdc = MaterialDynamicColors()
        val schemeSurfaceTone = mdc.surface().getTone(scheme)

        // Shades.
        var shades: Map<String, Int> = LinkedHashMap<String, Int>().also { out ->
            for (role in PaletteRole.SHADE_ROLES) {
                val pal = role.palette(scheme)
                ShadeScale.SHADES.forEachIndexed { i, shade ->
                    val tone = PaletteTuning.mapTone(role, ShadeScale.TONES[i].toDouble(), adjustments)
                    out[ShadeScale.key(role, shade)] = PaletteTuning.clip(pal.hue, pal.chroma, tone)
                }
            }
        }
        shades = PitchBlackRule.applyToShades(shades, isDark, adjustments.pitchBlack)
        shades = ShadeOverrides.apply(shades, adjustments.shadeOverrides)

        // Roles.
        var roles: Map<String, Int> = LinkedHashMap<String, Int>().also { out ->
            for (supplier in mdc.allDynamicColors()) {
                val dc: DynamicColor = supplier.get() ?: continue
                if (dc.name in out) continue
                out[dc.name] = resolveRole(dc, scheme, schemeSurfaceTone, isDark, adjustments)
            }
        }
        roles = TintlessTextRule.apply(roles, adjustments.tintedText)
        if (adjustments.roleOverrides.isNotEmpty()) {
            roles = LinkedHashMap(roles).apply {
                for ((k, v) in adjustments.roleOverrides) if (k in this) this[k] = v
            }
        }
        val surfaceTone = roles["surface"]?.let { Hct.fromInt(it).tone } ?: schemeSurfaceTone
        return ResolvedScheme(isDark, shades, roles, surfaceTone)
    }

    private fun resolveRole(
        dc: DynamicColor,
        scheme: DynamicScheme,
        surfaceTone: Double,
        isDark: Boolean,
        adj: ThemeAdjustments,
    ): Int {
        val mcuArgb = dc.getArgb(scheme)
        val palette = dc.palette.apply(scheme)
        val role = PaletteRole.ofPalette(scheme, palette)
        val schemeTone = dc.getTone(scheme)
        var tone = schemeTone
        if (role?.isNeutral == true && PitchBlackRule.appliesTo(dc.name, isDark, adj.pitchBlack)) {
            tone = PitchBlackRule.tone(tone, surfaceTone)
        }
        tone = PaletteTuning.mapTone(role, tone, adj)
        if (tone == schemeTone) return mcuArgb
        val multiplier = dc.chromaMultiplier?.apply(scheme) ?: 1.0
        val argb = PaletteTuning.clip(palette.hue, palette.chroma * multiplier, tone)
        return ColorMath.withAlpha(argb, ColorMath.alpha(mcuArgb))
    }
}
