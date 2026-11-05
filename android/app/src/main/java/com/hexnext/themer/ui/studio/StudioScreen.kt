package com.hexnext.themer.ui.studio

import com.hexnext.themer.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.TargetResult
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.Capability
import com.hexnext.themer.ui.ApplyBar
import com.hexnext.themer.ui.LocalAppSettings
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.NoticeAction
import com.hexnext.themer.ui.SafeModeBanner
import com.hexnext.themer.ui.Screen
import com.hexnext.themer.ui.StatusCard
import com.hexnext.themer.ui.StatusPill
import com.hexnext.themer.ui.UiState
import com.hexnext.themer.ui.findActivity
import com.hexnext.themer.billing.ProductIds
import com.hexnext.themer.ui.statusPillText

/** The bottom-navigation destinations of the shell (§ 1.2), in order. Saved by name. */
internal enum class Destination(val label: String, val icon: ImageVector) {
    STUDIO("Studio", Icons.Outlined.Home),
    SKINS("Skins", Icons.Outlined.Star),
    COLOURS("Colours", Icons.Outlined.Create),
    ICONS("Icons", Icons.Outlined.Face),
    SYSTEM("System", Icons.Outlined.Build),
}

/** The sub-tabs of Colours. */
internal enum class ColoursTab(val label: String) {
    ENGINE("Engine"),
    ELEMENTS("Elements"),
    QUICK_PANEL("Quick panel"),
    SHADES("Shades"),
}

/** The sub-tabs of Icons. */
internal enum class IconsTab(val label: String) {
    STYLE("Style"),
    CUSTOM("Custom icons"),
    FONTS("Fonts"),
    BACKGROUNDS("Backgrounds"),
}

/** The sub-tabs of System. */
internal enum class SystemTab(val label: String) {
    TARGETS("Targets"),
    TOOLS("Tools"),
}

/** Width from which the shell shows a navigation rail and two panes. */
internal val WIDE_WIDTH = 840.dp

/**
 * Below this height (a phone in landscape) the stacked chrome would leave almost no room for the
 * page, so the shell drops the top bar and the bottom navigation for a rail.
 */
internal val SHORT_HEIGHT = 480.dp

/** Width from which a short window also splits into two panes. */
private val SHORT_TWO_PANE_WIDTH = 600.dp

/**
 * The shell after the setup guide (Studio v2 phase 2 § 1): the top bar with the status pill and
 * App settings, the safe-mode banner, the status strip, one destination, the persistent Apply bar
 * and the bottom navigation (a navigation rail from 840 dp).
 *
 * Everything the old editor did is still here: snackbar notices with their actions and haptics,
 * Apply / Remove / "It works", the pack, colour, icon, background and font cards, and App settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(viewModel: MainViewModel, ui: UiState, shizuku: ShizukuState) {
    // Collected here but never read here: only the composables that draw it read the value (through
    // PreviewModel.state), so a drag tick recomposes the phone and the cards, not the whole shell.
    val previewState = viewModel.studioPreview.collectAsStateWithLifecycle()
    val ready = shizuku as? ShizukuState.Ready
    val appSettings = LocalAppSettings.current
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var destination by rememberSaveable { mutableStateOf(Destination.STUDIO) }
    var coloursTab by rememberSaveable { mutableStateOf(ColoursTab.ENGINE) }
    var iconsTab by rememberSaveable { mutableStateOf(IconsTab.STYLE) }
    var systemTab by rememberSaveable { mutableStateOf(SystemTab.TARGETS) }
    var previewTab by rememberSaveable { mutableStateOf(PreviewTab.SETTINGS) }
    var previewDark by rememberSaveable { mutableStateOf(true) }
    // Icons are rendered from the night or day jobs to match the mockup's mode.
    LaunchedEffect(previewDark) { viewModel.setPreviewDark(previewDark) }
    var peekCollapsed by rememberSaveable { mutableStateOf(false) }
    // A committed edit (a picked option, a released slider) opens the peek to the whole phone.
    // Held here, not in the peek's list item, so an edit made while the peek is scrolled away
    // still counts; only edits newer than the last one seen open it.
    var peekFull by rememberSaveable { mutableStateOf(false) }
    val edits by viewModel.edits.collectAsStateWithLifecycle()
    var editsSeen by rememberSaveable { mutableIntStateOf(edits) }
    LaunchedEffect(edits) {
        if (edits > editsSeen) {
            peekFull = true
            peekCollapsed = false
        }
        editsSeen = edits
    }
    var showPrivilege by rememberSaveable { mutableStateOf(false) }
    // The Studio's tap-to-edit sheet: "<group id>:<night>".
    var studioPick by rememberSaveable { mutableStateOf<String?>(null) }
    // The icon tapped on the phone ("pkg:drawable/name|Label"), for IconChooserSheet.
    var iconPick by rememberSaveable { mutableStateOf<String?>(null) }
    var elementsTick by remember { mutableIntStateOf(0) }
    // The last phone tap the Colours page has scrolled for; only a newer tap scrolls again.
    var elementsTickHandled by remember { mutableIntStateOf(0) }
    // Each destination and sub-tab keeps its own scroll position while another is on display.
    val pages = rememberSaveableStateHolder()

    // On Skins, Colours, Icons and System, Back returns to Studio; on Studio it leaves as before.
    BackHandler(enabled = destination != Destination.STUDIO) { destination = Destination.STUDIO }

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
                NoticeAction.UNLOCK_PACK -> {
                    val packId = current.packId
                    if (activity != null && packId != null) viewModel.unlock(activity, ProductIds.forPack(packId))
                }
                null -> Unit
            }
        }
    }

    // The Quick panel sub-tab shows the Quick panel on the phone; leaving it brings back the phone
    // tab that was showing before, unless the user picked another one meanwhile.
    var previewTabBeforeQs by rememberSaveable { mutableStateOf<PreviewTab?>(null) }
    LaunchedEffect(destination, coloursTab) {
        val onQuickPanel = destination == Destination.COLOURS && coloursTab == ColoursTab.QUICK_PANEL
        if (onQuickPanel) {
            if (previewTab != PreviewTab.QUICK_PANEL) {
                previewTabBeforeQs = previewTab
                previewTab = PreviewTab.QUICK_PANEL
            }
        } else {
            val before = previewTabBeforeQs
            if (before != null && previewTab == PreviewTab.QUICK_PANEL) previewTab = before
            previewTabBeforeQs = null
        }
    }

    val openTargets: () -> Unit = {
        destination = Destination.SYSTEM
        systemTab = SystemTab.TARGETS
    }
    val previewModel = remember(previewState, previewTab, previewDark, peekFull, viewModel) {
        PreviewModel(
            stateRef = previewState,
            tab = previewTab,
            onTab = { previewTab = it },
            dark = previewDark,
            onDark = { previewDark = it },
            onScan = viewModel::openScan,
            peekFull = peekFull,
            onPeekFull = { peekFull = it },
        )
    }
    // Studio edits in place; Colours and Icons jump to the element's row (§ 1.3). Remembered: the
    // lambda reads destination, tabs and mode from their state holders when it runs.
    val onElementTap: (ColorGroup) -> Unit = remember(viewModel) { { group ->
        viewModel.setHighlightedGroup(group)
        if (destination == Destination.STUDIO) {
            studioPick = "${group.id}:$previewDark"
        } else {
            destination = Destination.COLOURS
            coloursTab = when {
                previewTab == PreviewTab.QUICK_PANEL && group in QUICK_PANEL_GROUPS -> ColoursTab.QUICK_PANEL
                coloursTab == ColoursTab.QUICK_PANEL && group in QUICK_PANEL_GROUPS -> ColoursTab.QUICK_PANEL
                group.quickSettings -> ColoursTab.QUICK_PANEL
                else -> ColoursTab.ELEMENTS
            }
            elementsTick++
        }
    } }
    val connected = ready != null && !ready.viaSu && ui.capability != Capability.NONE
    // With the keyboard up the bars step aside, so the field being typed in stays in view.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A short window (landscape phone) trades the top bar and bottom navigation for a rail,
        // and splits into two panes when it is wide enough, so the page keeps usable height.
        val short = maxHeight < SHORT_HEIGHT
        val rail = maxWidth >= WIDE_WIDTH || short
        val wide = maxWidth >= WIDE_WIDTH || (short && maxWidth >= SHORT_TWO_PANE_WIDTH)
        // The app is edge-to-edge: keep Apply and Remove clear of a side navigation bar or cutout
        // (landscape), and of the bottom bar too when no NavigationBar sits below to absorb it.
        val applyBarInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
            .only(if (rail) WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal)
        // The first pack of the theme that is not unlocked: the bar offers Unlock instead of Apply.
        val previewOnlyPack = remember(ui.spec, ui.entitlement, ui.bundledPackIds, ui.officialPackIds) { ui.previewOnlyPacksInTheme().firstOrNull() }
        val applyBar: @Composable () -> Unit = {
            // Read here, so a new log line recomposes only the Apply bar.
            val progressLine by viewModel.lastLogLine.collectAsStateWithLifecycle()
            Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).windowInsetsPadding(applyBarInsets)) {
                ApplyBar(
                    // SU mode is Ready but cannot apply (Capability.of gives NONE): keep Apply off there.
                    connected = connected,
                    busy = ui.busy,
                    // Only Apply theme or Remove theme shows as running; a pack download or import only disables the buttons.
                    applying = ui.serviceBusy,
                    report = ui.lastApply,
                    unconfirmed = ui.applyUnconfirmed,
                    protectionExpired = ui.applyProtectionExpired,
                    progressLine = progressLine,
                    onApply = viewModel::applyTheme,
                    onRemove = viewModel::removeTheme,
                    onConfirmWorks = viewModel::confirmWorks,
                    onDetails = openTargets,
                    onConnection = { showPrivilege = true },
                    previewOnlyPack = previewOnlyPack?.let { ui.packDisplayName(it) },
                    previewOnlyPrice = previewOnlyPack?.let { ui.entitlement.priceOf(it) },
                    previewOnlyPending = previewOnlyPack?.let { ui.entitlement.isPending(it) } == true,
                    onUnlock = if (ui.canPurchase && previewOnlyPack != null && activity != null) {
                        { viewModel.unlock(activity, ProductIds.forPack(previewOnlyPack)) }
                    } else null,
                )
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            // The keyboard counts as an inset, so the hex, search and name fields scroll clear of it.
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                // Short windows move App settings to the rail header; the strip still shows privilege.
                if (!short) TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.semantics(mergeDescendants = true) {},
                        ) {
                            AppLogo(Modifier.size(32.dp))
                            Text("Hex Next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            StatusPill(text = statusPillText(ui.versionLine, shizuku), ok = ready != null && ui.capability != Capability.NONE)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.navigate(Screen.SETTINGS) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "App settings")
                        }
                    },
                )
            },
            bottomBar = {
                if (!imeVisible) {
                    Column {
                        applyBar()
                        if (!rail) {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                Destination.entries.forEach { entry ->
                                    NavigationBarItem(
                                        selected = destination == entry,
                                        onClick = { destination = entry },
                                        icon = { Icon(entry.icon, contentDescription = null) },
                                        label = { Text(entry.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (rail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.background,
                        header = if (short) {
                            {
                                // No top bar on short windows, so the logo leads the rail instead.
                                AppLogo(Modifier.padding(top = 8.dp).size(32.dp))
                                IconButton(onClick = { viewModel.navigate(Screen.SETTINGS) }) {
                                    Icon(Icons.Outlined.Settings, contentDescription = "App settings")
                                }
                            }
                        } else {
                            null
                        },
                    ) {
                        Destination.entries.forEach { entry ->
                            NavigationRailItem(
                                selected = destination == entry,
                                onClick = { destination = entry },
                                icon = { Icon(entry.icon, contentDescription = null) },
                                label = { Text(entry.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxSize()) {
                    ui.safeModeBanner?.let { banner ->
                        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                            SafeModeBanner(banner, failed = ui.safeModeFailed, onOpenDiagnostics = { viewModel.navigate(Screen.DIAGNOSTICS) }, onDismiss = viewModel::dismissSafeMode)
                        }
                    }
                    StatusStrip(
                        ui = ui,
                        onPrivilege = { showPrivilege = true },
                        onTargets = openTargets,
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            com.hexnext.themer.ui.preview.LocalIconTap provides { key, label -> iconPick = "$key|$label" },
                        ) {
                        pages.SaveableStateProvider(
                            when (destination) {
                                Destination.COLOURS -> "COLOURS:${coloursTab.name}"
                                Destination.ICONS -> "ICONS:${iconsTab.name}"
                                Destination.SYSTEM -> "SYSTEM:${systemTab.name}"
                                else -> destination.name
                            },
                        ) {
                            when (destination) {
                                Destination.STUDIO -> StudioPage(
                                    viewModel, ui, previewModel, onElementTap, wide,
                                    applyBlockedReason = when {
                                        !connected -> "Connect root or Shizuku to apply. The preview above already shows the result."
                                        previewOnlyPack != null -> "Unlock ${ui.packDisplayName(previewOnlyPack)} to apply this theme. The preview above already shows the result."
                                        else -> null
                                    },
                                )
                                Destination.SKINS -> SkinsPage(
                                    viewModel = viewModel,
                                    ui = ui,
                                    preview = previewModel,
                                    onElementTap = onElementTap,
                                    peekCollapsed = peekCollapsed,
                                    onPeekCollapsed = { peekCollapsed = it },
                                    wide = wide,
                                )
                                Destination.COLOURS -> ColoursPage(
                                    viewModel = viewModel,
                                    ui = ui,
                                    preview = previewModel,
                                    tab = coloursTab,
                                    onTab = { coloursTab = it },
                                    onElementTap = onElementTap,
                                    elementsTick = elementsTick,
                                    elementsTickHandled = elementsTickHandled,
                                    onElementsTickHandled = { handled -> if (handled > elementsTickHandled) elementsTickHandled = handled },
                                    peekCollapsed = peekCollapsed,
                                    onPeekCollapsed = { peekCollapsed = it },
                                    wide = wide,
                                )
                                Destination.ICONS -> IconsPage(
                                    viewModel = viewModel,
                                    ui = ui,
                                    preview = previewModel,
                                    tab = iconsTab,
                                    onTab = { iconsTab = it },
                                    onElementTap = onElementTap,
                                    peekCollapsed = peekCollapsed,
                                    onPeekCollapsed = { peekCollapsed = it },
                                    wide = wide,
                                )
                                Destination.SYSTEM -> SystemPage(
                                    viewModel = viewModel,
                                    ui = ui,
                                    shizuku = shizuku,
                                    tab = systemTab,
                                    onTab = { systemTab = it },
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    // The Studio's in-place colour sheet for the element tapped on the phone.
    val pick = studioPick?.let { saved ->
        ColorGroup.byId(saved.substringBefore(':'))?.let { it to (saved.substringAfter(':') == "true") }
    }
    if (pick != null) {
        StudioPickSheet(viewModel, ui, previewModel, pick.first, pick.second, onClose = { studioPick = null })
    }

    iconPick?.let { saved ->
        IconChooserSheet(
            viewModel, ui,
            slotKey = saved.substringBefore('|'),
            label = saved.substringAfter('|'),
            night = previewDark,
            onClose = { iconPick = null },
        )
    }

    if (showPrivilege) {
        ModalBottomSheet(
            onDismissRequest = { showPrivilege = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                StatusCard(shizuku, ui.capability, ui.versionLine, ui.rootToolLinks, viewModel::requestPermission)
            }
        }
    }
}

/**
 * The status strip (§ 1.3): the connection tier, "Themed n/m" after an apply and "Protection on"
 * while restart protection is armed. One horizontally scrolling row; each chip is 32 dp high in a
 * 48 dp touch area and always carries a word.
 */
@Composable
private fun StatusStrip(
    ui: UiState,
    onPrivilege: () -> Unit,
    onTargets: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StripChip(
            text = "Connection · ${ui.capability.label}",
            tone = when (ui.capability) {
                Capability.ROOT -> StatusTone.OK
                Capability.NONE -> StatusTone.BAD
                else -> StatusTone.WARN
            },
            onClickLabel = "Show connection",
            onClick = onPrivilege,
        )
        ui.lastApply?.let { report ->
            val failed = report.outcomes.count { it.result == TargetResult.FAILED }
            StripChip(
                text = "Themed ${report.okCount}/${report.okCount + report.failedCount}",
                tone = when {
                    !report.finished || failed > 0 -> StatusTone.BAD
                    report.partialCount > 0 -> StatusTone.WARN
                    else -> StatusTone.OK
                },
                onClickLabel = "Show targets",
                onClick = onTargets,
            )
        }
        if (ui.applyUnconfirmed && !ui.applyProtectionExpired) {
            StripChip(
                text = "Protection on",
                tone = StatusTone.WARN,
                onClickLabel = "Show restart protection",
                // Opens System > Targets, whose Restart protection card holds the explicit
                // "It works" button: a tap on a status chip never disarms the guard.
                onClick = onTargets,
            )
        }
    }
}

/** One chip of [StatusStrip]: a dot and a word in the tone colour, 32 dp inside a 48 dp target. */
@Composable
private fun StripChip(text: String, tone: StatusTone, onClickLabel: String, onClick: () -> Unit) {
    val color = statusColor(tone)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickableChip(onClickLabel, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        }
    }
}

private fun Modifier.clickableChip(label: String, onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick))

/**
 * The Studio's tap-to-edit colour sheet. Its own composable, so the preview it reads while a colour
 * is dragged recomposes only the sheet.
 */
@Composable
private fun StudioPickSheet(viewModel: MainViewModel, ui: UiState, preview: PreviewModel, group: ColorGroup, night: Boolean, onClose: () -> Unit) {
    GroupColorSheet(viewModel, ui.spec, preview.state, group, night, onClose = onClose)
}

/**
 * The preview state the destinations share: one phone tab and one light/dark choice for all.
 * [state] reads [stateRef] when it is called, so only the composables that use it recompose when
 * the preview changes.
 */
@Stable
internal class PreviewModel(
    private val stateRef: State<StudioPreviewState>,
    val tab: PreviewTab,
    val onTab: (PreviewTab) -> Unit,
    val dark: Boolean,
    val onDark: (Boolean) -> Unit,
    val onScan: () -> Unit,
    /** The peek shows the whole phone (after an edit) rather than its short top. */
    val peekFull: Boolean = false,
    val onPeekFull: (Boolean) -> Unit = {},
) {
    val state: StudioPreviewState get() = stateRef.value
}

/** Content padding of every destination list: the 16 dp gutter and room under the last card. */
internal val PAGE_PADDING = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)

/** The Hex Next app icon (hexagon with the NEXT ribbon), shown at the leading edge of the top bar. */
@Composable
private fun AppLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.hx_app_logo),
        contentDescription = null,
        modifier = modifier.clip(CircleShape),
    )
}
