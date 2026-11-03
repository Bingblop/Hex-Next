package com.hexnext.themer.ui.studio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.IconColorStyle
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.compile.effectiveGroupColors
import com.hexnext.engine.palette.ResolvedScheme
import com.hexnext.engine.palette.ResolvedTheme
import com.hexnext.engine.palette.ThemeResolver

/**
 * SHARED CONTRACT between the Colour Studio (producer: MainViewModel) and the live phone preview
 * (consumer: ui/preview). Read-only; the preview never writes the spec. Fields may be added with
 * defaults; existing fields must not be renamed or removed.
 */

/** The screens the phone mockup can show, in tab order. */
enum class PreviewTab(val label: String) {
    SETTINGS("Settings"),
    QUICK_PANEL("Quick panel"),
    NOTIFICATIONS("Notifications"),
    MY_FILES("My Files"),
    DIALER("Dialer"),
    KEYBOARD("Keyboard"),
    POWER_MENU("Power menu"),
}

/**
 * One icon in the preview.
 *
 * @property key the drawable resource name (`com.android.settings:drawable/sec_ic_settings_display`)
 *   or a built-in row key (`display`).
 * @property bitmap the rendered PNG (pack artwork, styled, custom or synthesised), null while
 *   loading or when nothing renders; the preview then draws a circle in [fallbackArgb].
 */
@Immutable
data class PreviewIcon(
    val key: String,
    val label: String,
    val bitmap: ImageBitmap?,
    val fallbackArgb: Int,
    val synthesised: Boolean = false,
)

/**
 * Resolved colours of one mode.
 *
 * @property groups the effective colour of every [ColorGroup] (user colour, else Auto).
 * @property shades the 65 `system_<role>_<shade>` shades (`system_accent1_500` → ARGB).
 */
@Immutable
data class PreviewScheme(
    val dark: Boolean,
    val groups: Map<ColorGroup, Int>,
    val shades: Map<String, Int>,
) {
    /** The colour of [group]; every group is present. */
    fun color(group: ColorGroup): Int = groups.getValue(group)

    /** A shade by key (`system_accent1_500` or `accent1_500`), null when unknown. */
    fun shade(key: String): Int? = shades[key] ?: shades["system_$key"]

    companion object {
        /** The scheme of [spec] for one mode, from an already resolved [scheme]. */
        fun of(spec: ThemeSpec, scheme: ResolvedScheme, theme: ResolvedTheme): PreviewScheme =
            PreviewScheme(scheme.isDark, spec.effectiveGroupColors(theme, scheme.isDark), scheme.shades)
    }
}

/**
 * Everything the phone mockup draws.
 *
 * Colours ([light], [dark]) are computed from the editor's spec on every change, without a scan or
 * a compile (see [colorsOf]); icons come from the debounced compile preview and may lag behind.
 *
 * @property seedArgb the primary seed actually applied (MainViewModel keeps it apart from the spec).
 * @property highlightedGroup the group the Studio is editing; the preview outlines its elements.
 * @property qsPanelAlpha 0 … 1 opacity of the Quick panel backdrop over the wallpaper.
 * @property iconsLoading true while the compile preview is rendering icons.
 * @property hasScan false before the first device scan: icons are built-in or fallbacks only.
 * @property note a short line under the mockup (why icons are missing), null for none.
 * @property iconsNight true when the icons were rendered for dark mode (night jobs), false for light.
 */
@Immutable
data class StudioPreviewState(
    val seedArgb: Int,
    val light: PreviewScheme,
    val dark: PreviewScheme,
    val iconStyle: IconColorStyle = IconColorStyle.PACK_ORIGINAL,
    val settingsIcons: List<PreviewIcon> = emptyList(),
    val myFilesIcons: List<PreviewIcon> = emptyList(),
    val background: ImageBitmap? = null,
    val fontFile: String? = null,
    val fontName: String? = null,
    val highlightedGroup: ColorGroup? = null,
    val qsPanelAlpha: Float = 1f,
    val iconsLoading: Boolean = false,
    val hasScan: Boolean = false,
    val note: String? = null,
    val iconsNight: Boolean = true,
) {
    /** The scheme for [dark] mode. */
    fun scheme(dark: Boolean): PreviewScheme = if (dark) this.dark else light

    companion object {
        /**
         * The colour part of the preview for [spec] with the primary seed [seedArgb]: cheap and pure
         * (one palette resolve, no scan, no compile), so it can run on every slider move.
         */
        fun colorsOf(spec: ThemeSpec, seedArgb: Int): StudioPreviewState {
            val source = spec.source.copy(seedArgb = seedArgb)
            val theme = ThemeResolver.resolve(source, spec.darkAdjustments, spec.lightAdjustments)
            return StudioPreviewState(
                seedArgb = seedArgb,
                light = PreviewScheme.of(spec, theme.light, theme),
                dark = PreviewScheme.of(spec, theme.dark, theme),
                iconStyle = spec.iconStyle,
            )
        }
    }
}
