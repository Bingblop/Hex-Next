package com.hexnext.themer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hexnext.engine.compile.ApplyReport
import com.hexnext.themer.IThemeService
import com.hexnext.themer.overlay.BuildResult
import com.hexnext.themer.overlay.OverlayBuilder
import com.hexnext.themer.overlay.SettingsTestOverlay
import com.hexnext.themer.device.DeviceReport
import com.hexnext.themer.device.DeviceScanner
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.LogLevel
import com.hexnext.themer.theme.LogLine
import com.hexnext.themer.theme.ThemeApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

data class DiagnosticsState(
    val busy: Boolean = false,
    val task: String? = null,
    val progress: String? = null,
    val report: DeviceReport? = null,
    val latestReportJson: File? = null,
    val lastResult: BuildResult? = null,
    val monitorStatus: String? = null,
    val safeModeFile: String? = null,
    val guard: String = "",
    /** The last compiled-theme apply ([ThemeApplier.lastReport]). */
    val applyReport: ApplyReport? = null,
    val log: List<LogLine> = emptyList(),
    /** False while the last apply (theme or Settings test) waits for "It works". */
    val applyConfirmed: Boolean = true,
    /** Non-zero once a scan finished and the screen has not yet shown its "Scan finished" notice. */
    val scanFinishedId: Long = 0L,
) {
    val latestReportTxt: File?
        get() = latestReportJson?.let { File(it.parentFile, it.nameWithoutExtension + ".txt") }?.takeIf { it.isFile }
}

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val guard = ApplyGuard(app)
    private val scanner = DeviceScanner(app)
    private val builder = OverlayBuilder(app)
    private val settingsTest = SettingsTestOverlay(app)

    private val _state = MutableStateFlow(
        DiagnosticsState(
            latestReportJson = DeviceScanner.latestReport(app),
            guard = guard.describe(), applyConfirmed = guard.confirmed,
            applyReport = ThemeApplier.lastReport(app),
        )
    )
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    /** Reloads the last [ApplyReport] (the main screen's Apply theme writes it). */
    fun refreshApplyReport() = _state.update { it.copy(applyReport = ThemeApplier.lastReport(getApplication<Application>())) }

    fun noteShareFailed(file: File?) =
        log(LogLevel.ERROR, if (file == null) "Nothing to share yet." else "Could not share ${file.path}.")

    fun runScan() = runTask("Scan") { service, ready ->
        val report = scanner.scan(service, ready.viaSu) { step -> _state.update { it.copy(progress = step) } }
        _state.update { it.copy(report = report, latestReportJson = report.jsonPath?.let(::File), scanFinishedId = System.nanoTime()) }
        log(LogLevel.OK, "Scan done: ${report.model}, ${report.oneUiVersion}, ${report.overlays.size} overlays (${report.semWtCount} Samsung theme overlays)")
        report.errors.forEach { log(LogLevel.WARN, it) }
        report.jsonPath?.let { log(LogLevel.INFO, "Report: $it") }
    }

    fun runSettingsTest(accent: Int) = runTask("Settings test overlay") { service, ready ->
        if (!ready.isPrivileged || ready.viaSu) {
            log(
                LogLevel.WARN,
                "Overlays can only be committed by a service running as uid 0 or 1000 (Shizuku started with root). " +
                    "In SU mode the service lives in the app process and the test will stop before applying anything."
            )
        }
        val result = withContext(Dispatchers.IO) {
            settingsTest.run(service, accent) { line -> log(LogLevel.INFO, line) }
        }
        _state.update { it.copy(lastResult = result) }
        when {
            result == null -> log(LogLevel.ERROR, "Settings test did not run.")
            result.ok -> log(LogLevel.OK, "Settings overlay applied: ${result.resourceCount} resources in ${result.millis} ms. Open Settings, then tap \"It works\".")
            else -> log(LogLevel.ERROR, "Settings overlay code ${result.code}: ${result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()}")
        }
        result?.let { r ->
            runCatching { JSONArray(r.skipped) }.getOrNull()?.let { skipped ->
                for (i in 0 until skipped.length()) {
                    val o = skipped.getJSONObject(i)
                    log(LogLevel.WARN, "Skipped ${o.optString("name")}: ${o.optString("reason")}")
                }
            }
        }
        refreshMonitorBlocking(service)
    }

    /** The screen showed the "Scan finished" notice for [id]. */
    fun consumeScanFinished(id: Long) = _state.update { if (it.scanFinishedId == id) it.copy(scanFinishedId = 0L) else it }

    fun confirmWorks() {
        guard.confirm()
        _state.update { it.copy(guard = guard.describe(), applyConfirmed = guard.confirmed) }
        log(LogLevel.OK, "Confirmed: restarts will no longer trigger safe mode for this apply.")
        runTask("Stop monitor", clearLog = false) { service, _ ->
            val r = withContext(Dispatchers.IO) { service.stopStabilityMonitor() }
            log(LogLevel.INFO, r.getString(ThemeService.KEY_OUTPUT).orEmpty())
            refreshMonitorBlocking(service)
        }
    }

    fun removeAll() = runTask("Turn off all Hex Next overlays") { service, _ ->
        val r = withContext(Dispatchers.IO) { service.disableHexOverlays() }
        val code = r.getInt(ThemeService.KEY_CODE, -1)
        log(if (code == 0) LogLevel.OK else LogLevel.WARN, r.getString(ThemeService.KEY_OUTPUT).orEmpty())
    }

    fun repairSettings() = runTask("Repair Settings overlay") { service, _ ->
        val result = withContext(Dispatchers.IO) {
            builder.repair(service, SettingsTestOverlay.TARGET) { line -> log(LogLevel.INFO, line) }
        }
        _state.update { it.copy(lastResult = result ?: it.lastResult) }
        when {
            result == null -> log(LogLevel.WARN, "Nothing to re-apply; run the Settings test first. To put your theme back, use Apply theme on the main screen.")
            result.ok -> log(LogLevel.OK, "Repaired: ${result.resourceCount} resources re-applied.")
            else -> log(LogLevel.ERROR, "Repair code ${result.code}: ${result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()}")
        }
    }

    fun refreshMonitor() = runTask("Monitor status", clearLog = false) { service, _ -> refreshMonitorBlocking(service) }

    private suspend fun refreshMonitorBlocking(service: IThemeService) {
        val (status, safe) = withContext(Dispatchers.IO) {
            val s = service.readStateFile("monitor_status.json")
            val m = service.readStateFile("safe_mode.json")
            s.contentOrNull() to m.contentOrNull()
        }
        _state.update { it.copy(monitorStatus = status, safeModeFile = safe, guard = guard.describe(), applyConfirmed = guard.confirmed) }
    }

    private fun android.os.Bundle.contentOrNull(): String? =
        if (getInt(ThemeService.KEY_CODE, -1) == 0) getString(ThemeService.KEY_CONTENT) else null

    private fun runTask(
        name: String,
        clearLog: Boolean = true,
        block: suspend (IThemeService, ShizukuState.Ready) -> Unit,
    ) {
        val ready = ShizukuManager.state.value as? ShizukuState.Ready ?: run {
            log(LogLevel.ERROR, "Not connected. Start Shizuku with root, or allow root access, then try again.")
            return
        }
        if (_state.value.busy) return
        _state.update {
            it.copy(busy = true, task = name, progress = null, log = if (clearLog) emptyList() else it.log)
        }
        viewModelScope.launch {
            try {
                val service = ShizukuManager.service()
                block(service, ready)
            } catch (t: Throwable) {
                log(LogLevel.ERROR, "$name failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                _state.update { it.copy(busy = false, task = null, progress = null, guard = guard.describe(), applyConfirmed = guard.confirmed) }
            }
        }
    }

    private fun log(level: LogLevel, text: String) = _state.update { it.copy(log = it.log + LogLine(level, text)) }
}
