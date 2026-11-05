package com.hexnext.themer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hexnext.engine.compile.ApplyReport
import com.hexnext.engine.compile.TargetResult
import com.hexnext.themer.ServiceTasks
import com.hexnext.themer.device.DeviceReport
import com.hexnext.themer.device.ReportSharer
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.Capability
import java.io.File

/**
 * Diagnostics: the landing page of every error path, so the top half is plain language (your
 * phone, what to do next, restart protection) and the developer detail (Settings test overlay,
 * raw scan lines, state files) sits under "Advanced details".
 *
 * @param autoScan true when the editor asked for a scan ("Scan phone"); the scan starts as soon as
 *   no task holds the service, and [onScanStarted] clears the request only then.
 * @param editorBusy true while the editor's Apply theme or Remove theme runs on the same service:
 *   the actions here stay off until it ends.
 * @param canApplyAgain false where the editor's Apply is off too (SU mode, no access, editor busy):
 *   "Apply again" then stays off.
 * @param onApplyAgain goes back to the editor and applies the theme once more.
 * @param onBackToEditor goes straight to the editor, whatever screen opened Diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    shizuku: ShizukuState,
    accent: Int,
    autoScan: Boolean,
    editorBusy: Boolean,
    canApplyAgain: Boolean,
    onScanStarted: () -> Unit,
    onApplyAgain: () -> Unit,
    onBack: () -> Unit,
    onBackToEditor: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val ready = shizuku as? ShizukuState.Ready
    // Any task on the service, including the editor's startup safety check, which sets neither
    // state.busy nor editorBusy: an action started now would only be refused.
    val serviceTask by ServiceTasks.running.collectAsStateWithLifecycle()
    val enabled = ready != null && !state.busy && !editorBusy && serviceTask == null
    // The Settings test commits an overlay, which only a service running as uid 0 or 1000 can do:
    // not the ADB shell (uid 2000) and not SU mode (the service then runs in the app process).
    val canCommit = ready != null && ready.isPrivileged && !ready.viaSu
    val snackbar = remember { SnackbarHostState() }
    var confirmTurnOff by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    // On open, and again when an editor apply or remove ends: it rewrites the report and the guard.
    LaunchedEffect(editorBusy) { viewModel.refreshApplyReport() }
    // The request waits while a task holds the service, so it is never consumed and then dropped.
    LaunchedEffect(autoScan, state.busy, editorBusy, serviceTask) {
        if (autoScan && !state.busy && !editorBusy && serviceTask == null) {
            onScanStarted()
            viewModel.runScan()
        }
    }
    val scanFinished = state.scanFinishedId
    LaunchedEffect(scanFinished) {
        if (scanFinished == 0L) return@LaunchedEffect
        // Consumed once the snackbar is gone: consuming first would change this effect's key and
        // the key change cancels showSnackbar, which takes the snackbar down within a frame.
        val result = try {
            snackbar.showSnackbar("Scan finished", actionLabel = "Back to editor", duration = SnackbarDuration.Long)
        } finally {
            // Kept when the activity is recreated (rotation, unfold), so the new screen shows it again.
            if (activity?.isChangingConfigurations != true) viewModel.consumeScanFinished(scanFinished)
        }
        if (result == SnackbarResult.ActionPerformed) onBackToEditor()
    }

    fun share(file: File?) {
        if (file == null || !ReportSharer.share(context, file)) {
            viewModel.noteShareFailed(file)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateStartPadding(LayoutDirection.Ltr),
                end = 16.dp + padding.calculateEndPadding(LayoutDirection.Ltr),
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "phone") {
                SectionCard("Your phone") {
                    Text(connectionSummary(shizuku), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Access: ${Capability.of(shizuku).label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.busy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                listOfNotNull(state.task, state.progress).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (editorBusy) {
                        Text(
                            "Apply theme or Remove theme is running in the editor. These actions turn back on when it finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (autoScan && (state.busy || editorBusy || serviceTask != null)) {
                        Text(
                            "Your scan starts when this finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = viewModel::runScan, enabled = enabled) { Text("Scan phone") }
                        OutlinedButton(
                            onClick = { share(state.latestReportJson) },
                            enabled = state.latestReportJson != null,
                        ) { Text("Share report") }
                        TextButton(
                            onClick = { share(state.latestReportTxt) },
                            enabled = state.latestReportTxt != null,
                        ) { Text("Share summary") }
                        TextButton(onClick = { viewModel.createBetaBundle(::share) }) { Text("Share beta report") }
                    }
                }
            }
            state.applyReport?.let { report ->
                item(key = "whatToDo") {
                    WhatToDoCard(
                        report = report,
                        enabled = enabled,
                        canApplyAgain = canApplyAgain,
                        editorBusy = editorBusy,
                        onScan = viewModel::runScan,
                        onApplyAgain = onApplyAgain,
                        onShareLog = { share(File(report.logFile).takeIf { it.isFile }) },
                    )
                }
            }
            item(key = "restartProtection") {
                SectionCard("Restart protection") {
                    Text(
                        if (state.applyConfirmed) {
                            "Nothing is waiting for confirmation. After the next apply, check your phone and tap \"It works\"."
                        } else if (state.applyProtectionExpired) {
                            "The last apply was never confirmed, and its 10 minutes of restart protection are over: " +
                                "restarts no longer turn the overlays off. Tap \"It works\" to clear this."
                        } else {
                            // Only root that survives a restart can act after one (see the Apply card).
                            "The last apply is not confirmed yet. If your phone restarts twice within 10 minutes, " +
                                "Hex Next turns its overlays off, but only if your root stays after a restart. A " +
                                "temporary root such as DirtyFrag is gone after a restart, so then nothing can turn " +
                                "them off. Tap \"It works\" once everything looks right."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Off while a task runs: an apply still in progress cannot have been checked yet.
                        FilledTonalButton(
                            onClick = viewModel::confirmWorks,
                            enabled = !state.applyConfirmed && !state.busy && !editorBusy,
                        ) { Text("It works") }
                        OutlinedButton(onClick = { confirmTurnOff = true }, enabled = enabled) { Text("Turn off all Hex Next overlays") }
                    }
                }
            }
            state.report?.let { report -> item(key = "scan") { ScanCard(report) } }
            item(key = "advanced") {
                AdvancedCard(
                    state = state,
                    enabled = enabled,
                    canCommit = canCommit,
                    onRunSettingsTest = { viewModel.runSettingsTest(accent) },
                    onRepair = viewModel::repairSettings,
                    onMonitor = viewModel::refreshMonitor,
                    onShare = ::share,
                )
            }
            if (state.log.isNotEmpty()) {
                item(key = "log") { LogCard(state.log, onClear = viewModel::clearLog, title = "Live log") }
            }
        }
    }
    if (confirmTurnOff) {
        ConfirmDialog(
            title = "Turn off every Hex Next overlay?",
            text = "Your phone goes back to Samsung's look. Tap Apply theme at the bottom of Studio to put it back.",
            confirmLabel = "Turn off",
            onConfirm = {
                confirmTurnOff = false
                viewModel.removeAll()
            },
            onDismiss = { confirmTurnOff = false },
        )
    }
}

/** One sentence on the last apply and the actions that follow from it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatToDoCard(
    report: ApplyReport,
    enabled: Boolean,
    canApplyAgain: Boolean,
    editorBusy: Boolean,
    onScan: () -> Unit,
    onApplyAgain: () -> Unit,
    onShareLog: () -> Unit,
) {
    // The editor's Apply theme saves the report as "started" / "running" while it works; that is
    // only an interrupted apply once nothing is running any more.
    val inProgress = editorBusy &&
        (report.status == ApplyReport.STATUS_STARTED || report.status == ApplyReport.STATUS_RUNNING)
    val advice = when {
        inProgress -> "An apply is running now. This card updates when it finishes."
        !report.finished && report.status == ApplyReport.STATUS_ABORTED ->
            "The last apply stopped early${if (report.error.isNotBlank()) ": ${report.error}" else ""}. Apply again. If it keeps failing, share the build log with support."
        !report.finished -> "The last apply was interrupted. Apply again."
        report.failedCount > 0 ->
            "Apply failed for ${report.failedCount} ${if (report.failedCount == 1) "app" else "apps"}. Scan your phone, then apply again. If it keeps failing, share the build log with support."
        report.okCount == 0 -> "The last apply changed nothing. Scan your phone, then apply again."
        else -> "The last apply went through: ${report.okCount} ${if (report.okCount == 1) "app was" else "apps were"} themed."
    }
    val problem = !inProgress && (report.failedCount > 0 || !report.finished || report.okCount == 0)
    SectionCard("What to do") {
        Text(
            advice,
            style = MaterialTheme.typography.bodyMedium,
            color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        report.outcomes.filter { it.result == TargetResult.FAILED || it.result == TargetResult.PARTIAL }.forEach { o ->
            Text(
                "${appName(o.target)}: ${if (o.result == TargetResult.FAILED) "failed" else "partly applied"}" +
                    if (o.message.isNotBlank()) " · ${o.message}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (o.result == TargetResult.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (problem) {
                Button(onClick = onApplyAgain, enabled = enabled && canApplyAgain) { Text("Apply again") }
                OutlinedButton(onClick = onScan, enabled = enabled) { Text("Scan phone") }
            }
            if (report.logFile.isNotBlank()) {
                TextButton(onClick = onShareLog) { Text("Share build log") }
            }
        }
    }
}

/** The last scan in plain words; the raw lines sit in [AdvancedCard]. */
@Composable
private fun ScanCard(report: DeviceReport) {
    val found = report.packages.count { it.installed }
    val readable = report.packages.count { it.installed && it.arscFound }
    val removed = report.packages.count { it.debloated }
    SectionCard("Last scan") {
        Text(
            "${report.model.ifBlank { "Unknown model" }} · ${report.oneUiVersion} · Android ${report.props["ro.build.version.release"].orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            buildString {
                append("$found of ${report.packages.size} One UI apps found, $readable ready to theme")
                if (removed > 0) append(" · $removed removed from this phone and skipped")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (report.errors.isNotEmpty()) {
            Text(
                "${report.errors.size} ${if (report.errors.size == 1) "problem" else "problems"} found. Share the report for details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Everything written for the developer, folded away: the Settings test overlay and the raw scan, apply and state-file lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedCard(
    state: DiagnosticsState,
    enabled: Boolean,
    canCommit: Boolean,
    onRunSettingsTest: () -> Unit,
    onRepair: () -> Unit,
    onMonitor: () -> Unit,
    onShare: (File?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = "Advanced details",
        trailing = { TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") } },
    ) {
        Text(
            "Raw scan and apply data, the Settings test overlay and the state files. Useful when reporting a problem.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Reveal(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Settings test overlay", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Builds one overlay for Settings only, from the scan: the Settings colours Samsung's theme drives " +
                        "(up to 10 dashboard colours if the scan saved none) and up to 80 icons, in your accent colour. " +
                        "It will not run while an applied theme holds Settings: Remove theme first. A 120 s watchdog " +
                        "turns Hex Next overlays off if System UI or the system server restarts twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = onRunSettingsTest, enabled = enabled && canCommit) { Text("Run Settings test") }
                    OutlinedButton(onClick = onRepair, enabled = enabled && canCommit) { Text("Repair Settings test overlay") }
                    TextButton(onClick = onMonitor, enabled = enabled) { Text("Monitor status") }
                }
                if (enabled && !canCommit) {
                    Text(
                        "Needs root: the Settings test commits an overlay, which needs Shizuku started with root.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.lastResult?.let { r ->
                    Text(
                        "Last build: code ${r.code}, ${r.resourceCount} resources, ${r.skippedCount} skipped, ${r.millis} ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (r.lookup.isNotBlank()) Mono("lookup: ${r.lookup}")
                    TextButton(onClick = { onShare(r.logFile) }) { Text("Share build log") }
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

                state.report?.let { report ->
                    Text("Scan", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${report.overlays.size} overlays · ${report.semWtCount} Samsung theme overlays · ${report.hexCount} Hex Next · " +
                            "${report.frroCopied.size}/${report.frroPaths.size} overlay files saved",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    report.packages.forEach { p ->
                        val installed = when {
                            p.installed -> "installed"
                            p.debloated -> "removed"
                            else -> "absent"
                        }
                        val resources = if (p.arscFound) "resources read, ${p.colorCount} colours, ${p.drawableCount} drawables" else "resources not read"
                        Text(
                            "${p.pkg}${if (p.deprecated) " (deprecated)" else ""}: $installed · $resources · ${p.semWtOverlays} Samsung theme overlays",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = when {
                                p.installed && p.arscFound -> MaterialTheme.colorScheme.onSurface
                                p.debloated -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                state.applyReport?.let { report ->
                    Text("Last theme apply", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${report.themeName} · ${java.util.Date(report.finishedAt)} · ${(report.finishedAt - report.startedAt) / 1000} s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        report.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (report.failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    report.notes.forEach { Mono(it) }
                    report.outcomes.filter { it.result == TargetResult.OK }.forEach { o ->
                        Mono("${o.target}: ${o.resourceCount} resources (${o.colorCount}c/${o.drawableCount}d)${if (o.semwtDisabled) ", Samsung overlay off" else ""}")
                    }
                    val debloated = report.outcomes.filter { it.result == TargetResult.SKIPPED_DEBLOATED }
                    if (debloated.isNotEmpty()) {
                        Text(
                            "Removed from this phone, skipped: ${debloated.joinToString { it.target }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** "Settings" for `com.android.settings`, else the last segment of the package name. */
private fun appName(target: String): String = when (target) {
    "com.android.settings" -> "Settings"
    "com.android.systemui" -> "System UI"
    "android" -> "Android framework"
    else -> target.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
