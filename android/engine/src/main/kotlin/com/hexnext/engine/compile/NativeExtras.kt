package com.hexnext.engine.compile

import com.hexnext.engine.pack.Pack
import com.hexnext.engine.pack.PackIdAliases
import com.hexnext.engine.pack.PackOption
import com.hexnext.engine.palette.ColorMath
import com.hexnext.engine.palette.ResolvedTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * One effect of a native extra choice. Exactly one of these forms:
 * - [group] + [alpha]: the group's colours get that alpha scale ([ThemeSpec.groupAlpha]).
 * - [group] + [colour]: the group's colour, when the user left the group on Auto.
 * - [names] (+ [targets]) + [colour] and/or [alpha]: every colour whose bare name matches the
 *   regular expression (case-insensitive) is recoloured, keeping Samsung's alpha unless [alpha]
 *   is given.
 * - [shape] + [dp]: a [ShapeControls] size, when the user did not set that control.
 *
 * Colour tokens: `primary`, `accent`, `box_stroke` (pack palette slots 1, 2, 4 derived from the
 * theme), `text_primary`, `text_secondary`, `notification_header`, `qs_tile_active`,
 * `qs_tile_icon_on` (the group's effective colour), `white`, `black`, `gray`, or `#AARRGGBB`.
 */
@Serializable
public data class NativeEffect(
    val group: String? = null,
    val alpha: Int? = null,
    val colour: String? = null,
    val names: String? = null,
    val targets: List<String> = emptyList(),
    val shape: String? = null,
    val dp: Double? = null,
)

/** The effects of one option key; [packs] restricts a generic key (`green`) to the packs it means that in. */
@Serializable
public data class NativeChoice(val effects: List<NativeEffect> = emptyList(), val packs: List<String> = emptyList())

/**
 * A group of pack options Hex Next rebuilds without the pack's artwork.
 *
 * @property element the [Element] whose pack is consulted and whose [ThemeSpec.elementOptions]
 *   store the choice.
 * @property keys option key → its effects.
 */
@Serializable
public data class NativeExtra(
    val id: String,
    val label: String = "",
    val element: String,
    val keys: Map<String, NativeChoice> = emptyMap(),
)

@Serializable
private data class NativeExtrasFile(val extras: List<NativeExtra> = emptyList())

/**
 * A colour change by resource name, from a [NativeEffect] with [NativeEffect.names]: [day] /
 * [night] replace the colour (keeping its alpha) when set, [alpha] replaces the alpha when set.
 * [targets] empty means every target.
 */
public data class ExtraColour(
    val names: Regex,
    val targets: Set<String>,
    val day: Int?,
    val night: Int?,
    val alpha: Int?,
    val source: String,
) {
    /** True when this change applies to [pkg]'s colour [bareName]. */
    public fun matches(pkg: String, bareName: String): Boolean =
        (targets.isEmpty() || pkg in targets) && names.containsMatchIn(bareName)

    /** [base] after this change, for night ([night] true) or day. */
    public fun apply(base: Int, night: Boolean): Int {
        var v = base
        (if (night) this.night else day)?.let { v = ColorMath.withAlpha(it, ColorMath.alpha(base)) }
        alpha?.let { v = ColorMath.withAlpha(v, it.coerceIn(0, 255)) }
        return v
    }
}

/**
 * What [NativeExtras.resolve] made of a theme: [spec] with the extras merged in below the user's
 * own settings, the colour changes by name for [ThemeCompiler.planColors], and one line per
 * applied choice for the build log.
 */
public data class ResolvedExtras(val spec: ThemeSpec, val colours: List<ExtraColour>, val applied: List<String>) {
    public companion object {
        /** No extras: [spec] unchanged. */
        public fun none(spec: ThemeSpec): ResolvedExtras = ResolvedExtras(spec, emptyList(), emptyList())
    }
}

/** One native option of a pack: the option shown in the pack card and its extra. */
public data class NativeOption(val live: LiveOption, val extra: NativeExtra)

/**
 * Pack options that gate no layer category in a pack (so they did nothing before) but that Hex
 * Next can rebuild natively: notification card transparency and corners, Quick panel glass,
 * list divider and control tints. The table is `/extras/native.json`.
 *
 * A native select gets a first choice [DEFAULT_CHOICE_LABEL] ([defaultKey]) that leaves One UI
 * as it is. A choice is on when the user picked it, or, before the user touched the option, when
 * the pack's own settings (`default.hex`) turn it on. A select's first key is never assumed on,
 * unlike layer options: a colour change nobody asked for would be a surprise.
 */
public object NativeExtras {
    /** Label of the choice that leaves One UI as it is. */
    public const val DEFAULT_CHOICE_LABEL: String = "One UI default"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Every extra of the bundled table. */
    public val ALL: List<NativeExtra> by lazy {
        val stream = NativeExtras::class.java.getResourceAsStream("/extras/native.json") ?: return@lazy emptyList()
        stream.use { json.decodeFromString(NativeExtrasFile.serializer(), String(it.readBytes(), Charsets.UTF_8)).extras }
    }

    /** The pseudo key of [extra]'s "One UI default" choice. */
    public fun defaultKey(extra: NativeExtra): String = "hexnext_default_${extra.id}"

    /**
     * The native options of [pack], in pack order: options with at least one supported key, none
     * of whose keys gates a layer category ([PackOptions.live] keeps those). A select keeps only its
     * supported keys, after the "One UI default" choice.
     */
    public fun options(pack: Pack, extras: List<NativeExtra> = ALL): List<NativeOption> {
        if (extras.isEmpty()) return emptyList()
        val layerKeys = PackOptions.live(pack).flatMapTo(HashSet()) { it.keys }
        val out = ArrayList<NativeOption>()
        val seen = HashSet<Pair<String, List<String>>>()
        for (option in pack.options) {
            val keys = PackOptions.keysOf(option)
            if (keys.isEmpty() || keys.any { it in layerKeys } || !seen.add(option.type to keys)) continue
            val extra = extras.firstOrNull { e -> keys.any { supports(e, pack, it) } } ?: continue
            val supported = keys.withIndex().filter { supports(extra, pack, it.value) }
            val label = option.label.trim().ifEmpty { keys.first() }
            val shown = if (option.type == PackOption.SELECT) {
                option.copy(
                    label = label,
                    keys = listOf(defaultKey(extra)) + supported.map { it.value },
                    choices = listOf(DEFAULT_CHOICE_LABEL) + supported.map { (i, k) -> option.choices.getOrNull(i)?.trim()?.ifEmpty { null } ?: k },
                )
            } else {
                option.copy(label = label)
            }
            out += NativeOption(LiveOption(shown, setOf(extra.element), extra = extra.id), extra)
        }
        return out
    }

    private fun supports(extra: NativeExtra, pack: Pack, key: String): Boolean {
        val choice = extra.keys[key] ?: return false
        return choice.effects.isNotEmpty() && (choice.packs.isEmpty() || PackIdAliases.current(pack.id) in choice.packs || pack.id in choice.packs)
    }

    /** Option keys [pack]'s own settings turn on (`<plugin>:<key>` = `"true"`). */
    public fun declaredDefaults(pack: Pack): Set<String> = pack.settings.mapNotNullTo(HashSet()) { (name, value) ->
        val sep = name.indexOf(':')
        if (sep <= 0 || (value as? JsonPrimitive)?.content != "true") null else name.substring(sep + 1)
    }

    /**
     * The current state of native [live] for [spec]: the key that is on for a select (its
     * [defaultKey] when none is), `"true"` / `"false"` for a switch.
     */
    public fun value(spec: ThemeSpec, pack: Pack, live: LiveOption, elementId: String? = null): String {
        val element = elementId ?: live.elementIds.firstOrNull()
        val effective = effectiveOptions(spec, pack, element)
        val option = live.option
        return if (option.type == PackOption.SELECT) {
            option.keys.drop(1).firstOrNull { effective[it] == "true" } ?: option.keys.first()
        } else {
            if (option.key != null && effective[option.key] == "true") "true" else "false"
        }
    }

    private fun effectiveOptions(spec: ThemeSpec, pack: Pack, element: String?): Map<String, String> {
        val declared = declaredDefaults(pack)
        val defaults = pack.defaultOptions.filter { (k, v) -> v == "true" && k in declared }
        return defaults + spec.elementOptions[element].orEmpty()
    }

    /**
     * Merges the native choices of [spec]'s packs into it, below the user's own settings: a group
     * colour only for groups on Auto, a group alpha only where [ThemeSpec.groupAlpha] (and, for the
     * panel, [ThemeSpec.qsPanelAlpha]) is untouched, a shape only where [ThemeSpec.shapes] has none.
     */
    public fun resolve(spec: ThemeSpec, packs: PackResolver, resolved: () -> ResolvedTheme, extras: List<NativeExtra> = ALL): ResolvedExtras {
        if (extras.isEmpty()) return ResolvedExtras.none(spec)
        var groups = spec.colorGroups
        var alphas = spec.groupAlpha
        var shapes = spec.shapes
        val colours = ArrayList<ExtraColour>()
        val applied = ArrayList<String>()
        val byElement = extras.groupBy { it.element }
        val packCache = HashMap<String, List<NativeOption>>()
        for ((elementId, _) in byElement) {
            if (!spec.isElementEnabled(elementId)) continue
            val packId = spec.packFor(elementId) ?: continue
            val pack = packs.pack(packId) ?: continue
            val options = packCache.getOrPut(pack.id) { options(pack, extras) }.filter { elementId in it.live.elementIds }
            if (options.isEmpty()) continue
            val effective = effectiveOptions(spec, pack, elementId)
            for (native in options) {
                val option = native.live.option
                val on = if (option.type == PackOption.SELECT) {
                    listOfNotNull(option.keys.drop(1).firstOrNull { effective[it] == "true" })
                } else {
                    listOfNotNull(option.key?.takeIf { effective[it] == "true" })
                }
                for (key in on) {
                    val choice = native.extra.keys[key] ?: continue
                    val source = "pack:${pack.id}:$key"
                    for (effect in choice.effects) {
                        val group = effect.group?.let(ColorGroup::byId)
                        when {
                            group != null && effect.alpha != null -> {
                                val untouched = group.id !in alphas && (group != ColorGroup.QS_PANEL_BG || spec.qsPanelAlpha == OPAQUE)
                                if (untouched) alphas = alphas + (group.id to effect.alpha.coerceIn(0, 255))
                            }
                            group != null && effect.colour != null -> {
                                if (groups[group.id]?.isAuto != false) {
                                    val day = token(effect.colour, spec, resolved, night = false)
                                    val night = token(effect.colour, spec, resolved, night = true)
                                    if (day != null && night != null) groups = groups + (group.id to GroupColor(day, night))
                                }
                            }
                            effect.names != null -> {
                                val regex = runCatching { Regex(effect.names, RegexOption.IGNORE_CASE) }.getOrNull() ?: continue
                                val day = effect.colour?.let { token(it, spec, resolved, night = false) }
                                val night = effect.colour?.let { token(it, spec, resolved, night = true) }
                                if (day == null && effect.alpha == null) continue
                                colours += ExtraColour(regex, effect.targets.toSet(), day, night, effect.alpha, source)
                            }
                            effect.shape != null && effect.dp != null -> {
                                if (effect.shape !in shapes) shapes = shapes + (effect.shape to ValueSetting.dimen(effect.dp))
                            }
                            else -> continue
                        }
                    }
                    applied += "${native.extra.id}: $source"
                }
            }
        }
        if (applied.isEmpty()) return ResolvedExtras.none(spec)
        return ResolvedExtras(spec.copy(colorGroups = groups, groupAlpha = alphas, shapes = shapes), colours, applied)
    }

    /** The ARGB of colour [token] for night or day; null for an unknown token. */
    public fun token(token: String, spec: ThemeSpec, resolved: () -> ResolvedTheme, night: Boolean): Int? {
        fun slot(index: Int): Int {
            val role = SlotPalette.ROLES[index]
            return if (night) resolved().dark.shade(role.role, role.darkShade) else resolved().light.shade(role.role, role.lightShade)
        }
        fun group(g: ColorGroup) = spec.effectiveGroupColor(g, resolved(), night)
        return when (token.trim().lowercase()) {
            "primary" -> slot(1)
            "accent" -> slot(2)
            "box_stroke" -> slot(4)
            "text_primary" -> group(ColorGroup.TEXT_PRIMARY)
            "text_secondary" -> group(ColorGroup.TEXT_SECONDARY)
            "notification_header" -> group(ColorGroup.ACCENT)
            "qs_tile_active" -> group(ColorGroup.QS_TILE_ACTIVE)
            "qs_tile_icon_on" -> group(ColorGroup.QS_TILE_ICON_ON)
            "white" -> WHITE
            "black" -> BLACK
            "gray", "grey" -> GRAY
            else -> if (token.startsWith("#")) Pack.parseColor(token) else null
        }
    }

    private const val OPAQUE = 255
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val GRAY = 0xFF9E9E9E.toInt()
}
