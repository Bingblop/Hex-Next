package com.hexnext.themer.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Color as AndroidColor
import android.graphics.Typeface as AndroidTypeface

private val PRESETS = listOf(
    0xFF00E5FF, 0xFF3E7BFA, 0xFF7E57C2, 0xFFEC407A, 0xFFE53935, 0xFFFF7043,
    0xFFF4B400, 0xFF10B981, 0xFF34A853, 0xFF00A6A6, 0xFF8D6E63, 0xFF78909C,
).map { it.toInt() }

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
    when (target) {
        Screen.MAIN -> EditorScreen(viewModel, ui, shizuku)
        Screen.SETTINGS -> AppSettingsScreen(
            store = settingsStore,
            onBack = viewModel::navigateBack,
            onOpenDiagnostics = { viewModel.navigate(Screen.DIAGNOSTICS) },
            onOpenAiProviders = { viewModel.navigate(Screen.AI_PROVIDERS) },
            onOpenOnboarding = { viewModel.navigate(Screen.ONBOARDING) },
        )
        Screen.DIAGNOSTICS -> DiagnosticsScreen(
            shizuku = shizuku,
            accent = ui.seed,
            autoScan = ui.scanRequested,
            onScanStarted = viewModel::consumeScanRequest,
            onApplyAgain = {
                viewModel.navigate(Screen.MAIN)
                viewModel.applyTheme()
            },
            onBack = {
                viewModel.navigateBack()
                // A scan may have happened: the slot lists come from it.
                viewModel.invalidateSlots()
            },
        )
        Screen.AI_PROVIDERS -> AiProvidersScreen(onBack = viewModel::navigateBack)
        Screen.ONBOARDING -> OnboardingScreen(
            shizuku = shizuku,
            versionLine = ui.versionLine,
            rootToolLinks = ui.rootToolLinks,
            packs = ui.packs,
            selectedPackId = ui.spec.simplePackId,
            onSelectPack = viewModel::selectPack,
            onImportPack = viewModel::importPack,
            onRequestPermission = viewModel::requestPermission,
            onFinish = viewModel::finishOnboarding,
        )
    }
}

/**
 * "Root · One UI 8.0": the pill next to the title. The connection state comes first because it is
 * the part that changes; the version keeps only its One UI half, the Connection card has the rest.
 */
private fun statusPillText(versionLine: String, state: ShizukuState): String {
    val connection = when (state) {
        is ShizukuState.Ready -> when {
            state.isRoot -> "Root"
            state.isSystemUid1000 -> "System"
            Capability.fromUid(state.uid) == Capability.SHELL -> "Colours only"
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
private fun StatusPill(text: String, ok: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** The editor: top bar with the status pill and the App settings gear, the cards, the snackbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(viewModel: MainViewModel, ui: UiState, shizuku: ShizukuState) {
    // Both saveable, so they survive a trip to Settings or Diagnostics (see MainScreen's holder).
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ready = shizuku as? ShizukuState.Ready
    val appSettings = LocalAppSettings.current
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }

    // One snackbar per notice: the notice is consumed as soon as it is picked up (so leaving the
    // editor while it shows does not replay it on return), then haptic on arrival and the action when tapped.
    val notice = ui.notice
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        viewModel.consumeNotice(current.id)
        if (appSettings.haptics && (current.isError || current.isSuccess)) {
            haptic.performHapticFeedback(if (current.isError) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        }
        val result = snackbar.showSnackbar(
            message = current.message,
            actionLabel = current.actionLabel,
            withDismissAction = current.actionLabel == null,
            duration = if (current.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            when (current.action) {
                NoticeAction.OPEN_DIAGNOSTICS -> viewModel.navigate(Screen.DIAGNOSTICS)
                NoticeAction.REQUEST_PERMISSION -> viewModel.requestPermission()
                NoticeAction.RETRY_APPLY -> viewModel.applyTheme()
                NoticeAction.RETRY_REMOVE -> viewModel.removeTheme()
                NoticeAction.CONFIRM_WORKS -> viewModel.confirmWorks()
                null -> Unit
            }
        }
    }

    val paletteHint = remember(ui.packs, ui.spec.simplePackId) {
        ui.packs.firstOrNull { it.id == ui.spec.simplePackId }
            ?.pack?.paletteArgb?.values?.map { it or 0xFF000000.toInt() }?.take(6).orEmpty()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // The keyboard counts as an inset, so the hex and description fields scroll clear of it.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                    ) {
                        Text("Hex Next", fontWeight = FontWeight.SemiBold)
                        StatusPill(text = statusPillText(ui.versionLine, shizuku), ok = ready != null && ui.capability != Capability.NONE)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.navigate(Screen.SETTINGS) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "App settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        // The Connection card is the first item, or the second behind the safe-mode banner.
        val connectionIndex = if (ui.safeModeBanner != null) 1 else 0
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateStartPadding(LayoutDirection.Ltr),
                end = 16.dp + padding.calculateEndPadding(LayoutDirection.Ltr),
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.safeModeBanner?.let { banner ->
                item { SafeModeBanner(banner, onOpenDiagnostics = { viewModel.navigate(Screen.DIAGNOSTICS) }, onDismiss = viewModel::dismissSafeMode) }
            }
            item { StatusCard(shizuku, ui.capability, ui.versionLine, ui.rootToolLinks, viewModel::requestPermission) }
            item {
                DescribeThemeCard(
                    packs = ui.packs,
                    spec = ui.spec,
                    busy = ui.busy,
                    onApply = { _, newSpec ->
                        // The apply path reads the seed from the preference, so both must move.
                        viewModel.setSeed(newSpec.source.seedArgb)
                        viewModel.replaceSpec(newSpec)
                    },
                    onOpenProviders = { viewModel.navigate(Screen.AI_PROVIDERS) },
                )
            }
            item { ColorCard(seed = ui.seed, onSeed = viewModel::setSeed) }
            item {
                PackCard(
                    packs = ui.packs,
                    errors = ui.packErrors,
                    spec = ui.spec,
                    busy = ui.busy,
                    catalogue = ui.catalogue,
                    onSelect = viewModel::selectPack,
                    onMode = viewModel::setMode,
                    onOption = viewModel::setOption,
                    onElementPack = viewModel::setElementPack,
                    onElementEnabled = viewModel::setElementEnabled,
                    onImport = viewModel::importPack,
                    onDelete = viewModel::deletePack,
                    onCatalogueUrl = viewModel::setCatalogueUrl,
                    onLoadCatalogue = viewModel::loadCatalogue,
                    onDownload = viewModel::downloadPack,
                )
            }
            item {
                CustomIconsCard(
                    assets = ui.customIcons,
                    slots = ui.iconSlots,
                    slotsError = ui.slotsError,
                    spec = ui.spec,
                    busy = ui.busy,
                    needsRoot = ui.capability == Capability.SHELL,
                    paletteHint = paletteHint,
                    fileOf = viewModel::assetFile,
                    onImport = viewModel::importCustomIcon,
                    onGenerated = viewModel::noteGeneratedIcon,
                    onAssign = viewModel::assignIcon,
                    onDelete = viewModel::deleteAsset,
                    onSynthesize = viewModel::setSynthesizeMissingIcons,
                    onOpenProviders = { viewModel.navigate(Screen.AI_PROVIDERS) },
                    onScan = viewModel::openScan,
                )
            }
            item {
                BackgroundsCard(
                    assets = ui.customBackgrounds,
                    slots = ui.backgroundSlots,
                    slotsError = ui.slotsError,
                    spec = ui.spec,
                    busy = ui.busy,
                    needsRoot = ui.capability == Capability.SHELL,
                    fileOf = viewModel::assetFile,
                    onImport = viewModel::importCustomBackground,
                    onAssign = viewModel::assignBackground,
                    onDelete = viewModel::deleteAsset,
                    onScan = viewModel::openScan,
                )
            }
            item {
                FontCard(
                    fonts = ui.customFonts,
                    spec = ui.spec,
                    busy = ui.busy,
                    needsRoot = ui.capability == Capability.SHELL,
                    fileOf = viewModel::assetFile,
                    onImport = viewModel::importFont,
                    onFont = viewModel::setFont,
                    onBold = viewModel::setFontBold,
                    onDelete = viewModel::deleteAsset,
                )
            }
            item { PreviewCard(ui.preview, onScan = viewModel::openScan) }
            item {
                CompiledThemeCard(
                    connected = ready != null,
                    busy = ui.busy,
                    report = ui.lastApply,
                    unconfirmed = ui.applyUnconfirmed,
                    progressLine = ui.log.lastOrNull()?.text,
                    onApply = viewModel::applyTheme,
                    onRemove = viewModel::removeTheme,
                    onConfirmWorks = viewModel::confirmWorks,
                    onDetails = { viewModel.navigate(Screen.DIAGNOSTICS) },
                    onConnection = { scope.launch { listState.animateScrollToItem(connectionIndex) } },
                )
            }
            if (ui.log.isNotEmpty()) {
                item { LogCard(ui.log, onClear = viewModel::clearLog) }
            }
        }
    }
}

@Composable
private fun SafeModeBanner(text: String, onOpenDiagnostics: () -> Unit, onDismiss: () -> Unit) {
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
                "Your phone restarted twice within 10 minutes of an apply that was not confirmed, so Hex Next turned " +
                    "its overlays off. When your phone is stable, tap Apply theme again, then \"It works\". " +
                    "Diagnostics has the details.",
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

/** The shared card: title (with an optional [trailing] control at its right) over [content]. */
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
    ) {
        Column(
            Modifier
                .then(if (reduceMotion) Modifier else Modifier.animateContentSize())
                .padding(20.dp),
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
 * The Connection card: the access tier ("Full (root)", "Colours only (Shizuku)", "Not
 * connected") as a pill next to the title, one sentence on the state, the version line, the
 * action for the state and, without root, the root guide.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(
    state: ShizukuState,
    capability: Capability,
    versionLine: String,
    rootToolLinks: Map<String, String>,
    onRequestPermission: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
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
            is ShizukuState.Ready -> if (!state.isPrivileged) {
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
        state.isSystemUid1000 ->
            "Connected with system access. Apply builds one overlay for each scanned One UI app and takes over from Samsung's built-in theme overlays."
        state.isRoot ->
            "Connected with root access. Apply builds one overlay for each scanned One UI app and takes over from Samsung's built-in theme overlays."
        Capability.fromUid(state.uid) == Capability.SHELL ->
            "Connected through Shizuku without root. Your accent colour applies as the system palette and as colour overlays. " +
                "For icons, backgrounds and fonts, start Shizuku with root."
        else ->
            "Connected, but without root. Start Shizuku with root, or restart it if it came up without root after your phone restarted."
    }
}

/** The access tier of the Connection card: tonal for a usable connection, error colours while disconnected. */
@Composable
private fun TierPill(capability: Capability) {
    val ok = capability != Capability.NONE
    Text(
        capability.label,
        style = MaterialTheme.typography.labelSmall,
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
private fun NeedsRootBadge() {
    Text(
        "Needs root",
        style = MaterialTheme.typography.labelSmall,
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
private fun NeedsRootReason(text: String) {
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
    val uriHandler = LocalUriHandler.current
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorCard(seed: Int, onSeed: (Int) -> Unit) {
    SectionCard("Accent colour") {
        Text(
            "The main colour of your theme. Also used by the Settings test in Diagnostics.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(seed))
            )
            Spacer(Modifier.width(16.dp))
            HexField(seed, onSeed)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PRESETS.forEach { color ->
                ColorSwatch(color = color, selected = color == seed, onClick = { onSeed(color) })
            }
        }
        val hsv = remember(seed) { FloatArray(3).also { AndroidColor.colorToHSV(seed, it) } }
        Text("Hue", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = hsv[0],
            onValueChange = { hue ->
                onSeed(AndroidColor.HSVToColor(floatArrayOf(hue, hsv[1].coerceAtLeast(0.35f), hsv[2].coerceAtLeast(0.45f))))
            },
            valueRange = 0f..359f,
            modifier = Modifier.semantics { contentDescription = "Hue" },
        )
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

@Composable
private fun HexField(seed: Int, onSeed: (Int) -> Unit) {
    var text by remember { mutableStateOf(seed.toHex()) }
    LaunchedEffect(seed) { if (parseHexColor(text) != seed) text = seed.toHex() }
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            parseHexColor(value)?.let(onSeed)
        },
        label = { Text("Hex") },
        singleLine = true,
        isError = parseHexColor(text) == null,
        supportingText = { if (parseHexColor(text) == null) Text("Enter a colour like #3E7BFA") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Pack choice for the next apply. Simple mode: colours only or one installed pack, with the live
 * options of the selected pack under its row. Advanced mode: the default pack plus a pack per
 * part of One UI. Import and catalogue in both.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PackCard(
    packs: List<InstalledPack>,
    errors: Map<String, String>,
    spec: ThemeSpec,
    busy: Boolean,
    catalogue: CatalogueState,
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
) {
    var showCatalogue by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val advanced = spec.mode == ThemeMode.ADVANCED
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
        if (packs.isEmpty()) {
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
        PackRow(selected = spec.simplePackId == null, enabled = !busy, onClick = { onSelect(null) }) {
            Text("Colours only", style = MaterialTheme.typography.titleSmall)
        }
        packs.forEach { pack ->
            val selected = spec.simplePackId == pack.id
            PackRow(selected = selected, enabled = !busy, onClick = { onSelect(pack.id) }) {
                Column(Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        packSummary(pack),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    onOption = onOption,
                    onElementPack = onElementPack,
                    onElementEnabled = onElementEnabled,
                )
            }
        }
        errors.forEach { (file, why) ->
            Text("$file: $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
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
private fun PackRow(selected: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
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

/** Subtitle of a pack row: author · version · drawables · Settings icons. */
private fun packSummary(pack: InstalledPack): String = listOfNotNull(
    pack.author.takeIf { it.isNotBlank() },
    pack.version.takeIf { it.isNotBlank() },
    "${pack.drawableCount} drawables",
    if (pack.hasSettingsIcons) "Settings icons" else null,
).joinToString(" · ")

/** A switch row written like [PackRow]: label left, [Switch] right, the whole row one toggleable node. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
                items(covering) { pack ->
                    PackRow(selected = enabled && setId == pack.id, enabled = !busy, onClick = { onElementPack(element.id, pack.id) }) {
                        Column(Modifier.weight(1f)) {
                            Text(pack.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                packSummary(pack),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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

/** A font family from a `.ttf` / `.otf` file, or null when it cannot be loaded (the text then falls back to the default). */
@Composable
private fun rememberFontFamily(path: String?): FontFamily? = remember(path) {
    path?.let { runCatching { FontFamily(Typeface(AndroidTypeface.createFromFile(File(it)))) }.getOrNull() }
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
private fun CustomIconsCard(
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
            "Replace any icon One UI can theme with your own PNG or ICO, or describe one and let AI draw it. " +
                "Files are converted for overlays automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (needsRoot) {
            NeedsRootReason("Custom icons replace system images, which needs root. Your colours still apply.")
        }
        SwitchRow(
            label = "Fill in missing Settings icons in the pack's style",
            checked = spec.synthesizeMissingIcons,
            enabled = !locked,
            onCheckedChange = onSynthesize,
        )
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
private fun BackgroundsCard(
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
    data class Header(val group: String) : SlotLine
    data class Entry(val slot: ThemePreviewer.IconSlot) : SlotLine
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
            for (slot in matching) {
                if (slot.group != last) {
                    add(SlotLine.Header(slot.group))
                    last = slot.group
                }
                add(SlotLine.Entry(slot))
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
                items(lines) { line ->
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
private fun FontCard(
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
    SectionCard(title = "Font", trailing = badge) {
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
            text = "The font file is deleted. The system font goes back to default.",
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
                    items(fonts.filter { it.fileName != font.regular }) { asset ->
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

// ---- Preview ----

private val PREVIEW_SAMPLE = "The quick brown fox jumps over the lazy dog"

/**
 * The theme as it will apply, rendered from the last device scan: a phone-shaped mock of the
 * Settings dashboard with the planned icons (pack, custom and generated), the chosen background
 * and font, and a strip of My Files icons. Nothing is clickable.
 */
@Composable
private fun PreviewCard(preview: PreviewState, onScan: () -> Unit) {
    SectionCard("Preview") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Settings as it will look, drawn from the last phone scan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            preview.accent?.let { accent ->
                Text("Accent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(accent))
                )
            }
        }
        if (preview.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Rendering…", style = MaterialTheme.typography.bodySmall)
            }
        }
        PhoneMock(preview)
        preview.note?.let { note ->
            val failed = note.startsWith("Could not")
            // Without a scan the previewer has nothing to draw: give the user the scan, not a sentence about it.
            if (preview.icons.isEmpty() && !failed && note.contains("scan", ignoreCase = true)) {
                EmptyState(
                    icon = Icons.Outlined.Search,
                    text = "Scan your phone once to preview the icons.",
                    actionLabel = "Scan phone",
                    onAction = onScan,
                )
            } else {
                Text(
                    note.replace("device scan", "phone scan"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The phone-shaped mock of [PreviewCard]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneMock(preview: PreviewState) {
    val family = rememberFontFamily(preview.fontFile)
    val accent = preview.accent?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val rows = remember(preview.icons) { preview.icons.ifEmpty { ThemePreviewer.defaultRows(PREVIEW_ROWS) }.take(PREVIEW_ROWS) }
    val background = preview.background
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // One stop for the whole mock: nothing in it is interactive and every icon has its label beside it.
            .semantics(mergeDescendants = true) { contentDescription = "Preview of Settings with your theme" }
    ) {
        if (background != null) {
            Image(
                background.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(
                    if (background != null) MaterialTheme.colorScheme.surface.copy(alpha = 0.78f) else Color.Transparent,
                    RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, fontFamily = family)
            rows.forEach { icon ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val bitmap = icon.bitmap
                    if (bitmap != null) {
                        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp))
                    } else {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.35f))
                        )
                    }
                    Column {
                        Text(icon.label, style = MaterialTheme.typography.bodyMedium, fontFamily = family, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (icon.name in preview.synthesised) {
                            Text("generated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (preview.myFiles.isNotEmpty()) {
                Text("My Files", style = MaterialTheme.typography.titleSmall, fontFamily = family)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    preview.myFiles.forEach { icon ->
                        val bitmap = icon.bitmap
                        if (bitmap != null) {
                            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
                        } else {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(accent.copy(alpha = 0.35f))
                            )
                        }
                    }
                }
            }
            Text(PREVIEW_SAMPLE, style = MaterialTheme.typography.bodyMedium, fontFamily = family)
            Text(
                preview.fontName?.let { "Font: $it" } ?: "Font: system default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val PREVIEW_ROWS = 6

/**
 * The "Get packs" dialog: the catalogue is fetched when the dialog opens empty, so the default
 * path is open → spinner → list. Refresh re-reads it; the catalogue address sits behind
 * "Change source".
 */
@Composable
private fun CatalogueDialog(
    state: CatalogueState,
    busy: Boolean,
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
                items(state.entries) { entry ->
                    CatalogueRow(entry, state.downloading[entry.id], busy, onDownload)
                }
            }
        },
    )
}

@Composable
private fun CatalogueRow(entry: CatalogueEntry, progress: Float?, busy: Boolean, onDownload: (CatalogueEntry) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(
                    entry.author.takeIf { it.isNotBlank() },
                    entry.version.takeIf { it.isNotBlank() },
                    "%.1f MB".format(entry.bytes / 1_000_000.0),
                    if (entry.hasSettingsIcons) "Settings icons" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            progress != null -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(72.dp))
            entry.installed -> Text("Installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            else -> TextButton(
                onClick = { onDownload(entry) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Download ${entry.name}" },
            ) { Text("Download") }
        }
    }
}

/**
 * Applies the compiled theme to every scanned target, or removes it. Says why the buttons are
 * off while disconnected, shows progress while busy, formats the last apply in plain words and
 * carries the "It works" confirmation while restart protection is armed.
 */
@Composable
private fun CompiledThemeCard(
    connected: Boolean,
    busy: Boolean,
    report: ApplyReport?,
    unconfirmed: Boolean,
    progressLine: String?,
    onApply: () -> Unit,
    onRemove: () -> Unit,
    onConfirmWorks: () -> Unit,
    onDetails: () -> Unit,
    onConnection: () -> Unit,
) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    SectionCard("Apply") {
        Text(
            "Builds one Hex Next overlay for each scanned One UI app and keeps it in place after wallpaper changes. " +
                "Quick Settings keeps Samsung's layout and only takes your colours. Apps you've removed are skipped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (report != null) {
            val failed = report.failedCount > 0 || !report.finished
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    lastApplyLine(report),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDetails) { Text("Details") }
            }
        }
        if (unconfirmed) {
            Text(
                "Applied and not confirmed yet: restart protection is on for 10 minutes. If your phone restarts twice " +
                    "in that time, the theme is turned off. Check your phone, then confirm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            FilledTonalButton(onClick = onConfirmWorks) { Text("It works") }
        }
        if (!connected) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Connect first: start Shizuku with root, or allow root access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onConnection) { Text("Connection") }
            }
        }
        Actions(
            enabled = connected && !busy,
            busy = busy,
            applyLabel = "Apply theme",
            onApply = onApply,
            onRemove = { confirmRemove = true },
            removeLabel = "Remove theme",
        )
        if (busy) {
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

/** "Applied to 12 apps · 1 failed · 2 removed apps skipped", or the interruption, in plain words. */
private fun lastApplyLine(report: ApplyReport): String {
    if (!report.finished) return "Last apply was interrupted. Apply again."
    val parts = ArrayList<String>(3)
    parts += "Applied to ${report.okCount} ${if (report.okCount == 1) "app" else "apps"}"
    if (report.failedCount > 0) parts += "${report.failedCount} failed"
    if (report.debloatedCount > 0) parts += "${report.debloatedCount} removed ${if (report.debloatedCount == 1) "app" else "apps"} skipped"
    return parts.joinToString(" · ")
}

@Composable
private fun Actions(
    enabled: Boolean,
    busy: Boolean,
    applyLabel: String,
    onApply: () -> Unit,
    onRemove: () -> Unit,
    removeLabel: String = "Remove",
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onApply,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .then(if (busy) Modifier.semantics { contentDescription = "Applying theme" } else Modifier),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(applyLabel)
            }
        }
        OutlinedButton(onClick = onRemove, enabled = enabled, modifier = Modifier.heightIn(min = 52.dp)) {
            Text(removeLabel)
        }
    }
}

/** The build log of the editor and the live log of Diagnostics: monospace lines in a tinted card. */
@Composable
internal fun LogCard(lines: List<LogLine>, onClear: () -> Unit, title: String = "Log") {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onClear) { Text("Clear") }
            }
            lines.forEach { line ->
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = when (line.level) {
                        LogLevel.OK -> MaterialTheme.colorScheme.primary
                        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(end = 12.dp, bottom = 4.dp),
                )
            }
        }
    }
}
