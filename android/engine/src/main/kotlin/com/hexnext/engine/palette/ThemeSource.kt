package com.hexnext.engine.palette

import kotlinx.serialization.Serializable

/**
 * What a theme is generated from.
 *
 * @property seedArgb primary seed (wallpaper, picked or preset colour); alpha is ignored.
 * @property secondaryArgb optional secondary seed: replaces the secondary palette's hue, keeping
 *   the chroma the style gives that palette.
 * @property tertiaryArgb optional tertiary seed: replaces the tertiary palette's hue the same way
 *   (for [PaletteStyle.CMF] it is also passed to MCU as the second source colour).
 * @property contrast -1.0 … 1.0; 0 is standard.
 */
@Serializable
public data class ThemeSource(
    val seedArgb: Int,
    val secondaryArgb: Int? = null,
    val tertiaryArgb: Int? = null,
    val style: PaletteStyle = PaletteStyle.TONAL_SPOT,
    val spec: ColorSpecVersion = ColorSpecVersion.SPEC_2021,
    val contrast: Double = 0.0,
)

/** Pitch-black mode (dark theme only). */
@Serializable
public enum class PitchBlack {
    /** Normal dark surfaces. */
    OFF,

    /** Surface roles and neutral1_900 go black. */
    SURFACES,

    /** As [SURFACES], plus the surface-container roles. */
    SURFACES_AND_CONTAINERS,
}

/**
 * User adjustments applied on top of a generated scheme; persisted separately for light and dark.
 *
 * @property accentSaturation 0 … 200 (100 = unchanged), applied to accent palettes.
 * @property backgroundSaturation 0 … 200, applied to neutral palettes.
 * @property backgroundLightness 0 … 200; below 100 blends neutral tones toward the darkest curve,
 *   above 100 toward the brightest curve.
 * @property accurateShades false = "vivid": accent chroma × 1.25 and accent tones above 50 lowered.
 * @property tintedText false forces `on*` / text roles to pure white or black.
 * @property shadeOverrides pinned shades keyed `accent1_500` (or `system_accent1_500`).
 * @property roleOverrides pinned Material roles keyed by snake_case role name (`on_primary`).
 */
@Serializable
public data class ThemeAdjustments(
    val accentSaturation: Int = 100,
    val backgroundSaturation: Int = 100,
    val backgroundLightness: Int = 100,
    val accurateShades: Boolean = true,
    val pitchBlack: PitchBlack = PitchBlack.OFF,
    val tintedText: Boolean = true,
    val shadeOverrides: Map<String, Int> = emptyMap(),
    val roleOverrides: Map<String, Int> = emptyMap(),
)
