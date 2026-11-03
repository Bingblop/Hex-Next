package com.hexnext.engine.compile

import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ShadeScale
import com.hexnext.engine.palette.ThemeAdjustments
import com.hexnext.engine.palette.ThemeSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Whether a theme uses one pack for everything or a pack per element. */
@Serializable
public enum class ThemeMode {
    /** [ThemeSpec.simplePackId] for every element. */
    SIMPLE,

    /** [ThemeSpec.elementPacks] per element, falling back to [ThemeSpec.simplePackId]. */
    ADVANCED,
}

/** A user pin for one colour resource; pins win over role inference. */
@Serializable
public sealed class ColorPin {
    /** A fixed colour, used in every configuration. */
    @Serializable
    @SerialName("fixed")
    public data class Fixed(val argb: Int) : ColorPin()

    /**
     * A palette shade: [role] at Android shade number [shade] (0, 10, 50 … 1000), taken from the
     * dark scheme in night configurations and from the light scheme otherwise.
     */
    @Serializable
    @SerialName("shade")
    public data class Shade(val role: PaletteRole, val shade: Int) : ColorPin() {
        init {
            require(role.systemName != null) { "$role has no system shades" }
            require(ShadeScale.indexOf(shade) >= 0) { "unknown shade $shade" }
        }
    }
}

/**
 * How pack icons (Settings rows, My Files categories) and synthesised Settings icons are coloured.
 * System UI drawables are never affected: Quick Settings stays colour-only.
 */
@Serializable
public enum class IconColorStyle {
    /** The pack's own artwork and palette, never re-tinted by the theme. The default. */
    PACK_ORIGINAL,

    /** Every accent layer tinted from the theme's accent shades (the pre-Studio behaviour). */
    ACCENT,

    /** Each Settings row / My Files category gets its own vivid hue, like stock One UI. */
    MULTICOLOR,

    /** Accent layers alternate between the primary and tertiary accent. */
    DUOTONE,

    /** As [MULTICOLOR], with each accent layer drawn as a two-stop linear gradient. */
    GRADIENT,
}

/**
 * The user's colour for one [ColorGroup], per mode; a null side means Auto (derived from the
 * palette, see [ColorGroup.auto]).
 */
@Serializable
public data class GroupColor(val light: Int? = null, val dark: Int? = null) {
    /** The override for night ([night] true) or day configurations, null for Auto. */
    public fun forNight(night: Boolean): Int? = if (night) dark else light

    /** True when neither mode is overridden. */
    public val isAuto: Boolean get() = light == null && dark == null
}

/**
 * A custom font the user imported: file names under `custom/fonts`.
 *
 * @property regular file name of the regular weight.
 * @property bold file name of the bold weight, null to reuse [regular].
 * @property displayName family name shown in the UI.
 */
@Serializable
public data class CustomFont(val regular: String, val bold: String? = null, val displayName: String)

/**
 * The persisted user theme.
 *
 * Map keys: [elementPacks], [elementEnabled] and [elementOptions] are [Element.id]s;
 * [targetEnabled] is keyed by target package; [colorPins] by `package:type/name`. A missing
 * entry in [elementEnabled] / [targetEnabled] means enabled.
 *
 * @property elementOptions per element, option key → value. An option token of a pack category
 *   (see [CategoryName.optionTokens]) is on when its value is `"true"`; the key `palette` with
 *   value `"pack"` keeps the pack's own palette instead of the derived theme palette.
 * @property customIcons `pkg:drawable/name` → file name under `custom/icons` (ARGB PNG, already
 *   converted); replaces the pack's drawable for that resource.
 * @property customBackgrounds `pkg:drawable/name` → file name under `custom/backgrounds` (ARGB PNG).
 * @property customFont the system font to install with the theme, null for the system default.
 * @property synthesizeMissingIcons when true the compiler plans pack-style icons for Settings
 *   dashboard rows the pack lacks (see `PlannedDrawableJob.synthesis`).
 * @property colorGroups [ColorGroup.id] → user colours for that One UI element group. A group
 *   colour beats role inference for every resource the group matches, in every Samsung target;
 *   [colorPins] still beat groups. Missing or [GroupColor.isAuto] entries mean Auto.
 * @property iconStyle how pack and synthesised icons are coloured (see [IconColorStyle]).
 * @property builtInIcons when true, Settings rows and My Files categories no pack or custom icon
 *   covers get the app's own colourful icons (see [IconColors.builtInJobs]). Off in files written
 *   before it existed; on in [default] themes.
 * @property qsPanelAlpha 0 … 255 alpha written to the Quick panel background resources (the
 *   [ColorGroup.QS_PANEL_BG] group), Auto or custom; 255 leaves the alpha Samsung uses.
 */
@Serializable
public data class ThemeSpec(
    val id: String,
    val name: String,
    val source: ThemeSource,
    val darkAdjustments: ThemeAdjustments = ThemeAdjustments(),
    val lightAdjustments: ThemeAdjustments = ThemeAdjustments(),
    val mode: ThemeMode = ThemeMode.SIMPLE,
    val simplePackId: String? = null,
    val elementPacks: Map<String, String> = emptyMap(),
    val elementEnabled: Map<String, Boolean> = emptyMap(),
    val elementOptions: Map<String, Map<String, String>> = emptyMap(),
    val targetEnabled: Map<String, Boolean> = emptyMap(),
    val colorPins: Map<String, ColorPin> = emptyMap(),
    val customIcons: Map<String, String> = emptyMap(),
    val customBackgrounds: Map<String, String> = emptyMap(),
    val customFont: CustomFont? = null,
    val synthesizeMissingIcons: Boolean = true,
    val colorGroups: Map<String, GroupColor> = emptyMap(),
    val iconStyle: IconColorStyle = IconColorStyle.PACK_ORIGINAL,
    val builtInIcons: Boolean = false,
    val qsPanelAlpha: Int = 255,
    val format: Int = FORMAT,
) {
    init {
        require(qsPanelAlpha in 0..255) { "qsPanelAlpha $qsPanelAlpha is not 0..255" }
    }

    /** True unless [elementEnabled] switches [elementId] off. */
    public fun isElementEnabled(elementId: String): Boolean = elementEnabled[elementId] != false

    /** True unless [targetEnabled] switches [packageName] off. */
    public fun isTargetEnabled(packageName: String): Boolean = targetEnabled[packageName] != false

    /** The pack id used for [elementId]: per element in [ThemeMode.ADVANCED], else [simplePackId]. */
    public fun packFor(elementId: String): String? = when (mode) {
        ThemeMode.SIMPLE -> simplePackId
        ThemeMode.ADVANCED -> elementPacks[elementId] ?: simplePackId
    }

    /** Every pack id the theme references, sorted. */
    public val referencedPackIds: List<String>
        get() = (listOfNotNull(simplePackId) + if (mode == ThemeMode.ADVANCED) elementPacks.values else emptyList())
            .distinct().sorted()

    /** This theme as JSON. */
    public fun toJson(): String = SpecJson.encodeToString(serializer(), this)

    public companion object {
        /**
         * Current [format] version. Additive fields (such as [colorGroups] and [iconStyle]) keep
         * the version: they have defaults, so older files load and older readers skip them.
         */
        public const val FORMAT: Int = 1

        /** Default seed: One UI blue. */
        public const val DEFAULT_SEED: Int = 0xFF3E91FF.toInt()

        /** A fresh theme: default seed, no packs, everything enabled. */
        public fun default(): ThemeSpec =
            ThemeSpec(id = "default", name = "Hex Next", source = ThemeSource(DEFAULT_SEED), builtInIcons = true)

        /** Parses JSON written by [toJson]; unknown keys are ignored. */
        public fun fromJson(json: String): ThemeSpec = try {
            SpecJson.decodeFromString(serializer(), json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("invalid theme: ${e.message}", e)
        }
    }
}

// coerceInputValues: an enum constant written by a newer build (say a future IconColorStyle)
// falls back to the property's default instead of making the whole theme unreadable.
internal val SpecJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false; coerceInputValues = true }
