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
 */
public data class LiveOption(
    val option: PackOption,
    val elementIds: Set<String>,
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
    public fun live(pack: Pack): List<LiveOption> {
        val gated = LinkedHashMap<String, MutableSet<String>>() // option token → element ids
        for (category in pack.layers.map { it.category }.distinct()) {
            val parsed = CategoryName.parse(category)
            if (parsed.optionTokens.isEmpty()) continue
            val elementId = Elements.elementForCategory(category).id
            for (token in parsed.optionTokens) gated.getOrPut(token) { LinkedHashSet() } += elementId
        }
        val seen = HashSet<Pair<String, List<String>>>()
        val result = ArrayList<LiveOption>()
        for (option in pack.options) {
            val keys = keysOf(option)
            if (keys.isEmpty() || !seen.add(option.type to keys)) continue
            val elementIds = LinkedHashSet<String>()
            for (key in keys) gated[key]?.let(elementIds::addAll)
            if (elementIds.isEmpty()) continue
            val label = option.label.trim().ifEmpty { keys.first() }
            result += LiveOption(option.copy(label = label), elementIds)
        }
        return result
    }

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
        val element = elementId ?: live(pack).firstOrNull { it.option.type == option.type && keysOf(it.option) == keys }
            ?.elementIds?.firstOrNull()
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
        option.keys.associateWith { if (it == value) "true" else "false" }
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
