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
 * The persisted user theme.
 *
 * Map keys: [elementPacks], [elementEnabled] and [elementOptions] are [Element.id]s;
 * [targetEnabled] is keyed by target package; [colorPins] by `package:type/name`. A missing
 * entry in [elementEnabled] / [targetEnabled] means enabled.
 *
 * @property elementOptions per element, option key → value. An option token of a pack category
 *   (see [CategoryName.optionTokens]) is on when its value is `"true"`; the key `palette` with
 *   value `"pack"` keeps the pack's own palette instead of the derived theme palette.
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
    val format: Int = FORMAT,
) {
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
        /** Current [format] version. */
        public const val FORMAT: Int = 1

        /** Default seed: One UI blue. */
        public const val DEFAULT_SEED: Int = 0xFF3E91FF.toInt()

        /** A fresh theme: default seed, no packs, everything enabled. */
        public fun default(): ThemeSpec = ThemeSpec(id = "default", name = "Hex Next", source = ThemeSource(DEFAULT_SEED))

        /** Parses JSON written by [toJson]; unknown keys are ignored. */
        public fun fromJson(json: String): ThemeSpec = try {
            SpecJson.decodeFromString(serializer(), json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("invalid theme: ${e.message}", e)
        }
    }
}

internal val SpecJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
