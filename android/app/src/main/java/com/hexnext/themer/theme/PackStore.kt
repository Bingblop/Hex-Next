package com.hexnext.themer.theme

import android.content.Context
import android.net.Uri
import com.hexnext.engine.pack.DirectoryPackResolver
import com.hexnext.engine.pack.Pack
import com.hexnext.engine.pack.PackFormatException
import com.hexnext.engine.pack.PackIndex
import com.hexnext.engine.pack.PackIndexEntry
import com.hexnext.engine.pack.PackReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

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
    val installed: Boolean,
)

/**
 * The pack zips on the device ([ThemeApplier.packsDir]): listing, import from a `content://`
 * document, deletion, and download from a catalogue (`index.json` + zips under one base URL).
 *
 * Every function blocks on file or network I/O; call them on `Dispatchers.IO`. Each stored pack is
 * named `<id>.zip` so that [DirectoryPackResolver] finds it directly.
 */
class PackStore(context: Context) {

    private val appContext = context.applicationContext

    private val dir: File get() = ThemeApplier.packsDir(appContext)

    /**
     * Copies the packs bundled under `assets/packs/` (debug builds only: the CI copies a few test
     * packs there) into the pack directory, skipping ids that are already installed. Returns the
     * ids it added and, per asset that could not be installed, why. A release build has no such
     * assets and this is a no-op.
     */
    fun seedBundled(): Seeded {
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

    /** Result of [seedBundled]: installed ids plus asset name → reason for the ones that failed. */
    data class Seeded(val added: List<String>, val failed: Map<String, String>)

    /** Every readable pack zip, sorted by name; unreadable zips are reported in [InstalledPacks.errors]. */
    fun installed(): InstalledPacks {
        val zips = dir.listFiles { f -> f.isFile && isPackFile(f.name) }.orEmpty()
        val packs = ArrayList<InstalledPack>()
        val errors = LinkedHashMap<String, String>()
        for (zip in zips.sortedBy { it.name }) {
            try {
                packs += PackReader.read(zip).toInstalled(zip)
            } catch (e: PackFormatException) {
                errors[zip.name] = e.message.orEmpty()
            }
        }
        packs.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return InstalledPacks(packs, errors)
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
            val target = install(temp, pack.id)
            return pack.toInstalled(target)
        } catch (e: IOException) {
            throw IllegalArgumentException("Reading the selected file failed: ${e.message}", e)
        } finally {
            temp.delete()
        }
    }

    /** Deletes the zip(s) of pack [id]; true when a file was removed. */
    fun delete(id: String): Boolean {
        requireSafeId(id)
        var removed = false
        for (file in listOf(zipFor(id), File(dir, "$id.${DirectoryPackResolver.EXTENSION}"))) {
            if (file.isFile && file.delete()) removed = true
        }
        return removed
    }

    /** Fetches and parses `index.json` under [baseUrl]; [CatalogueEntry.installed] reflects [installed]. */
    fun fetchCatalogue(baseUrl: String): List<CatalogueEntry> {
        val base = normaliseBaseUrl(baseUrl)
        val buffer = ByteArrayOutputStream()
        fetchTo(base + DirectoryPackResolver.INDEX, buffer, -1L) { _, _ -> }
        val text = String(buffer.toByteArray(), Charsets.UTF_8)
        val index = try {
            PackIndex.parse(text)
        } catch (e: PackFormatException) {
            throw IOException("The catalogue index is invalid: ${e.message}", e)
        }
        val installedIds = installed().packs.mapTo(HashSet()) { it.id }
        return index.packs.map { it.toEntry(it.id in installedIds) }
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
                fetchTo(base + entry.file, out, if (entry.bytes > 0) entry.bytes else -1L, onProgress)
            }
            val pack = try {
                PackReader.read(part)
            } catch (e: PackFormatException) {
                throw IOException("The downloaded file is not a valid pack: ${e.message}", e)
            }
            if (pack.id != entry.id) {
                throw IOException("The downloaded pack is \"${pack.id}\", but the catalogue lists \"${entry.id}\".")
            }
            val target = install(part, pack.id)
            return pack.toInstalled(target)
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

    private fun partFor(id: String): File = File(dir, "$id.zip.part")

    private fun zipFor(id: String): File = File(dir, "$id.zip")

    /** GET [url] into [out]; non-200 responses and network errors become [IOException]s. */
    private fun fetchTo(url: String, out: OutputStream, expected: Long, onProgress: (Long, Long) -> Unit) {
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
            var read = 0L
            connection.inputStream.use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
            out.flush()
        } finally {
            connection.disconnect()
        }
    }

    private fun Pack.toInstalled(file: File) = InstalledPack(
        id = id,
        name = name.ifBlank { id },
        author = author,
        version = version,
        file = file,
        bytes = file.length(),
        dayNight = dayNight,
        drawableCount = drawableCount,
        hasSettingsIcons = layers.any { it.category.startsWith(SETTINGS_ICON_PREFIX) },
        features = features,
        pack = this,
    )

    private fun PackIndexEntry.toEntry(installed: Boolean) = CatalogueEntry(
        id = id,
        name = name.ifBlank { id },
        author = author,
        version = version,
        bytes = bytes,
        file = file,
        hasSettingsIcons = categories.any { it.startsWith(SETTINGS_ICON_PREFIX) },
        dayNight = dayNight,
        installed = installed,
    )

    companion object {
        /** Catalogue of the `packs` branch of the Hex Next repository. */
        const val DEFAULT_CATALOGUE_URL = "https://raw.githubusercontent.com/Bingblop/Hex-Next/packs/"

        /** Asset folder of the packs bundled into debug builds. */
        private const val ASSET_DIR = "packs"

        private const val TIMEOUT_MS = 10_000
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
