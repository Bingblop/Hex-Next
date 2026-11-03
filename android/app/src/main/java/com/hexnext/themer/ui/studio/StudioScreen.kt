package com.hexnext.themer.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.Capability
import com.hexnext.themer.ui.BackgroundsCard
import com.hexnext.themer.ui.CompiledThemeCard
import com.hexnext.themer.ui.CustomIconsCard
import com.hexnext.themer.ui.EmptyState
import com.hexnext.themer.ui.FontCard
import com.hexnext.themer.ui.LocalAppSettings
import com.hexnext.themer.ui.LogCard
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.NoticeAction
import com.hexnext.themer.ui.PackCard
import com.hexnext.themer.ui.PackLocks
import com.hexnext.themer.ui.SafeModeBanner
import com.hexnext.themer.ui.Screen
import com.hexnext.themer.ui.StatusCard
import com.hexnext.themer.ui.StatusPill
import com.hexnext.themer.ui.UiState
import com.hexnext.themer.ui.ai.DescribeThemeCard
import com.hexnext.themer.ui.findActivity
import com.hexnext.themer.ui.preview.PhonePreview
import com.hexnext.themer.ui.statusPillText
import kotlinx.coroutines.launch

/** The Studio's steps, in tab order. Persisted by name (rememberSaveable). */
internal enum class StudioStep(val label: String) {
    SKINS("Skins"),
    COLOURS("Colours"),
    QUICK_PANEL("Quick panel"),
    ICONS("Icons"),
    FONTS("Fonts & backgrounds"),
}

/** Width from which the Studio shows the preview and the steps side by side. */
private val TWO_PANE_WIDTH = 840.dp

/**
 * The theme editor (the Studio): top bar with the status pill, the safe-mode banner and the
 * connection strip, the step tabs, the live phone preview (a collapsible card on phones, a sticky
 * pane from 840 dp), the cards of the selected step, then the Apply card and the log on every step.
 *
 * Everything the old editor did is still here: snackbar notices with their actions and haptics,
 * Apply / Remove / "It works", the pack, icon, background and font cards, and App settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(viewModel: MainViewModel, ui: UiState, shizuku: ShizukuState) {
    val preview by viewModel.studioPreview.collectAsStateWithLifecycle()
    val counts by viewModel.groupCounts.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ready = shizuku as? ShizukuState.Ready
    val appSettings = LocalAppSettings.current
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var step by rememberSaveable { mutableStateOf(StudioStep.COLOURS) }
    var previewTab by rememberSaveable { mutableStateOf(PreviewTab.SETTINGS) }
    var previewDark by rememberSaveable { mutableStateOf(true) }
    var previewExpanded by rememberSaveable { mutableStateOf(false) }
    var connectionExpanded by rememberSaveable { mutableStateOf(false) }
    var scrollTick by remember { mutableIntStateOf(0) }

    // One snackbar per notice: haptic on arrival, the action when tapped. The notice is consumed
    // only once the snackbar is gone (shown, dismissed, or the editor left while it showed, so it
    // does not replay on return). Consuming it earlier would change this effect's key, and the
    // key change cancels showSnackbar. An activity recreated for a configuration change keeps it.
    val notice = ui.notice
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        if (appSettings.haptics && (current.isError || current.isSuccess)) {
            haptic.performHapticFeedback(if (current.isError) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        }
        val result = try {
            snackbar.showSnackbar(
                message = current.message,
                actionLabel = current.actionLabel,
                withDismissAction = current.actionLabel == null,
                duration = if (current.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        } finally {
            if (activity?.isChangingConfigurations != true) viewModel.consumeNotice(current.id)
        }
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

    // The Quick panel step shows the Quick panel on the phone.
    LaunchedEffect(step) {
        if (step == StudioStep.QUICK_PANEL) previewTab = PreviewTab.QUICK_PANEL
    }

    val onElementTap: (ColorGroup) -> Unit = { group ->
        step = when {
            step == StudioStep.QUICK_PANEL && group in QUICK_PANEL_GROUPS -> StudioStep.QUICK_PANEL
            group.quickSettings -> StudioStep.QUICK_PANEL
            else -> StudioStep.COLOURS
        }
        viewModel.setHighlightedGroup(group)
        scrollTick++
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // The keyboard counts as an inset, so the hex and description fields scroll clear of it.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                    ) {
                        Text("Hex Next", fontWeight = FontWeight.SemiBold, maxLines = 1)
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
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val wide = maxWidth >= TWO_PANE_WIDTH
            val paneWidth = (maxWidth * 0.42f).coerceIn(360.dp, 480.dp)
            val previewPanel: @Composable (Boolean) -> Unit = { collapsible ->
                PreviewPanel(
                    preview = preview,
                    tab = previewTab,
                    onTab = { previewTab = it },
                    dark = previewDark,
                    onDark = { previewDark = it },
                    onElementTap = onElementTap,
                    onScan = viewModel::openScan,
                    collapsible = collapsible,
                    expanded = previewExpanded,
                    onExpand = { previewExpanded = it },
                )
            }
            val stepList: @Composable (Modifier, Boolean) -> Unit = { modifier, withPreview ->
                StepList(
                    viewModel = viewModel,
                    ui = ui,
                    shizuku = shizuku,
                    preview = preview,
                    counts = counts,
                    step = step,
                    onStep = { step = it },
                    listState = listState,
                    scrollTick = scrollTick,
                    connectionExpanded = connectionExpanded,
                    onConnectionExpanded = { connectionExpanded = it },
                    previewCard = if (withPreview) ({ previewPanel(true) }) else null,
                    onConnection = {
                        connectionExpanded = true
                        scope.launch { listState.animateScrollToItem(if (ui.safeModeBanner != null) 1 else 0) }
                    },
                    modifier = modifier,
                )
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .width(paneWidth)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, top = 4.dp, bottom = 24.dp),
                    ) {
                        previewPanel(false)
                    }
                    stepList(Modifier.weight(1f), false)
                }
            } else {
                stepList(Modifier.fillMaxSize(), true)
            }
        }
    }
}

/**
 * The scrolling column of the Studio: banner, connection, step tabs, the preview card (phones
 * only, [previewCard]), the cards of [step], Apply and the log.
 */
@Composable
private fun StepList(
    viewModel: MainViewModel,
    ui: UiState,
    shizuku: ShizukuState,
    preview: StudioPreviewState,
    counts: Map<ColorGroup, com.hexnext.engine.compile.GroupCount>,
    step: StudioStep,
    onStep: (StudioStep) -> Unit,
    listState: LazyListState,
    scrollTick: Int,
    connectionExpanded: Boolean,
    onConnectionExpanded: (Boolean) -> Unit,
    previewCard: (@Composable () -> Unit)?,
    onConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = shizuku as? ShizukuState.Ready
    val locks = PackLocks.of(ui)
    val activity = LocalContext.current.findActivity()
    val onUnlock: (String) -> Unit = { productId -> activity?.let { viewModel.unlock(it, productId) } }
    // "Describe your theme" may only pick packs this install can apply.
    val unlockedPacks = remember(ui.packs, ui.entitlement, ui.bundledPackIds, ui.officialPackIds) { ui.packs.filter { ui.isPackUnlocked(it.id) } }
    val paletteHint = remember(ui.packs, ui.spec.simplePackId) {
        ui.packs.firstOrNull { it.id == ui.spec.simplePackId }
            ?.pack?.paletteArgb?.values?.map { it or OPAQUE_ARGB }?.take(6).orEmpty()
    }
    // The first card of the step: banner, connection, tabs, then the preview card on phones.
    val contentStart = (if (ui.safeModeBanner != null) 1 else 0) + 2 + (if (previewCard != null) 1 else 0)
    // A tap on the preview lands on the element rows, the second card of Colours and Quick panel.
    LaunchedEffect(scrollTick) {
        if (scrollTick > 0) listState.animateScrollToItem(contentStart + 1)
    }
    val customGroups = ui.spec.colorGroups.count { !it.value.isAuto }
    val tabs = StudioStep.entries.map { s ->
        StepTab(
            key = s.name,
            label = s.label,
            badge = if (s == StudioStep.COLOURS && customGroups > 0) "$customGroups" else null,
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ui.safeModeBanner?.let { banner ->
            item(key = "safeMode") {
                SafeModeBanner(banner, failed = ui.safeModeFailed, onOpenDiagnostics = { viewModel.navigate(Screen.DIAGNOSTICS) }, onDismiss = viewModel::dismissSafeMode)
            }
        }
        item(key = "connection") {
            ConnectionStrip(
                shizuku = shizuku,
                ui = ui,
                expanded = connectionExpanded,
                onExpanded = onConnectionExpanded,
                onRequestPermission = viewModel::requestPermission,
            )
        }
        item(key = "steps") {
            StepTabs(tabs = tabs, selectedKey = step.name, onSelect = { key -> StudioStep.entries.firstOrNull { it.name == key }?.let(onStep) })
        }
        if (previewCard != null) {
            item(key = "preview") { previewCard() }
        }
        when (step) {
            StudioStep.SKINS -> skinsItems(viewModel, ui, unlockedPacks, locks, onUnlock)
            StudioStep.COLOURS -> coloursItems(viewModel, ui, preview, counts)
            StudioStep.QUICK_PANEL -> quickPanelItems(viewModel, ui, preview, counts)
            StudioStep.ICONS -> iconsItems(viewModel, ui, preview, paletteHint)
            StudioStep.FONTS -> fontsItems(viewModel, ui)
        }
        item(key = "apply") {
            CompiledThemeCard(
                // SU mode is Ready but cannot apply (Capability.of gives NONE): keep Apply off there.
                connected = ready != null && !ready.viaSu && ui.capability != Capability.NONE,
                busy = ui.busy,
                // Only Apply theme or Remove theme shows as running; a pack download or import only disables the buttons.
                applying = ui.serviceBusy,
                report = ui.lastApply,
                unconfirmed = ui.applyUnconfirmed,
                protectionExpired = ui.applyProtectionExpired,
                progressLine = ui.log.lastOrNull()?.text,
                onApply = viewModel::applyTheme,
                onRemove = viewModel::removeTheme,
                onConfirmWorks = viewModel::confirmWorks,
                onDetails = { viewModel.navigate(Screen.DIAGNOSTICS) },
                onConnection = onConnection,
            )
        }
        if (ui.log.isNotEmpty()) {
            item(key = "log") { LogCard(ui.log, onClear = viewModel::clearLog) }
        }
    }
}

private fun LazyListScope.skinsItems(
    viewModel: MainViewModel,
    ui: UiState,
    unlockedPacks: List<com.hexnext.themer.theme.InstalledPack>,
    locks: PackLocks,
    onUnlock: (String) -> Unit,
) {
    item(key = "describe") {
        DescribeThemeCard(
            packs = unlockedPacks,
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
    item(key = "packs") {
        PackCard(
            packs = ui.packs,
            errors = ui.packErrors,
            spec = ui.spec,
            busy = ui.busy,
            catalogue = ui.catalogue,
            locks = locks,
            onUnlock = onUnlock,
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
}

private fun LazyListScope.coloursItems(
    viewModel: MainViewModel,
    ui: UiState,
    preview: StudioPreviewState,
    counts: Map<ColorGroup, com.hexnext.engine.compile.GroupCount>,
) {
    item(key = "engine") { ColorEngineCard(viewModel, ui.spec, ui.seed, preview) }
    item(key = "elements") {
        ElementColorsCard(
            viewModel = viewModel,
            spec = ui.spec,
            preview = preview,
            counts = counts,
            onOpenResources = { viewModel.navigate(Screen.RESOURCES) },
        )
    }
}

private fun LazyListScope.quickPanelItems(
    viewModel: MainViewModel,
    ui: UiState,
    preview: StudioPreviewState,
    counts: Map<ColorGroup, com.hexnext.engine.compile.GroupCount>,
) {
    item(key = "quickPanel") { QuickPanelCard(viewModel, ui.spec, preview) }
    item(key = "quickPanelGroups") {
        ElementColorsCard(
            viewModel = viewModel,
            spec = ui.spec,
            preview = preview,
            counts = counts,
            groups = QUICK_PANEL_GROUPS,
            title = "Quick panel colours",
            subtitle = "The panel backdrop, tiles, sliders and notification cards, for light and dark.",
            eyebrow = "SystemUI · ${QUICK_PANEL_GROUPS.size} groups",
        )
    }
}

private fun LazyListScope.iconsItems(
    viewModel: MainViewModel,
    ui: UiState,
    preview: StudioPreviewState,
    paletteHint: List<Int>,
) {
    item(key = "iconStyle") { IconStyleCard(viewModel, ui.spec, preview, ui.busy, ui.capability) }
    item(key = "customIcons") {
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
            showSynthesize = false,
        )
    }
}

private fun LazyListScope.fontsItems(viewModel: MainViewModel, ui: UiState) {
    item(key = "fonts") {
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
    item(key = "backgrounds") {
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
}

/**
 * The connection, compact while everything works (root and Shizuku): a status dot, the tier and
 * the version, with Details for the full Connection card. Otherwise, or when expanded, the full
 * card with its actions and the root guide.
 */
@Composable
private fun ConnectionStrip(
    shizuku: ShizukuState,
    ui: UiState,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val ready = shizuku as? ShizukuState.Ready
    val healthy = ready != null && !ready.viaSu && ui.capability == Capability.ROOT
    if (!healthy || expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusCard(shizuku, ui.capability, ui.versionLine, ui.rootToolLinks, onRequestPermission)
            if (healthy) {
                TextButton(onClick = { onExpanded(false) }) { Text("Hide connection details") }
            }
        }
        return
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f)) {
            Text(
                "PRIVILEGE · ${ui.capability.label}",
                style = monoStyle(MaterialTheme.typography.labelSmall),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ui.versionLine,
                style = monoStyle(MaterialTheme.typography.labelSmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { onExpanded(true) }) { Text("Details") }
    }
}

/**
 * The live phone preview in a console card. On phones ([collapsible]) it shows the top 300 dp of
 * the phone until expanded; on wide screens it is shown whole. Under it: why icons may be missing,
 * and the scan action before the first scan.
 */
@Composable
private fun PreviewPanel(
    preview: StudioPreviewState,
    tab: PreviewTab,
    onTab: (PreviewTab) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    onElementTap: (ColorGroup) -> Unit,
    onScan: () -> Unit,
    collapsible: Boolean,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
) {
    val collapsed = collapsible && !expanded
    StudioCard(
        title = "Live preview",
        eyebrow = "Real-time One UI preview",
        subtitle = "Tap any part of the phone to edit its colour.",
        trailing = if (collapsible) ({ TextButton(onClick = { onExpand(!expanded) }) { Text(if (expanded) "Collapse" else "Expand") } }) else null,
    ) {
        if (collapsed) {
            val fade = MaterialTheme.colorScheme.surfaceContainer
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clipToBounds(),
            ) {
                PhonePreview(
                    state = preview,
                    tab = tab,
                    onTabChange = onTab,
                    dark = dark,
                    onDarkChange = onDark,
                    onElementTap = onElementTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, fade))),
                )
            }
            TextButton(onClick = { onExpand(true) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Show the whole phone")
            }
        } else {
            PhonePreview(
                state = preview,
                tab = tab,
                onTabChange = onTab,
                dark = dark,
                onDarkChange = onDark,
                onElementTap = onElementTap,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!preview.hasScan) {
            EmptyState(
                icon = Icons.Outlined.Search,
                text = "Colours are live already. Scan your phone once to preview your pack's icons too.",
                actionLabel = "Scan phone",
                onAction = onScan,
            )
        } else {
            preview.note?.let { note ->
                Text(
                    note.replace("device scan", "phone scan"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (note.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
