package com.hexnext.engine.pack

import com.hexnext.engine.compile.PackResolver
import java.io.File

/**
 * A [PackResolver] over the pack zips in [dir] (the app's `files/packs`).
 *
 * A pack is found by id in this order: the `file` of its entry in `index.json` (when the
 * directory holds one, see `tools/hex_pack_builder.py`), `<id>.zip`, `<id>.hexnext`, then any
 * `*.zip` / `*.hexnext` whose `pack.json` declares that id. Parsed packs and zip listings are
 * cached for the lifetime of the resolver; unreadable zips resolve to null and are listed in
 * [errors] so an apply never fails because one pack is broken.
 */
public class DirectoryPackResolver(private val dir: File) : PackResolver {
    private val cache = HashMap<String, Loaded?>()
    private var scanned: Map<String, File>? = null
    private val problems = LinkedHashMap<String, String>()

    private class Loaded(val file: File, val pack: Pack, val entries: Set<String>)

    /** Zip name → why it could not be read, for the build log. */
    public val errors: Map<String, String> get() = problems.toMap()

    override fun pack(id: String): Pack? = load(id)?.pack

    override fun entryNames(id: String): Set<String> = load(id)?.entries.orEmpty()

    /** The zip file of pack [id], or null when it is not installed. */
    public fun zipFile(id: String): File? = load(id)?.file

    /** Every pack id found in [dir] (index entries and zips), sorted. */
    public fun availableIds(): List<String> = (indexFiles().keys + scan().keys).distinct().sorted()

    private fun load(id: String): Loaded? = cache.getOrPut(id) {
        val candidates = listOfNotNull(
            indexFiles()[id],
            File(dir, "$id.zip"),
            File(dir, "$id.$EXTENSION"),
            scan()[id],
        ).distinct().filter { it.isFile }
        candidates.firstNotNullOfOrNull { file -> read(file)?.takeIf { it.pack.id == id } }
    }

    private fun read(file: File): Loaded? = try {
        Loaded(file, PackReader.read(file), PackReader.entryNames(file))
    } catch (e: PackFormatException) {
        problems[file.name] = e.message.orEmpty()
        null
    }

    private fun indexFiles(): Map<String, File> {
        val index = File(dir, INDEX)
        if (!index.isFile) return emptyMap()
        return try {
            PackIndex.parse(index.readText()).packs
                .filter { isPlainName(it.file) }
                .associate { it.id to File(dir, it.file) }
        } catch (e: PackFormatException) {
            problems[INDEX] = e.message.orEmpty()
            emptyMap()
        }
    }

    /** Pack id → zip for every readable pack zip in [dir]. */
    private fun scan(): Map<String, File> = scanned ?: run {
        val zips = dir.listFiles { f -> f.isFile && (f.name.endsWith(".zip") || f.name.endsWith(".$EXTENSION")) }
            .orEmpty().sortedBy { it.name }
        val byId = LinkedHashMap<String, File>()
        for (zip in zips) {
            val id = try {
                PackReader.read(zip).id
            } catch (e: PackFormatException) {
                problems[zip.name] = e.message.orEmpty()
                null
            }
            if (id != null) byId.putIfAbsent(id, zip)
        }
        byId.also { scanned = it }
    }

    private fun isPlainName(name: String) = name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != ".."

    public companion object {
        /** Optional extension of pack zips downloaded by the app. */
        public const val EXTENSION: String = "hexnext"

        /** Pack listing written by `tools/hex_pack_builder.py`. */
        public const val INDEX: String = "index.json"
    }
}
