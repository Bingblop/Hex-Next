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
 * @property extraColours System UI colour names allowed in addition to [colours] (or to the
 *   regex when [colours] is empty): verified names from a device dump that the regex misses,
 *   such as One UI 9's `open_theme_noti_header_color`. Every other name keeps the existing
 *   behaviour (Samsung's value copied verbatim, reported as [ThemeCompiler.REASON_QS_POLICY]).
 * @property drawablesAllowed true lets packs build System UI drawables for elements that are
 *   not [QuickSettingsPolicy.COLOURS_ONLY]. Every bundled version keeps it false.
 * @property outsideDrawables System UI drawables (`drawable/tw_ic_do_poweroff`, or a prefix ending
 *   in `*` such as `drawable/sec_ic_wifi_signal_*`) built even when [drawablesAllowed] is off:
 *   the power menu and status bar icons, which do not touch the Quick panel layout.
 * @property filterColours false switches the colour filter off entirely (every recoloured
 *   System UI colour is kept). Meant for tests and experiments, not bundled rule files.
 * @property values [ShapeControls] id → the candidate resources behind that shape control
 *   (non-colour values: dimens, integers, bools). Only candidates the phone defines are written
 *   (see [ValuePlanner]); a version without entries gets no shape controls at all.
 * @property valueNames further `pkg:type/name` values that [ThemeSpec.values] (advanced and pack
 *   entries) may set; every other name is skipped with [ValuePlanner.REASON_VALUE_POLICY].
 */
@Serializable
public data class QuickSettingsRules(
    val layout: String = "",
    val colours: List<String> = emptyList(),
    val extraColours: List<String> = emptyList(),
    val drawablesAllowed: Boolean = false,
    val outsideDrawables: List<String> = emptyList(),
    val filterColours: Boolean = true,
    val values: Map<String, ValueRule> = emptyMap(),
    val valueNames: List<String> = emptyList(),
) {
    /**
     * True when the value [name] (`pkg:type/name`) may be written: it is a candidate of some
     * [values] control or listed in [valueNames].
     */
    public fun allowsValue(name: String): Boolean = name in allowedValueNames

    /** True when System UI drawable [name] (`drawable/x`) is one of [outsideDrawables]. */
    public fun allowsOutside(name: String): Boolean = outsideDrawables.any {
        if (it.endsWith("*")) name.startsWith(it.dropLast(1)) else name == it
    }

    /** The [values] control whose candidates include [name], or null. */
    public fun valueRuleFor(name: String): Pair<String, ValueRule>? =
        values.entries.firstOrNull { name in it.value.names }?.toPair()

    private val allowedValueNames: Set<String> by lazy { values.values.flatMapTo(HashSet()) { it.names } + valueNames }

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
        if (extraColours.any { it.substringAfter('/') == short }) return true
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
 * The resources behind one shape control ([QuickSettingsRules.values]).
 *
 * @property type resource type of every candidate: `dimen`, `integer`, `bool` or `float`
 *   (a `<item format="float" type="dimen">`).
 * @property names candidate resources, `pkg:type/name`, best first. Several can be written at
 *   once (the same corner drawn by two layouts); names the phone lacks are skipped.
 * @property min lowest value written, in [unit] for dimens; values are clamped, never rejected.
 * @property max highest value written.
 * @property unit unit of [min], [max] and the control's slider.
 * @property step slider step in [unit].
 * @property scale true when the control is a factor of the stock value rather than a size.
 */
@Serializable
public data class ValueRule(
    val type: String = "dimen",
    val names: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val unit: DimenUnit = DimenUnit.DP,
    val step: Double = 1.0,
    val scale: Boolean = false,
)

/**
 * App-specific colour group rules for the packages [OneUiRules.colorGroupPackages] assigns to it.
 *
 * @property groups [ColorGroup.id] → patterns, with the same syntax and order as
 *   [OneUiRules.colorGroups]; tried before the generic rules.
 * @property remap generic result → group for this family: a colour the generic rules or the
 *   fallback put in `background` becomes `keyboard_bg` in the keyboard, for example.
 * @property exact [ColorGroup.id] → bare colour names (`qs_tile_round_background_on`, no
 *   `color/`, compared case-insensitively) verified on a device to be that element. They are
 *   tried before [groups], skip the tone check (a white active tile is still the active tile),
 *   and take the group's Auto colour when the user left the group on Auto, so the device shows
 *   what the Studio preview shows ([ThemeSpec.effectiveGroupColor]). They are written whether
 *   SemWT sets them or only the APK defines them, and System UI exact names pass the Quick
 *   Settings filter ([OneUiRules.allowsSystemUiColour]).
 */
@Serializable
public data class ColorGroupFamily(
    val groups: Map<String, List<String>> = emptyMap(),
    val remap: Map<String, String> = emptyMap(),
    val exact: Map<String, List<String>> = emptyMap(),
)

/**
 * Version-specific rules from `engine/src/main/resources/oneui/<major>.json`.
 *
 * @property major One UI major version the rules are for.
 * @property targets target packages to theme, in build order.
 * @property deprecated packages never themed on this version (even if installed).
 * @property quickSettings the Quick Settings policy for System UI: which colours are kept and
 *   whether pack drawables may be built. Defaults to colours only with the regex filter.
 * @property renamed resource renames, `type/name` → `type/name`, applied before every lookup.
 * @property fallbackRenames renames used only for a pack drawable whose own name the target does
 *   not have: older packs name switches and checkboxes `switch_thumb_on` or `checkbox_026`, which
 *   One UI 9 apps call `sesl_switch_thumb_on` and `sesl_btn_check_to_on_026`. A target that still
 *   has the old name keeps it.
 * @property drawableCopies pack drawable `type/name` → more target `type/name`s it also paints when
 *   the target has them. Packs name dialog and popup art after the plugin's own slots (`dialog_`,
 *   `popup_`, `dialog_xmlonly_oui3_night`); no One UI 9 resource has those names, so without a copy
 *   the art never reached a dialog (device lab, lab-015/016: Force stop stayed stock grey).
 * @property dashboardRows Settings dashboard row keys (`display`, `galaxy_ai` …) whose icons are
 *   `com.android.settings:drawable/sec_ic_settings_<row>`; the rows icon synthesis may fill in.
 * @property colorGroups [ColorGroup.id] → regular expressions over the bare colour name
 *   (`sesl_switch_track_on_color_light`, no `color/`), matched case-insensitively anywhere in the
 *   name. Groups are tried in file order and patterns in list order; the first group whose pattern
 *   matches and whose tone fits the observed colour wins (see [ColorGroupMatcher.classify]).
 *   Unknown group ids are ignored, so newer files stay readable.
 * @property colorGroupPackages package → key of [colorGroupFamilies]: the app-specific rules run
 *   before [colorGroups] for that package (`com.samsung.android.honeyboard` → `keyboard`).
 * @property colorGroupFamilies app-specific rule families (see [ColorGroupFamily]).
 */
@Serializable
public data class OneUiRules(
    val major: Int = 0,
    val targets: List<String> = emptyList(),
    val deprecated: List<String> = emptyList(),
    val quickSettings: QuickSettingsRules = QuickSettingsRules(),
    val renamed: Map<String, String> = emptyMap(),
    val fallbackRenames: Map<String, String> = emptyMap(),
    val drawableCopies: Map<String, List<String>> = emptyMap(),
    val dashboardRows: List<String> = emptyList(),
    val colorGroups: Map<String, List<String>> = emptyMap(),
    val colorGroupPackages: Map<String, String> = emptyMap(),
    val colorGroupFamilies: Map<String, ColorGroupFamily> = emptyMap(),
) {
    /** [targets] without [deprecated] packages. */
    public val activeTargets: List<String> get() = targets.filterNot { it in deprecated }

    /** [typeName] (`type/name`) after [renamed]. */
    public fun rename(typeName: String): String = renamed[typeName] ?: typeName

    /**
     * [typeName] after [renamed], then after [fallbackRenames] when the target has no resource of
     * that name ([has] is false for it).
     */
    public fun renameFor(typeName: String, has: (String) -> Boolean): String {
        val renamedName = rename(typeName)
        if (has(renamedName)) return renamedName
        return fallbackRenames[renamedName] ?: renamedName
    }

    /**
     * The [ColorGroupFamily.exact] names of [pkg]'s family, bare and lower case; empty when the
     * package has no family or the family no exact names.
     */
    public fun exactColourNames(pkg: String): Set<String> {
        val family = colorGroupPackages[pkg]?.let(colorGroupFamilies::get) ?: return emptySet()
        return family.exact.values.flatMapTo(HashSet()) { names -> names.map { it.substringAfter('/').lowercase() } }
    }

    /**
     * True when the System UI colour [name] (`qs_tile_bg` or `color/qs_tile_bg`) may be written:
     * [QuickSettingsRules.allowsColour], or, under the pattern filter (no explicit
     * [QuickSettingsRules.colours] list, which stays the complete set), a device-verified
     * [ColorGroupFamily.exact] name of System UI such as `shade_header_clock_color`.
     */
    public fun allowsSystemUiColour(name: String): Boolean =
        quickSettings.allowsColour(name) ||
            (quickSettings.colours.isEmpty() && name.substringAfter('/').lowercase() in systemUiExact)

    private val systemUiExact: Set<String> by lazy { exactColourNames(SYSTEMUI_PACKAGE) }

    public companion object {
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
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
