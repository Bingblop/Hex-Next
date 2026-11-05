package com.hexnext.themer.ui.studio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.DimenUnit
import com.hexnext.engine.compile.groupColor
import com.hexnext.engine.compile.IconColorStyle
import com.hexnext.engine.compile.ShapeControls
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.compile.ValueKind
import com.hexnext.engine.compile.ValueSetting
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
enum class PreviewTab(val label: String, val packageName: String? = null) {
    SETTINGS("Settings"),
    QUICK_PANEL("Quick panel"),
    NOTIFICATIONS("Notifications"),
    MY_FILES("My Files", "com.sec.android.app.myfiles"),
    DIALER("Dialer", "com.samsung.android.dialer"),
    KEYBOARD("Keyboard", "com.samsung.android.honeyboard"),
    POWER_MENU("Power menu"),
    CONTACTS("Contacts", "com.samsung.android.app.contacts"),
    CALCULATOR("Calculator", "com.sec.android.app.popupcalculator"),
    VOICE_RECORDER("Voice Recorder", "com.sec.android.app.voicenote"),
    GALLERY("Gallery", "com.sec.android.gallery3d"),
    CLOCK("Clock", "com.sec.android.app.clockpackage"),
    CALENDAR("Calendar", "com.samsung.android.calendar"),
    NOTES("Notes", "com.samsung.android.app.notes"),
    REMINDER("Reminder", "com.samsung.android.app.reminder"),
    INTERNET("Internet", "com.sec.android.app.sbrowser"),
    EMAIL("Email", "com.samsung.android.email.provider"),
    MUSIC("Music", "com.sec.android.app.music"),
    HEALTH("Health", "com.sec.android.app.shealth"),
    DEVICE_CARE("Device care", "com.samsung.android.lool"),
    ;

    companion object {
        /** The tab that previews [packageName], null when none does. */
        fun forPackage(packageName: String): PreviewTab? = entries.firstOrNull { it.packageName == packageName }
    }
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
    /** Groups on Auto whose app keeps its own colours ([ColorGroup.autoKeepsApp]). */
    val keptByApp: Set<ColorGroup> = emptySet(),
) {
    /** The colour of [group]; every group is present. */
    fun color(group: ColorGroup): Int = groups.getValue(group)

    /** A shade by key (`system_accent1_500` or `accent1_500`), null when unknown. */
    fun shade(key: String): Int? = shades[key] ?: shades["system_$key"]

    companion object {
        /** The scheme of [spec] for one mode, from an already resolved [scheme]. */
        fun of(spec: ThemeSpec, scheme: ResolvedScheme, theme: ResolvedTheme): PreviewScheme =
            PreviewScheme(
                scheme.isDark,
                spec.effectiveGroupColors(theme, scheme.isDark),
                scheme.shades,
                ColorGroup.ALL.filterTo(HashSet()) { it.autoKeepsApp && spec.groupColor(it, scheme.isDark) == null },
            )
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
 * @property qsPanelAlpha 0 … 1 opacity of the Quick panel backdrop, as a fraction of Samsung's own
 *   (the compiler scales Samsung's alpha by it; the preview draws it over an opaque panel colour).
 * @property iconsLoading true while the compile preview is rendering icons.
 * @property hasScan false before the first device scan: icons are built-in or fallbacks only.
 * @property note a short line under the mockup (why icons are missing), null for none.
 * @property iconsNight true when the icons were rendered for dark mode (night jobs), false for light.
 * @property dragging true while a slider or colour drag shows a draft: the mockup then jumps to the
 *   new colours instead of animating, so it keeps up with the finger.
 * @property qsShapes the Quick panel sizes the theme sets ([ThemeSpec.shapes]), for the mockup.
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
    val dragging: Boolean = false,
    val qsShapes: QsShapes = QsShapes(),
    val deviceColors: DeviceColors = DeviceColors(),
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

/**
 * The Quick panel shape settings the mockup draws ([ShapeControls]); null leaves the mockup's
 * One UI default. The preview scales device dp to its 300 dp design screen ([PREVIEW_DP]).
 */
@Immutable
data class QsShapes(
    val tileCorner: ValueSetting? = null,
    val panelCorner: ValueSetting? = null,
    val sliderThickness: ValueSetting? = null,
    val notificationCorner: ValueSetting? = null,
) {
    companion object {
        /** Preview dp per device dp: the mockup is 300 dp wide, a One UI phone about 412 dp. */
        const val PREVIEW_DP: Float = 300f / 412f

        fun of(spec: ThemeSpec): QsShapes = QsShapes(
            tileCorner = spec.shapes[ShapeControls.TILE_CORNER],
            panelCorner = spec.shapes[ShapeControls.PANEL_CORNER],
            sliderThickness = spec.shapes[ShapeControls.SLIDER_THICKNESS],
            notificationCorner = spec.shapes[ShapeControls.NOTIFICATION_CORNER],
        )

        /**
         * [setting] in preview dp: an absolute size converted to device dp with [density]
         * (px by its density, sp by its font scale) and scaled by [PREVIEW_DP], a factor times
         * [default] (already in preview dp); null for none or an unknown kind.
         */
        fun previewDp(setting: ValueSetting?, default: Float, density: Density): Float? {
            val n = setting?.number ?: return null
            return when (setting.kind) {
                ValueKind.DIMEN -> {
                    val dp = when (setting.unit) {
                        DimenUnit.DP -> n
                        DimenUnit.PX -> n / density.density
                        DimenUnit.SP -> n * density.fontScale
                    }
                    (dp * PREVIEW_DP).toFloat()
                }
                ValueKind.SCALE -> (default * n).toFloat()
                else -> null
            }?.coerceAtLeast(0f)
        }
    }
}

/**
 * Colours one target's overlay really writes, by resource name (`qs_tile_round_background_on`),
 * for the default configuration ([day]) and `night`. Read the way the phone resolves them: in
 * dark mode the night value, else the default one.
 */
@Immutable
data class ResourceColors(val day: Map<String, Int> = emptyMap(), val night: Map<String, Int> = emptyMap()) {
    fun get(name: String, dark: Boolean): Int? = if (dark) night[name] ?: day[name] else day[name]
    val isEmpty: Boolean get() = day.isEmpty() && night.isEmpty()
}

/**
 * What the last compile preview plans for the targets the phone mockup draws, so each mockup
 * element can show the exact colour of the resource that paints it on the phone. Empty before
 * the first scan; the mockup then falls back to the colour groups.
 */
@Immutable
data class DeviceColors(
    val systemUi: ResourceColors = ResourceColors(),
    val settings: ResourceColors = ResourceColors(),
    val myFiles: ResourceColors = ResourceColors(),
    val keyboard: ResourceColors = ResourceColors(),
    /** The key art Samsung Keyboard gets (`key_bg`, a nine-patch with its guides), or null. */
    val keyboardKey: ImageBitmap? = null,
    /** Pack art for the power menu buttons by drawable name (`tw_ic_do_poweroff` …); missing ones stay stock. */
    val powerIcons: Map<String, ImageBitmap> = emptyMap(),
)
