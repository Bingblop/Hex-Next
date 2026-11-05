package com.hexnext.themer.ui.studio

import com.hexnext.themer.ui.settings.AppSettingsStore
import com.hexnext.themer.ui.settings.AppSettingsContent
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.hexnext.engine.compile.ApplyReport
import com.hexnext.engine.compile.TargetOutcome
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.TargetResult
import com.hexnext.themer.ServiceTasks
import com.hexnext.themer.device.ReportSharer
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.Capability
import com.hexnext.themer.theme.LogLevel
import com.hexnext.themer.ui.ConfirmDialog
import com.hexnext.themer.ui.DiagnosticsViewModel
import com.hexnext.themer.ui.EmptyState
import com.hexnext.themer.ui.LogCard
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.Screen
import com.hexnext.themer.ui.UiState
import com.hexnext.themer.ui.restartProtectionText
import com.hexnext.engine.compile.ThemeSpec as EngineThemeSpec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Settings tab: General (the app's own appearance, behaviour, AI providers, purchases and
 * about), Themed apps (each target's result and switch, restart protection) and Tools (build
 * log, safe mode, diagnostics, device). No preview here: nothing on this tab changes the theme.
 */
@Composable
internal fun SettingsPage(
    viewModel: MainViewModel,
    ui: UiState,
    shizuku: ShizukuState,
    section: SettingsSection,
    onSection: (SettingsSection) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { AppSettingsStore.get(context) }
    Column(Modifier.fillMaxSize()) {
        SubTabs(
            SettingsSection.entries,
            section,
            { it.label },
            onSection,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                SettingsSection.GENERAL -> AppSettingsContent(
                    store = store,
                    onOpenDiagnostics = { viewModel.navigate(Screen.DIAGNOSTICS) },
                    onOpenAiProviders = { viewModel.navigate(Screen.AI_PROVIDERS) },
                    onOpenOnboarding = { viewModel.navigate(Screen.ONBOARDING) },
                    purchases = ui.entitlement,
                    canPurchase = ui.canPurchase,
                    onRestorePurchases = viewModel::restorePurchases,
                    contentPadding = PAGE_PADDING,
                )
                SettingsSection.APPS -> TargetsTab(viewModel, ui)
                SettingsSection.TOOLS -> ToolsTab(viewModel, ui, shizuku)
            }
        }
    }
}

// ---- Targets ----

private enum class TargetFilter(val label: String) {
    ALL("All"), OK("OK"), PARTIAL("Partial"), FAILED("Failed"), SKIPPED("Skipped"), OFF("Off");

    fun matches(outcome: TargetOutcome?, enabled: Boolean): Boolean = when (this) {
        ALL -> true
        OFF -> !enabled
        OK -> outcome?.result == TargetResult.OK
        PARTIAL -> outcome?.result == TargetResult.PARTIAL
        FAILED -> outcome?.result == TargetResult.FAILED
        SKIPPED -> outcome != null && outcome.result.isSkipped()
    }
}

private fun TargetResult.isSkipped(): Boolean =
    this == TargetResult.SKIPPED || this == TargetResult.SKIPPED_DEBLOATED || this == TargetResult.EMPTY

/** The one word a row's status chip carries, and its colour. */
private fun TargetResult.word(): Pair<String, StatusTone> = when (this) {
    TargetResult.OK -> "OK" to StatusTone.OK
    TargetResult.PARTIAL -> "Partial" to StatusTone.WARN
    TargetResult.FAILED -> "Failed" to StatusTone.BAD
    TargetResult.SKIPPED_DEBLOATED -> "Removed" to StatusTone.NEUTRAL
    TargetResult.SKIPPED -> "Skipped" to StatusTone.NEUTRAL
    TargetResult.EMPTY -> "Nothing to theme" to StatusTone.NEUTRAL
}

/** A readable name for a One UI package: known system parts first, then the installed app's label. */
private fun targetLabel(context: Context, pkg: String): String = when (pkg) {
    "android" -> "Android framework"
    "com.android.settings" -> "Settings"
    "com.android.systemui" -> "System UI"
    else -> runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg }
        ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetsTab(viewModel: MainViewModel, ui: UiState) {
    val context = LocalContext.current
    val report = ui.lastApply
    val scanned by viewModel.scannedTargets.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(TargetFilter.ALL) }
    val confirmWorksBusy = ui.serviceBusy

    // Packages shown: the last apply's targets with an outcome (skipped ones are recorded before
    // any build starts, so an aborted apply still has some) plus its pending targets, so the app
    // that stopped an apply keeps its row. With no report at all, the installed One UI apps of
    // the last scan keep the per-app switches reachable.
    val reported: List<String> = remember(report) {
        val applied = report?.outcomes?.map { it.target }.orEmpty()
        (applied + report?.pendingTargets.orEmpty()).distinct()
    }
    val noReportedTargets = reported.isEmpty()
    LaunchedEffect(noReportedTargets) {
        if (noReportedTargets) viewModel.loadScannedTargets()
    }
    val packages: List<String> = remember(reported, scanned) {
        reported.ifEmpty { scanned.orEmpty().distinct() }
    }
    val outcomes = remember(report) { report?.outcomes?.associateBy { it.target }.orEmpty() }
    // PackageManager lookups are binder calls, one per package: load them off the main thread
    // and show the package name until they arrive.
    val labels by produceState(emptyMap<String, String>(), packages) {
        value = withContext(Dispatchers.IO) { packages.associateWith { targetLabel(context, it) } }
    }
    val shown = packages.filter { filter.matches(outcomes[it], ui.spec.isTargetEnabled(it)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PAGE_PADDING,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.applyUnconfirmed) {
            item(key = "protection") {
                StudioCard(
                    title = "Restart protection",
                    trailing = {
                        if (ui.applyProtectionExpired) {
                            StatusChip("Window over", StatusTone.NEUTRAL)
                        } else {
                            StatusChip("On", StatusTone.WARN)
                        }
                    },
                ) {
                    Text(
                        restartProtectionText(unconfirmed = true, expired = ui.applyProtectionExpired),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = viewModel::confirmWorks,
                        enabled = !confirmWorksBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("It works") }
                }
            }
        }

        item(key = "header") {
            StudioCard(
                title = "Targets",
                eyebrow = if (report != null) "Last apply" else "Not applied yet",
                subtitle = "Apply turns off Samsung's theme overlay for each app below and puts the Hex Next one in its place.",
            ) {
                if (report != null) {
                    TargetFacts(report)
                } else {
                    Text(
                        "Apply theme builds one overlay for each app below. Switch an app off to leave it with Samsung's look.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (packages.isEmpty()) {
            item(key = "empty") {
                StudioCard(title = "No apps to show") {
                    if (scanned == null && noReportedTargets) {
                        Text("Reading the last scan…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        EmptyState(
                            icon = Icons.Filled.Search,
                            text = "Scan your phone to see which One UI apps Hex Next can theme.",
                            actionLabel = "Scan phone",
                            onAction = viewModel::openScan,
                        )
                    }
                }
            }
        } else {
            item(key = "filters") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val filters = if (report != null) TargetFilter.entries else listOf(TargetFilter.ALL, TargetFilter.OFF)
                    filters.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text(
                        "No apps match this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { "target:$it" }) { pkg ->
                TargetRow(
                    pkg = pkg,
                    label = labels[pkg] ?: pkg,
                    outcome = outcomes[pkg],
                    enabled = ui.spec.isTargetEnabled(pkg),
                    switchEnabled = !ui.serviceBusy,
                    onEnabled = { viewModel.setTargetEnabled(pkg, it) },
                    onResources = { viewModel.openResources(pkg) },
                )
            }
        }
    }
}

/** The header facts of the last apply: apps themed, colours written, Samsung overlays turned off, time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetFacts(report: ApplyReport) {
    val colours = report.outcomes.sumOf { it.colorCount }
    val seconds = ((report.finishedAt - report.startedAt).coerceAtLeast(0L) / 100L) / 10.0
    // Only targets that were built count: OK, PARTIAL (both in okCount) and FAILED. Skipped,
    // removed and EMPTY targets were never attempted, so they are not part of the denominator.
    val attempted = report.okCount + report.failedCount
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusChip(
            "${report.okCount} of $attempted themed",
            if (report.failedCount > 0) StatusTone.WARN else StatusTone.OK,
        )
        if (report.failedCount > 0) StatusChip("${report.failedCount} failed", StatusTone.BAD)
        if (report.partialCount > 0) StatusChip("${report.partialCount} partial", StatusTone.WARN)
        StatusChip("$colours colours", StatusTone.INFO)
        if (report.semwtDisabledCount > 0) {
            StatusChip(
                "${report.semwtDisabledCount} Samsung theme ${if (report.semwtDisabledCount == 1) "overlay" else "overlays"} off",
                StatusTone.NEUTRAL,
            )
        }
        if (report.finishedAt > report.startedAt) StatusChip("$seconds s", StatusTone.NEUTRAL)
    }
    if (!report.finished) {
        Text(
            "The last apply did not finish. Apply again.",
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor(StatusTone.BAD),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetRow(
    pkg: String,
    label: String,
    outcome: TargetOutcome?,
    enabled: Boolean,
    switchEnabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onResources: () -> Unit,
) {
    var expanded by rememberSaveable(pkg) { mutableStateOf(false) }
    val (word, tone) = when {
        !enabled -> "Off" to StatusTone.NEUTRAL
        outcome == null -> "Not applied yet" to StatusTone.NEUTRAL
        else -> outcome.result.word()
    }
    StudioCard(title = label) {
        Text(pkg, style = monoStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(word, tone)
            if (outcome != null && enabled) {
                if (outcome.verified == true) StatusChip("Checked", StatusTone.OK)
                if (outcome.verified == false) StatusChip("Check failed", StatusTone.WARN)
                if (outcome.semwtDisabled) StatusChip("Samsung overlay off", StatusTone.NEUTRAL)
            }
        }
        if (outcome != null && enabled) {
            val counts = buildList {
                if (outcome.colorCount > 0) add("${outcome.colorCount} colours")
                if (outcome.drawableCount > 0) add("${outcome.drawableCount} drawables")
                if (outcome.resourceCount > 0) add("${outcome.resourceCount} other values")
                if (outcome.millis > 0) add("${outcome.millis} ms")
            }
            if (counts.isNotEmpty()) {
                Text(
                    counts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (outcome.message.isNotBlank()) {
                Text(
                    outcome.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = if (expanded) "Show less" else "Show more") { expanded = !expanded },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (enabled) "Theme this app" else "Left with Samsung's look",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                enabled = switchEnabled,
                modifier = Modifier.semantics {
                    contentDescription = "Theme $label"
                    stateDescription = if (enabled) "On" else "Off"
                },
            )
        }
        TextButton(onClick = onResources, modifier = Modifier.heightIn(min = 48.dp)) { Text("Resources") }
    }
}

// ---- Tools ----

private enum class LogFilter(val label: String, val level: LogLevel?) {
    ALL("All", null), ERRORS("Errors", LogLevel.ERROR), WARNINGS("Warnings", LogLevel.WARN), OK("OK", LogLevel.OK), INFO("Info", LogLevel.INFO)
}

@Composable
internal fun ToolsTab(viewModel: MainViewModel, ui: UiState, shizuku: ShizukuState, library: Boolean = false) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val diagnostics: DiagnosticsViewModel = composeViewModel()
    val dstate by diagnostics.state.collectAsStateWithLifecycle()
    val serviceTask by ServiceTasks.running.collectAsStateWithLifecycle()
    val ready = shizuku as? ShizukuState.Ready

    var pendingImport by remember { mutableStateOf<EngineThemeSpec?>(null) }
    var pendingRestore by remember { mutableStateOf<com.hexnext.themer.theme.ThemeBackup.Contents?>(null) }
    val autoBackup by viewModel.autoBackup.collectAsStateWithLifecycle()
    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(com.hexnext.themer.theme.ThemeBackup.MIME)) { uri ->
        if (uri != null) viewModel.backUpTo(uri)
    }
    val backupOpener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.readBackup(uri) { pendingRestore = it }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setBackupFolder(uri)
    }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var logFilter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val nameCommit = remember { NameCommit() }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.exportTheme(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.readThemeFile(uri) { pendingImport = it }
    }

    // Turning everything off in Diagnostics changes the last apply and confirms the restart
    // guard; read both again when that ends so the apply bar and safe-mode banner are not stale.
    LaunchedEffect(dstate.busy) {
        if (!dstate.busy) {
            viewModel.refreshLastApply()
            viewModel.refreshGuard()
        }
    }

    // SU mode (su found, Shizuku not running) is Ready but cannot turn overlays off.
    val canControl = ready != null && !ready.viaSu && ui.capability != Capability.NONE
    val canTurnOff = canControl && !dstate.busy && !ui.serviceBusy && serviceTask == null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PAGE_PADDING,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Library: the theme file and the backups. Settings › Tools: log, safe mode, diagnostics.
        if (library) {
        item(key = "file") {
            StudioCard(
                title = "Theme file",
                subtitle = "Save this theme as a file, or open one. A file holds colours and settings, never pack contents.",
            ) {
                ThemeNameField(ui.spec.name, nameCommit) { name -> viewModel.editSpec { it.copy(name = name) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilledTonalButton(
                        onClick = {
                            val name = nameCommit.flush?.invoke() ?: ui.spec.name
                            exporter.launch("${fileNameOf(name)}.hexnext")
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Export") }
                    OutlinedButton(
                        onClick = { importer.launch(arrayOf("*/*")) },
                        enabled = !ui.serviceBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Import") }
                    TextButton(
                        onClick = {
                            nameCommit.flush?.invoke()
                            clipboard.setText(AnnotatedString(viewModel.themeJson()))
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Copy JSON") }
                }
            }
        }

        item(key = "backup") {
            StudioCard(
                title = "Backup and restore",
                subtitle = "One file with your theme, imported icons, fonts and backgrounds, app settings, and what Hex Next learned about this phone. " +
                    "AI keys and purchases are never included; packs download again.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilledTonalButton(
                        onClick = {
                            nameCommit.flush?.invoke()
                            backupSaver.launch(com.hexnext.themer.theme.ThemeBackup.fileName())
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Back up") }
                    OutlinedButton(
                        onClick = { backupOpener.launch(arrayOf("*/*")) },
                        enabled = !ui.serviceBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Restore") }
                }
                val folder = autoBackup.folderName
                Text(
                    when {
                        folder == null -> "Automatic backups are off. Choose a folder and Hex Next backs up there after every successful apply, keeping the last ${com.hexnext.themer.theme.AutoBackup.KEEP_DATED} days."
                        autoBackup.lastAt > 0 -> "Automatic backups go to \"$folder\" after every apply. Last: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(autoBackup.lastAt))}."
                        else -> "Automatic backups go to \"$folder\" after every apply."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                autoBackup.lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (folder == null) "Choose folder" else "Change folder")
                    }
                    if (folder != null) {
                        TextButton(onClick = { viewModel.backUpNow() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back up now") }
                        TextButton(onClick = { viewModel.clearBackupFolder() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Turn off") }
                    }
                }
            }
        }

        }
        if (!library) {
        item(key = "log") {
            val log by viewModel.log.collectAsStateWithLifecycle()
            val lines = remember(log, logFilter) {
                // Filter the whole log first so older matching lines (e.g. an early ERROR) are not dropped.
                val matching = if (logFilter.level == null) log else log.filter { it.level == logFilter.level }
                matching.takeLast(200)
            }
            LogCard(
                lines = lines,
                onClear = viewModel::clearLog,
                title = "Build log",
                emptyText = if (log.isEmpty()) "Nothing logged yet. Apply theme writes here." else "No lines at this level.",
                header = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        LogFilter.entries.forEach { f ->
                            FilterChip(
                                selected = logFilter == f,
                                onClick = { logFilter = f },
                                label = { Text(f.label) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    val logFile = ui.lastApply?.logFile?.takeIf { it.isNotBlank() }
                    if (logFile != null) {
                        TextButton(
                            onClick = {
                                val file = File(logFile)
                                if (!file.isFile || !ReportSharer.share(context, file)) {
                                    viewModel.noteBuildLogShareFailed()
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Share build log") }
                    }
                },
            )
        }

        item(key = "safe") {
            StudioCard(
                title = "Safe mode",
                subtitle = "If the phone looks wrong, remove the theme: overlays, font and system colours go back to Samsung's.",
            ) {
                if (dstate.busy && dstate.task != null) {
                    Text(
                        "${dstate.task}…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "${dstate.task} in progress" },
                    )
                }
                // One action: Remove theme turns every Hex Next overlay off, turns Samsung's
                // overlays back on, restores the system palette and removes the font.
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = canTurnOff,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Remove theme") }
                if (!canControl) {
                    Text(
                        if (ready?.viaSu == true) "Start Shizuku with root first. SU mode alone cannot turn overlays off." else "Connect Shizuku or root first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "diagnostics") {
            StudioCard(title = "Diagnostics") {
                StudioNavRow(
                    title = "Diagnostics",
                    summary = "Scan the phone, check Settings and share a report",
                    onClick = { viewModel.navigate(Screen.DIAGNOSTICS) },
                )
            }
        }

        item(key = "device") {
            StudioCard(title = "Device") {
                Text(ui.versionLine, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Connection: ${ui.capability.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }

    pendingImport?.let { spec ->
        ConfirmDialog(
            title = "Replace your theme?",
            text = "\"${spec.name.ifBlank { "Untitled" }}\" replaces the theme you are editing. Your phone keeps its look until you apply.",
            confirmLabel = "Replace",
            onConfirm = {
                pendingImport = null
                viewModel.importTheme(spec)
            },
            onDismiss = { pendingImport = null },
        )
    }
    pendingRestore?.let { backup ->
        val sum = backup.summary
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(sum.createdAt))
        ConfirmDialog(
            title = "Restore this backup?",
            text = buildString {
                append("\"${sum.themeName.ifBlank { "Untitled" }}\" from $date (${sum.model}) replaces the theme you are editing")
                if (sum.assets > 0) append(", and brings back ${sum.assets} imported file${if (sum.assets == 1) "" else "s"}")
                append(". ")
                if (sum.learnedMaps > 0) {
                    append(if (sum.samePhone) "What Hex Next learned about this phone comes back too. " else "It was made on another phone, so Hex Next scans this one instead of using its phone data. ")
                }
                append("Your phone keeps its look until you apply.")
            },
            confirmLabel = "Restore",
            onConfirm = {
                pendingRestore = null
                viewModel.restoreBackup(backup)
            },
            onDismiss = { pendingRestore = null },
        )
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove theme?",
            text = "Your phone goes back to Samsung's look. Your edits stay in Hex Next.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemove = false
                viewModel.removeTheme()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/**
 * The theme's name, saved half a second after the last keystroke rather than on every key.
 * A pending edit is also saved when the field leaves the screen (another tab, Back), and
 * [commitRef] lets the caller save it at once before it reads the name (Export, Copy JSON).
 */
@Composable
private fun ThemeNameField(name: String, commitRef: NameCommit, onCommit: (String) -> Unit) {
    var text by rememberSaveable(name) { mutableStateOf(name) }
    val latestName by rememberUpdatedState(name)
    val latestText by rememberUpdatedState(text)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val flush: () -> String = remember {
        {
            if (latestText != latestName) latestOnCommit(latestText)
            latestText
        }
    }
    SideEffect { commitRef.flush = flush }
    LaunchedEffect(text) {
        if (text == name) return@LaunchedEffect
        delay(500)
        onCommit(text)
    }
    DisposableEffect(Unit) {
        onDispose {
            flush()
            commitRef.flush = null
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(60) },
        label = { Text("Theme name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Saves a pending theme name edit now; returns the name to use. */
private class NameCommit {
    var flush: (() -> String)? = null
}

/** A file name from the theme name: letters, digits, spaces and dashes, never empty. */
private fun fileNameOf(name: String): String =
    name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "theme" }
