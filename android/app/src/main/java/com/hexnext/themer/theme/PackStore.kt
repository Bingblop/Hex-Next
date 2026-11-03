package com.hexnext.themer.theme

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hexnext.engine.pack.DirectoryPackResolver
import com.hexnext.engine.pack.Pack
import com.hexnext.engine.pack.PackFormatException
import com.hexnext.engine.pack.PackIdAliases
import com.hexnext.engine.pack.PackIndex
import com.hexnext.engine.pack.PackIndexEntry
import com.hexnext.engine.pack.PackReader
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A pack zip in `files/packs` whose `pack.json` was read. */
data class InstalledPack(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val file: File,
    val bytes: Long,
    val dayNight: Boolean,
    val drawableCount: Int,
    /** True when the pack has a Settings dashboard icon layer (`settingsicon*` category). */
    val hasSettingsIcons: Boolean,
    val features: List<String>,
    /** The parsed `pack.json`, for option and element coverage lookups. */
    val pack: Pack,
)

/** [InstalledPack]s plus the zips that could not be read (file name → message). */
data class InstalledPacks(
    val packs: List<InstalledPack>,
    val errors: Map<String, String>,
)

/** One pack of a remote `index.json`. */
data class CatalogueEntry(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val bytes: Long,
    val file: String,
    val hasSettingsIcons: Boolean,
    val dayNight: Boolean,
    /** True when a zip with this id and this (or a newer) version is already on the device. */
    val installed: Boolean,
)

/**
 * The pack zips on the device ([ThemeApplier.packsDir]): listing, import from a `content://`
 * document, deletion, and download from a catalogue (`index.json` + zips under one base URL).
 *
 * Every function blocks on file or network I/O; call them on `Dispatchers.IO`. Each stored pack is
 * named `<id>.zip` so that [DirectoryPackResolver] finds it directly.
 *
 * Catalogue packs were renamed once ([PackIdAliases]). A zip that still carries a former id is
 * rewritten in place under its current id the first time it is listed, imported or downloaded
 * ([migrateFormer]); while that fails it is listed under its current id and name, never its old
 * one. Ids passed in ([delete], the catalogue's installed check) may be former ids.
 */
class PackStore(context: Context) {

    private val appContext = context.applicationContext

    private val dir: File get() = ThemeApplier.packsDir(appContext)

    /**
     * `pack.json` of each zip by path, size and mtime: [installed] opens only the zips that
     * changed since the last listing (the catalogue, every import and every delete relist).
     * Guarded by itself; a [PackStore] may be used from more than one IO coroutine.
     */
    private val parsed = HashMap<String, Pack>()

    /**
     * Copies the packs bundled under `assets/packs/` (debug builds only: the CI copies a few test
     * packs there) into the pack directory, skipping ids that are already installed. Returns the
     * ids it added and, per asset that could not be installed, why. A release build has no such
     * assets and seeding is a no-op. Runs once at start-up, so it first deletes stale `.part`
     * files that a download or install killed with the process left behind ([sweepStaleParts]).
     */
    fun seedBundled(): Seeded {
        sweepStaleParts()
        val names = runCatching { appContext.assets.list(ASSET_DIR)?.toList() }.getOrNull().orEmpty()
            .filter { isPackFile(it) }
        if (names.isEmpty()) return Seeded(emptyList(), emptyMap())
        val added = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        for (name in names) {
            val id = name.substringBeforeLast('.')
            if (!runCatching { requireSafeId(id) }.isSuccess || zipFor(id).isFile) continue
            try {
                val staged = partFor(id)
                appContext.assets.open("$ASSET_DIR/$name").use { input -> staged.outputStream().use { input.copyTo(it) } }
                val pack = PackReader.read(staged)
                if (pack.id != id) {
                    staged.delete()
                    failed[name] = "pack id is \"${pack.id}\", expected \"$id\""
                    continue
                }
                install(staged, id)
                added += id
            } catch (e: Exception) {
                partFor(id).delete()
                failed[name] = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return Seeded(added, failed)
    }

    /**
     * Ids of the packs bundled under `assets/packs/` (debug builds only; empty in a release
     * build). Debuggable builds treat them as unlocked so a test phone can apply them.
     */
    fun bundledIds(): Set<String> =
        runCatching { appContext.assets.list(ASSET_DIR)?.toList() }.getOrNull().orEmpty()
            .filter { isPackFile(it) }
            .mapTo(LinkedHashSet()) { it.substringBeforeLast('.') }

    /** Result of [seedBundled]: installed ids plus asset name → reason for the ones that failed. */
    data class Seeded(val added: List<String>, val failed: Map<String, String>)

    /**
     * Every readable pack zip, sorted by name; unreadable zips are reported in
     * [InstalledPacks.errors]. Zips under a former catalogue id are migrated first; one pack id is
     * listed once, preferring a zip that already carries the current id.
     */
    fun installed(): InstalledPacks {
        migrateFormerIds()
        val zips = dir.listFiles { f -> f.isFile && isPackFile(f.name) }.orEmpty()
        val packs = LinkedHashMap<String, InstalledPack>()
        val errors = LinkedHashMap<String, String>()
        val live = HashSet<String>()
        for (zip in zips.sortedBy { it.name }) {
            live += stampOf(zip)
            try {
                val installed = readCached(zip).toInstalled(zip)
                val kept = packs[installed.id]
                if (kept == null || (PackIdAliases.isFormer(kept.pack.id) && !PackIdAliases.isFormer(installed.pack.id))) {
                    packs[installed.id] = installed
                }
            } catch (e: PackFormatException) {
                errors[zip.name] = e.message.orEmpty()
            }
        }
        synchronized(parsed) { parsed.keys.retainAll(live) }
        val sorted = packs.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return InstalledPacks(sorted, errors)
    }

    private fun stampOf(zip: File) = "${zip.absolutePath}|${zip.length()}|${zip.lastModified()}"

    /** `pack.json` of [zip], parsed once per path, size and mtime. */
    private fun readCached(zip: File): Pack {
        val stamp = stampOf(zip)
        return synchronized(parsed) { parsed[stamp] }
            ?: PackReader.read(zip).also { synchronized(parsed) { parsed[stamp] = it } }
    }

    /** [migrateFormer] for every readable zip in the pack directory that carries a former id. */
    private fun migrateFormerIds() {
        val zips = dir.listFiles { f -> f.isFile && isPackFile(f.name) }.orEmpty()
        for (zip in zips.sortedBy { it.name }) {
            val pack = try {
                readCached(zip)
            } catch (e: PackFormatException) {
                continue
            }
            if (PackIdAliases.isFormer(pack.id)) migrateFormer(zip, pack)
        }
    }

    /**
     * Rewrites [zip], whose `pack.json` carries the former catalogue id [pack].id, the way
     * `tools/rename_packs.py` does: current id, its name, package and catalogue version, a blank
     * author and former pack names in option and category text replaced by current ones. Every
     * other entry is copied unchanged. The result is staged in `<id>.zip.part`, checked, and
     * renamed to `<id>.zip`; then [zip] is removed. When `<id>.zip` already holds the pack,
     * [zip] is only removed. Returns the current zip, or null (logged, [zip] untouched) on failure.
     */
    private fun migrateFormer(zip: File, pack: Pack): File? {
        val newId = PackIdAliases.current(pack.id)
        if (newId == pack.id) return null
        val target = zipFor(newId)
        if (target.absoluteFile != zip.absoluteFile && target.isFile &&
            runCatching { readCached(target).id == newId }.getOrDefault(false)
        ) {
            if (!zip.delete()) Log.w(TAG, "Could not remove ${zip.name}; $newId.zip already holds the pack")
            return target
        }
        val staged = partFor(newId)
        return try {
            ZipFile(zip).use { source ->
                val packJson = source.getEntry(PACK_JSON) ?: throw IOException("pack.json missing")
                val json = String(source.getInputStream(packJson).use { it.readBytes() }, Charsets.UTF_8)
                ZipOutputStream(staged.outputStream().buffered()).use { out ->
                    val seen = HashSet<String>()
                    for (entry in source.entries()) {
                        if (entry.name == PACK_JSON || !seen.add(entry.name)) continue
                        out.putNextEntry(copyOf(entry))
                        if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    out.putNextEntry(ZipEntry(PACK_JSON))
                    out.write(migratedPackJson(json, newId).toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                }
            }
            val check = PackReader.read(staged)
            if (check.id != newId) throw IOException("rewritten pack.json declares \"${check.id}\"")
            File(dir, "$newId.${DirectoryPackResolver.EXTENSION}").delete()
            if (!staged.renameTo(target)) throw IOException("Could not install $newId.zip")
            if (zip.absoluteFile != target.absoluteFile && !zip.delete()) {
                Log.w(TAG, "Could not remove ${zip.name} after migrating it to $newId.zip")
            }
            target
        } catch (e: Exception) {
            staged.delete()
            Log.w(TAG, "Pack ${zip.name} could not be moved to its current id $newId", e)
            null
        }
    }

    /** [install] and, for a pack that carries a former catalogue id, [migrateFormer]. */
    private fun installCurrent(source: File, pack: Pack): InstalledPack {
        val target = install(source, pack.id)
        if (!PackIdAliases.isFormer(pack.id)) return pack.toInstalled(target)
        val migrated = migrateFormer(target, pack) ?: return pack.toInstalled(target)
        return readCached(migrated).toInstalled(migrated)
    }

    /**
     * Copies the document at [uri] into the pack directory as `<id>.zip` (replacing a pack with
     * the same id). Throws [IllegalArgumentException] when the file is not a valid pack.
     */
    fun import(uri: Uri): InstalledPack {
        val temp = File.createTempFile("import-", ".zip", appContext.cacheDir)
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("The selected file could not be opened.")
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
            val pack = try {
                PackReader.read(temp)
            } catch (e: PackFormatException) {
                throw IllegalArgumentException("Not a Hex Next pack: ${e.message}", e)
            }
            requireSafeId(pack.id)
            return installCurrent(temp, pack)
        } catch (e: IOException) {
            throw IllegalArgumentException("Reading the selected file failed: ${e.message}", e)
        } finally {
            temp.delete()
        }
    }

    /** Deletes the zip(s) of pack [id], also under its current and former ids; true when a file was removed. */
    fun delete(id: String): Boolean {
        requireSafeId(id)
        var removed = false
        for (known in PackIdAliases.allIdsOf(id) + id) {
            for (file in listOf(File(dir, "$known.zip"), File(dir, "$known.${DirectoryPackResolver.EXTENSION}"))) {
                if (file.isFile && file.delete()) removed = true
            }
        }
        // A renamed catalogue pack: also a zip whose file name is neither id but whose pack.json
        // declares one of them (a migration that could not finish).
        val ids = PackIdAliases.allIdsOf(id).toSet()
        if (ids.size > 1) {
            dir.listFiles { f -> f.isFile && isPackFile(f.name) }.orEmpty().forEach { zip ->
                val declared = runCatching { readCached(zip).id }.getOrNull()
                if (declared != null && declared in ids && zip.delete()) removed = true
            }
        }
        return removed
    }

    /** Fetches and parses `index.json` under [baseUrl]; [CatalogueEntry.installed] reflects [installed]. */
    fun fetchCatalogue(baseUrl: String): List<CatalogueEntry> {
        val base = normaliseBaseUrl(baseUrl)
        val buffer = ByteArrayOutputStream()
        // index.json is a few KB: cap its size and total time so a wrong or hostile URL cannot
        // grow the in-memory buffer until OutOfMemoryError or keep the dialog loading forever.
        fetchTo(base + DirectoryPackResolver.INDEX, buffer, -1L, maxBytes = MAX_INDEX_BYTES, maxMillis = INDEX_DEADLINE_MS) { _, _ -> }
        val text = String(buffer.toByteArray(), Charsets.UTF_8)
        val index = try {
            PackIndex.parse(text)
        } catch (e: PackFormatException) {
            throw IOException("The catalogue index is invalid: ${e.message}", e)
        }
        // An entry counts as installed only when the catalogue does not list a newer version than
        // the zip on the device; otherwise the row offers the download, which replaces <id>.zip.
        val installedVersions = installed().packs.associate { it.id to it.version }
        return index.packs.map { it.toEntry() }.distinctBy { it.id }.map { entry ->
            entry.copy(installed = isCatalogueInstalled(entry.version, installedVersions[entry.id]))
        }
    }

    /**
     * Downloads [entry] from [baseUrl] into `<id>.zip.part`, validates it as a pack whose id is
     * [entry].id, then renames it to `<id>.zip`. [onProgress] gets (bytes read, total or -1).
     */
    fun download(baseUrl: String, entry: CatalogueEntry, onProgress: (Long, Long) -> Unit): InstalledPack {
        requireSafeId(entry.id)
        require(entry.file.isNotEmpty() && ".." !in entry.file) { "Invalid pack file name \"${entry.file}\"." }
        val base = normaliseBaseUrl(baseUrl)
        val part = partFor(entry.id)
        try {
            part.outputStream().use { out ->
                // Bound the transfer like the index fetch: a wrong or hostile catalogue whose zip URL
                // returns an endless body must not fill the device's storage or keep the app busy.
                fetchTo(
                    base + entry.file,
                    out,
                    if (entry.bytes > 0) entry.bytes else -1L,
                    maxBytes = downloadLimit(entry.bytes),
                    maxMillis = PACK_DEADLINE_MS,
                    onProgress = onProgress,
                )
            }
            val pack = try {
                PackReader.read(part)
            } catch (e: PackFormatException) {
                throw IOException("The downloaded file is not a valid pack: ${e.message}", e)
            }
            if (PackIdAliases.current(pack.id) != PackIdAliases.current(entry.id)) {
                throw IOException("The downloaded pack is \"${PackIdAliases.current(pack.id)}\", but the catalogue lists \"${entry.id}\".")
            }
            return installCurrent(part, pack)
        } finally {
            part.delete()
        }
    }

    /**
     * Moves [source] to `<id>.zip`, replacing an existing pack with that id. The bytes are staged
     * in `<id>.zip.part` inside the pack directory first, so the existing pack is only ever
     * replaced by one atomic rename and a failed copy never leaves a truncated `<id>.zip`.
     */
    private fun install(source: File, id: String): File {
        val target = zipFor(id)
        val staged = partFor(id)
        if (source.absoluteFile != staged.absoluteFile && !source.renameTo(staged)) {
            try {
                source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
            } catch (e: IOException) {
                staged.delete()
                throw e
            }
        }
        File(dir, "$id.${DirectoryPackResolver.EXTENSION}").delete()
        if (!staged.renameTo(target)) {
            staged.delete()
            throw IOException("Could not install $id.zip")
        }
        return target
    }

    private fun partFor(id: String): File = File(dir, "${PackIdAliases.current(id)}.zip.part")

    /**
     * Deletes `.part` files in the pack directory that nothing has written to for
     * [STALE_PART_MS]. A download or install deletes its own `.part` in a finally block, which
     * never runs when the process is killed; [installed] and [delete] ignore such files, so
     * without this sweep a half-finished download (up to [MAX_PACK_BYTES]) would stay forever.
     * A live download keeps its file's mtime fresh and ends within [PACK_DEADLINE_MS].
     */
    private fun sweepStaleParts() {
        val cutoff = System.currentTimeMillis() - STALE_PART_MS
        dir.listFiles { f -> f.isFile && f.name.endsWith(".part") && f.lastModified() < cutoff }
            .orEmpty()
            .forEach { it.delete() }
    }

    /** `<id>.zip` of pack [id] under its current id. */
    private fun zipFor(id: String): File = File(dir, "${PackIdAliases.current(id)}.zip")

    /**
     * GET [url] into [out]; non-200 responses and network errors become [IOException]s. A body
     * longer than [maxBytes], or a transfer still running after [maxMillis] (0 = no limit), is
     * aborted with an [IOException].
     */
    private fun fetchTo(
        url: String,
        out: OutputStream,
        expected: Long,
        maxBytes: Long = Long.MAX_VALUE,
        maxMillis: Long = 0L,
        onProgress: (Long, Long) -> Unit,
    ) {
        val startedAt = System.currentTimeMillis()
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IOException("Invalid URL $url: ${e.message}", e)
        }
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "*/*")
        try {
            val code = try {
                connection.responseCode
            } catch (e: FileNotFoundException) {
                throw IOException("Not found (404): $url", e)
            }
            if (code != HttpURLConnection.HTTP_OK) {
                val reason = connection.responseMessage?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                throw IOException(if (code == 404) "Not found (404): $url" else "$reason ($code): $url")
            }
            val total = if (expected > 0) expected else connection.contentLengthLong
            if (connection.contentLengthLong > maxBytes) {
                throw IOException("Response too large (${connection.contentLengthLong} bytes, limit $maxBytes): $url")
            }
            var read = 0L
            connection.inputStream.use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    read += n
                    if (read > maxBytes) throw IOException("Response too large (over $maxBytes bytes): $url")
                    if (maxMillis > 0 && System.currentTimeMillis() - startedAt > maxMillis) {
                        throw IOException("Timed out after ${maxMillis / 1000} s: $url")
                    }
                    out.write(chunk, 0, n)
                    onProgress(read, total)
                }
            }
            out.flush()
        } finally {
            connection.disconnect()
        }
    }

    /** A zip still under a former id (its migration failed) is shown under its current identity. */
    private fun Pack.toInstalled(file: File): InstalledPack {
        val former = PackIdAliases.isFormer(id)
        val currentId = PackIdAliases.current(id)
        return InstalledPack(
            id = currentId,
            name = if (former) displayName(currentId) else name.ifBlank { id },
            author = if (former) "" else author,
            version = if (former) CATALOGUE_VERSION else version,
            file = file,
            bytes = file.length(),
            dayNight = dayNight,
            drawableCount = drawableCount,
            hasSettingsIcons = layers.any { it.category.startsWith(SETTINGS_ICON_PREFIX) },
            features = features,
            pack = this,
        )
    }

    /** An entry of an older catalogue that lists a former id is shown under the current identity. */
    private fun PackIndexEntry.toEntry(): CatalogueEntry {
        val former = PackIdAliases.isFormer(id)
        val currentId = PackIdAliases.current(id)
        return CatalogueEntry(
            id = currentId,
            name = if (former) displayName(currentId) else name.ifBlank { id },
            author = if (former) "" else author,
            version = if (former) CATALOGUE_VERSION else version,
            bytes = bytes,
            file = file,
            hasSettingsIcons = categories.any { it.startsWith(SETTINGS_ICON_PREFIX) },
            dayNight = dayNight,
            installed = false,
        )
    }

    companion object {
        private const val TAG = "PackStore"
        private const val PACK_JSON = "pack.json"

        /** The version every catalogue pack ships under (tools/pack_names.json `version`). */
        internal const val CATALOGUE_VERSION = "1.0"

        /** The display name of catalogue id [id]: the id capitalised, as in tools/pack_names.json. */
        internal fun displayName(id: String): String = id.replaceFirstChar { it.titlecase() }

        /** Former display name (an id "a-and-b" as "A & B" or "A and B") → current name. */
        private val formerNames: List<Pair<Regex, String>> by lazy {
            PackIdAliases.FORMER_TO_CURRENT.entries
                .sortedByDescending { it.key.length }
                .map { (former, current) ->
                    val words = former.split('-').joinToString("[\\s_/-]*") { w ->
                        if (w == "and") "(?:and|&)" else Regex.escape(w)
                    }
                    Regex("(?<![A-Za-z0-9])$words(?![A-Za-z0-9])", RegexOption.IGNORE_CASE) to displayName(current)
                }
        }

        /** [text] with every former catalogue pack name replaced by the pack's current name. */
        internal fun replaceFormerNames(text: String): String =
            formerNames.fold(text) { acc, (pattern, name) -> pattern.replace(acc, name) }

        /**
         * `pack.json` [json] under catalogue id [newId]: id, name, package, catalogue version and a
         * blank author set, `sourcePackage` dropped, former pack names in option labels and choices
         * and in category labels replaced. Keys, layers and every other field stay as they are.
         */
        internal fun migratedPackJson(json: String, newId: String): String {
            val obj = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw IOException("pack.json is not valid JSON: ${e.message}", e)
            }
            obj.put("id", newId)
            obj.put("name", displayName(newId))
            obj.put("package", "com.hexnext.pack." + newId.replace('-', '_'))
            obj.put("author", "")
            obj.put("version", CATALOGUE_VERSION)
            obj.remove("sourcePackage")
            obj.optJSONArray("options")?.let { options ->
                for (i in 0 until options.length()) {
                    val option = options.optJSONObject(i) ?: continue
                    option.optString("label", null)?.let { option.put("label", replaceFormerNames(it)) }
                    val choices = option.optJSONArray("choices") ?: continue
                    val scrubbed = JSONArray()
                    for (j in 0 until choices.length()) {
                        val choice = choices.opt(j)
                        scrubbed.put(if (choice is String) replaceFormerNames(choice) else choice)
                    }
                    option.put("choices", scrubbed)
                }
            }
            obj.optJSONObject("categories")?.let { categories ->
                for (key in categories.keys().asSequence().toList()) {
                    val label = categories.opt(key)
                    if (label is String) categories.put(key, replaceFormerNames(label))
                }
            }
            return obj.toString(1)
        }

        /** A fresh entry for copying [entry]'s bytes: stored entries keep their size and CRC. */
        private fun copyOf(entry: ZipEntry): ZipEntry = ZipEntry(entry.name).apply {
            time = entry.time
            if (entry.method == ZipEntry.STORED && !entry.isDirectory) {
                method = ZipEntry.STORED
                size = entry.size
                compressedSize = entry.size
                crc = entry.crc
            } else if (entry.isDirectory) {
                method = ZipEntry.STORED
                size = 0
                compressedSize = 0
                crc = 0
            }
        }

        /**
         * True when [candidate] is a later version than [current]: dot-separated numbers are
         * compared part by part (a missing part counts as 0); when either has a non-numeric part,
         * any difference counts as newer.
         */
        internal fun isNewerVersion(candidate: String, current: String): Boolean {
            val a = candidate.trim().split('.').map { it.toIntOrNull() }
            val b = current.trim().split('.').map { it.toIntOrNull() }
            if (a.any { it == null } || b.any { it == null }) return candidate.trim() != current.trim()
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrNull(i) ?: 0
                val y = b.getOrNull(i) ?: 0
                if (x != y) return x > y
            }
            return false
        }

        /**
         * The [CatalogueEntry.installed] flag: true when the device holds the pack
         * ([installedVersion] non-null) and the catalogue's [catalogueVersion] is not newer, so an
         * available update keeps its download button. Callers that recompute the flag after a
         * download, import or delete must use this rather than a plain id check.
         */
        fun isCatalogueInstalled(catalogueVersion: String, installedVersion: String?): Boolean =
            installedVersion != null && !isNewerVersion(catalogueVersion, installedVersion)

        /** Catalogue of the `packs` branch of the Hex Next repository. */
        const val DEFAULT_CATALOGUE_URL = "https://raw.githubusercontent.com/Bingblop/Hex-Next/packs/"

        /** Asset folder of the packs bundled into debug builds. */
        private const val ASSET_DIR = "packs"

        private const val TIMEOUT_MS = 10_000
        private const val MAX_INDEX_BYTES = 4L * 1024 * 1024
        private const val INDEX_DEADLINE_MS = 60_000L

        /** Ceiling for one pack download, also used when the catalogue gives no size. */
        private const val MAX_PACK_BYTES = 512L * 1024 * 1024

        /** Slack over the catalogue's `bytes` before a download is aborted. */
        private const val PACK_SLACK_BYTES = 1L * 1024 * 1024

        /** Total time one pack download may take. */
        private const val PACK_DEADLINE_MS = 15L * 60 * 1000

        /** Age after which an untouched `.part` file is treated as left over from a killed process. */
        private const val STALE_PART_MS = 4 * PACK_DEADLINE_MS

        /**
         * Most bytes a pack download may read: the catalogue's [listed] size plus some slack (a
         * repacked zip may differ a little), never more than [MAX_PACK_BYTES].
         */
        internal fun downloadLimit(listed: Long): Long =
            if (listed > 0 && listed < MAX_PACK_BYTES) minOf(MAX_PACK_BYTES, listed + listed / 4 + PACK_SLACK_BYTES) else MAX_PACK_BYTES
        private const val SETTINGS_ICON_PREFIX = "settingsicon"

        /** [url] with a single trailing slash. */
        fun normaliseBaseUrl(url: String): String = url.trim().trimEnd('/') + "/"

        private fun isPackFile(name: String) = name.endsWith(".zip") || name.endsWith(".${DirectoryPackResolver.EXTENSION}")

        /** Rejects ids that are empty or could escape the pack directory as a file name. */
        private fun requireSafeId(id: String) {
            require(id.isNotBlank() && '/' !in id && '\\' !in id && ".." !in id) { "Invalid pack id \"$id\"." }
        }
    }
}
