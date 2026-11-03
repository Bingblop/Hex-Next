package com.hexnext.engine.compile

import com.hexnext.engine.mcu.hct.Hct
import com.hexnext.engine.palette.ColorMath
import com.hexnext.engine.palette.InferredColor
import com.hexnext.engine.palette.ResolvedTheme
import com.hexnext.engine.palette.RoleInference
import com.hexnext.engine.palette.ShadeReference
import com.hexnext.engine.palette.ThemeResolver
import java.util.regex.PatternSyntaxException
import kotlin.math.abs

/**
 * One themable colour resource of a scanned target, for the per-app resource editor.
 *
 * @property resourceName `pkg:color/name` (the [ThemeSpec.colorPins] key).
 * @property configuration `""` (day) or `"night"`.
 * @property observed Samsung's value at scan time: the SemWT value, else the APK literal (for a
 *   pin on a name with neither, the pinned value).
 * @property inferred nearest scan-time system shade, null when none was computed.
 * @property group the element group this resource belongs to, null when it matches none.
 * @property planned the value the current spec writes into the overlay for this configuration,
 *   exactly as [ThemeCompiler.compile] plans it (a SemWT colour no rule recolours is copied
 *   verbatim, so it equals [observed]); null when nothing is written and the APK value stays.
 * @property pin the user's pin on this resource, if any.
 */
public data class ResourceColor(
    val resourceName: String,
    val configuration: String,
    val observed: Int,
    val inferred: InferredColor?,
    val group: ColorGroup?,
    val planned: Int?,
    val pin: ColorPin?,
)

/**
 * Decides which [ColorGroup] each colour resource belongs to, and lists a target's colours.
 *
 * Classification ([classify]) runs three stages, first hit wins:
 * 1. **App rules.** The [ColorGroupFamily] that [OneUiRules.colorGroupPackages] assigns to the
 *    package (keyboard, dialer, AOD, System UI lock screen).
 * 2. **Name rules.** [OneUiRules.colorGroups], groups in file order, patterns in list order.
 * 3. **Fallback** on the inferred shade (only within [RoleInference.DEFAULT_MAX_DELTA_E]): an
 *    accent shade is [ColorGroup.ACCENT]; a neutral shade of a name that does not look like a
 *    foreground part (text, icon, stroke, thumb …) is a backdrop, [ColorGroup.BACKGROUND] or
 *    [ColorGroup.SURFACE] by shade.
 *
 * A name rule only claims a colour whose tone fits the group: fill groups such as switches,
 * buttons, sliders and active tiles take accent colours, backdrop groups such as backgrounds,
 * cards, inactive tiles and keyboard keys take neutral ones; text, icons, panels, dialogs and the
 * lock screen take any. The white thumb of a switch therefore never becomes the switch colour; a
 * colour whose tone does not fit falls through to the next rule. A colour with no reliable
 * inferred shade (Samsung's fixed colours) is matched by name alone.
 *
 * A name rule that lands in [ColorGroup.BACKGROUND] or [ColorGroup.SURFACE] is settled by shade
 * when the colour is neutral, because One UI names are ambiguous here: `sesl_background_color` is
 * the white card colour while `round_and_bgcolor` is the grey screen behind the cards. Shades 50,
 * 100 and 1000 are the screen ([ColorGroup.BACKGROUND]); 0, 10, 200, 700, 800 and 900 are cards
 * ([ColorGroup.SURFACE]). This matches the One UI 9 Settings dump in both modes (day: cards 0,
 * screen 50; night: cards neutral2 900, screen 1000) and the groups' Auto shades.
 */
public object ColorGroupMatcher {
    /**
     * The group [typeName] (`color/name`, already renamed by [OneUiRules.rename]) of package [pkg]
     * belongs to, or null. [inferred] is the observed value's nearest system shade when known.
     */
    public fun classify(pkg: String, typeName: String, inferred: InferredColor?, rules: OneUiRules): ColorGroup? =
        match(pkg, typeName, inferred, rules)?.group

    /**
     * Every themable colour of [target] in both configurations, sorted by resource name then
     * configuration, with its group and the value [spec] would write.
     *
     * Rows are every SemWT colour, every literal colour of the APK's resource table in the
     * default or `night` configuration, every colour the compiler adds, and every pin of the
     * target. [ResourceColor.planned] comes from the same planning code [ThemeCompiler.compile]
     * runs, so the two cannot disagree.
     */
    public fun inventory(
        target: TargetSnapshot,
        systemShades: Map<String, Int>,
        spec: ThemeSpec,
        rules: OneUiRules,
    ): List<ResourceColor> {
        val pkg = target.packageName
        val table = target.table
        val resolved: ResolvedTheme by lazy { ThemeResolver.resolve(spec.source, spec.darkAdjustments, spec.lightAdjustments) }
        val reference = ShadeReference(systemShades)
        val decisions = ThemeCompiler.planColors(pkg, table, target.semwt, reference, spec, rules) { resolved }
        val compiles = table != null && target.installed && !target.debloated &&
            pkg !in rules.deprecated && spec.isTargetEnabled(pkg)
        val planned: Map<Pair<String, String>, Int> = if (compiles) {
            ThemeCompiler.mapColors(pkg, table!!, target.semwt, decisions.values).first
                .associate { (it.resourceName.substringAfter(':') to it.configuration) to it.argb }
        } else {
            emptyMap()
        }
        val literals = table?.let(ThemeCompiler::tableColors).orEmpty()
        val literalValues = HashMap<Pair<String, String>, Int>()
        for (c in literals) for ((config, argb) in c.literals) literalValues[c.typeName to config] = argb

        val pins = HashMap<String, ColorPin>()
        for ((key, pin) in spec.colorPins) {
            if (key.substringBefore(':', "") != pkg) continue
            pins[rules.rename(key.substringAfter(':'))] = pin
        }
        val keys = LinkedHashSet<Pair<String, String>>()
        keys += decisions.observed.keys
        keys += decisions.values.keys
        keys += literalValues.keys
        for (name in pins.keys) if (keys.none { it.first == name }) keys += name to ""

        val rows = ArrayList<ResourceColor>(keys.size)
        for (key in keys) {
            val (typeName, config) = key
            if (!typeName.startsWith(COLOR_PREFIX)) continue
            val written = planned[key]
            val observed = decisions.observed[key] ?: literalValues[key] ?: literalValues[typeName to ""]
                ?: decisions.tableObserved[key] ?: written ?: decisions.values[key] ?: 0
            val inferred = if (decisions.inferred.containsKey(key)) decisions.inferred[key] else reference.infer(observed)
            val group = decisions.groups[key] ?: classify(pkg, typeName, inferred, rules)
            rows += ResourceColor("$pkg:$typeName", config, observed, inferred, group, written, pins[typeName])
        }
        rows.sortWith(compareBy<ResourceColor>({ it.resourceName }, { it.configuration }))
        return rows
    }

    /**
     * Number of resources per group over every target of [device] (for "Background: 412 in 31 apps").
     *
     * Counts the distinct resource names a colour for the group would really reach: the same
     * planning [ThemeCompiler.compile] runs, with every group set, over every target it would
     * compile. Names the Quick Settings policy keeps and names the target table lacks are not
     * counted. Groups that reach nothing are left out.
     */
    public fun groupCounts(device: DeviceSnapshot, rules: OneUiRules): Map<ColorGroup, GroupCount> {
        var spec = ThemeSpec.default()
        for (g in ColorGroup.ALL) {
            spec = spec.withGroupColor(g, night = false, argb = COUNT_PROBE).withGroupColor(g, night = true, argb = COUNT_PROBE)
        }
        val resolved: ResolvedTheme by lazy { ThemeResolver.resolve(spec.source, spec.darkAdjustments, spec.lightAdjustments) }
        val reference = ShadeReference(device.systemShades)
        val names = HashMap<ColorGroup, MutableSet<String>>()
        val packages = HashMap<ColorGroup, MutableSet<String>>()
        for ((pkg, target) in device.targets) {
            val table = target.table ?: continue
            if (pkg in rules.deprecated || !target.installed || target.debloated) continue
            val d = ThemeCompiler.planColors(pkg, table, target.semwt, reference, spec, rules) { resolved }
            for (key in d.reached) {
                val group = d.groups[key] ?: continue
                if (table.find(key.first) == null) continue
                names.getOrPut(group) { HashSet() } += "$pkg:${key.first}"
                packages.getOrPut(group) { HashSet() } += pkg
            }
        }
        val out = LinkedHashMap<ColorGroup, GroupCount>()
        for (g in ColorGroup.ALL) {
            val n = names[g] ?: continue
            out[g] = GroupCount(n.size, packages[g]?.size ?: 0)
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Internals shared with ThemeCompiler.

    /** A classification: the group, and whether it came from the shade fallback rather than a name. */
    internal data class Match(val group: ColorGroup, val byFallback: Boolean)

    /**
     * Groups whose resources a group colour may also reach in the APK's own resource table (names
     * SemWT does not set). Only structural groups whose names are unambiguous: text, accents and
     * icon tints stay with SemWT, where Samsung's own theming already marks what is themable.
     */
    internal val TABLE_GROUPS: Set<ColorGroup> = setOf(
        ColorGroup.BACKGROUND, ColorGroup.SURFACE, ColorGroup.DIALOG, ColorGroup.SWITCH, ColorGroup.BUTTON,
        ColorGroup.SLIDER, ColorGroup.QS_PANEL_BG, ColorGroup.QS_TILE_ACTIVE, ColorGroup.QS_TILE_INACTIVE,
        ColorGroup.NOTIFICATION_BG, ColorGroup.SYSTEM_BARS, ColorGroup.KEYBOARD_BG, ColorGroup.KEYBOARD_KEY,
        ColorGroup.DIALER, ColorGroup.LOCK_SCREEN,
    )

    /** APK colour names a group colour never reaches: status colours whose meaning is their hue. */
    internal val TABLE_EXCLUDED: Regex = Regex(
        "error|warning|warn_|danger|alert|emergency|(^|_)sos(_|$)|critical|battery_low|sunday|saturday|(^|_)(red|green|orange|yellow|purple|pink|brown)(_|$)|favorite|rating",
        RegexOption.IGNORE_CASE,
    )

    /** [classify] with the stage that matched. */
    internal fun match(pkg: String, typeName: String, inferred: InferredColor?, rules: OneUiRules): Match? {
        val bare = typeName.substringAfter(':')
        val type = bare.substringBefore('/', "color")
        if (type != "color") return null
        val name = bare.substringAfter('/')
        if (name.isEmpty()) return null
        val compiled = compiled(rules)
        val tone = toneOf(inferred)
        val family = compiled.familyFor(pkg)
        // Quick Settings groups only ever reach System UI.
        val qsAllowed = pkg == SYSTEMUI
        if (family != null) {
            for (rule in family.rules) {
                if (!qsAllowed && rule.group.quickSettings) continue
                if (fits(rule.group, tone) && rule.regex.containsMatchIn(name)) {
                    val g = settle(rule.group, inferred, tone)
                    if (!qsAllowed && g.quickSettings) continue
                    return Match(g, false)
                }
            }
        }
        for (rule in compiled.generic) {
            if (!qsAllowed && rule.group.quickSettings) continue
            if (fits(rule.group, tone) && rule.regex.containsMatchIn(name)) {
                val settled = settle(rule.group, inferred, tone)
                val g = family?.remap?.get(settled) ?: settled
                if (!qsAllowed && g.quickSettings) continue
                return Match(g, false)
            }
        }
        if (tone == Tone.NEUTRAL && STATELESS_TILE_FILL.containsMatchIn(name)) {
            return Match(ColorGroup.QS_TILE_INACTIVE, false)
        }
        val g = fallback(name, inferred, tone) ?: return null
        val out = family?.remap?.get(g) ?: g
        if (!qsAllowed && out.quickSettings) return null
        return Match(out, true)
    }

    /**
     * The colour a resource of [group] gets when the user picked [userArgb]: the user colour with
     * the [observed] alpha. For accent fill groups a very light or very dark accent container
     * (HCT tone ≥ 85 or ≤ 20, such as a pale tonal button) keeps its tone and takes the user
     * colour's hue, so containers stay containers; every other colour is the user colour exactly.
     */
    internal fun groupValue(group: ColorGroup, userArgb: Int, observed: Int, inferred: InferredColor?): Int {
        var rgb = userArgb or OPAQUE
        if (group in TONE_FOLLOWING && toneOf(inferred) == Tone.ACCENT) {
            val observedTone = ColorMath.lstar(observed)
            if (observedTone >= CONTAINER_LIGHT_TONE || observedTone <= CONTAINER_DARK_TONE) {
                val user = Hct.fromInt(rgb)
                if (abs(observedTone - user.tone) > CONTAINER_TONE_SLACK) rgb = ColorMath.clipToGamut(user.hue, user.chroma, observedTone)
            }
        }
        return ColorMath.withAlpha(rgb, ColorMath.alpha(observed))
    }

    private const val COLOR_PREFIX = "color/"
    private const val SYSTEMUI = "com.android.systemui"
    private const val OPAQUE = 0xFF000000.toInt()
    private const val COUNT_PROBE = 0xFF808080.toInt()
    private const val CONTAINER_LIGHT_TONE = 85.0
    private const val CONTAINER_DARK_TONE = 20.0
    private const val CONTAINER_TONE_SLACK = 10.0

    private enum class Tone { ACCENT, NEUTRAL, ANY }

    private val GROUP_TONES: Map<ColorGroup, Tone> = mapOf(
        ColorGroup.ACCENT to Tone.ACCENT,
        ColorGroup.SWITCH to Tone.ACCENT,
        ColorGroup.BUTTON to Tone.ACCENT,
        ColorGroup.SLIDER to Tone.ACCENT,
        ColorGroup.QS_TILE_ACTIVE to Tone.ACCENT,
        ColorGroup.DIALER to Tone.ACCENT,
        ColorGroup.BACKGROUND to Tone.NEUTRAL,
        ColorGroup.SURFACE to Tone.NEUTRAL,
        ColorGroup.QS_TILE_INACTIVE to Tone.NEUTRAL,
        ColorGroup.KEYBOARD_BG to Tone.NEUTRAL,
        ColorGroup.KEYBOARD_KEY to Tone.NEUTRAL,
    )

    private val TONE_FOLLOWING: Set<ColorGroup> = setOf(
        ColorGroup.ACCENT, ColorGroup.ICON_TINT, ColorGroup.SWITCH, ColorGroup.BUTTON, ColorGroup.SLIDER,
        ColorGroup.QS_TILE_ACTIVE, ColorGroup.DIALER,
    )

    private val WHITE_AND_BLACK = setOf(0, 10, 1000)
    private val BACKGROUND_SHADES = setOf(50, 100, 1000)
    private val SURFACE_SHADES = setOf(0, 10, 200, 700, 800, 900)

    /** Names that are parts drawn on top of something, never a backdrop the fallback may claim. */
    private val FOREGROUND = Regex(
        "text|title|label|summary|hint|icon|glyph|stroke|divider|line|outline|border|shadow|ripple|dot|tick|mark|" +
            "thumb|track|cursor|handle|scrim|(^|_)dim(_|$)|arrow|indicator|button|btn|switch|check|radio|progress|seek|" +
            "tile|selected|pressed|focus|badge|underline",
        RegexOption.IGNORE_CASE,
    )
    private val STATELESS_TILE_FILL = Regex(
        """(^|_)qs_tile_(?:round_)?(?:bg|background)(?:_color)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** The colour's tone, null when unknown (no reliable shade, or fully transparent). */
    private fun toneOf(inferred: InferredColor?): Tone? {
        if (inferred == null || inferred.deltaE > RoleInference.DEFAULT_MAX_DELTA_E || inferred.alpha == 0) return null
        // Shades 0, 10 and 1000 (tones 100, 99 and 0) are white and black in every role, and ties
        // pick accent1, so they are never accents.
        return if (inferred.role.isNeutral || inferred.shade in WHITE_AND_BLACK) Tone.NEUTRAL else Tone.ACCENT
    }

    private fun fits(group: ColorGroup, tone: Tone?): Boolean {
        val wanted = GROUP_TONES[group] ?: Tone.ANY
        return tone == null || wanted == Tone.ANY || wanted == tone
    }

    /** Settles a backdrop between screen and card by the neutral shade (see the class notes). */
    private fun settle(group: ColorGroup, inferred: InferredColor?, tone: Tone?): ColorGroup {
        if ((group != ColorGroup.BACKGROUND && group != ColorGroup.SURFACE) || tone != Tone.NEUTRAL || inferred == null) return group
        return when (inferred.shade) {
            in BACKGROUND_SHADES -> ColorGroup.BACKGROUND
            in SURFACE_SHADES -> ColorGroup.SURFACE
            else -> group
        }
    }

    private fun fallback(name: String, inferred: InferredColor?, tone: Tone?): ColorGroup? = when (tone) {
        null, Tone.ANY -> null
        Tone.ACCENT -> ColorGroup.ACCENT
        Tone.NEUTRAL -> if (FOREGROUND.containsMatchIn(name)) {
            null
        } else {
            when (inferred!!.shade) {
                in BACKGROUND_SHADES -> ColorGroup.BACKGROUND
                in SURFACE_SHADES -> ColorGroup.SURFACE
                else -> null
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Compiled rules, cached for the last rule set (the compiler and the editor reuse one).

    private class Rule(val group: ColorGroup, val regex: Regex)

    private class Family(val rules: List<Rule>, val remap: Map<ColorGroup, ColorGroup>)

    private class Compiled(val generic: List<Rule>, val families: Map<String, Family>, val packages: Map<String, String>) {
        fun familyFor(pkg: String): Family? = packages[pkg]?.let(families::get)
    }

    @Volatile
    private var cache: Pair<OneUiRules, Compiled>? = null

    private fun compiled(rules: OneUiRules): Compiled {
        val cached = cache
        if (cached != null && (cached.first === rules || cached.first == rules)) return cached.second
        val compiled = Compiled(
            generic = compileRules(rules.colorGroups),
            families = rules.colorGroupFamilies.mapValues { (_, f) ->
                Family(
                    compileRules(f.groups),
                    f.remap.entries.mapNotNull { (from, to) ->
                        val a = ColorGroup.byId(from) ?: return@mapNotNull null
                        val b = ColorGroup.byId(to) ?: return@mapNotNull null
                        a to b
                    }.toMap(),
                )
            },
            packages = rules.colorGroupPackages,
        )
        cache = rules to compiled
        return compiled
    }

    private fun compileRules(groups: Map<String, List<String>>): List<Rule> {
        val out = ArrayList<Rule>()
        for ((id, patterns) in groups) {
            val group = ColorGroup.byId(id) ?: continue
            for (p in patterns) {
                val regex = try {
                    Regex(p, RegexOption.IGNORE_CASE)
                } catch (e: PatternSyntaxException) {
                    continue
                }
                out += Rule(group, regex)
            }
        }
        return out
    }
}

/** How many resources ([resources]) in how many packages ([packages]) a group reaches. */
public data class GroupCount(val resources: Int, val packages: Int)
