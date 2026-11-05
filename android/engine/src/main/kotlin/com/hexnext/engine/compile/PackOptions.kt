package com.hexnext.engine.compile

import com.hexnext.engine.pack.Pack
import com.hexnext.engine.pack.PackOption

/**
 * A [PackOption] of a pack that actually changes something: at least one layer category of the
 * pack carries one of its keys as an option token (see [CategoryName.optionTokens]).
 *
 * @property option the option as declared in `pack.json`, with its [PackOption.label] trimmed
 *   and falling back to the key when blank.
 * @property elementIds ids of the [Element]s that consume the categories gated by this option.
 * @property extra the [NativeExtra.id] of an option Hex Next rebuilds natively ([NativeExtras]),
 *   null for a layer option.
 */
public data class LiveOption(
    val option: PackOption,
    val elementIds: Set<String>,
    val extra: String? = null,
    /**
     * Every key the pack declares for this option. A select offers only its choices with art
     * ([option] keeps those), but reads and writes state over all of them, so a choice hidden now
     * (no art, or no target until the next scan) never stays switched on behind the offered ones.
     */
    val declaredKeys: List<String> = if (option.type == PackOption.SELECT) option.keys else listOfNotNull(option.key),
) {
    /** The keys this option toggles: [PackOption.keys] for a select, the single [PackOption.key] for a switch. */
    public val keys: List<String>
        get() = if (option.type == PackOption.SELECT) option.keys else listOfNotNull(option.key)
}

/**
 * Pack option helpers for the UI: which options of a pack are live, what the effective state of
 * an option is for a [ThemeSpec], and how to write a choice back into the spec.
 *
 * The compiler (see `ThemeCompiler.elementJobs`) uses a category only when every one of its
 * option tokens has the value `"true"` in `pack.defaultOptions + spec.elementOptions[elementId]`.
 * An option whose keys never appear as option tokens therefore has no effect and must not be shown.
 */
public object PackOptions {
    /**
     * The live options of [pack], in pack order. Options whose keys gate no layer category are
     * dropped; duplicate declarations (same type and keys) are kept once; blank labels fall back
     * to the first key.
     */
    public fun live(pack: Pack, reach: OptionReach? = null): List<LiveOption> {
        val gated = LinkedHashMap<String, MutableSet<String>>() // option token → element ids
        for (category in pack.layers.map { it.category }.distinct()) {
            val parsed = CategoryName.parse(category)
            if (parsed.optionTokens.isEmpty()) continue
            // With a scan, artwork that reaches nothing on this phone (One UI 3 toggles) gates nothing.
            if (reach != null && !reach.reaches(pack, category)) continue
            val elementId = Elements.elementForCategory(category).id
            for (token in parsed.optionTokens) gated.getOrPut(token) { LinkedHashSet() } += elementId
        }
        val seen = HashSet<Pair<String, List<String>>>()
        val result = ArrayList<LiveOption>()
        for (declared in pack.options) {
            val keys = keysOf(declared)
            if (keys.isEmpty() || !seen.add(declared.type to keys)) continue
            // A select keeps only the choices with artwork (Carnelian's "OG Type" has none); with
            // fewer than two left there is nothing to choose.
            val option = if (declared.type == PackOption.SELECT) {
                val kept = keys.indices.filter { keys[it] in gated }
                if (kept.size < 2) continue
                declared.copy(
                    keys = kept.map { keys[it] },
                    choices = if (declared.choices.size == keys.size) kept.map { declared.choices[it].trim() } else declared.choices,
                )
            } else {
                declared
            }
            val elementIds = LinkedHashSet<String>()
            for (key in keysOf(option)) gated[key]?.let(elementIds::addAll)
            if (elementIds.isEmpty()) continue
            val label = option.label.trim().ifEmpty { keys.first() }
            result += LiveOption(option.copy(label = label), elementIds, declaredKeys = keys)
        }
        return result
    }

    /**
     * Every option of [pack] the pack card shows: the [live] layer options, then the options
     * Hex Next rebuilds natively ([NativeExtras.options]).
     */
    public fun all(pack: Pack, reach: OptionReach? = null): List<LiveOption> =
        live(pack, reach) + NativeExtras.options(pack).map { it.live }

    /** The state of [live] in [spec]: [NativeExtras.value] for a native option, else [value]. */
    public fun value(spec: ThemeSpec, pack: Pack, live: LiveOption, elementId: String? = null): String =
        if (live.extra != null) NativeExtras.value(spec, pack, live, elementId) else value(spec, pack, live.option, elementId)

    /**
     * The effective state of [option] of [pack] in [spec]: `"true"` / `"false"` for a switch, the
     * key that is on for a select (the first key in [PackOption.keys] whose effective value is
     * `"true"`, else the first key).
     *
     * The effective values are `pack.defaultOptions + spec.elementOptions[elementId]`. With a null
     * [elementId] the first element the option gates is used; simple mode writes every gated
     * element identically, so any one is representative.
     */
    public fun value(spec: ThemeSpec, pack: Pack, option: PackOption, elementId: String? = null): String {
        val keys = keysOf(option)
        val element = elementId ?: live(pack).firstOrNull {
            it.option.type == option.type && (keysOf(it.option) == keys || it.declaredKeys == keys)
        }?.elementIds?.firstOrNull()
        val effective = pack.defaultOptions + spec.elementOptions[element].orEmpty()
        return if (option.type == PackOption.SELECT) {
            keys.firstOrNull { effective[it] == "true" } ?: keys.firstOrNull().orEmpty()
        } else {
            val key = option.key ?: return "false"
            if (effective[key] == "true") "true" else "false"
        }
    }

    /**
     * The elements, in [Elements.ALL] order, that at least one layer category of [pack] resolves to.
     */
    public fun coveredElements(pack: Pack): List<Element> {
        val ids = pack.layers.mapTo(HashSet()) { Elements.elementForCategory(it.category).id }
        return Elements.ALL.filter { it.id in ids }
    }

    internal fun keysOf(option: PackOption): List<String> =
        if (option.type == PackOption.SELECT) option.keys else listOfNotNull(option.key)
}

/**
 * This spec with [live] of [pack] set to [value] for every element in [elementIds].
 *
 * A switch writes its key as `"true"` / `"false"`; a select writes the chosen key as `"true"` and
 * every other key of the select as `"false"`, explicitly, so the pack default no longer wins.
 * Other option entries of the elements are kept. Pure: returns a copy.
 */
public fun ThemeSpec.withOption(
    pack: Pack,
    live: LiveOption,
    value: String,
    elementIds: Set<String> = live.elementIds,
): ThemeSpec {
    val option = live.option
    val writes: Map<String, String> = if (option.type == PackOption.SELECT) {
        (live.declaredKeys + option.keys).distinct().associateWith { if (it == value) "true" else "false" }
    } else {
        val key = option.key ?: return this
        mapOf(key to if (value == "true") "true" else "false")
    }
    if (writes.isEmpty()) return this
    val options = elementOptions.toMutableMap()
    for (id in elementIds) options[id] = elementOptions[id].orEmpty() + writes
    return copy(elementOptions = options)
}

/**
 * This spec with [elementId] using [packId] in [ThemeMode.ADVANCED]; null removes the entry so
 * the element falls back to [ThemeSpec.simplePackId]. Pure.
 */
public fun ThemeSpec.withElementPack(elementId: String, packId: String?): ThemeSpec =
    copy(elementPacks = if (packId == null) elementPacks - elementId else elementPacks + (elementId to packId))

/** This spec with [elementId] switched on or off ([enabled]); a missing entry means enabled. Pure. */
public fun ThemeSpec.withElementEnabled(elementId: String, enabled: Boolean): ThemeSpec =
    copy(elementEnabled = if (enabled) elementEnabled - elementId else elementEnabled + (elementId to false))

/**
 * Which layer categories of a pack reach a resource on this phone: at least one of the category's
 * resources, after [OneUiRules.renameFor] and [OneUiRules.drawableCopies], is a drawable or mipmap
 * of an installed target. The option lists use it to hide options whose artwork has no target on
 * the running One UI version (One UI 3 media buttons, One UI 4-6 brightness bars). Keeps only the
 * drawable and mipmap names, not the resource tables.
 */
public class OptionReach(private val names: Set<String>, private val rules: OneUiRules) {
    private val cache = HashMap<Pair<String, String>, Boolean>()

    /** True when [category] of [pack] has a resource that maps to a drawable or mipmap on this phone. */
    public fun reaches(pack: Pack, category: String): Boolean = synchronized(cache) {
        cache.getOrPut(pack.id to category) {
            val type = if (CategoryName.parse(category).base == "mipmap") "mipmap" else "drawable"
            pack.layers.asSequence().filter { it.category == category }.flatMap { it.resources }.distinct().any { res ->
                rules.renameFor("$type/$res") { it in names } in names ||
                    rules.drawableCopies["$type/$res"].orEmpty().any { it in names }
            }
        }
    }

    public companion object {
        /** The reach over every installed target of [device]. */
        public fun of(device: DeviceSnapshot, rules: OneUiRules): OptionReach {
            val names = HashSet<String>()
            for (t in device.targets.values) {
                if (!t.installed) continue
                for (e in t.table?.entries.orEmpty()) if (e.type == "drawable" || e.type == "mipmap") names += "${e.type}/${e.name}"
            }
            return OptionReach(names, rules)
        }
    }
}
