package com.hexnext.themer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.hexnext.engine.compile.ApplyReport
import com.hexnext.engine.compile.Element
import com.hexnext.engine.compile.Elements
import com.hexnext.engine.compile.LiveOption
import com.hexnext.engine.compile.PackOptions
import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.pack.Pack
import com.hexnext.engine.pack.PackOption
import com.hexnext.themer.R
import com.hexnext.themer.billing.ProductIds
import com.hexnext.themer.device.RootTools
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.BackgroundSlots
import com.hexnext.themer.theme.Capability
import com.hexnext.themer.theme.CatalogueEntry
import com.hexnext.themer.theme.CustomAssetStore
import com.hexnext.themer.theme.InstalledPack
import com.hexnext.themer.theme.LogLevel
import com.hexnext.themer.theme.LogLine
import com.hexnext.themer.theme.ThemePreviewer
import com.hexnext.themer.theme.parseHexColor
import com.hexnext.themer.theme.toHex
import com.hexnext.themer.ui.ai.AiProvidersScreen
import com.hexnext.themer.ui.ai.DescribeThemeCard
import com.hexnext.themer.ui.ai.GenerateIconSheet
import com.hexnext.themer.ui.onboarding.OnboardingScreen
import com.hexnext.themer.ui.settings.AppSettingsScreen
import com.hexnext.themer.ui.settings.AppSettingsStore
import com.hexnext.themer.ui.settings.rememberSafeUriHandler
import com.hexnext.themer.ui.studio.ResourceEditorScreen
import com.hexnext.themer.ui.studio.StudioScreen
import com.hexnext.themer.ui.studio.StudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Color as AndroidColor
import android.graphics.Typeface as AndroidTypeface

/** The 12 colour presets of the Studio's seed and colour pickers. */
internal val COLOR_PRESETS: List<Int> = listOf(
    0xFF00E5FF, 0xFF3E7BFA, 0xFF7E57C2, 0xFFEC407A, 0xFFE53935, 0xFFFF7043,
    0xFFF4B400, 0xFF10B981, 0xFF34A853, 0xFF00A6A6, 0xFF8D6E63, 0xFF78909C,
).map { it.toInt() }

/** How often the editor re-reads an armed restart-protection guard to notice its window ending. */
private const val GUARD_RECHECK_MS = 15_000L

/**
 * The app's screens: the editor plus the full-screen destinations the view model's [Screen]
 * state names. Each destination has its own back handling; a crossfade joins them unless the
 * user turned motion off in App settings.
 */
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizuku.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settingsStore = remember(context) { AppSettingsStore.get(context) }
    val reduceMotion = LocalAppSettings.current.reduceMotion
    // Each destination keeps its rememberSaveable state (scroll position, collapsed top bar,
    // open blocks) while another screen is on display, so Back lands where the user left.
    val holder = rememberSaveableStateHolder()
    // The same activity-scoped instance DiagnosticsScreen uses: a scan keeps running after Back.
    val diagnostics: DiagnosticsViewModel = composeViewModel()
    LaunchedEffect(diagnostics) {
        diagnostics.state.map { it.scanFinishedId }.distinctUntilChanged().collect { id ->
            if (id == 0L) return@collect
            // The slot lists and the preview come from the scan, wherever the user is now.
            viewModel.invalidateSlots()
            // Diagnostics shows "Scan finished" itself; elsewhere the notice is dropped so it
            // does not replay on the next visit.
            if (viewModel.screen.value != Screen.DIAGNOSTICS) diagnostics.consumeScanFinished(id)
        }
    }
    // Restart protection ends 10 minutes after an apply even if nobody taps "It works": re-read
    // the guard now and then while it is armed so the Apply card stops saying it is on.
    LaunchedEffect(ui.applyUnconfirmed, ui.applyProtectionExpired) {
        while (ui.applyUnconfirmed && !ui.applyProtectionExpired) {
            delay(GUARD_RECHECK_MS)
            viewModel.refreshGuard()
        }
    }
    // A scan that ended just as the user left Diagnostics, before its notice could show.
    LaunchedEffect(screen) {
        val id = diagnostics.state.value.scanFinishedId
        if (screen != Screen.DIAGNOSTICS && id != 0L) diagnostics.consumeScanFinished(id)
    }

    val content: @Composable (Screen) -> Unit = { target ->
        holder.SaveableStateProvider(target) {
            ScreenContent(target, viewModel, ui, shizuku, settingsStore)
        }
    }
    if (reduceMotion) {
        content(screen)
    } else {
        Crossfade(targetState = screen, label = "screen") { target -> content(target) }
    }
}

@Composable
private fun ScreenContent(target: Screen, viewModel: MainViewModel, ui: UiState, shizuku: ShizukuState, settingsStore: AppSettingsStore) {
    // The setup guide keeps the app theme and layout it always had; every other screen gets the
    // console look (Studio v2 phase 2 § 3).
    if (target == Screen.ONBOARDING) {
        OnboardingScreen(
            shizuku = shizuku,
            versionLine = ui.versionLine,
            rootToolLinks = ui.rootToolLinks,
            packs = ui.packs,
            selectedPackId = ui.spec.simplePackId,
            onSelectPack = viewModel::selectPack,
            onImportPack = viewModel::importPack,
            isPackLocked = { id -> !ui.packLooksUnlocked(id) },
            onRequestPermission = viewModel::requestPermission,
            onFinish = viewModel::finishOnboarding,
        )
        return
    }
    StudioTheme {
        // An opaque page behind each screen, so a crossfade never shows one screen through another.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ConsoleScreenContent(target, viewModel, ui, shizuku, settingsStore)
        }
    }
}

@Composable
private fun ConsoleScreenContent(target: Screen, viewModel: MainViewModel, ui: UiState, shizuku: ShizukuState, settingsStore: AppSettingsStore) {
    when (target) {
        Screen.MAIN -> StudioScreen(viewModel, ui, shizuku)
        Screen.RESOURCES -> ResourceEditorScreen(viewModel, ui)
        Screen.SETTINGS -> AppSettingsScreen(
            store = settingsStore,
            onBack = viewModel::navigateBack,
            onOpenDiagnostics = { viewModel.navigate(Screen.DIAGNOSTICS) },
            onOpenAiProviders = { viewModel.navigate(Screen.AI_PROVIDERS) },
            onOpenOnboarding = { viewModel.navigate(Screen.ONBOARDING) },
            purchases = ui.entitlement,
            canPurchase = ui.canPurchase,
            onRestorePurchases = viewModel::restorePurchases,
        )
        Screen.DIAGNOSTICS -> DiagnosticsScreen(
            shizuku = shizuku,
            accent = ui.seed,
            autoScan = ui.scanRequested,
            // Only Apply theme or Remove theme shares the service; a pack download or import does not.
            editorBusy = ui.serviceBusy,
            // As the editor's Apply card: SU mode and no access cannot apply, and applyTheme ignores a busy editor.
            canApplyAgain = (shizuku as? ShizukuState.Ready)?.let { !it.viaSu } == true &&
                ui.capability != Capability.NONE && !ui.busy,
            onScanStarted = viewModel::consumeScanRequest,
            onApplyAgain = {
                viewModel.navigate(Screen.MAIN)
                // As on Back: a scan may have happened, and the slot lists come from it.
                viewModel.invalidateSlots()
                viewModel.applyTheme()
            },
            onBack = {
                viewModel.navigateBack()
                // A scan may have happened: the slot lists come from it.
                viewModel.invalidateSlots()
            },
            onBackToEditor = {
                viewModel.navigate(Screen.MAIN)
                viewModel.invalidateSlots()
            },
        )
        Screen.AI_PROVIDERS -> AiProvidersScreen(onBack = viewModel::navigateBack)
        // Handled by ScreenContent; never reached.
        Screen.ONBOARDING -> Unit
    }
}

/**
 * "Root · One UI 8.0": the pill next to the title. The connection state comes first because it is
 * the part that changes; the version keeps only its One UI half, the Connection card has the rest.
 */
internal fun statusPillText(versionLine: String, state: ShizukuState): String {
    val connection = when (state) {
        is ShizukuState.Ready -> when {
            // su was found but Shizuku is not running: the service cannot commit overlays.
            state.viaSu -> "SU only"
            state.isRoot -> "Root"
            state.isSystemUid1000 -> "System"
            Capability.fromUid(state.uid) == Capability.SHELL -> "System palette"
            else -> "No root"
        }
        ShizukuState.PermissionNeeded, ShizukuState.PermissionDenied -> "Shizuku: allow"
        ShizukuState.TooOld -> "Shizuku: update"
        ShizukuState.NotRunning -> "Not connected"
    }
    val oneUi = versionLine.substringAfter(" · ", versionLine)
    return "$connection · $oneUi"
}

@Composable
internal fun StatusPill(text: String, ok: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
internal fun SafeModeBanner(text: String, failed: Boolean, onOpenDiagnostics: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.titleSmall)
            Text(
                if (failed) {
                    "Your phone restarted twice within 10 minutes of an apply that was not confirmed, but Hex Next " +
                        "could not turn its overlays off. Open Diagnostics and tap \"Turn off all Hex Next overlays\"."
                } else {
                    "Your phone restarted twice within 10 minutes of an apply that was not confirmed, so Hex Next turned " +
                        "its overlays off. When your phone is stable, tap Apply theme again, then \"It works\". " +
                        "Diagnostics has the details."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/**
 * Shows [content] with a size animation when it appears or goes, or plainly when the user
 * turned motion off. For toggled blocks inside cards (options, steps, pickers).
 */
@Composable
internal fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    if (LocalAppSettings.current.reduceMotion) {
        if (visible) content()
    } else {
        AnimatedVisibility(visible = visible) { content() }
    }
}

/**
 * An empty section: a muted icon, one sentence and, when there is something to do about it, a
 * tonal button. Shared by the editor cards and the setup guide.
 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    text: String,
    actionLabel: String? = null,
    enabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
        }
    }
}

/**
 * The one confirmation dialog for destructive actions: [title] as a question, [text] on the
 * consequence, an error-coloured [confirmLabel] and Cancel.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Opens the Shizuku app, or its download page when it is not installed. */
internal fun openShizuku(context: Context, uriHandler: UriHandler) {
    val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    if (intent != null) {
        runCatching { context.startActivity(intent) }.onSuccess { return }
    }
    uriHandler.openUri(SHIZUKU_DOWNLOAD_URL)
}

internal const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
internal const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

/**
 * The shared card: title (with an optional [trailing] control at its right) over [content]. A
 * 1 dp outline on the card colour, no elevation (§ 3.2); 16 dp corners under the console shapes.
 */
@Composable
internal fun SectionCard(
    title: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val reduceMotion = LocalAppSettings.current.reduceMotion
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .then(if (reduceMotion) Modifier else Modifier.animateContentSize())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (trailing == null) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    trailing()
                }
            }
            content()
        }
    }
}

/**
 * The Connection card: the access tier ("Full (root)", "System palette only (Shizuku)", "Not
 * connected") as a pill next to the title, one sentence on the state, the version line, the
 * action for the state and, without root, the root guide.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusCard(
    state: ShizukuState,
    capability: Capability,
    versionLine: String,
    rootToolLinks: Map<String, String>,
    onRequestPermission: () -> Unit,
) {
    // Never the raw handler: it throws out of the click when no browser is enabled.
    val uriHandler = rememberSafeUriHandler()
    val context = LocalContext.current
    SectionCard(
        title = "Connection",
        trailing = { TierPill(capability) },
    ) {
        Text(connectionSummary(state), style = MaterialTheme.typography.bodyMedium)
        Text(
            versionLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (capability == Capability.SHELL) {
            Text(
                Capability.NEEDS_ROOT_REASON,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            ShizukuState.NotRunning, ShizukuState.TooOld ->
                OutlinedButton(onClick = { uriHandler.openUri(SHIZUKU_DOWNLOAD_URL) }) {
                    Text("Get Shizuku")
                }
            ShizukuState.PermissionNeeded -> Button(onClick = onRequestPermission) { Text("Allow access") }
            ShizukuState.PermissionDenied -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onRequestPermission) { Text("Try again") }
                OutlinedButton(onClick = { openShizuku(context, uriHandler) }) { Text("Open Shizuku") }
            }
            is ShizukuState.Ready -> if (state.viaSu) {
                OutlinedButton(onClick = { uriHandler.openUri(SHIZUKU_DOWNLOAD_URL) }) { Text("Get Shizuku") }
            } else if (!state.isPrivileged) {
                OutlinedButton(onClick = { openShizuku(context, uriHandler) }) { Text("Open Shizuku") }
            }
        }
        val needsRoot = when (state) {
            ShizukuState.NotRunning, ShizukuState.PermissionNeeded, ShizukuState.PermissionDenied -> true
            is ShizukuState.Ready -> !state.isPrivileged
            ShizukuState.TooOld -> false
        }
        if (needsRoot) {
            RootGuide(rootToolLinks)
        }
    }
}

/** One sentence on the connection [state], in the words of the Connection card and the setup guide. */
internal fun connectionSummary(state: ShizukuState): String = when (state) {
    ShizukuState.NotRunning ->
        "Hex Next needs root access, or Shizuku running with root, to change your phone's theme. Theme Park is not required."
    ShizukuState.TooOld -> "This version of Shizuku is too old. Update it to version 11 or later."
    ShizukuState.PermissionNeeded -> "Allow Hex Next to use Shizuku to continue."
    ShizukuState.PermissionDenied -> "Shizuku access was denied. Open Shizuku and allow Hex Next under Authorised apps."
    is ShizukuState.Ready -> when {
        state.viaSu ->
            "Root (su) was found, but Shizuku is not running. Applying a theme needs Hex Next's theme service, " +
                "which Shizuku starts: start Shizuku with root, then come back."
        state.isSystemUid1000 ->
            "Connected with system access. Apply builds one overlay for each scanned One UI app and takes over from Samsung's built-in theme overlays."
        state.isRoot ->
            "Connected with root access. Apply builds one overlay for each scanned One UI app and takes over from Samsung's built-in theme overlays."
        Capability.fromUid(state.uid) == Capability.SHELL ->
            "Connected through Shizuku without root. Your accent colour applies as the system palette only. " +
                "For per-app colours, icons, backgrounds and fonts, start Shizuku with root."
        else ->
            "Connected, but without root. Start Shizuku with root, or restart it if it came up without root after your phone restarted."
    }
}

/** The access tier of the Connection card: tonal for a usable connection, error colours while disconnected. */
@Composable
internal fun TierPill(capability: Capability) {
    val ok = capability != Capability.NONE
    Text(
        capability.label,
        style = MaterialTheme.typography.labelMedium,
        color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** "Needs root" at the right of a card title whose feature the colours-only tier cannot apply. */
@Composable
internal fun NeedsRootBadge() {
    Text(
        "Needs root",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The one-line reason under a "Needs root" badge. */
@Composable
internal fun NeedsRootReason(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
}

/**
 * The "No root yet?" block of [StatusCard] and of the setup guide: how to get Samsung-compatible
 * temporary root with Diabl0w's DFRoot and KernelSU forks. Title, body and the two APK download
 * buttons always show; the four steps with their screenshots unfold behind "What is this?"
 * (or right away with [startExpanded]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RootGuide(links: Map<String, String>, startExpanded: Boolean = false) {
    // Never the raw handler: it throws out of the click when no browser is enabled.
    val uriHandler = rememberSafeUriHandler()
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    val emphasis = SpanStyle(fontWeight = FontWeight.SemiBold)
    val body = buildAnnotatedString {
        append("Hex Next needs root. On Samsung, Diabl0w's DFRoot and KernelSU forks are ")
        withStyle(emphasis) { append("the only compatible option") }
        append(". They are built for Samsung and ")
        withStyle(emphasis) { append("work on most phones running Android 15 to 17") }
        append(
            ". It is a temporary, system-less root that clears when the phone restarts. It does not trip Knox or " +
                "void your warranty, and banking apps keep working. Your theme stays after a restart.",
        )
    }
    val downloads: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RootTools.ALL.forEach { tool ->
                OutlinedButton(onClick = { uriHandler.openUri(links[tool.name] ?: tool.pinnedApk) }) {
                    Text("Download ${tool.name}")
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No root yet? Four easy steps", style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall)
        if (!expanded) {
            downloads()
        }
        Reveal(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Download and install both APKs.", style = MaterialTheme.typography.bodyMedium)
                downloads()
                Text("2. Open DFRoot. Above the Root button, tap and select KernelSU.", style = MaterialTheme.typography.bodyMedium)
                Image(
                    painter = painterResource(R.drawable.hx_guide_dfroot),
                    contentDescription = "DFRoot: Select a SU Manager, KernelSU",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
                )
                Text("3. Press Root and wait for success. KernelSU opens.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "4. In KernelSU, open the Superuser tab at the bottom, find Hex Next, tap it and turn on Superuser. Done.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Image(
                    painter = painterResource(R.drawable.hx_guide_kernelsu),
                    contentDescription = "KernelSU app profile for Hex Next with Superuser on",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
                )
            }
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide steps" else "What is this?") }
    }
}

/**
 * One colour choice: a 36 dp circle inside a 48 dp touch target, announced as a radio button
 * named by its hex value. Shared with App settings' seed swatches.
 */
@Composable
internal fun ColorSwatch(color: Int, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val label = color.toHex()
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Colour $label" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(color))
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                ),
        )
    }
}

/** A `#RRGGBB` field: [onSeed] gets every valid colour typed; an outside change of [seed] rewrites the text. */
@Composable
internal fun HexField(seed: Int, onSeed: (Int) -> Unit, modifier: Modifier = Modifier, label: String = "Hex") {
    var text by remember { mutableStateOf(seed.toHex()) }
    LaunchedEffect(seed) { if (parseHexColor(text) != seed) text = seed.toHex() }
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            parseHexColor(value)?.let(onSeed)
        },
        label = { Text(label) },
        singleLine = true,
        isError = parseHexColor(text) == null,
        supportingText = { if (parseHexColor(text) == null) Text("Enter a colour like #3E7BFA") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Pack choice for the next apply. Simple mode: colours only or one installed pack, with the live
 * options of the selected pack under its row. Advanced mode: the default pack plus a pack per
 * part of One UI. Import and catalogue in both.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PackCard(
    packs: List<InstalledPack>,
    errors: Map<String, String>,
    spec: ThemeSpec,
    busy: Boolean,
    catalogue: CatalogueState,
    locks: PackLocks,
    onUnlock: (String) -> Unit,
    onSelect: (String?) -> Unit,
    onMode: (ThemeMode) -> Unit,
    onOption: (Pack, LiveOption, String, String?) -> Unit,
    onElementPack: (String, String?) -> Unit,
    onElementEnabled: (String, Boolean) -> Unit,
    onImport: (Uri) -> Unit,
    onDelete: (String) -> Unit,
    onCatalogueUrl: (String) -> Unit,
    onLoadCatalogue: () -> Unit,
    onDownload: (CatalogueEntry) -> Unit,
    /** A catalogue skin that is not on the phone yet was picked: download it (preview only while locked) and select it. */
    onPreviewCatalogue: ((CatalogueEntry) -> Unit)? = null,
) {
    var showCatalogue by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val advanced = spec.mode == ThemeMode.ADVANCED
    // Client-side name search; the selected pack always stays listed so its options stay reachable.
    val needle = query.trim()
    val shownPacks = remember(packs, needle, spec.simplePackId) {
        if (needle.isEmpty()) packs else packs.filter { it.name.contains(needle, ignoreCase = true) || it.id == spec.simplePackId }
    }
    // Catalogue skins not on the phone yet, locked ones included: every skin can be previewed.
    val notDownloaded = remember(catalogue.entries, packs, needle) {
        val onPhone = packs.mapTo(HashSet()) { it.id }
        catalogue.entries.filter { it.id !in onPhone && (needle.isEmpty() || it.name.contains(needle, ignoreCase = true)) }
    }
    val skinCount = packs.size + catalogue.entries.count { e -> packs.none { it.id == e.id } }
    SectionCard(
        title = "Theme pack",
        trailing = {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !advanced,
                    onClick = { onMode(ThemeMode.SIMPLE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = !busy,
                    label = { Text("Simple") },
                )
                SegmentedButton(
                    selected = advanced,
                    onClick = { onMode(ThemeMode.ADVANCED) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = !busy,
                    label = { Text("Advanced") },
                )
            }
        },
    ) {
        Text(
            "Simple uses one pack for everything. Advanced picks a pack for each part of One UI.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (packs.isEmpty() && (onPreviewCatalogue == null || catalogue.entries.isEmpty())) {
            EmptyState(
                icon = Icons.Outlined.Star,
                text = "No theme packs yet. Your accent colour still applies without one.",
                actionLabel = "Get packs",
                enabled = !busy,
                onAction = { showCatalogue = true },
            )
        }
        if (advanced) {
            Text("Default pack", style = MaterialTheme.typography.labelLarge)
        }
        if (skinCount > 0) {
            Text(
                if (skinCount == 1) "1 skin" else "$skinCount skins",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (skinCount > 1) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search packs") },
                label = { Text("Search packs") },
            )
        }
        PackRow(selected = spec.simplePackId == null, enabled = !busy, onClick = { onSelect(null) }) {
            Column(Modifier.weight(1f)) {
                Text("Colours only", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your colours with Samsung's own icons and images",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (skinCount > 0 && shownPacks.isEmpty() && notDownloaded.isEmpty()) {
            Text(
                "No pack name matches \"$needle\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        shownPacks.forEach { pack ->
            val selected = spec.simplePackId == pack.id
            val locked = locks.locked(pack.id)
            // A locked skin can be selected and previewed; Apply asks for the unlock.
            PackRow(selected = selected, enabled = !busy, onClick = { onSelect(pack.id) }) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaletteDots(pack)
                        Text(
                            pack.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    PackBadges(pack, locked = locked, pending = locks.pending(pack.id), price = locks.price(pack.id))
                    if (locked && selected) {
                        Text(
                            "Preview only. Unlock to apply.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (locked) {
                        if (locks.canPurchase) {
                            UnlockButton(
                                name = pack.name,
                                price = locks.price(pack.id),
                                pending = locks.pending(pack.id),
                                enabled = !busy,
                                onClick = { onUnlock(ProductIds.forPack(pack.id)) },
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { confirmDelete = pack.id },
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "Remove ${pack.name}" },
                ) { Text("Remove") }
            }
            Reveal(visible = selected && !advanced) {
                val options = remember(pack.pack) { PackOptions.live(pack.pack) }
                OptionList(
                    pack = pack.pack,
                    options = options,
                    spec = spec,
                    elementId = null,
                    enabled = !busy,
                    onOption = onOption,
                )
            }
        }
        if (onPreviewCatalogue != null && notDownloaded.isNotEmpty() && !advanced) {
            Text("More skins", style = MaterialTheme.typography.labelLarge)
            notDownloaded.forEach { entry ->
                CatalogueSkinRow(
                    entry = entry,
                    progress = catalogue.downloading[entry.id],
                    busy = busy,
                    locks = locks,
                    onUnlock = onUnlock,
                    onPreview = onPreviewCatalogue,
                )
            }
        }
        if (advanced) {
            val parts = remember(packs) { coveredParts(packs) }
            if (parts.isNotEmpty()) {
                Text("Parts", style = MaterialTheme.typography.labelLarge)
            }
            parts.forEach { (element, covering) ->
                ElementRow(
                    element = element,
                    covering = covering,
                    packs = packs,
                    spec = spec,
                    busy = busy,
                    locks = locks,
                    onOption = onOption,
                    onElementPack = onElementPack,
                    onElementEnabled = onElementEnabled,
                )
            }
        }
        errors.forEach { (file, why) ->
            Text("$file: $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        UnlockAllRow(locks = locks, busy = busy, onUnlock = onUnlock)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !busy,
            ) { Text("Import pack file") }
            // With no packs the empty state above already carries "Get packs".
            if (packs.isNotEmpty()) {
                OutlinedButton(onClick = { showCatalogue = true }, enabled = !busy) { Text("Get packs") }
            }
        }
    }
    confirmDelete?.let { id ->
        val name = packs.firstOrNull { it.id == id }?.name ?: id
        ConfirmDialog(
            title = "Remove $name?",
            text = "The pack file is deleted from this phone. Parts that use it go back to colours only.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmDelete = null
                onDelete(id)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    if (showCatalogue) {
        CatalogueDialog(
            state = catalogue,
            busy = busy,
            locks = locks,
            onUnlock = onUnlock,
            onUrl = onCatalogueUrl,
            onLoad = onLoadCatalogue,
            onDownload = onDownload,
            onDismiss = { showCatalogue = false },
        )
    }
}

/** The indent of blocks nested under a radio row: the 48 dp button plus the row's 8 dp gap. */
internal val RADIO_INDENT = 56.dp

/**
 * One radio row of [PackCard]; [content] fills the space right of the button. The row is the one
 * selectable node (named by its text, with its selected state); the button only draws.
 */
@Composable
internal fun PackRow(selected: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        content()
    }
}

/**
 * Subtitle of a pack row: drawables · Settings icons · day and night. Only what the pack holds:
 * never an author, version or source name, which can tie a pack to where its artwork came from.
 */
private fun packSummary(pack: InstalledPack): String = listOfNotNull(
    "${pack.drawableCount} images",
    if (pack.hasSettingsIcons) "Settings icons" else null,
    if (pack.dayNight) "Day and night" else null,
).joinToString(" · ")

/** The first three colours of the pack's own palette, as 14 dp dots; nothing when it has none. */
@Composable
private fun PaletteDots(pack: InstalledPack) {
    val colors = remember(pack.pack) { pack.pack.paletteArgb.values.take(3).map { it or 0xFF000000.toInt() } }
    if (colors.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics { contentDescription = "Pack colours ${colors.joinToString { it.toHex() }}" },
    ) {
        colors.forEach { argb ->
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}

/** Badges of a pack row: lock state, day and night, drawables, Settings icons. Each one a word. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackBadges(pack: InstalledPack, locked: Boolean, pending: Boolean, price: String? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (locked) {
            LockedLabel(pending = pending, price = price)
        }
        if (pack.dayNight) PackBadge("Day and night")
        coverageLabels(pack.pack.layers.map { it.category }).forEach { PackBadge(it) }
        PackBadge("${pack.drawableCount} images")
        if (pack.hasSettingsIcons) PackBadge("Settings icons")
    }
}

@Composable
private fun PackBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A switch row written like [PackRow]: label left, [Switch] right, the whole row one toggleable node. */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * The live [options] of [pack], indented under the row they belong to: a select as a label and
 * one radio row per choice, a switch as a [SwitchRow]. Writes go to every element the option
 * gates when [elementId] is null (simple mode), else to that element only. Nothing is shown
 * when there are no options.
 */
@Composable
private fun OptionList(
    pack: Pack,
    options: List<LiveOption>,
    spec: ThemeSpec,
    elementId: String?,
    enabled: Boolean,
    onOption: (Pack, LiveOption, String, String?) -> Unit,
) {
    if (options.isEmpty()) return
    Column(Modifier.padding(start = RADIO_INDENT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { live ->
            val current = PackOptions.value(spec, pack, live.option, elementId)
            if (live.option.type == PackOption.SELECT) {
                Text(live.option.label, style = MaterialTheme.typography.labelLarge)
                live.option.keys.forEachIndexed { index, key ->
                    PackRow(selected = current == key, enabled = enabled, onClick = { onOption(pack, live, key, elementId) }) {
                        Text(
                            live.option.choices.getOrNull(index)?.takeIf { it.isNotBlank() } ?: key,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            } else {
                SwitchRow(
                    label = live.option.label,
                    checked = current == "true",
                    enabled = enabled,
                    onCheckedChange = { on -> onOption(pack, live, if (on) "true" else "false", elementId) },
                )
            }
        }
    }
}

/** The parts of One UI, in registry order, that at least one installed pack covers, with those packs. */
private fun coveredParts(packs: List<InstalledPack>): List<Pair<Element, List<InstalledPack>>> {
    val coverage = packs.associateWith { installed -> PackOptions.coveredElements(installed.pack).mapTo(HashSet()) { it.id } }
    return Elements.ALL.mapNotNull { element ->
        val covering = packs.filter { element.id in coverage.getValue(it) }
        if (covering.isEmpty()) null else element to covering
    }
}

/**
 * One part in advanced mode: enable switch, label and effective pack, "Change" opening
 * [ElementPackDialog]; the live options of the effective pack for this part underneath.
 *
 * "Colours only" for a part is the part switched off ([ThemeSpec.elementEnabled]): the compiler
 * then takes no drawables for it from any pack, while colours still apply.
 */
@Composable
private fun ElementRow(
    element: Element,
    covering: List<InstalledPack>,
    packs: List<InstalledPack>,
    spec: ThemeSpec,
    busy: Boolean,
    locks: PackLocks,
    onOption: (Pack, LiveOption, String, String?) -> Unit,
    onElementPack: (String, String?) -> Unit,
    onElementEnabled: (String, Boolean) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val enabled = spec.isElementEnabled(element.id)
    val setId = spec.elementPacks[element.id]
    val effective = if (enabled) packs.firstOrNull { it.id == (setId ?: spec.simplePackId) } else null
    val subtitle = when {
        !enabled -> "Colours only"
        // A pack that is not installed yields no drawables, so "Colours only" is the effective state.
        setId != null -> effective?.name ?: "Colours only"
        else -> "Default pack · " + (effective?.name ?: "Colours only")
    }
    // One toggleable node named by the part, like SwitchRow; "Change" is the only other stop.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = enabled, enabled = !busy, role = Role.Switch, onValueChange = { on -> onElementEnabled(element.id, on) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(element.label, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(
            onClick = { showDialog = true },
            enabled = !busy,
            modifier = Modifier.semantics { contentDescription = "Change pack for ${element.label}" },
        ) { Text("Change") }
        Switch(checked = enabled, onCheckedChange = null, enabled = !busy)
    }
    if (effective != null) {
        val options = remember(effective.pack, element.id) {
            PackOptions.live(effective.pack).filter { element.id in it.elementIds }
        }
        OptionList(
            pack = effective.pack,
            options = options,
            spec = spec,
            elementId = element.id,
            enabled = !busy,
            onOption = onOption,
        )
    }
    if (showDialog) {
        ElementPackDialog(
            element = element,
            covering = covering,
            enabled = enabled,
            setId = setId,
            busy = busy,
            locks = locks,
            onElementPack = onElementPack,
            onElementEnabled = onElementEnabled,
            onDismiss = { showDialog = false },
        )
    }
}

/** Pack picker of one part: same as the default pack, colours only, or a pack covering the part. */
@Composable
private fun ElementPackDialog(
    element: Element,
    covering: List<InstalledPack>,
    enabled: Boolean,
    setId: String?,
    busy: Boolean,
    locks: PackLocks,
    onElementPack: (String, String?) -> Unit,
    onElementEnabled: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(element.label) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    PackRow(selected = enabled && setId == null, enabled = !busy, onClick = { onElementPack(element.id, null) }) {
                        Text("Same as default pack", style = MaterialTheme.typography.titleSmall)
                    }
                }
                item {
                    PackRow(selected = !enabled, enabled = !busy, onClick = { onElementEnabled(element.id, false) }) {
                        Text("Colours only", style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(covering, key = { it.id }) { pack ->
                    val locked = locks.locked(pack.id)
                    // A locked pack can be chosen for the preview; Apply asks for the unlock.
                    PackRow(selected = enabled && setId == pack.id, enabled = !busy, onClick = { onElementPack(element.id, pack.id) }) {
                        Column(Modifier.weight(1f)) {
                            Text(pack.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                packSummary(pack),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (locked) LockedLabel(pending = locks.pending(pack.id), price = locks.price(pack.id))
                        }
                    }
                }
            }
        },
    )
}

// ---- Custom assets ----

private val IMAGE_MIME_TYPES = arrayOf("image/*", "image/x-icon", "image/vnd.microsoft.icon", "application/octet-stream")
private val FONT_MIME_TYPES = arrayOf(
    "font/*", "application/x-font-ttf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream",
)

/** "1024×1024 · 48 KB" for images, "48 KB" for fonts. */
private fun assetSummary(asset: CustomAssetStore.Asset): String = listOfNotNull(
    if (asset.width > 0 && asset.height > 0) "${asset.width}×${asset.height}" else null,
    "${(asset.bytes + 1023) / 1024} KB",
).joinToString(" · ")

/** The slot labels an asset [file] is assigned to, from [assignments] (`slot key → file`), joined with " · ". */
private fun assignedSummary(file: String, assignments: Map<String, String>, labels: Map<String, String>): String? {
    val keys = assignments.filterValues { it == file }.keys
    if (keys.isEmpty()) return null
    return keys.joinToString(" · ") { key -> labels[key] ?: key.substringAfter('/') }
}

/** Decodes [file] scaled down to about [targetPx] on its longer side, off the main thread. */
@Composable
private fun rememberThumbnail(file: File, targetPx: Int = 192): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, key1 = file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}

/**
 * A font family from a `.ttf` / `.otf` file, or null while it loads or when it cannot be loaded
 * (the text then falls back to the default). The file is parsed off the main thread and kept in
 * [FontFamilyCache], so list items that scroll back into view do not parse it again.
 */
@Composable
internal fun rememberFontFamily(path: String?): FontFamily? {
    val state = produceState(initialValue = path?.let(FontFamilyCache::peek), key1 = path) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) { FontFamilyCache.load(File(p)) }
        }
    }
    return state.value
}

/** The last few font families [rememberFontFamily] loaded, by path; a changed file is parsed again. */
private object FontFamilyCache {
    private const val MAX_ENTRIES = 8

    private class CachedFont(val stamp: String, val family: FontFamily)

    private val entries = object : LinkedHashMap<String, CachedFont>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedFont>?): Boolean = size > MAX_ENTRIES
    }

    /** The cached family for [path] without touching the file; [load] checks it is still current. */
    @Synchronized
    fun peek(path: String): FontFamily? = entries[path]?.family

    /** Parses [file] unless the cached copy matches its size and modification time. Call off the main thread. */
    fun load(file: File): FontFamily? {
        val stamp = "${file.length()}:${file.lastModified()}"
        synchronized(this) { entries[file.path]?.takeIf { it.stamp == stamp }?.let { return it.family } }
        val family = runCatching { FontFamily(Typeface(AndroidTypeface.createFromFile(file))) }.getOrNull()
        synchronized(this) {
            if (family != null) entries[file.path] = CachedFont(stamp, family) else entries.remove(file.path)
        }
        return family
    }
}

/**
 * One imported image: 48 dp thumbnail beside name, size and the slots it fills; the actions on
 * their own line underneath, so the text keeps the width on a phone.
 */
@Composable
private fun AssetRow(
    asset: CustomAssetStore.Asset,
    file: File,
    assigned: String?,
    busy: Boolean,
    assignLabel: String,
    onAssign: () -> Unit,
    onDelete: () -> Unit,
    /** False while the tier cannot apply this kind of asset ("Needs root"); Remove stays available. */
    assignEnabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val thumbnail = rememberThumbnail(file)
            if (thumbnail != null) {
                Image(thumbnail, contentDescription = null, modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small))
            } else {
                Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest))
            }
            Column(Modifier.weight(1f)) {
                Text(asset.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(assetSummary(asset), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (assigned != null) {
                    Text(assigned, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.padding(start = 60.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = onAssign,
                enabled = !busy && assignEnabled,
                modifier = Modifier.semantics { contentDescription = "${assignLabel.trimEnd('…')} ${asset.displayName}" },
            ) { Text(assignLabel) }
            TextButton(
                onClick = onDelete,
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Remove ${asset.displayName}" },
            ) { Text("Remove") }
        }
    }
}

/** The "Remove <asset>?" confirmation of the icon, background and font cards. */
@Composable
private fun ConfirmAssetDelete(asset: CustomAssetStore.Asset, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Remove ${asset.displayName}?",
        text = text,
        confirmLabel = "Remove",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Imported icons and the drawables they replace. "Assign…" opens [SlotDialog] with every slot of
 * the last scan grouped by part; the switch controls icon synthesis for rows the pack lacks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomIconsCard(
    assets: List<CustomAssetStore.Asset>,
    slots: List<ThemePreviewer.IconSlot>,
    slotsError: String?,
    spec: ThemeSpec,
    busy: Boolean,
    needsRoot: Boolean,
    paletteHint: List<Int>,
    fileOf: (CustomAssetStore.Asset) -> File,
    onImport: (Uri) -> Unit,
    onGenerated: (CustomAssetStore.Asset) -> Unit,
    onAssign: (String, String?) -> Unit,
    onDelete: (CustomAssetStore.Asset) -> Unit,
    onSynthesize: (Boolean) -> Unit,
    onOpenProviders: () -> Unit,
    onScan: () -> Unit,
    /** False when the icon style card already carries the synthesis switch (the Studio's Icons step). */
    showSynthesize: Boolean = true,
) {
    var assigning by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var generating by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val labels = remember(slots) { slots.associate { it.key to it.label } }
    val names = remember(assets) { assets.associate { it.fileName to it.displayName } }
    // Colours-only tier: the card stays readable, its controls are off, files can still be removed.
    val locked = busy || needsRoot
    val badge: (@Composable RowScope.() -> Unit)? = if (needsRoot) ({ NeedsRootBadge() }) else null
    SectionCard(title = "Custom icons", trailing = badge) {
        Text(
            "Use your own PNG or ICO for Settings rows, My Files categories and any icon your packs change, " +
                "or describe one and let AI draw it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (needsRoot) {
            NeedsRootReason("Custom icons replace system images, which needs root. Your colours still apply.")
        }
        if (showSynthesize) {
            SwitchRow(
                label = "Fill in missing Settings icons in the pack's style",
                checked = spec.synthesizeMissingIcons,
                enabled = !locked,
                onCheckedChange = onSynthesize,
            )
        }
        if (assets.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Add,
                text = "No custom icons yet. Import an image, or generate one from a few words.",
                actionLabel = "Generate with AI",
                enabled = !locked,
                onAction = { generating = true },
            )
        } else if (slots.isEmpty()) {
            if (slotsError == null) {
                EmptyState(
                    icon = Icons.Outlined.Search,
                    text = "Scan your phone once to list the icons you can replace.",
                    actionLabel = "Scan phone",
                    enabled = !busy,
                    onAction = onScan,
                )
            } else {
                EmptyState(
                    icon = Icons.Outlined.Warning,
                    text = "Couldn't list the icons on this phone. Scan again.",
                    actionLabel = "Scan phone",
                    enabled = !busy,
                    onAction = onScan,
                )
            }
        }
        assets.forEach { asset ->
            AssetRow(
                asset = asset,
                file = fileOf(asset),
                assigned = assignedSummary(asset.fileName, spec.customIcons, labels),
                busy = busy,
                assignLabel = "Assign…",
                onAssign = { assigning = asset.fileName },
                onDelete = { deleting = asset.fileName },
                assignEnabled = !needsRoot,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(IMAGE_MIME_TYPES) }, enabled = !locked) { Text("Import image") }
            // With no icons the empty state above already carries "Generate with AI".
            if (assets.isNotEmpty()) {
                OutlinedButton(onClick = { generating = true }, enabled = !locked) { Text("Generate with AI") }
            }
        }
    }
    assets.firstOrNull { it.fileName == deleting }?.let { asset ->
        ConfirmAssetDelete(
            asset = asset,
            text = "The image is deleted and taken out of every slot it fills.",
            onConfirm = {
                deleting = null
                onDelete(asset)
            },
            onDismiss = { deleting = null },
        )
    }
    val dialogAsset = assets.firstOrNull { it.fileName == assigning }
    if (dialogAsset != null) {
        SlotDialog(
            title = dialogAsset.displayName,
            file = dialogAsset.fileName,
            slots = slots,
            assignments = spec.customIcons,
            assetNames = names,
            emptyText = slotsError ?: "Scan your phone in Diagnostics to list the icons you can replace.",
            busy = busy,
            onAssign = onAssign,
            onDismiss = { assigning = null },
        )
    }
    if (generating) {
        GenerateIconSheet(
            slotLabel = null,
            paletteHint = paletteHint,
            onUse = onGenerated,
            onOpenProviders = {
                generating = false
                onOpenProviders()
            },
            onDismiss = { generating = false },
        )
    }
}

/** Imported backgrounds and the background drawables of the last scan they can fill. */
@Composable
internal fun BackgroundsCard(
    assets: List<CustomAssetStore.Asset>,
    slots: List<BackgroundSlots.Slot>,
    slotsError: String?,
    spec: ThemeSpec,
    busy: Boolean,
    needsRoot: Boolean,
    fileOf: (CustomAssetStore.Asset) -> File,
    onImport: (Uri) -> Unit,
    onAssign: (String, String?) -> Unit,
    onDelete: (CustomAssetStore.Asset) -> Unit,
    onScan: () -> Unit,
) {
    var assigning by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val entries = remember(slots) { slots.map { ThemePreviewer.IconSlot(it.key, it.label, it.packageName) } }
    val labels = remember(slots) { slots.associate { it.key to it.label } }
    val names = remember(assets) { assets.associate { it.fileName to it.displayName } }
    val locked = busy || needsRoot
    val badge: (@Composable RowScope.() -> Unit)? = if (needsRoot) ({ NeedsRootBadge() }) else null
    SectionCard(title = "Backgrounds", trailing = badge) {
        Text(
            "Put your own image behind Settings and other One UI screens. Large images are scaled down for overlays.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (needsRoot) {
            NeedsRootReason("Backgrounds replace system images, which needs root. Your colours still apply.")
        }
        // One empty state, like CustomIconsCard: the scan hint only once there is something to assign.
        if (assets.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Add,
                text = "No backgrounds yet. Import an image to put behind One UI screens.",
                actionLabel = "Import background",
                enabled = !locked,
                onAction = { picker.launch(IMAGE_MIME_TYPES) },
            )
        } else if (slots.isEmpty()) {
            if (slotsError == null) {
                EmptyState(
                    icon = Icons.Outlined.Search,
                    text = "Scan your phone once to list the screens that can take a background.",
                    actionLabel = "Scan phone",
                    enabled = !busy,
                    onAction = onScan,
                )
            } else {
                EmptyState(
                    icon = Icons.Outlined.Warning,
                    text = "Couldn't list the screens on this phone. Scan again.",
                    actionLabel = "Scan phone",
                    enabled = !busy,
                    onAction = onScan,
                )
            }
        }
        assets.forEach { asset ->
            AssetRow(
                asset = asset,
                file = fileOf(asset),
                assigned = assignedSummary(asset.fileName, spec.customBackgrounds, labels),
                busy = busy,
                assignLabel = "Use for…",
                onAssign = { assigning = asset.fileName },
                onDelete = { deleting = asset.fileName },
                assignEnabled = !needsRoot,
            )
        }
        // With no backgrounds the empty state above already carries "Import background".
        if (assets.isNotEmpty()) {
            OutlinedButton(onClick = { picker.launch(IMAGE_MIME_TYPES) }, enabled = !locked) { Text("Import background") }
        }
    }
    assets.firstOrNull { it.fileName == deleting }?.let { asset ->
        ConfirmAssetDelete(
            asset = asset,
            text = "The image is deleted and taken out of every screen it fills.",
            onConfirm = {
                deleting = null
                onDelete(asset)
            },
            onDismiss = { deleting = null },
        )
    }
    val dialogAsset = assets.firstOrNull { it.fileName == assigning }
    if (dialogAsset != null) {
        SlotDialog(
            title = dialogAsset.displayName,
            file = dialogAsset.fileName,
            slots = entries,
            assignments = spec.customBackgrounds,
            assetNames = names,
            emptyText = slotsError ?: "Scan your phone in Diagnostics to list the screens that can take a background.",
            busy = busy,
            onAssign = onAssign,
            onDismiss = { assigning = null },
        )
    }
}

/** A row of [SlotDialog]: a group header or one slot. */
private sealed interface SlotLine {
    /** A stable, unique LazyColumn key, so rows keep their state when the filter changes. */
    val key: String

    data class Header(val group: String, override val key: String) : SlotLine
    data class Entry(val slot: ThemePreviewer.IconSlot, override val key: String) : SlotLine
}

/**
 * Picks the slots an asset [file] fills: a filter field, then the slots grouped under their part
 * label, each with a radio that is on when this file is assigned. Tapping an assigned slot
 * gives it back; a slot another file fills says so in its subtitle.
 */
@Composable
private fun SlotDialog(
    title: String,
    file: String,
    slots: List<ThemePreviewer.IconSlot>,
    assignments: Map<String, String>,
    assetNames: Map<String, String>,
    emptyText: String,
    busy: Boolean,
    onAssign: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    val lines = remember(slots, filter) {
        val needle = filter.trim()
        val matching = if (needle.isEmpty()) slots else slots.filter {
            it.label.contains(needle, ignoreCase = true) || it.key.contains(needle, ignoreCase = true) || it.group.contains(needle, ignoreCase = true)
        }
        buildList<SlotLine> {
            var last: String? = null
            val used = HashSet<String>()
            // A group can come back after another one, and keys must never repeat.
            fun unique(base: String): String {
                var key = base
                var n = 1
                while (!used.add(key)) key = "$base#${n++}"
                return key
            }
            for (slot in matching) {
                if (slot.group != last) {
                    add(SlotLine.Header(slot.group, unique("h:${slot.group}")))
                    last = slot.group
                }
                add(SlotLine.Entry(slot, unique("s:${slot.key}")))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (slots.isEmpty()) {
                    item { Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    item {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("Filter") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (slots.isNotEmpty() && lines.isEmpty()) {
                    item { Text("Nothing matches your filter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(lines, key = { it.key }) { line ->
                    when (line) {
                        is SlotLine.Header -> Text(line.group, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                        is SlotLine.Entry -> {
                            val slot = line.slot
                            val current = assignments[slot.key]
                            val selected = current == file
                            PackRow(selected = selected, enabled = !busy, onClick = { onAssign(slot.key, if (selected) null else file) }) {
                                Column(Modifier.weight(1f)) {
                                    Text(slot.label, style = MaterialTheme.typography.titleSmall)
                                    // The group header already names the target; only another asset's use is worth a line.
                                    if (current != null && !selected) {
                                        Text("Uses ${assetNames[current] ?: current}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * The system font of the theme: "System default" or an imported font, each row set in its own
 * face; the chosen font can take a second file for bold. A sample line shows the choice.
 */
@Composable
internal fun FontCard(
    fonts: List<CustomAssetStore.Asset>,
    spec: ThemeSpec,
    busy: Boolean,
    needsRoot: Boolean,
    fileOf: (CustomAssetStore.Asset) -> File,
    onImport: (Uri) -> Unit,
    onFont: (CustomAssetStore.Asset?) -> Unit,
    onBold: (CustomAssetStore.Asset?) -> Unit,
    onDelete: (CustomAssetStore.Asset) -> Unit,
) {
    var showBold by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val font = spec.customFont
    val chosen = fonts.firstOrNull { it.fileName == font?.regular }
    val chosenFamily = rememberFontFamily(chosen?.let { fileOf(it).path })
    val locked = busy || needsRoot
    val badge: (@Composable RowScope.() -> Unit)? = if (needsRoot) ({ NeedsRootBadge() }) else null
    SectionCard(title = "Fonts", trailing = badge) {
        Text(
            "Set the system font from a .ttf or .otf file. It applies with the theme; restart your phone to see it everywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (needsRoot) {
            NeedsRootReason("A system font is installed as a module on your phone, which needs root. Your colours still apply.")
        }
        PackRow(selected = font == null, enabled = !locked, onClick = { onFont(null) }) {
            Text("System default", style = MaterialTheme.typography.titleSmall)
        }
        fonts.forEach { asset ->
            val selected = font?.regular == asset.fileName
            val family = rememberFontFamily(fileOf(asset).path)
            PackRow(selected = selected, enabled = !locked, onClick = { onFont(asset) }) {
                Column(Modifier.weight(1f)) {
                    Text(
                        asset.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = family,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = if (selected) {
                        val bold = font?.bold?.let { b -> fonts.firstOrNull { it.fileName == b }?.displayName ?: b }
                        "Bold file: ${bold ?: "same file"} · ${assetSummary(asset)}"
                    } else {
                        assetSummary(asset)
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selected) {
                    TextButton(onClick = { showBold = true }, enabled = !locked) { Text("Bold…") }
                }
                TextButton(
                    onClick = { deleting = asset.fileName },
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "Remove ${asset.displayName}" },
                ) { Text("Remove") }
            }
        }
        Text(
            "The quick brown fox jumps over the lazy dog. 0123456789",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = chosenFamily,
        )
        OutlinedButton(onClick = { picker.launch(FONT_MIME_TYPES) }, enabled = !locked) { Text("Import font") }
    }
    fonts.firstOrNull { it.fileName == deleting }?.let { asset ->
        ConfirmAssetDelete(
            asset = asset,
            // Deleting only edits the saved theme: a font already installed on the phone stays
            // until the next Apply theme or Remove theme, so say so instead of promising a reset.
            text = when (asset.fileName) {
                font?.regular -> "The font file is deleted and your theme goes back to the system font. " +
                    "If this font is on your phone now, it stays until you apply or remove the theme."
                font?.bold -> "The font file is deleted and bold text uses the regular file. " +
                    "If this bold is on your phone now, it stays until you apply or remove the theme."
                else -> "The font file is deleted. Your theme doesn't use it, so the font on your phone doesn't change."
            },
            onConfirm = {
                deleting = null
                onDelete(asset)
            },
            onDismiss = { deleting = null },
        )
    }
    if (showBold && font != null) {
        AlertDialog(
            onDismissRequest = { showBold = false },
            confirmButton = { TextButton(onClick = { showBold = false }) { Text("Close") } },
            title = { Text("Bold file") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        PackRow(selected = font.bold == null, enabled = !busy, onClick = { onBold(null) }) {
                            Text("Same as regular", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    items(fonts.filter { it.fileName != font.regular }, key = { it.fileName }) { asset ->
                        val family = rememberFontFamily(fileOf(asset).path)
                        PackRow(selected = font.bold == asset.fileName, enabled = !busy, onClick = { onBold(asset) }) {
                            Text(
                                asset.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = family,
                            )
                        }
                    }
                }
            },
        )
    }
}

/**
 * The "Get packs" dialog: the catalogue is fetched when the dialog opens empty, so the default
 * path is open → spinner → list. Refresh re-reads it; the catalogue address sits behind
 * "Change source".
 */
@Composable
private fun CatalogueDialog(
    state: CatalogueState,
    busy: Boolean,
    locks: PackLocks,
    onUnlock: (String) -> Unit,
    onUrl: (String) -> Unit,
    onLoad: () -> Unit,
    onDownload: (CatalogueEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var showSource by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (state.entries.isEmpty() && !state.loading && state.error == null) onLoad()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Get packs") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onLoad, enabled = !state.loading && !busy) { Text("Refresh") }
                        TextButton(onClick = { showSource = !showSource }) { Text(if (showSource) "Hide source" else "Change source") }
                        if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                if (locks.offerUnlockAll && state.entries.isNotEmpty()) {
                    item { UnlockAllRow(locks = locks, busy = busy, onUnlock = onUnlock) }
                }
                if (showSource) {
                    item {
                        OutlinedTextField(
                            value = state.url,
                            onValueChange = onUrl,
                            label = { Text("Catalogue address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                state.error?.let { error ->
                    item { Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                if (state.loading && state.entries.isEmpty()) {
                    item {
                        Text(
                            "Loading the pack list…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!state.loading && state.error == null && state.entries.isEmpty()) {
                    item {
                        Text(
                            "No packs are listed at this address yet. Tap Refresh to try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.entries, key = { it.id }) { entry ->
                    CatalogueRow(entry, state.downloading[entry.id], busy, locks, onUnlock, onDownload)
                }
            }
        },
    )
}

@Composable
private fun CatalogueRow(
    entry: CatalogueEntry,
    progress: Float?,
    busy: Boolean,
    locks: PackLocks,
    onUnlock: (String) -> Unit,
    onDownload: (CatalogueEntry) -> Unit,
) {
    // A locked pack can be downloaded to preview it (kept apart from the packs an apply may use);
    // its row also offers the unlock.
    val locked = locks.locked(entry.id)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            if (locked) LockedLabel(pending = locks.pending(entry.id), price = locks.price(entry.id))
            Text(
                // Size and contents only: never an author or version (see packSummary).
                listOfNotNull(
                    "%.1f MB".format(entry.bytes / 1_000_000.0),
                    if (entry.hasSettingsIcons) "Settings icons" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            progress != null -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(72.dp))
            entry.installed && locked && locks.canPurchase -> UnlockButton(
                name = entry.name,
                price = locks.price(entry.id),
                pending = locks.pending(entry.id),
                enabled = !busy,
                onClick = { onUnlock(ProductIds.forPack(entry.id)) },
            )
            entry.installed -> Text("Installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            else -> TextButton(
                onClick = { onDownload(entry) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = (if (locked) "Preview " else "Download ") + entry.name },
            ) { Text(if (locked) "Preview" else "Download") }
        }
    }
}

/**
 * The parts of One UI a catalogue skin styles, from its index categories (e.g. "Keyboard",
 * "Phone", "Status bar"): at most [max] labels plus "+N".
 */
internal fun coverageLabels(categories: List<String>, max: Int = 4): List<String> {
    val labels = categories.mapNotNull { Elements.resolveCategory(it) }.distinctBy { it.id }.map { it.label }
    return if (labels.size <= max) labels else labels.take(max) + "+${labels.size - max}"
}

/**
 * A catalogue skin that is not on the phone yet, in the Skins list: its name, what it styles,
 * its lock state and price. Tapping it downloads it (preview only while locked) and selects it,
 * so the live phone shows it at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogueSkinRow(
    entry: CatalogueEntry,
    progress: Float?,
    busy: Boolean,
    locks: PackLocks,
    onUnlock: (String) -> Unit,
    onPreview: (CatalogueEntry) -> Unit,
) {
    val locked = locks.locked(entry.id)
    PackRow(selected = false, enabled = !busy && progress == null, onClick = { onPreview(entry) }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (locked) LockedLabel(pending = locks.pending(entry.id), price = locks.price(entry.id)) else PackBadge("Free")
                if (entry.dayNight) PackBadge("Day and night")
                coverageLabels(entry.categories).forEach { PackBadge(it) }
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    if (locked) "Tap to preview. Unlock to apply." else "Tap to download and preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (locked && locks.canPurchase) {
                UnlockButton(
                    name = entry.name,
                    price = locks.price(entry.id),
                    pending = locks.pending(entry.id),
                    enabled = !busy,
                    onClick = { onUnlock(ProductIds.forPack(entry.id)) },
                )
            }
        }
    }
}

/**
 * What restart protection does right now, in the words of the old Apply card: shown on the
 * Targets restart-protection card. [expired] is true once its 10 minutes ended unconfirmed.
 */
internal fun restartProtectionText(unconfirmed: Boolean, expired: Boolean): String = when {
    !unconfirmed -> "Nothing is waiting for confirmation. After the next apply, check your phone and tap \"It works\"."
    expired -> "Applied and not confirmed. Restart protection ended 10 minutes after the apply, so restarts " +
        "no longer turn the theme off. Tap \"It works\" to clear this."
    // Phone restarts are only caught when root is still there after the restart. The temporary
    // DFRoot root that RootGuide recommends is gone by then, so promise only what the in-session
    // watchdog (120 s, System UI or system server restarts) does.
    else -> "Not confirmed yet. For 2 minutes, the theme turns off if System UI keeps crashing. A temporary " +
        "root such as DFRoot is gone after a restart, so check the phone before you restart, then tap \"It works\"."
}

/**
 * The persistent Apply bar above the bottom navigation (§ 1.3): the logic of the old Apply card
 * in a compact bar. Apply and Remove are on only while [connected] and not [busy]; while
 * [applying] a progress bar and the last log line (a polite live region) show; while
 * [unconfirmed] an "It works" row sits on top. Tapping the summary opens the target list
 * ([onDetails]); while disconnected it opens the connection sheet ([onConnection]).
 *
 * While the theme uses a pack that is not unlocked ([previewOnlyPack], its name) the bar says it
 * is a preview and, in a store build ([onUnlock] not null), offers Unlock in place of Apply.
 * Apply is refused for such a theme anyway (MainViewModel.applyTheme).
 */
@Composable
internal fun ApplyBar(
    connected: Boolean,
    busy: Boolean,
    applying: Boolean,
    report: ApplyReport?,
    unconfirmed: Boolean,
    protectionExpired: Boolean,
    progressLine: String?,
    onApply: () -> Unit,
    onRemove: () -> Unit,
    onConfirmWorks: () -> Unit,
    onDetails: () -> Unit,
    onConnection: () -> Unit,
    modifier: Modifier = Modifier,
    previewOnlyPack: String? = null,
    previewOnlyPrice: String? = null,
    previewOnlyPending: Boolean = false,
    onUnlock: (() -> Unit)? = null,
) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val status = LocalStatusPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .drawTopHairline(MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (applying) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (progressLine != null) {
                Text(
                    progressLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (unconfirmed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (protectionExpired) "Not confirmed. Restart protection has ended." else "Check your phone, then confirm it works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = status.warn,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Off while an apply runs: it has armed the guard for overlays nobody has checked yet.
                FilledTonalButton(onClick = onConfirmWorks, enabled = !applying) { Text("It works") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val summary = when {
                previewOnlyPack != null -> "Unlock $previewOnlyPack to apply — preview only"
                !connected -> "Connect first: start Shizuku with root"
                report == null -> "Not applied yet"
                else -> lastApplyLine(report)
            }
            val failed = report != null && (report.failedCount > 0 || !report.finished)
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        onClickLabel = if (connected) "Show targets" else "Show connection",
                        onClick = if (connected) onDetails else onConnection,
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        previewOnlyPack != null -> status.warn
                        !connected -> MaterialTheme.colorScheme.onSurfaceVariant
                        failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = { confirmRemove = true },
                enabled = connected && !busy,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Remove") }
            if (previewOnlyPack != null && onUnlock != null) {
                UnlockButton(
                    name = previewOnlyPack,
                    price = previewOnlyPrice,
                    pending = previewOnlyPending,
                    enabled = !busy,
                    onClick = onUnlock,
                )
            } else Button(
                onClick = onApply,
                enabled = connected && !busy,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(if (applying) Modifier.semantics { contentDescription = "Applying theme" } else Modifier),
            ) {
                if (applying) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Text("Apply theme")
                }
            }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove theme?",
            text = "Your phone goes back to Samsung's look. Your packs, colours and custom files stay in Hex Next so you can apply again.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** A 1 dp line along the top edge, the border between the Apply bar and the content above it. */
private fun Modifier.drawTopHairline(color: Color): Modifier = drawBehind {
    drawRect(color = color, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
}

/** The activity hosting [this] context, unwrapping [ContextWrapper]s; null when there is none. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** "Applied to 12 apps · 1 failed · 2 removed apps skipped", or the interruption, in plain words. */
internal fun lastApplyLine(report: ApplyReport): String {
    if (!report.finished) return "Last apply was interrupted. Apply again."
    val parts = ArrayList<String>(3)
    parts += "Applied to ${report.okCount} ${if (report.okCount == 1) "app" else "apps"}"
    if (report.failedCount > 0) parts += "${report.failedCount} failed"
    if (report.debloatedCount > 0) parts += "${report.debloatedCount} removed ${if (report.debloatedCount == 1) "app" else "apps"} skipped"
    return parts.joinToString(" · ")
}

/** The build log of the editor and the live log of Diagnostics: monospace lines in an outlined card. */
@Composable
internal fun LogCard(
    lines: List<LogLine>,
    onClear: () -> Unit,
    title: String = "Log",
    /** Extra controls under the title (the level filter of System > Tools). */
    header: (@Composable () -> Unit)? = null,
    /** Text shown instead of the lines when [lines] is empty. */
    emptyText: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onClear, enabled = lines.isNotEmpty()) { Text("Clear") }
            }
            header?.invoke()
            if (lines.isEmpty() && emptyText != null) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp, top = 4.dp),
                )
            }
            val status = LocalStatusPalette.current
            lines.forEach { line ->
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
                        lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                    color = when (line.level) {
                        LogLevel.OK -> status.ok
                        LogLevel.WARN -> status.warn
                        LogLevel.ERROR -> status.bad
                        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(end = 12.dp, bottom = 4.dp),
                )
            }
        }
    }
}
