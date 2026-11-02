package com.hexnext.themer.device

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import com.hexnext.engine.device.OneUi9Facts
import com.hexnext.engine.device.OverlayDump
import com.hexnext.engine.frro.FrroReader
import com.hexnext.themer.IThemeService
import com.hexnext.themer.shizuku.ExecOutput
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.SnapshotBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Reads the device before anything is themed: target package status, every overlay, the
 * targets' `resources.arsc`, Samsung's `SemWT_*` overlays and their `.frro` files. Writes
 * `files/reports/<timestamp>.json` plus a readable `.txt` beside it.
 *
 * Samsung's themed resources are learned primarily from `cmd overlay dump android:SemWT_<target>`
 * (parsed by the engine's [OverlayDump]): it needs only `cmd overlay`, returns the resource
 * names already resolved and both the default and `night` colours, and reports `mPriority` /
 * `mState`. The copied `.frro` (parsed with [FrroReader]) is the fallback when the dump has no
 * mapping.
 */
class DeviceScanner(context: Context) {

    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir

    data class Target(val pkg: String, val deprecated: Boolean = false)

    suspend fun scan(service: IThemeService, viaSu: Boolean = false, progress: (String) -> Unit = {}): DeviceReport =
        withContext(Dispatchers.IO) { scanBlocking(service, viaSu, progress) }

    /** [scan] for callers already on a background thread (the apply pipeline scans when no report exists). */
    fun scanBlocking(service: IThemeService, viaSu: Boolean = false, progress: (String) -> Unit = {}): DeviceReport {
        val errors = ArrayList<String>()
        val uid = runCatching { service.uid }.getOrElse { errors.add("getUid: ${it.message}"); -1 }

        progress("Reading package status and device props…")
        val info = service.packageInfo(TARGETS.map { it.pkg }.toTypedArray())
        if (info.code() != 0) errors.add("packageInfo: ${info.output()}")
        val props = ThemeService.DEVICE_PROPS.associateWith { info.getString(it).orEmpty() }
        val basePackages = parsePackages(info, errors)

        progress("Listing overlays…")
        val overlayBundle = service.listOverlays()
        if (overlayBundle.code() != 0) errors.add("listOverlays: ${overlayBundle.output()}")
        val overlays = parseOverlays(overlayBundle, errors)
        val frroPaths = overlayBundle.getStringArray(ThemeService.KEY_FRRO_PATHS)?.toList().orEmpty()

        val scanDir = File(filesDir, "scan").apply { mkdirs() }

        progress("Copying ${frroPaths.size} .frro candidates…")
        val frroCopied = copyFrros(service, frroPaths, basePackages.filter { it.installed }.map { it.pkg }, File(scanDir, "frro"), errors)

        val semWtTargets = (
            basePackages.map { it.pkg } +
                overlays.filter { it.isSamsungWallpaperTheme && it.enabled }.mapNotNull { OneUi9Facts.semWtTarget(it.overlayId) }
            ).distinct()
        val semWt = semWtTargets.map { target ->
            progress("Dumping ${OneUi9Facts.semWtOverlayId(target)}…")
            scanSemWt(service, target, frroCopied, File(scanDir, "semwt"))
        }
        val semWtByTarget = semWt.associateBy { it.target }

        val packages = basePackages.map { base ->
            val semWtCount = overlays.count { it.target == base.pkg && it.isSamsungWallpaperTheme }
            val dumped = semWtByTarget[base.pkg]?.takeIf { it.found }
            val missingTarget = dumped?.state?.let { OneUi9Facts.isDebloated(it) } == true
            val status = base.copy(
                semWtOverlays = semWtCount,
                semwtPriority = dumped?.priority,
                semwtState = dumped?.state,
                // Not installed for the user but Samsung still ships its SemWT overlay: debloated.
                debloated = base.debloated || (!base.installed && missingTarget),
            )
            if (!status.installed || status.apkPath == null) {
                status
            } else {
                progress("Extracting resources.arsc from ${status.pkg}…")
                extractArsc(service, status, File(scanDir, status.pkg))
            }
        }

        progress("Reading system colours…")
        val shades = runCatching { SnapshotBuilder.saveSystemShades(appContext) }.getOrElse {
            errors.add("system shades: ${it.message}")
            emptyMap()
        }
        if (shades.isEmpty()) errors.add("system shades: no android:color/system_* colour could be read")

        val createdAt = System.currentTimeMillis()
        val report = DeviceReport(
            createdAt = createdAt,
            uid = uid,
            viaSu = viaSu,
            props = props,
            overlays = overlays,
            frroPaths = frroPaths,
            frroCopied = frroCopied,
            packages = packages,
            errors = errors,
            semWt = semWt,
        )

        progress("Writing report…")
        val reportsDir = File(filesDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(createdAt))
        val json = File(reportsDir, "$stamp.json")
        val txt = File(reportsDir, "$stamp.txt")
        json.writeText(report.toJson().toString(2))
        txt.writeText(report.summaryText())
        return report.copy(jsonPath = json.absolutePath, txtPath = txt.absolutePath)
    }

    private fun parsePackages(info: Bundle, errors: MutableList<String>): List<PackageStatus> {
        val byPkg = HashMap<String, PackageStatus>()
        runCatching {
            val array = JSONArray(info.getString(ThemeService.KEY_JSON) ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val pkg = o.optString("pkg")
                byPkg[pkg] = PackageStatus(
                    pkg = pkg,
                    deprecated = false,
                    apkPath = if (o.isNull("apkPath")) null else o.optString("apkPath").ifBlank { null },
                    installed = o.optBoolean("installed"),
                    debloated = o.optBoolean("debloated"),
                    error = if (o.has("error")) o.optString("error") else null,
                )
            }
        }.onFailure { errors.add("packageInfo json: ${it.message}") }
        return TARGETS.map { t ->
            (byPkg[t.pkg] ?: PackageStatus(t.pkg, t.deprecated, null, installed = false, debloated = false, error = "no data"))
                .copy(deprecated = t.deprecated)
        }
    }

    private fun parseOverlays(bundle: Bundle, errors: MutableList<String>): List<OverlayStatus> = runCatching {
        val array = JSONArray(bundle.getString(ThemeService.KEY_JSON) ?: "[]")
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            OverlayStatus(
                overlayId = o.optString("overlayId"),
                target = o.optString("target"),
                enabled = o.optBoolean("enabled"),
                isSamsungWallpaperTheme = o.optBoolean("isSamsungWallpaperTheme"),
            )
        }
    }.getOrElse {
        errors.add("listOverlays json: ${it.message}")
        emptyList()
    }

    /** Copies only `resources.arsc` out of the APK: direct read first, then the service's openFile pipe. */
    private fun extractArsc(service: IThemeService, status: PackageStatus, outDir: File): PackageStatus {
        val apk = status.apkPath ?: return status
        outDir.mkdirs()
        val out = File(outDir, "resources.arsc")
        out.delete()
        val problems = ArrayList<String>()

        val direct = File(apk)
        if (direct.canRead()) {
            runCatching {
                ZipFile(direct).use { zip ->
                    val entry = zip.getEntry(ARSC) ?: error("$ARSC not in APK")
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }.onFailure { problems.add("direct: ${it.message}") }
        }

        if (!out.isFile || out.length() == 0L) {
            runCatching {
                val pfd = service.openFile(apk) ?: error("openFile returned null")
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { raw ->
                    if (!copyZipEntry(raw, ARSC, out)) error("$ARSC not found in stream")
                }
            }.onFailure { problems.add("openFile: ${it.message}") }
        }

        if (!out.isFile || out.length() == 0L) {
            out.delete()
            return status.copy(error = "resources.arsc not extracted (${problems.joinToString("; ")})")
        }

        return runCatching {
            // Only the counted types: skipping strings, styles and layouts keeps the per-package scan small.
            val parsed = ArscReader.read(out, ArscReader.THEME_TYPES)
            // Plain-text name lists beside the arsc: cheap to share, and they tell us which drawable
            // names a One UI build really uses (the test overlay and the pack mapping depend on it).
            runCatching {
                for (type in listOf("drawable", "mipmap", "color")) {
                    File(out.parentFile, "names-$type.txt")
                        .writeText(parsed.ofType(type).map { it.name }.distinct().sorted().joinToString("\n"))
                }
            }
            status.copy(
                arscPath = out.absolutePath,
                arscBytes = out.length(),
                colorCount = parsed.countOf("color"),
                drawableCount = parsed.countOf("drawable"),
                error = parsed.warnings.firstOrNull(),
            )
        }.getOrElse {
            status.copy(arscPath = out.absolutePath, arscBytes = out.length(), error = "arsc parse: ${it.message}")
        }
    }

    private fun copyZipEntry(input: InputStream, name: String, out: File): Boolean {
        val zip = ZipInputStream(input.buffered())
        while (true) {
            val entry = zip.nextEntry ?: return false
            if (entry.name == name) {
                out.outputStream().use { zip.copyTo(it) }
                return true
            }
        }
    }

    /**
     * Runs `cmd overlay dump android:SemWT_<target>` and parses it with [OverlayDump]. Saves the
     * raw output to `<outDir>/<target>.txt` and the parsed resources to `<outDir>/<target>.json`.
     * When the dump has no mapping (no idmap, missing target, dump failed), falls back to the
     * copied `.frro` matching `mBaseCodePath` (or any copied `SemWT_<target>` file).
     */
    private fun scanSemWt(service: IThemeService, target: String, frroCopied: List<String>, outDir: File): SemWtStatus {
        val overlayId = OneUi9Facts.semWtOverlayId(target)
        if (!PACKAGE_NAME.matches(target)) return SemWtStatus(target, overlayId, error = "unsafe package name")
        outDir.mkdirs()
        val problems = ArrayList<String>()
        val dumpFile = File(outDir, "${sanitize(target)}.txt")
        val mappingFile = File(outDir, "${sanitize(target)}.json")
        dumpFile.delete()
        mappingFile.delete()

        // A binder failure ("Transaction failed on small parcel …") is retried once after a short
        // pause; the service spills long output to a file, read back by ExecOutput.
        var dumpError: String? = null
        var dump: OverlayDump? = null
        for (attempt in 0 until DUMP_ATTEMPTS) {
            if (attempt > 0) Thread.sleep(DUMP_RETRY_DELAY_MS)
            val outcome = runCatching {
                val r = service.exec("cmd overlay dump $overlayId")
                val text = ExecOutput.fullText(service, r)
                dumpFile.writeText(text)
                r.code() to OverlayDump.parseAll(text).firstOrNull { it.overlayId == overlayId }
            }
            val value = outcome.getOrNull()
            if (value != null) {
                dumpError = if (value.first != 0) "dump exit ${value.first}" else null
                dump = value.second
                break
            }
            dumpError = "dump: ${outcome.exceptionOrNull()?.message}" + if (attempt > 0) " (after retry)" else ""
        }

        var source = SemWtStatus.SOURCE_NONE
        var resourceCount = 0
        var valueCount = 0
        if (dump != null && dump.entries.isNotEmpty()) {
            source = SemWtStatus.SOURCE_DUMP
            resourceCount = dump.entries.size
            valueCount = dump.entries.size + dump.entries.count { it.nightValue != null }
            runCatching { mappingFile.writeText(mappingJson(dump).toString(2)) }
                .onFailure { problems.add("mapping json: ${it.message}") }
        } else {
            val live = dump?.baseCodePath?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.let { sanitize(it) }
            val candidates = frroCopied.filter { it.substringAfterLast('/').contains("SemWT_$target-") }
            val copy = live?.let { name -> candidates.firstOrNull { it.endsWith("_$name") } } ?: candidates.firstOrNull()
            if (copy != null) {
                runCatching {
                    val parsed = FrroReader.read(File(copy))
                    source = SemWtStatus.SOURCE_FRRO
                    resourceCount = parsed.entries.map { it.resourceName }.distinct().size
                    valueCount = parsed.entries.size
                }.onFailure { problems.add("frro ${copy.substringAfterLast('/')}: ${it.message}") }
            }
        }

        // A dump failure only counts as an error when the .frro fallback did not cover it.
        dumpError?.let { problems.add(0, if (source == SemWtStatus.SOURCE_FRRO) "$it; used the copied .frro" else it) }

        return SemWtStatus(
            target = target,
            overlayId = overlayId,
            found = dump != null,
            owner = dump?.packageName,
            state = dump?.state,
            enabled = dump?.isEnabled ?: false,
            priority = dump?.priority,
            targetOverlayable = dump?.targetOverlayable,
            frroPath = dump?.baseCodePath?.ifBlank { null },
            source = source,
            resourceCount = resourceCount,
            valueCount = valueCount,
            missingCount = dump?.missing?.size ?: 0,
            dumpPath = dumpFile.takeIf { it.isFile }?.absolutePath,
            mappingPath = mappingFile.takeIf { it.isFile }?.absolutePath,
            error = problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    private fun mappingJson(dump: OverlayDump): JSONObject = JSONObject().apply {
        put("overlayId", dump.overlayId)
        put("target", dump.targetPackage)
        put("state", dump.state)
        put("priority", dump.priority ?: JSONObject.NULL)
        put("entries", JSONArray().apply {
            dump.entries.forEach { e ->
                put(
                    JSONObject()
                        .put("resId", "0x%08x".format(e.resId))
                        .put("name", e.typeAndName)
                        .put("kind", e.kind)
                        .put("default", e.defaultValue)
                        .put("night", e.nightValue ?: JSONObject.NULL)
                )
            }
        })
        put("missing", JSONArray(dump.missing.toList()))
    }

    /**
     * Copies every `.frro` whose path or first 64 KB mentions an installed target, or whose
     * name contains `SemWT`. Falls back to a root `cat > ` into our own files dir when the
     * descriptor cannot be read (SELinux).
     */
    private fun copyFrros(
        service: IThemeService,
        paths: List<String>,
        targets: List<String>,
        outDir: File,
        errors: MutableList<String>,
    ): List<String> {
        if (paths.isEmpty()) return emptyList()
        outDir.mkdirs()
        val copied = ArrayList<String>()
        paths.forEachIndexed { index, path ->
            val base = path.substringAfterLast('/')
            val nameMatch = base.contains("SemWT") || targets.any { path.contains(it) }
            val dest = File(outDir, "${index}_${sanitize(base)}")
            when (streamViaService(service, path, dest, nameMatch, targets)) {
                FrroCopy.COPIED -> copied.add(dest.absolutePath)
                FrroCopy.SKIPPED -> Unit
                FrroCopy.UNREADABLE -> {
                    // Unreadable through the descriptor: a name match is worth the root copy, anything else is dropped.
                    if (!nameMatch) return@forEachIndexed
                    if (shellCopy(service, path, dest)) copied.add(dest.absolutePath) else errors.add("frro not copied: $path")
                }
            }
        }
        return copied
    }

    private enum class FrroCopy { COPIED, SKIPPED, UNREADABLE }

    /**
     * Streams [path] through the service's descriptor into [dest]. The first [HEADER_SCAN_BYTES]
     * decide whether the file is kept ([nameMatch], or one of [targets] named in the header); the
     * rest is copied in 64 KB chunks, so a 16 MB `.frro` never sits in the heap as a whole. An
     * empty, unreadable or over-[MAX_FRRO_BYTES] file is [FrroCopy.UNREADABLE] and [dest] is removed.
     */
    private fun streamViaService(service: IThemeService, path: String, dest: File, nameMatch: Boolean, targets: List<String>): FrroCopy {
        val pfd = runCatching { service.openFile(path) }.getOrNull() ?: return FrroCopy.UNREADABLE
        return runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val header = ByteArray(HEADER_SCAN_BYTES)
                var headerLength = 0
                while (headerLength < header.size) {
                    val n = input.read(header, headerLength, header.size - headerLength)
                    if (n < 0) break
                    headerLength += n
                }
                val keep = nameMatch || targets.any { containsAscii(header, headerLength, it) }
                when {
                    headerLength == 0 -> FrroCopy.UNREADABLE
                    !keep -> FrroCopy.SKIPPED
                    else -> {
                        var total = headerLength.toLong()
                        dest.outputStream().use { out ->
                            out.write(header, 0, headerLength)
                            val chunk = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(chunk)
                                if (n < 0) break
                                total += n
                                if (total > MAX_FRRO_BYTES) error("larger than $MAX_FRRO_BYTES bytes")
                                out.write(chunk, 0, n)
                            }
                        }
                        FrroCopy.COPIED
                    }
                }
            }
        }.getOrElse {
            dest.delete()
            FrroCopy.UNREADABLE
        }
    }

    /** Root copy into our own files dir, chowned back to the app uid. */
    private fun shellCopy(service: IThemeService, src: String, dest: File): Boolean {
        if (!isShellSafe(src) || !isShellSafe(dest.absolutePath)) return false
        dest.parentFile?.mkdirs()
        val uid = Process.myUid()
        val d = dest.absolutePath
        val r = service.exec(
            "cat '$src' > '$d' && chown $uid:$uid '$d' && chmod 600 '$d' && (restorecon '$d' 2>/dev/null; true)"
        )
        return r.code() == 0 && dest.isFile && dest.length() > 0
    }

    /** True when [needle] occurs in the first [length] bytes of [bytes] (at most [HEADER_SCAN_BYTES]). */
    private fun containsAscii(bytes: ByteArray, length: Int, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        val limit = minOf(bytes.size, length, HEADER_SCAN_BYTES) - n.size
        var i = 0
        while (i <= limit) {
            var j = 0
            while (j < n.size && bytes[i + j] == n[j]) j++
            if (j == n.size) return true
            i++
        }
        return false
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun isShellSafe(path: String) =
        path.startsWith("/") && path.none { it == '\'' || it == '"' || it == '\n' || it == '\r' || it == '\u0000' }

    private fun Bundle.code() = getInt(ThemeService.KEY_CODE, -1)
    private fun Bundle.output() = getString(ThemeService.KEY_OUTPUT).orEmpty()

    companion object {
        private const val ARSC = "resources.arsc"
        private const val MAX_FRRO_BYTES = 16L * 1024 * 1024
        private const val HEADER_SCAN_BYTES = 64 * 1024
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")
        private const val DUMP_ATTEMPTS = 2
        private const val DUMP_RETRY_DELAY_MS = 750L

        /** One UI targets the scan always checks. */
        val TARGETS = listOf(
            Target("android"),
            Target("com.android.systemui"),
            Target("com.android.settings"),
            Target("com.sec.android.app.launcher"),
            Target("com.samsung.android.honeyboard"),
            Target("com.samsung.android.dialer"),
            Target("com.samsung.android.incallui"),
            Target("com.samsung.android.app.contacts"),
            Target("com.sec.android.app.myfiles"),
            Target("com.sec.android.gallery3d"),
            Target("com.sec.android.app.clockpackage"),
            Target("com.sec.android.app.popupcalculator"),
            Target("com.samsung.android.calendar"),
            Target("com.samsung.android.lool"),
            Target("com.samsung.android.app.aodservice"),
            Target("com.samsung.android.app.smartcapture"),
            Target("com.samsung.android.app.notes"),
            Target("com.samsung.android.app.reminder"),
            Target("com.samsung.android.messaging", deprecated = true),
        )

        /** `files/scan/<pkg>/resources.arsc` written by the last scan. */
        fun arscFile(context: Context, pkg: String): File =
            File(File(File(context.filesDir, "scan"), pkg), ARSC)

        /** Scan reports are named `yyyyMMdd-HHmmss.json`; other files in `files/reports/` (e.g. the apply report) are not scans. */
        private val SCAN_REPORT_NAME = Regex("""\d{8}-\d{6}\.json""")

        /** The newest scan report, or null. `apply-last.json` (the theme apply report) is deliberately excluded. */
        fun latestReport(context: Context): File? =
            File(context.filesDir, "reports").listFiles { f -> SCAN_REPORT_NAME.matches(f.name) }
                ?.maxByOrNull { it.name }
    }
}
