package com.hexnext.themer.theme

import android.content.Context
import android.os.Build
import com.hexnext.engine.compile.ThemeSpec as EngineThemeSpec
import com.hexnext.engine.compile.ThemeExport
import com.hexnext.engine.compile.ThemeFormatException
import com.hexnext.themer.BuildConfig
import com.hexnext.themer.device.DeviceScanner
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A full Hex Next backup (`.hexnextbackup`): everything needed to get the same theme back after
 * an uninstall, a factory reset or on a new phone, in one zip:
 *
 *  - `manifest.json`: format, app version, when, and the phone it came from;
 *  - `theme.hexnext`: the theme file ([ThemeExport], the same file Export writes);
 *  - `settings.json`: the app's own settings ([com.hexnext.themer.ui.settings.AppSettingsStore]);
 *  - `custom/<icons|backgrounds|fonts|generated>/…`: imported and generated assets, with their
 *    `.name` sidecars ([CustomAssetStore]);
 *  - `learned/semwt-known/…`: the Samsung colour maps and palette the scans remembered
 *    ([DeviceScanner.KNOWN_SEMWT_DIR]), so a phone without a colour palette theme still themes
 *    fully after a restore. Restored only onto the same model and One UI build.
 *
 * Never included: AI provider keys (they stay on the phone), purchases (read from Google Play
 * again), pack zips (downloaded again), and the phone's scan, logs and overlay state.
 */
object ThemeBackup {
    const val EXTENSION = "hexnextbackup"
    const val MIME = "application/octet-stream"
    private const val FORMAT = 1

    private const val MANIFEST = "manifest.json"
    private const val THEME = "theme.hexnext"
    private const val SETTINGS = "settings.json"
    private const val CUSTOM = "custom/"
    private const val LEARNED = "learned/semwt-known/"

    /** Custom asset folders that are backed up (subfolders of `files/custom`). */
    private val CUSTOM_DIRS = listOf("icons", "backgrounds", "fonts", "generated")

    /** A restore refuses archives larger than this once unpacked (fonts are up to 64 MB each). */
    private const val MAX_TOTAL_BYTES = 768L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 96L * 1024 * 1024
    private val SAFE_NAME = Regex("^[A-Za-z0-9._ ()+-]{1,160}$")

    /** What a backup holds, for the restore prompt. */
    data class Summary(
        val createdAt: Long,
        val appVersion: String,
        val model: String,
        val oneUi: String,
        val themeName: String,
        val assets: Int,
        val learnedMaps: Int,
        val samePhone: Boolean,
    )

    /** A read backup, not yet restored. */
    class Contents internal constructor(
        val summary: Summary,
        val spec: EngineThemeSpec,
        val settingsJson: String?,
        internal val custom: Map<String, ByteArray>,
        internal val learned: Map<String, ByteArray>,
    )

    /** Writes a backup of [spec], [settingsJson] and this phone's assets and learned maps to [out]. */
    fun write(context: Context, spec: EngineThemeSpec, settingsJson: String, out: OutputStream) {
        val files = context.applicationContext.filesDir
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun putFile(name: String, file: File) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            var assets = 0
            val customRoot = File(files, "custom")
            for (dir in CUSTOM_DIRS) {
                File(customRoot, dir).listFiles()?.filter { it.isFile && SAFE_NAME.matches(it.name) && !it.name.endsWith(".part") }
                    ?.sortedBy { it.name }
                    ?.forEach { f ->
                        putFile("$CUSTOM$dir/${f.name}", f)
                        if (!f.name.endsWith(".name")) assets++
                    }
            }
            var learned = 0
            File(files, DeviceScanner.KNOWN_SEMWT_DIR).listFiles()?.filter { it.isFile && SAFE_NAME.matches(it.name) }
                ?.sortedBy { it.name }
                ?.forEach { f -> putFile("$LEARNED${f.name}", f); learned++ }
            put(THEME, ThemeExport.export(spec))
            put(SETTINGS, settingsJson.toByteArray())
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("app", "Hex Next")
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("createdAt", System.currentTimeMillis())
                .put("model", Build.MODEL)
                .put("oneUi", oneUiBuild())
                .put("theme", spec.name)
                .put("assets", assets)
                .put("learnedMaps", learned)
            put(MANIFEST, manifest.toString(2).toByteArray())
        }
    }

    /** Reads a backup from [input]; throws [ThemeFormatException] when it is not one. */
    fun read(input: InputStream): Contents {
        var manifest: JSONObject? = null
        var themeBytes: ByteArray? = null
        var settings: String? = null
        val custom = LinkedHashMap<String, ByteArray>()
        val learned = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                val bytes = readLimited(zip)
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw ThemeFormatException("The backup is too large.")
                when {
                    name == MANIFEST -> manifest = runCatching { JSONObject(String(bytes)) }.getOrNull()
                    name == THEME -> themeBytes = bytes
                    name == SETTINGS -> settings = String(bytes)
                    name.startsWith(CUSTOM) -> {
                        // Only custom/<known dir>/<plain file name>: nothing can escape files/custom.
                        val parts = name.removePrefix(CUSTOM).split('/')
                        if (parts.size == 2 && parts[0] in CUSTOM_DIRS && SAFE_NAME.matches(parts[1])) custom[name.removePrefix(CUSTOM)] = bytes
                    }
                    name.startsWith(LEARNED) -> {
                        val file = name.removePrefix(LEARNED)
                        if (SAFE_NAME.matches(file)) learned[file] = bytes
                    }
                }
                zip.closeEntry()
            }
        }
        val m = manifest ?: throw ThemeFormatException("That file is not a Hex Next backup.")
        if (m.optInt("format", 0) !in 1..FORMAT) throw ThemeFormatException("This backup needs a newer Hex Next.")
        val theme = themeBytes ?: throw ThemeFormatException("The backup has no theme.")
        val spec = ThemeExport.read(theme).spec
        val model = m.optString("model")
        val oneUi = m.optString("oneUi")
        val summary = Summary(
            createdAt = m.optLong("createdAt"),
            appVersion = m.optString("appVersion"),
            model = model,
            oneUi = oneUi,
            themeName = spec.name,
            assets = custom.keys.count { !it.endsWith(".name") },
            learnedMaps = learned.size,
            samePhone = model == Build.MODEL && oneUi == oneUiBuild(),
        )
        return Contents(summary, spec, settings, custom, learned)
    }

    /**
     * Puts the backup's assets (and, on the same phone model and One UI build, its learned maps)
     * back into the app's files. Existing files of the same name are replaced; others are kept.
     * The theme and settings are restored by the caller (they live in the view model and the
     * settings store). Returns the number of files written.
     */
    fun restoreFiles(context: Context, contents: Contents): Int {
        val files = context.applicationContext.filesDir
        var written = 0
        fun save(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            written++
        }
        val customRoot = File(files, "custom")
        for ((path, bytes) in contents.custom) save(File(customRoot, path), bytes)
        if (contents.summary.samePhone) {
            val known = File(files, DeviceScanner.KNOWN_SEMWT_DIR)
            for ((name, bytes) in contents.learned) save(File(known, name), bytes)
        }
        return written
    }

    /** `Hex Next backup 2026-11-03.hexnextbackup`. */
    fun fileName(dated: Boolean = true): String {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        return if (dated) "Hex Next backup $day.$EXTENSION" else "Hex Next backup (latest).$EXTENSION"
    }

    private fun readLimited(stream: InputStream): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
            if (buffer.size() > MAX_ENTRY_BYTES) throw ThemeFormatException("A file in the backup is too large.")
        }
        return buffer.toByteArray()
    }

    @Suppress("PrivateApi")
    private fun oneUiBuild(): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "ro.build.version.oneui") as String
    }.getOrDefault("") + "/" + Build.VERSION.SDK_INT
}
