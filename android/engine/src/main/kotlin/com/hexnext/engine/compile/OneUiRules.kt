package com.hexnext.engine.compile

import com.hexnext.engine.device.OneUiProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Quick Settings rules for one One UI version (`"quickSettings"` in the rule file).
 *
 * One UI 7, 8 and 9 each rebuilt the Quick Settings layout, so overlays that ship tile shapes,
 * panel backgrounds or other System UI drawables made for one version break the panel on the
 * next. Hex Next therefore themes System UI with colours only, and only colours that are safe
 * to change on every layout.
 *
 * @property layout layout identifier, `oneui7`, `oneui8` or `oneui9`; filled in from the rule
 *   file's major when blank.
 * @property colours allowlisted System UI colour names (`qs_tile_bg`, or `color/qs_tile_bg`).
 *   Empty means "any name matching [ThemeCompiler.QS_COLOUR_NAMES]"; a non-empty list is the
 *   complete set of colours kept.
 * @property drawablesAllowed true lets packs build System UI drawables for elements that are
 *   not [QuickSettingsPolicy.COLOURS_ONLY]. Every bundled version keeps it false.
 * @property filterColours false switches the colour filter off entirely (every recoloured
 *   System UI colour is kept). Meant for tests and experiments, not bundled rule files.
 */
@Serializable
public data class QuickSettingsRules(
    val layout: String = "",
    val colours: List<String> = emptyList(),
    val drawablesAllowed: Boolean = false,
    val filterColours: Boolean = true,
) {
    /** True when the policy only writes colours into System UI (no pack drawables). */
    public val coloursOnly: Boolean get() = !drawablesAllowed

    /**
     * `"One UI 9"` for the layout `oneui9`; any other [layout] is returned as given, and a
     * blank layout gives `"One UI"`.
     */
    public val layoutLabel: String
        get() = when {
            layout.isBlank() -> "One UI"
            layout.startsWith(LAYOUT_PREFIX) && layout.length > LAYOUT_PREFIX.length &&
                layout.substring(LAYOUT_PREFIX.length).all(Char::isDigit) -> "One UI " + layout.substring(LAYOUT_PREFIX.length)
            else -> layout
        }

    /**
     * True when the System UI colour [name] (`qs_tile_bg` or `color/qs_tile_bg`) may be
     * written under this policy.
     */
    public fun allowsColour(name: String): Boolean {
        if (!filterColours) return true
        val short = name.substringAfter('/')
        return if (colours.isEmpty()) ThemeCompiler.QS_COLOUR_NAMES.containsMatchIn(short) else colours.any { it.substringAfter('/') == short }
    }

    /** This rule set with [layout] defaulted to `oneui<major>` when blank. */
    public fun withDefaultLayout(major: Int): QuickSettingsRules =
        if (layout.isBlank() && major > 0) copy(layout = layoutFor(major)) else this

    public companion object {
        private const val LAYOUT_PREFIX = "oneui"

        /** `oneui<major>`. */
        public fun layoutFor(major: Int): String = "$LAYOUT_PREFIX$major"

        /** The safe default: colours only, regex filter, latest known layout when [major] is 0. */
        public fun default(major: Int = 0): QuickSettingsRules = QuickSettingsRules().withDefaultLayout(major)
    }
}

/**
 * Version-specific rules from `engine/src/main/resources/oneui/<major>.json`.
 *
 * @property major One UI major version the rules are for.
 * @property targets target packages to theme, in build order.
 * @property deprecated packages never themed on this version (even if installed).
 * @property quickSettings the Quick Settings policy for System UI: which colours are kept and
 *   whether pack drawables may be built. Defaults to colours only with the regex filter.
 * @property renamed resource renames, `type/name` → `type/name`, applied before every lookup.
 * @property dashboardRows Settings dashboard row keys (`display`, `galaxy_ai` …) whose icons are
 *   `com.android.settings:drawable/sec_ic_settings_<row>`; the rows icon synthesis may fill in.
 */
@Serializable
public data class OneUiRules(
    val major: Int = 0,
    val targets: List<String> = emptyList(),
    val deprecated: List<String> = emptyList(),
    val quickSettings: QuickSettingsRules = QuickSettingsRules(),
    val renamed: Map<String, String> = emptyMap(),
    val dashboardRows: List<String> = emptyList(),
) {
    /** [targets] without [deprecated] packages. */
    public val activeTargets: List<String> get() = targets.filterNot { it in deprecated }

    /** [typeName] (`type/name`) after [renamed]. */
    public fun rename(typeName: String): String = renamed[typeName] ?: typeName

    public companion object {
        private val RulesJson = Json { ignoreUnknownKeys = true }

        /** Parses a rule file; a blank Quick Settings layout is filled in from `major`. */
        public fun parse(json: String): OneUiRules = try {
            val rules = RulesJson.decodeFromString(serializer(), json)
            rules.copy(quickSettings = rules.quickSettings.withDefaultLayout(rules.major))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("invalid One UI rules: ${e.message}", e)
        }

        /** Loads the bundled rules for One UI [major]; throws [IllegalArgumentException] when there are none. */
        public fun load(major: Int): OneUiRules {
            val stream = OneUiRules::class.java.getResourceAsStream("/oneui/$major.json")
                ?: throw IllegalArgumentException("no rules for One UI $major")
            val rules = stream.use { parse(String(it.readBytes(), Charsets.UTF_8)) }
            return if (rules.major == 0) {
                rules.copy(major = major, quickSettings = rules.quickSettings.withDefaultLayout(major))
            } else {
                rules
            }
        }

        /**
         * Rules for [profile]: its major when supported, otherwise the newest supported major
         * (unknown or newer devices are treated like the latest One UI).
         */
        public fun forProfile(profile: OneUiProfile): OneUiRules {
            val major = profile.version?.major
            return load(if (major != null && major in OneUiProfile.SUPPORTED_MAJORS) major else OneUiProfile.SUPPORTED_MAJORS.last)
        }
    }
}
