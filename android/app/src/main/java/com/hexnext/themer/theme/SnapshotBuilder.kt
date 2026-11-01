package com.hexnext.themer.theme

import android.content.Context
import com.hexnext.engine.compile.DeviceSnapshot
import com.hexnext.engine.compile.SnapshotMapper
import com.hexnext.engine.compile.TargetAvailability
import com.hexnext.engine.compile.TargetScan
import com.hexnext.engine.compile.TargetSnapshot
import com.hexnext.engine.device.LegacyOverlays
import com.hexnext.engine.device.OneUi9Facts
import com.hexnext.engine.device.OneUiProfile
import com.hexnext.engine.device.OverlayDump
import com.hexnext.engine.frro.FabricatedOverlayFile
import com.hexnext.engine.frro.FrroReader
import com.hexnext.engine.palette.ShadeScale
import com.hexnext.themer.device.DeviceReport
import com.hexnext.themer.device.DeviceScanner
import com.hexnext.themer.device.PackageStatus
import com.hexnext.themer.device.SemWtStatus
import org.json.JSONObject
import java.io.File
import com.hexnext.engine.arsc.ArscReadOptions
import com.hexnext.engine.arsc.ArscReader as EngineArscReader
import com.hexnext.engine.arsc.ResourceTable as EngineResourceTable

/**
 * Builds the engine's [DeviceSnapshot] from the last device scan: the [DeviceReport] in
 * `files/reports/`, the SemWT dumps in `files/scan/semwt/<target>.txt`, the extracted
 * `files/scan/<pkg>/resources.arsc` and, as a fallback, the copied SemWT `.frro` files.
 *
 * Targets are the scanned packages plus every target with an enabled SemWT overlay. Debloated
 * packages are kept in the snapshot, marked, so the UI can grey them and the compiler skips them.
 *
 * Memory: targets are processed one at a time. Each arsc is memory-mapped and read with
 * [ArscReadOptions.THEME] (colours, drawable/mipmap names and densities only), and each
 * [TargetScan] is turned into its [TargetSnapshot] before the next target is read, so only the
 * slim tables are kept. Reading full tables of all 19 targets at once (every string of every
 * locale, every style) exhausted the 256 MB heap on One UI 9.
 */
class SnapshotBuilder(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @property availability per target, for the UI.
     * @property warnings problems that did not stop the build (unreadable dumps, arsc …).
     */
    data class Result(
        val snapshot: DeviceSnapshot,
        val report: DeviceReport,
        val availability: Map<String, TargetAvailability>,
        val warnings: List<String>,
    )

    /** The latest saved scan report, or null when there is none or it cannot be read. */
    fun latestReport(): DeviceReport? {
        val file = DeviceScanner.latestReport(appContext) ?: return null
        return runCatching { DeviceReport.fromJson(JSONObject(file.readText()), file.absolutePath) }.getOrNull()
    }

    /** Builds the snapshot of [report] (the latest saved report by default); null without a scan. */
    fun build(report: DeviceReport? = latestReport()): Result? {
        if (report == null) return null
        val warnings = ArrayList<String>()
        val byPkg = report.packages.associateBy { it.pkg }
        val semWtByTarget = report.semWt.associateBy { it.target }
        val targets = (report.packages.map { it.pkg } + report.semWt.filter { it.found }.map { it.target }).distinct()

        val snapshots = LinkedHashMap<String, TargetSnapshot>()
        for (pkg in targets) {
            val status = byPkg[pkg]
            val semWt = semWtByTarget[pkg]
            val dump = semWt?.let { readDump(it, warnings) }
            val state = semWt?.state ?: status?.semwtState
            val installed = status?.installed
                ?: (semWt != null && semWt.found && state != null && !OneUi9Facts.isDebloated(state))
            val scan = TargetScan(
                packageName = pkg,
                installed = installed,
                debloated = status?.debloated ?: false,
                semwtState = state,
                dump = dump,
                table = status?.let { readArsc(it, warnings) },
                semwtFrro = if (dump == null || dump.entries.isEmpty()) semWt?.let { readFrro(report, it, warnings) } else null,
            )
            // Reduce now: the dump text and the scan are dropped before the next target is read.
            snapshots[pkg] = SnapshotMapper.target(scan)
        }

        val legacy = report.overlays.filter { it.enabled && LegacyOverlays.isLegacy(it.overlayId) }
        if (legacy.isNotEmpty()) {
            warnings += "The scan ran while legacy overlays were enabled (${legacy.joinToString { it.name }}); system colours may be skewed. " +
                "Re-scan after Apply/Remove theme cleaned them up."
        }

        val shades = loadSystemShades(appContext) ?: readSystemShades(appContext).also {
            warnings += "No system shades saved with the scan; using the current system palette."
        }
        if (shades.size < ShadeScale.ALL_KEYS.size) {
            warnings += "Only ${shades.size}/${ShadeScale.ALL_KEYS.size} system shades could be read."
        }
        val snapshot = DeviceSnapshot(OneUiProfile.fromProps(report.props), snapshots, shades)
        val availability = snapshot.targets.mapValues { SnapshotMapper.availability(it.value) }
        return Result(snapshot, report, availability, warnings)
    }

    private fun readDump(status: SemWtStatus, warnings: MutableList<String>): OverlayDump? {
        val path = status.dumpPath ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            OverlayDump.parseAll(file.readText()).firstOrNull { it.overlayId == status.overlayId }
        }.onFailure { warnings += "dump ${file.name}: ${it.message}" }.getOrNull()
    }

    private fun readArsc(status: PackageStatus, warnings: MutableList<String>): EngineResourceTable? {
        val path = status.arscPath ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            // Memory-mapped (no heap copy) and filtered to what the compiler consumes.
            val tables = EngineArscReader.readAll(file, ArscReadOptions.THEME)
            tables.firstOrNull { it.packageName == status.pkg } ?: tables.firstOrNull()
        }.onFailure { warnings += "arsc ${status.pkg}: ${it.javaClass.simpleName}: ${it.message}" }.getOrNull()
    }

    private fun readFrro(report: DeviceReport, status: SemWtStatus, warnings: MutableList<String>): FabricatedOverlayFile? {
        val live = status.frroPath?.substringAfterLast('/')
        val candidates = report.frroCopied.filter { it.substringAfterLast('/').contains("SemWT_${status.target}-") }
        val copy = live?.let { name -> candidates.firstOrNull { it.endsWith("_$name") } } ?: candidates.firstOrNull() ?: return null
        return runCatching { FrroReader.read(File(copy)) }
            .onFailure { warnings += "frro ${copy.substringAfterLast('/')}: ${it.message}" }
            .getOrNull()
    }

    companion object {
        private const val SHADES_FILE = "system_shades.json"

        private fun shadesFile(context: Context): File = File(File(context.filesDir, "scan"), SHADES_FILE)

        /** The 65 `android:color/system_*` colours the app currently resolves, keyed `system_accent1_500`. */
        fun readSystemShades(context: Context): Map<String, Int> {
            val res = context.resources
            val out = LinkedHashMap<String, Int>()
            for (key in ShadeScale.ALL_KEYS) {
                @Suppress("DiscouragedApi")
                val id = res.getIdentifier(key, "color", "android")
                if (id == 0) continue
                runCatching { context.getColor(id) }.getOrNull()?.let { out[key] = it }
            }
            return out
        }

        /** Saves [readSystemShades] beside the scan; called by [DeviceScanner] so inference uses scan-time colours. */
        fun saveSystemShades(context: Context): Map<String, Int> {
            val shades = readSystemShades(context)
            runCatching {
                val file = shadesFile(context)
                file.parentFile?.mkdirs()
                file.writeText(JSONObject().apply { shades.forEach { (k, v) -> put(k, v) } }.toString())
            }
            return shades
        }

        /** Shades saved by the last scan, or null. */
        fun loadSystemShades(context: Context): Map<String, Int>? = runCatching {
            val file = shadesFile(context)
            if (!file.isFile) return null
            val json = JSONObject(file.readText())
            json.keys().asSequence().associateWith { json.getInt(it) }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
