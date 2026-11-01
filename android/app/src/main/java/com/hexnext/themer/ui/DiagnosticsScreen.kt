package com.hexnext.themer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hexnext.engine.compile.ApplyReport
import com.hexnext.engine.compile.TargetResult
import com.hexnext.themer.device.DeviceReport
import com.hexnext.themer.device.ReportSharer
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.LogLevel
import java.io.File

/** Device scan, report sharing, the first Settings overlay test and the safety controls. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    shizuku: ShizukuState,
    accent: Int,
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ready = shizuku as? ShizukuState.Ready
    val enabled = ready != null && !state.busy
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { viewModel.refreshApplyReport() }

    fun share(file: File?) {
        if (file == null || !ReportSharer.share(context, file)) {
            viewModel.noteShareFailed(file)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DiagCard("Device") {
                    val privilege = when {
                        ready == null -> "Not connected: start Shizuku (root) or grant SU."
                        ready.viaSu -> "uid ${ready.uid} via SU (service in app process; drawables unavailable)"
                        else -> "Shizuku uid ${ready.uid}${if (ready.isPrivileged) "" else " (unprivileged)"}"
                    }
                    Text(privilege, style = MaterialTheme.typography.bodyMedium)
                    if (state.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                listOfNotNull(state.task, state.progress).joinToString(" — "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = viewModel::runScan, enabled = enabled) { Text("Run scan") }
                        OutlinedButton(
                            onClick = { share(state.latestReportJson) },
                            enabled = state.latestReportJson != null,
                        ) { Text("Share report") }
                        OutlinedButton(
                            onClick = { share(state.latestReportTxt) },
                            enabled = state.latestReportTxt != null,
                        ) { Text("Share summary") }
                    }
                }
            }
            state.report?.let { report -> item { ReportCard(report) } }
            state.applyReport?.let { report ->
                item { ApplyReportCard(report, onShareLog = { share(File(report.logFile).takeIf { it.isFile }) }) }
            }
            item {
                DiagCard("Settings test overlay (One UI 9)") {
                    Text(
                        "Builds HexNext_com_android_settings from the scanned Settings resources: up to 10 " +
                            "dashboard/icon colours and 3 ic_settings* icons in your accent colour. A 120 s " +
                            "watchdog turns Hex overlays off if SystemUI or system_server restarts twice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { viewModel.runSettingsTest(accent) }, enabled = enabled) {
                            Text("Run Settings test")
                        }
                        FilledTonalButton(onClick = viewModel::confirmWorks) { Text("It works") }
                        OutlinedButton(onClick = viewModel::repairSettings, enabled = enabled) {
                            Text("Repair Settings overlay")
                        }
                        OutlinedButton(onClick = viewModel::removeAll, enabled = enabled) {
                            Text("Remove all Hex overlays")
                        }
                        TextButton(onClick = viewModel::refreshMonitor, enabled = enabled) {
                            Text("Monitor status")
                        }
                    }
                    state.lastResult?.let { r ->
                        Text(
                            "Last build: code ${r.code}, ${r.resourceCount} resources, ${r.skippedCount} skipped, ${r.millis} ms",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (r.lookup.isNotBlank()) Mono("lookup: ${r.lookup}")
                        TextButton(onClick = { share(r.logFile) }) { Text("Share build log") }
                    }
                    state.monitorStatus?.let { Mono("monitor_status.json: $it") }
                    state.safeModeFile?.let {
                        Text(
                            "safe_mode.json: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Mono("guard: ${state.guard}")
                }
            }
            if (state.log.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Live log",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearLog) { Text("Clear") }
                    }
                }
                items(state.log) { line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = when (line.level) {
                            LogLevel.OK -> MaterialTheme.colorScheme.primary
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportCard(report: DeviceReport) {
    DiagCard("Scan summary") {
        Text(
            "${report.model.ifBlank { "Unknown model" }} · ${report.oneUiVersion} · " +
                "Android ${report.props["ro.build.version.release"].orEmpty()} · uid ${report.uid}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${report.overlays.size} overlays · ${report.semWtCount} SemWT_* · ${report.hexCount} HexNext · " +
                "${report.frroCopied.size}/${report.frroPaths.size} .frro copied",
            style = MaterialTheme.typography.bodySmall,
        )
        report.packages.forEach { p ->
            val state = when {
                p.installed -> "installed"
                p.debloated -> "debloated"
                else -> "absent"
            }
            val arsc = if (p.arscFound) "arsc ✓ ${p.colorCount}c/${p.drawableCount}d" else "arsc ✗"
            Text(
                "${p.pkg}${if (p.deprecated) " (deprecated)" else ""}: $state · $arsc · ${p.semWtOverlays} SemWT",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = when {
                    p.installed && p.arscFound -> MaterialTheme.colorScheme.onSurface
                    p.debloated -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (report.errors.isNotEmpty()) {
            Text(
                "${report.errors.size} problems (see report)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ApplyReportCard(report: ApplyReport, onShareLog: () -> Unit) {
    DiagCard("Last theme apply") {
        Text(
            "${report.themeName} · ${java.util.Date(report.finishedAt)} · ${(report.finishedAt - report.startedAt) / 1000} s",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            report.summary(),
            style = MaterialTheme.typography.bodySmall,
            color = if (report.failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        report.notes.forEach { Mono(it) }
        report.outcomes.filter { it.result == TargetResult.FAILED || it.result == TargetResult.PARTIAL }.forEach { o ->
            Text(
                "${o.target}: ${o.result.name.lowercase()}${if (o.message.isNotBlank()) " — ${o.message}" else ""}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (o.result == TargetResult.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
        }
        report.outcomes.filter { it.result == TargetResult.OK }.forEach { o ->
            Mono("${o.target}: ${o.resourceCount} resources (${o.colorCount}c/${o.drawableCount}d)${if (o.semwtDisabled) ", SemWT off" else ""}")
        }
        val debloated = report.outcomes.filter { it.result == TargetResult.SKIPPED_DEBLOATED }
        if (debloated.isNotEmpty()) {
            Text(
                "Debloated (skipped): ${debloated.joinToString { it.target }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        if (report.logFile.isNotBlank()) {
            TextButton(onClick = onShareLog) { Text("Share build log") }
        }
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
