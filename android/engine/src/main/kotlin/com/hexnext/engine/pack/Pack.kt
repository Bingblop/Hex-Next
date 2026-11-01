package com.hexnext.engine.pack

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.IOException
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
    val sourcePackage: String = "",
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
)

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

    /** Returns the bytes of the zip entry [path] (e.g. `layers/once/base/xx1/qs/foo.png`). */
    public fun openLayer(zip: File, path: String): ByteArray = withZip(zip) { z ->
        val entry = z.getEntry(path) ?: throw PackFormatException("$path not found in ${zip.name}")
        z.getInputStream(entry).use { it.readBytes() }
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
     * (`night == false`) the `"l" + slot` variant is preferred when present. `fixed` uses the
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
                    ?: null.also { unresolved += slot }
            }
            else -> null
        }
}
