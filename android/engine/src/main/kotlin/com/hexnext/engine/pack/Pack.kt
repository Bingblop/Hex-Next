package com.hexnext.engine.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * `pack.json` of a Hex Next theme pack, as written by `tools/hex_pack_builder.py`.
 *
 * @property palette slot → colour string (`#AARRGGBB` or `#RRGGBB`); light variants of a slot
 *   are stored under `"l" + slot`. Use [paletteArgb] for parsed values.
 * @property settings remaining plugin defaults (`default.hex`), values kept as raw JSON.
 * @property categories category id → display label.
 */
@Serializable
public data class Pack(
    val format: Int = 1,
    val id: String,
    val name: String,
    val author: String = "",
    val version: String = "",
    /** The pack's own package name, `com.hexnext.pack.<id>`; packs never carry their source plugin's. */
    @SerialName("package") val packageName: String = "",
    val dayNight: Boolean = false,
    val features: List<String> = emptyList(),
    val palette: Map<String, String> = emptyMap(),
    val settings: Map<String, JsonElement> = emptyMap(),
    val options: List<PackOption> = emptyList(),
    val categoryOptions: Map<String, List<String>> = emptyMap(),
    val categories: Map<String, String> = emptyMap(),
    val layers: List<PackLayer> = emptyList(),
    val previewCount: Int = 0,
    val drawableCount: Int = 0,
) {
    /** [palette] parsed to ARGB ints; entries that are not valid colours are dropped. */
    public val paletteArgb: Map<String, Int> by lazy {
        palette.mapNotNull { (k, v) -> parseColor(v)?.let { k to it } }.toMap()
    }

    /**
     * The option tokens the legacy Hex plugin had on before the user touched anything, read from
     * the plugin defaults in [settings] (keys `<plugin>:<option key>`, value `"true"`): a `switch`
     * is on when its key is `"true"` there; a `select` uses the first of its keys that is `"true"`
     * there, else its first key. When an option key appears under several plugin prefixes, the
     * prefix that names the most option keys of this pack wins. The compiler overlays
     * [com.hexnext.engine.compile.ThemeSpec.elementOptions] on these, so a pack whose only
     * Settings icons live under `settingsicon-1sett` still themes the dashboard by default.
     */
    public val defaultOptions: Map<String, String> by lazy {
        val optionKeys = options.flatMapTo(HashSet()) { if (it.type == PackOption.SELECT) it.keys else listOfNotNull(it.key) }
        val byPrefix = LinkedHashMap<String, MutableMap<String, String>>()
        for ((name, value) in settings) {
            val sep = name.indexOf(':')
            if (sep <= 0) continue
            val key = name.substring(sep + 1)
            if (key !in optionKeys) continue
            val text = (value as? JsonPrimitive)?.content ?: continue
            byPrefix.getOrPut(name.substring(0, sep)) { LinkedHashMap() }[key] = text
        }
        val ranked: List<Map<String, String>> = byPrefix.entries
            .sortedWith(compareByDescending<Map.Entry<String, Map<String, String>>> { it.value.size }.thenBy { it.key })
            .map { it.value }
        val result = LinkedHashMap<String, String>()
        for (option in options) {
            when (option.type) {
                PackOption.SELECT -> {
                    val chosen = ranked.firstNotNullOfOrNull { values -> option.keys.firstOrNull { values[it] == "true" } }
                    (chosen ?: option.keys.firstOrNull())?.let { result[it] = "true" }
                }
                PackOption.SWITCH -> {
                    val key = option.key
                    if (key != null && ranked.firstNotNullOfOrNull { it[key] } == "true") result[key] = "true"
                }
            }
        }
        result
    }

    public companion object {
        /** Parses `#AARRGGBB`, `#RRGGBB`, `#ARGB` or `#RGB` (leading `#` optional); null when invalid. */
        public fun parseColor(text: String): Int? {
            val hex = text.trim().removePrefix("#")
            if (hex.isEmpty() || hex.any { Character.digit(it, 16) < 0 }) return null
            val full = when (hex.length) {
                8 -> hex
                6 -> "FF$hex"
                4 -> hex.map { "$it$it" }.joinToString("")
                3 -> "FF" + hex.map { "$it$it" }.joinToString("")
                else -> return null
            }
            return full.toLong(16).toInt()
        }
    }
}

/** An entry of `pack.json` `options`: a `select` over [keys]/[choices] or a `switch` on [key]. */
@Serializable
public data class PackOption(
    val type: String,
    val label: String = "",
    val key: String? = null,
    val keys: List<String> = emptyList(),
    val choices: List<String> = emptyList(),
) {
    public companion object {
        /** [type] of a one-of option over [keys]. */
        public const val SELECT: String = "select"

        /** [type] of an on/off option on [key]. */
        public const val SWITCH: String = "switch"
    }
}

/**
 * A group of greyscale PNG layers sharing pass, stage, tint and category.
 *
 * @property pass `once` or `twice`.
 * @property stage `base`, `first` or `second`.
 * @property path zip directory holding `<resource>.png` / `<resource>.9.png`.
 * @property resources resource names (without `.png` / `.9.png`) present in [path].
 */
@Serializable
public data class PackLayer(
    val pass: String,
    val stage: String,
    val category: String,
    val tint: Tint,
    val path: String,
    val resources: List<String> = emptyList(),
)

/** How a layer is coloured: `slot` (palette lookup), `fixed` ([argb] `#AARRGGBB`) or `none`. */
@Serializable
public data class Tint(
    val kind: String,
    val slot: String? = null,
    val argb: String? = null,
) {
    public companion object {
        public const val SLOT: String = "slot"
        public const val FIXED: String = "fixed"
        public const val NONE: String = "none"
    }
}

/** `index.json` listing the packs available for download. */
@Serializable
public data class PackIndex(
    val format: Int = 1,
    val packs: List<PackIndexEntry> = emptyList(),
) {
    public companion object {
        /** Parses `index.json` text. */
        public fun parse(json: String): PackIndex = try {
            PackJson.decodeFromString(serializer(), json)
        } catch (e: SerializationException) {
            throw PackFormatException("invalid index.json: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw PackFormatException("invalid index.json: ${e.message}", e)
        }
    }
}

/** One pack in [PackIndex]. [file] is the zip name relative to the index; [bytes] its size. */
@Serializable
public data class PackIndexEntry(
    val id: String,
    val name: String,
    val author: String = "",
    val version: String = "",
    val file: String,
    val bytes: Long = 0,
    val categories: List<String> = emptyList(),
    val dayNight: Boolean = false,
    val previewCount: Int = 0,
)

/** Thrown when a pack zip or its JSON is missing or invalid. */
public class PackFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Lenient JSON used for pack metadata: unknown keys from newer builders are ignored. */
internal val PackJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Reads pack zips. */
public object PackReader {
    /** Name of the metadata entry inside a pack zip. */
    public const val PACK_JSON: String = "pack.json"

    /** Parses `pack.json` text. */
    public fun parse(json: String): Pack = try {
        PackJson.decodeFromString(Pack.serializer(), json)
    } catch (e: SerializationException) {
        throw PackFormatException("invalid pack.json: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw PackFormatException("invalid pack.json: ${e.message}", e)
    }

    /** Reads `pack.json` from the pack [zipFile]. */
    public fun read(zipFile: File): Pack = String(openLayer(zipFile, PACK_JSON), Charsets.UTF_8).let(::parse)

    /**
     * Largest inflated size accepted for one zip entry. Real layers and `pack.json` are a few MB
     * at most; the cap stops a hostile zip from inflating past the heap.
     */
    public const val MAX_ENTRY_BYTES: Int = 32 * 1024 * 1024

    /**
     * Returns the bytes of the zip entry [path] (e.g. `layers/once/base/xx1/qs/foo.png`).
     *
     * @throws PackFormatException when the entry is missing or inflates past [MAX_ENTRY_BYTES].
     */
    public fun openLayer(zip: File, path: String): ByteArray = withZip(zip) { z ->
        val entry = z.getEntry(path) ?: throw PackFormatException("$path not found in ${zip.name}")
        if (entry.size > MAX_ENTRY_BYTES) throw PackFormatException("$path in ${zip.name} is too large (${entry.size} bytes)")
        z.getInputStream(entry).use { input -> readBounded(input, MAX_ENTRY_BYTES) }
            ?: throw PackFormatException("$path in ${zip.name} inflates past $MAX_ENTRY_BYTES bytes")
    }

    /** Reads [input] to the end, or returns null as soon as it yields more than [limit] bytes. */
    internal fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Returns the names of all file entries in [zip]; pass to [LayerPlanner.plan] as `entryNames`. */
    public fun entryNames(zip: File): Set<String> = withZip(zip) { z ->
        z.entries().asSequence().filterNot { it.isDirectory }.mapTo(LinkedHashSet()) { it.name }
    }

    private inline fun <T> withZip(zip: File, block: (ZipFile) -> T): T = try {
        ZipFile(zip).use(block)
    } catch (e: ZipException) {
        throw PackFormatException("${zip.name} is not a valid zip: ${e.message}", e)
    } catch (e: IOException) {
        throw PackFormatException("cannot read ${zip.name}: ${e.message}", e)
    }
}

/** One layer to draw: [layerPath] is a zip entry; [tintArgb] null means draw untinted. */
public data class CompositeStep(
    val layerPath: String,
    val tintArgb: Int?,
) {
    /** True for compiled/9-patch sources (`.9.png`). */
    public val isNinePatch: Boolean get() = layerPath.endsWith(".9.png")
}

/**
 * Ordered layers for one resource, bottom first.
 *
 * @property unresolvedSlots palette slots a `slot` tint referenced that neither the given
 *   palette nor the pack palette define; those steps have a null tint.
 */
public data class CompositePlan(
    val steps: List<CompositeStep>,
    val unresolvedSlots: List<String> = emptyList(),
) {
    /** True when there is nothing to draw. */
    public val isEmpty: Boolean get() = steps.isEmpty()
}

/** Orders and tints a pack's layers for one resource. */
public object LayerPlanner {
    private val PASS_ORDER = listOf("once", "twice")
    private val STAGE_ORDER = listOf("base", "first", "second")

    /**
     * Plans the composite of [resourceName] in [category].
     *
     * Order: pass `once` (base → first → second), then pass `twice` (base → first → second);
     * unknown passes/stages sort after the known ones, alphabetically.
     *
     * Tint: `slot` looks the slot up in [palette], then in the pack palette; in light mode
     * (`night == false`) the `"l" + slot` variant is preferred when present. A slot found in
     * neither that reads as a colour (`H000000`, `#ffffff`, `FF33BEA2`, `C80000`) is drawn in
     * that fixed colour instead of untinted. `fixed` uses the
     * layer's ARGB; `none` draws untinted.
     *
     * Steps whose layer file is missing are skipped: with [entryNames] (see
     * [PackReader.entryNames]) the zip listing decides and `.9.png` is preferred over `.png`;
     * without it the layer's `resources` list decides and `.png` is assumed.
     */
    public fun plan(
        pack: Pack,
        category: String,
        resourceName: String,
        palette: Map<String, Int>,
        night: Boolean,
        entryNames: Set<String>? = null,
    ): CompositePlan {
        val unresolved = LinkedHashSet<String>()
        val steps = pack.layers
            .filter { it.category == category }
            .sortedWith(compareBy<PackLayer>({ rank(PASS_ORDER, it.pass) }, { it.pass }, { rank(STAGE_ORDER, it.stage) }, { it.stage }))
            .mapNotNull { layer ->
                val path = layerFile(layer, resourceName, entryNames) ?: return@mapNotNull null
                CompositeStep(path, tintFor(layer.tint, pack, palette, night, unresolved))
            }
        return CompositePlan(steps, unresolved.toList())
    }

    private fun rank(order: List<String>, value: String): Int = order.indexOf(value).let { if (it < 0) order.size else it }

    private fun layerFile(layer: PackLayer, resource: String, entryNames: Set<String>?): String? {
        val base = "${layer.path.trimEnd('/')}/$resource"
        if (entryNames == null) return if (resource in layer.resources) "$base.png" else null
        return listOf("$base.9.png", "$base.png").firstOrNull { it in entryNames }
    }

    private fun tintFor(tint: Tint, pack: Pack, palette: Map<String, Int>, night: Boolean, unresolved: MutableSet<String>): Int? =
        when (tint.kind) {
            Tint.FIXED -> tint.argb?.let(Pack::parseColor)
            Tint.SLOT -> {
                val slot = tint.slot.orEmpty()
                val candidates = if (night) listOf(slot) else listOf("l$slot", slot)
                candidates.firstNotNullOfOrNull { palette[it] ?: pack.paletteArgb[it] }
                    ?: fixedSlotColor(slot)
                    ?: null.also { unresolved += slot }
            }
            else -> null
        }

    private val FIXED_SLOT_RE = Regex("^[Hh#]?([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    /**
     * A `slot` that is really a fixed colour, as the pack builder writes them (`H000000`,
     * `Hffffff`, `#ffffff`, `FF33BEA2`, `C80000`): `H`/`#` plus 6 or 8 hex digits, or bare
     * 6/8 hex digits. Six digits get alpha FF. Null when the slot is not of that form.
     */
    internal fun fixedSlotColor(slot: String): Int? =
        FIXED_SLOT_RE.matchEntire(slot.trim())?.groupValues?.get(1)?.let(Pack::parseColor)
}
