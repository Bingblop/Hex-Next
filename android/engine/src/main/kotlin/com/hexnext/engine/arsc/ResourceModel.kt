package com.hexnext.engine.arsc

/**
 * One resource package parsed from a binary `resources.arsc`.
 *
 * @property packageName package name stored in the package chunk (e.g. `android`).
 * @property packageId package id, `0x7f` for apps, `0x01` for the framework.
 * @property entries every resource that has at least one value, in id order.
 * @property overlayable `<overlayable>` declarations: which resources the package allows
 *   runtime resource overlays to replace, and under which policies.
 */
public data class ResourceTable(
    val packageName: String,
    val packageId: Int,
    val entries: List<ResourceEntry>,
    val overlayable: List<OverlayableGroup> = emptyList(),
) {
    private val byKey: Map<String, ResourceEntry> by lazy { entries.associateBy { "${it.type}/${it.name}" } }
    private val byId: Map<Int, ResourceEntry> by lazy { entries.associateBy { it.id } }

    /** Returns the entry `type/name` (e.g. `color/accent`), or null. */
    public fun find(type: String, name: String): ResourceEntry? = byKey["$type/$name"]

    /** Returns the entry for a `type/name` key, or null. */
    public fun find(key: String): ResourceEntry? = byKey[key]

    /** Returns the entry with resource id [id], or null. */
    public fun findById(id: Int): ResourceEntry? = byId[id]

    /**
     * Follows non-attribute [ResourceValue.Reference]s inside this package until a concrete value
     * is reached, preferring the value whose config equals [config] and falling back to the
     * default (`""`) config, then to the first value. Returns the last value reached when a
     * reference leaves the package, is unknown, or the chain exceeds [maxDepth].
     */
    public fun resolve(value: ResourceValue, config: String = "", maxDepth: Int = 16): ResourceValue {
        var current = value
        repeat(maxDepth) {
            val ref = current as? ResourceValue.Reference ?: return current
            if (ref.attr) return current
            val target = findById(ref.id) ?: return current
            val next = target.values.firstOrNull { it.config == config }
                ?: target.values.firstOrNull { it.config.isEmpty() }
                ?: target.values.firstOrNull()
                ?: return current
            current = next.value
        }
        return current
    }

    /** Ids of every resource covered by any overlayable policy. */
    public val overlayableIds: Set<Int> by lazy { overlayable.flatMapTo(HashSet()) { it.resourceIds } }
}

/**
 * One resource (`type/name`) and all its configuration variants.
 *
 * @property id full resource id `0xPPTTEEEE`.
 */
public data class ResourceEntry(
    val type: String,
    val name: String,
    val id: Int,
    val values: List<ConfiguredValue>,
) {
    /** `type/name` key. */
    public val key: String get() = "$type/$name"
}

/**
 * A value for one configuration.
 *
 * @property config qualifier string such as `""`, `night`, `xxhdpi`, `night-xxhdpi-v26`
 *   (see [ResConfig.qualifiers]).
 * @property resConfig the decoded configuration the [config] string was derived from.
 */
public data class ConfiguredValue(
    val config: String,
    val value: ResourceValue,
    val resConfig: ResConfig = ResConfig.DEFAULT,
)

/** A decoded `Res_value` / `ResTable_map_entry`. */
public sealed class ResourceValue {
    /** A colour literal (`TYPE_INT_COLOR_*`), always expanded to ARGB8888. */
    public data class Color(val argb: Int) : ResourceValue()

    /** A `TYPE_DIMENSION` value in its packed complex form (unit + radix + mantissa). */
    public data class Dimension(val raw: Int) : ResourceValue()

    /** A `TYPE_INT_BOOLEAN` value. */
    public data class BoolValue(val value: Boolean) : ResourceValue()

    /** A `TYPE_INT_DEC` / `TYPE_INT_HEX` value. */
    public data class IntValue(val value: Int) : ResourceValue()

    /** A string value naming a file inside the APK, e.g. `res/drawable-xxhdpi/foo.png`. */
    public data class FileRef(val path: String) : ResourceValue()

    /** A reference (`@id`) or, when [attr] is true, a theme attribute reference (`?id`). */
    public data class Reference(val id: Int, val attr: Boolean) : ResourceValue()

    /** A string literal. */
    public data class Str(val value: String) : ResourceValue()

    /** A bag (style, attr, array, plurals …): [items] maps the item name/key id to its value. */
    public data class Complex(val parentId: Int, val items: Map<Int, ResourceValue>) : ResourceValue()

    /** Any other `Res_value` (floats, fractions, null …) left undecoded. */
    public data class Other(val dataType: Int, val data: Int) : ResourceValue()

    public companion object {
        /** Placeholder of a value [ArscReader] did not decode ([ArscReadOptions.namesOnly]). */
        public val NOT_READ: ResourceValue = Other(-1, 0)
    }
}

/**
 * One `<policy>` block of an `<overlayable>` declaration.
 *
 * An `<overlayable>` with several `<policy>` elements produces one group per policy chunk,
 * all sharing [name] and [actor].
 *
 * @property policies bit set of [OverlayablePolicy] flags.
 * @property resourceIds resources overlays satisfying [policies] may replace.
 */
public data class OverlayableGroup(
    val name: String,
    val actor: String,
    val policies: Int,
    val resourceIds: List<Int>,
)

/** `ResTable_overlayable_policy_header::PolicyFlags` bits. */
public object OverlayablePolicy {
    public const val PUBLIC: Int = 0x00000001
    public const val SYSTEM_PARTITION: Int = 0x00000002
    public const val VENDOR_PARTITION: Int = 0x00000004
    public const val PRODUCT_PARTITION: Int = 0x00000008
    public const val SIGNATURE: Int = 0x00000010
    public const val ODM_PARTITION: Int = 0x00000020
    public const val OEM_PARTITION: Int = 0x00000040
    public const val ACTOR_SIGNATURE: Int = 0x00000080
    public const val CONFIG_SIGNATURE: Int = 0x00000100
}

/** Thrown when a `resources.arsc` is truncated or structurally invalid. */
public class ArscFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
