package com.hexnext.themer.ui.studio

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.hexnext.engine.compile.ApplyReport
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.TargetResult
import com.hexnext.themer.R
import com.hexnext.themer.ServiceTasks
import com.hexnext.themer.billing.PackAccess
import com.hexnext.themer.billing.PackPricing
import com.hexnext.themer.billing.ProductIds
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.Capability
import com.hexnext.themer.ui.ApplyBar
import com.hexnext.themer.ui.DiagnosticsViewModel
import com.hexnext.themer.ui.LocalAppSettings
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.NoticeAction
import com.hexnext.themer.ui.SafeModeBanner
import com.hexnext.themer.ui.Screen
import com.hexnext.themer.ui.StatusCard
import com.hexnext.themer.ui.UiState
import com.hexnext.themer.ui.findActivity
import com.hexnext.themer.ui.preview.LocalIconTap
import com.hexnext.themer.ui.rememberHapticTap
import com.hexnext.themer.ui.restartProtectionText
import com.hexnext.themer.ui.settings.AppSettingsStore

/** Intent extra naming the [Destination] to open first. */
internal const val EXTRA_DESTINATION = "hexnext.destination"

/**
 * The four bottom-navigation destinations, in order: what the user is doing, not how the engine
 * is organised. Theme (preview and colours), Packs, Library (icons, fonts, backgrounds) and
 * Settings (this phone's themed apps, tools, and Hex Next's own settings). Saved by name.
 */
internal enum class Destination(val label: String, @DrawableRes val icon: Int) {
    THEME("Theme", R.drawable.hx_nav_theme),
    PACKS("Packs", R.drawable.hx_nav_packs),
    LIBRARY("Library", R.drawable.hx_nav_library),
    SETTINGS("Settings", R.drawable.hx_nav_settings);

    companion object {
        /**
         * The destination for [name], which may be an older page name (the device lab opens
         * pages as `hexnext:<PAGE>`): that content's new home opens instead. Null for no or an
         * unknown name.
         */
        fun parse(name: String?): Destination? = when (name) {
            null -> null
            "STUDIO", "COLOURS" -> THEME
            "SKINS" -> PACKS
            "ICONS" -> LIBRARY
            "SYSTEM" -> SETTINGS
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

/** The sub-tabs of Theme: the colour engine, the element colours, the Quick panel and the AI chat. */
internal enum class ThemeTab(val label: String) {
    PALETTE("Palette"),
    ELEMENTS("Elements"),
    QUICK_PANEL("Quick panel"),
    AI("AI"),
}

/** The sub-tabs of Library. */
internal enum class LibraryTab(val label: String) {
    ICONS("Icons"),
    FONTS("Fonts"),
    BACKGROUNDS("Backgrounds"),
}

/** The sub-tabs of Settings: this phone's themed apps, the tools, and Hex Next's own settings. */
internal enum class SettingsTab(val label: String) {
    APPS("Apps"),
    TOOLS("Tools"),
    APP("Hex Next"),
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
 * The shell after the setup guide: a top bar with one status word, the safe-mode banner, one
 * destination, the persistent Apply bar and the bottom navigation (a navigation rail from 840 dp).
 *
 * Every page edits in place. A tap on the phone preview opens that element's colour sheet right
 * where the user is; the status word opens one status sheet; a phone scan runs in place and says
 * so in the status word. The only full screens left are Diagnostics, the per-app colour editor,
 * the AI provider keys and the setup guide, each with its own Back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(viewModel: MainViewModel, ui: UiState, shizuku: ShizukuState) {
    if (Capability.of(shizuku) == Capability.SHELL) {
        MonetOnlyScreen(viewModel, ui, shizuku)
        return
    }
    // Collected here but never read here: only the composables that draw it read the value (through
    // PreviewModel.state), so a drag tick recomposes the phone and the cards, not the whole shell.
    val previewState = viewModel.studioPreview.collectAsStateWithLifecycle()
    val ready = shizuku as? ShizukuState.Ready
    val appSettings = LocalAppSettings.current
    val haptic = LocalHapticFeedback.current
    val tap = rememberHapticTap()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val settingsStore = remember(context) { AppSettingsStore.get(context) }
    LaunchedEffect(activity) { viewModel.bindBillingActivity(activity) }

    // The scan runs on the activity-scoped Diagnostics model, right here: no screen change, the
    // status word says "Scanning…" meanwhile and a snackbar reports the end.
    val diagnostics: DiagnosticsViewModel = composeViewModel()
    val dstate by diagnostics.state.collectAsStateWithLifecycle()
    val serviceTask by ServiceTasks.running.collectAsStateWithLifecycle()
    val scanning = dstate.busy && dstate.task == "Scan"
    LaunchedEffect(dstate.scanFinishedId) {
        val id = dstate.scanFinishedId
        if (id == 0L) return@LaunchedEffect
        try {
            snackbar.showSnackbar("Scan finished. Previews now use your phone's icons.", duration = SnackbarDuration.Short)
        } finally {
            if (activity?.isChangingConfigurations != true) diagnostics.consumeScanFinished(id)
        }
    }

    // `hexnext.destination` (a Destination name, old page names included) opens that page first.
    var destination by rememberSaveable {
        mutableStateOf(Destination.parse(activity?.intent?.getStringExtra(EXTRA_DESTINATION)) ?: Destination.THEME)
    }
    var themeTab by rememberSaveable { mutableStateOf(ThemeTab.PALETTE) }
    var libraryTab by rememberSaveable { mutableStateOf(LibraryTab.ICONS) }
    var settingsTab by rememberSaveable { mutableStateOf(SettingsTab.APPS) }
    var previewTab by rememberSaveable { mutableStateOf(PreviewTab.SETTINGS) }
    var previewDark by rememberSaveable { mutableStateOf(true) }
    // Icons are rendered from the night or day jobs to match the mockup's mode.
    LaunchedEffect(previewDark) { viewModel.setPreviewDark(previewDark) }
    var peekCollapsed by rememberSaveable { mutableStateOf(false) }
    var showStatus by rememberSaveable { mutableStateOf(false) }
    // The in-place colour sheet for the element tapped on the phone: "<group id>:<night>".
    var groupPick by rememberSaveable { mutableStateOf<String?>(null) }
    // The icon tapped on the phone ("pkg:drawable/name|Label"), for IconChooserSheet.
    var iconPick by rememberSaveable { mutableStateOf<String?>(null) }
    var elementsTick by remember { mutableIntStateOf(0) }
    // The last phone tap the Elements list has scrolled for; only a newer tap scrolls again.
    var elementsTickHandled by remember { mutableIntStateOf(0) }
    // Each destination and sub-tab keeps its own scroll position while another is on display.
    val pages = rememberSaveableStateHolder()

    // On Packs, Library and Settings, Back returns to Theme; on Theme it leaves as before.
    BackHandler(enabled = destination != Destination.THEME) { destination = Destination.THEME }

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
    LaunchedEffect(destination, themeTab) {
        val onQuickPanel = destination == Destination.THEME && themeTab == ThemeTab.QUICK_PANEL
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

    val connected = ready != null && !ready.viaSu && ui.capability != Capability.NONE
    // A scan needs the service too: without a connection the status sheet explains what to do.
    val scanPhone: () -> Unit = {
        if (ready != null) diagnostics.runScan() else showStatus = true
    }
    val previewModel = remember(previewState, previewTab, previewDark, scanning, viewModel) {
        PreviewModel(
            stateRef = previewState,
            tab = previewTab,
            onTab = { previewTab = it },
            dark = previewDark,
            onDark = { previewDark = it },
            onScan = scanPhone,
            scanning = scanning,
        )
    }
    // Every tap on the phone edits in place: the element's colour sheet opens over the page the
    // user is on. On Theme > Elements the element's row is scrolled into view as well.
    val onElementTap: (ColorGroup) -> Unit = remember(viewModel) { { group ->
        viewModel.setHighlightedGroup(group)
        groupPick = "${group.id}:$previewDark"
        if (destination == Destination.THEME && themeTab == ThemeTab.ELEMENTS) elementsTick++
    } }
    // With the keyboard up the bars step aside, so the field being typed in stays in view.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val status = shellStatus(ui, shizuku, serviceTask, if (dstate.busy) dstate.task else null)

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
        val lockedPacks = remember(ui.spec, ui.seed, ui.entitlement, ui.bundledPackIds, ui.officialPackIds) {
            PackAccess.lockedPacksIn(ui.spec.copy(source = ui.spec.source.copy(seedArgb = ui.seed))) { ui.isPackUnlocked(it) }
        }
        val previewOnlyPack = lockedPacks.singleOrNull()
        val applyBar: @Composable () -> Unit = {
            // Read here, so a new log line recomposes only the Apply bar.
            val progressLine by viewModel.lastLogLine.collectAsStateWithLifecycle()
            Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).windowInsetsPadding(applyBarInsets)) {
                ApplyBar(
                    // SU mode is Ready but cannot apply (Capability.of gives NONE): keep Apply off there.
                    connected = connected,
                    // A scan or another Diagnostics task holds the service too.
                    busy = ui.busy || serviceTask != null,
                    // Only Apply theme or Remove theme shows as running; a pack download or import only disables the buttons.
                    applying = ui.serviceBusy,
                    report = ui.lastApply,
                    unconfirmed = ui.applyUnconfirmed,
                    protectionExpired = ui.applyProtectionExpired,
                    progressLine = progressLine,
                    onApply = {
                        if (appSettings.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.applyTheme()
                    },
                    onRemove = viewModel::removeTheme,
                    onConfirmWorks = viewModel::confirmWorks,
                    onDetails = { showStatus = true },
                    onConnection = { showStatus = true },
                    previewOnlyPack = previewOnlyPack?.let { ui.packDisplayName(it) },
                    lockedPackCount = lockedPacks.size,
                    lockedPackPrice = if (lockedPacks.isEmpty()) null else PackPricing.labelForOffer(
                        lockedPacks.size,
                        ui.entitlement.prices,
                        lockedPacks.singleOrNull(),
                    ),
                    previewOnlyPending = lockedPacks.any { ui.entitlement.isPending(it) } ||
                        (lockedPacks.size >= PackPricing.ALL_FROM && ProductIds.ALL_PACKS in ui.entitlement.pendingProductIds),
                )
            }
        }
        val select: (Destination) -> Unit = { entry ->
            tap()
            destination = entry
        }
        val navIcon: @Composable (Destination) -> Unit = { entry -> Icon(painterResource(entry.icon), contentDescription = null) }
        val navLabel: @Composable (Destination) -> Unit = { entry -> Text(entry.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            // The keyboard counts as an inset, so the hex, search and name fields scroll clear of it.
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                // Short windows move the status word to the rail header.
                if (!short) TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppLogo(Modifier.size(32.dp))
                            Text("Hex Next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    },
                    actions = {
                        Box(Modifier.padding(end = 12.dp)) {
                            StatusWord(status, onClick = { showStatus = true })
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
                                        onClick = { select(entry) },
                                        icon = { navIcon(entry) },
                                        label = { navLabel(entry) },
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
                                // No top bar on short windows, so the logo and status lead the rail.
                                AppLogo(Modifier.padding(top = 8.dp).size(32.dp))
                                Box(Modifier.padding(top = 8.dp)) {
                                    StatusWord(status, onClick = { showStatus = true }, compact = true)
                                }
                            }
                        } else {
                            null
                        },
                    ) {
                        Destination.entries.forEach { entry ->
                            NavigationRailItem(
                                selected = destination == entry,
                                onClick = { select(entry) },
                                icon = { navIcon(entry) },
                                label = { navLabel(entry) },
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
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CompositionLocalProvider(
                            LocalIconTap provides { key, label -> iconPick = "$key|$label" },
                        ) {
                            val page: @Composable (Destination) -> Unit = { dest ->
                                pages.SaveableStateProvider(
                                    when (dest) {
                                        Destination.THEME -> "THEME:${themeTab.name}"
                                        Destination.LIBRARY -> "LIBRARY:${libraryTab.name}"
                                        Destination.SETTINGS -> "SETTINGS:${settingsTab.name}"
                                        else -> dest.name
                                    },
                                ) {
                                    when (dest) {
                                        Destination.THEME -> ThemePage(
                                            viewModel = viewModel,
                                            ui = ui,
                                            preview = previewModel,
                                            tab = themeTab,
                                            onTab = { themeTab = it },
                                            onElementTap = onElementTap,
                                            elementsTick = elementsTick,
                                            elementsTickHandled = elementsTickHandled,
                                            onElementsTickHandled = { handled -> if (handled > elementsTickHandled) elementsTickHandled = handled },
                                            peekCollapsed = peekCollapsed,
                                            onPeekCollapsed = { peekCollapsed = it },
                                            wide = wide,
                                            applyBlockedReason = when {
                                                !connected -> "Connect root or Shizuku to apply. The preview already shows the result."
                                                previewOnlyPack != null -> "Unlock ${ui.packDisplayName(previewOnlyPack)} to apply this theme. The preview already shows the result."
                                                else -> null
                                            },
                                        )
                                        Destination.PACKS -> PacksPage(
                                            viewModel = viewModel,
                                            ui = ui,
                                            preview = previewModel,
                                            onElementTap = onElementTap,
                                            peekCollapsed = peekCollapsed,
                                            onPeekCollapsed = { peekCollapsed = it },
                                            wide = wide,
                                        )
                                        Destination.LIBRARY -> LibraryPage(
                                            viewModel = viewModel,
                                            ui = ui,
                                            preview = previewModel,
                                            tab = libraryTab,
                                            onTab = { libraryTab = it },
                                            onElementTap = onElementTap,
                                            peekCollapsed = peekCollapsed,
                                            onPeekCollapsed = { peekCollapsed = it },
                                            wide = wide,
                                        )
                                        Destination.SETTINGS -> SettingsPage(
                                            viewModel = viewModel,
                                            ui = ui,
                                            shizuku = shizuku,
                                            settingsStore = settingsStore,
                                            tab = settingsTab,
                                            onTab = { settingsTab = it },
                                            onScan = scanPhone,
                                            scanning = scanning,
                                        )
                                    }
                                }
                            }
                            if (appSettings.reduceMotion) {
                                page(destination)
                            } else {
                                AnimatedContent(
                                    targetState = destination,
                                    transitionSpec = {
                                        val forward = targetState.ordinal >= initialState.ordinal
                                        val slide = tween<IntOffset>(260)
                                        (slideInHorizontally(slide) { full -> if (forward) full / 5 else -full / 5 } + fadeIn(tween(200)))
                                            .togetherWith(slideOutHorizontally(slide) { full -> if (forward) -full / 6 else full / 6 } + fadeOut(tween(160)))
                                    },
                                    label = "destination",
                                ) { dest -> page(dest) }
                            }
                        }
                    }
                }
            }
        }
    }

    // The in-place colour sheet for the element tapped on the phone, whatever page is showing.
    val pick = groupPick?.let { saved ->
        ColorGroup.byId(saved.substringBefore(':'))?.let { it to (saved.substringAfter(':') == "true") }
    }
    if (pick != null) {
        GroupPickSheet(viewModel, ui, previewModel, pick.first, pick.second, onClose = { groupPick = null })
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

    if (showStatus) {
        StatusSheet(
            ui = ui,
            shizuku = shizuku,
            status = status,
            onRequestPermission = viewModel::requestPermission,
            onConfirmWorks = viewModel::confirmWorks,
            onShowApps = {
                showStatus = false
                destination = Destination.SETTINGS
                settingsTab = SettingsTab.APPS
            },
            onDiagnostics = {
                showStatus = false
                viewModel.navigate(Screen.DIAGNOSTICS)
            },
            onDismiss = { showStatus = false },
        )
    }
}

// ---- Status ----

/** The one status word of the shell and its tone. */
internal data class ShellStatus(val text: String, val tone: StatusTone)

/**
 * The status word in plain words, first match wins: what is running now, then the connection,
 * then what the last apply left behind. [serviceTask] is [ServiceTasks.running]; [diagnosticsTask]
 * the Diagnostics task running, if any ("Scan").
 */
internal fun shellStatus(ui: UiState, shizuku: ShizukuState, serviceTask: String?, diagnosticsTask: String?): ShellStatus {
    if (ui.serviceBusy) return ShellStatus("${(ui.serviceTask ?: "Working").substringBefore(' ')}…", StatusTone.INFO)
    if (diagnosticsTask != null) {
        return ShellStatus(if (diagnosticsTask == "Scan") "Scanning…" else "$diagnosticsTask…", StatusTone.INFO)
    }
    if (serviceTask == MainViewModel.STARTUP_TASK) return ShellStatus("Checking…", StatusTone.INFO)
    when (shizuku) {
        ShizukuState.PermissionNeeded, ShizukuState.PermissionDenied -> return ShellStatus("Allow Shizuku", StatusTone.WARN)
        ShizukuState.TooOld -> return ShellStatus("Update Shizuku", StatusTone.WARN)
        ShizukuState.NotRunning -> return ShellStatus("Not connected", StatusTone.BAD)
        is ShizukuState.Ready -> when {
            shizuku.viaSu -> return ShellStatus("Start Shizuku", StatusTone.WARN)
            ui.capability == Capability.NONE -> return ShellStatus("No root", StatusTone.BAD)
            ui.capability == Capability.SHELL -> return ShellStatus("Palette only", StatusTone.WARN)
        }
    }
    if (ui.applyUnconfirmed && !ui.applyProtectionExpired) return ShellStatus("Check your phone", StatusTone.WARN)
    val report = ui.lastApply ?: return ShellStatus("Ready", StatusTone.OK)
    return when {
        !report.finished || report.failedCount > 0 -> ShellStatus("Needs attention", StatusTone.BAD)
        report.partialCount > 0 -> ShellStatus("Partly applied", StatusTone.WARN)
        else -> ShellStatus("Applied", StatusTone.OK)
    }
}

/** The tappable status word of the top bar: a dot and the word in the tone colour, 48 dp target. */
@Composable
private fun StatusWord(status: ShellStatus, onClick: () -> Unit, compact: Boolean = false) {
    val color = statusColor(status.tone)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = "Show status", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Status: ${status.text}" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            if (!compact) Text(status.text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        }
    }
}

/**
 * The status sheet: the connection, what the last apply did, the restart-protection check and
 * the one door to Diagnostics. Everything the old status strip, pill and System tab reported, in
 * one place, reached from the status word and from the Apply bar's summary.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StatusSheet(
    ui: UiState,
    shizuku: ShizukuState,
    status: ShellStatus,
    onRequestPermission: () -> Unit,
    onConfirmWorks: () -> Unit,
    onShowApps: () -> Unit,
    onDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Status", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StatusChip(status.text, status.tone)
            }
            StatusCard(shizuku, ui.capability, ui.versionLine, onRequestPermission)
            if (ui.applyUnconfirmed) {
                StudioCard(
                    title = "Check your phone",
                    trailing = { StatusChip(if (ui.applyProtectionExpired) "Window over" else "Protection on", StatusTone.WARN) },
                ) {
                    Text(
                        restartProtectionText(unconfirmed = true, expired = ui.applyProtectionExpired),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = onConfirmWorks, enabled = !ui.serviceBusy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("It works")
                    }
                }
            }
            val report = ui.lastApply
            StudioCard(
                title = "Last apply",
                subtitle = if (report == null) "Not applied yet. Apply theme builds one overlay per One UI app." else null,
            ) {
                if (report != null) {
                    ApplyFacts(report)
                    val trouble = report.outcomes.filter { it.result == TargetResult.FAILED || it.result == TargetResult.PARTIAL }
                    trouble.take(6).forEach { outcome ->
                        Text(
                            "${outcome.target.substringAfterLast('.').replaceFirstChar { it.uppercase() }}: " +
                                if (outcome.result == TargetResult.FAILED) "failed" else "partly applied",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor(if (outcome.result == TargetResult.FAILED) StatusTone.BAD else StatusTone.WARN),
                        )
                    }
                    if (trouble.size > 6) {
                        Text("and ${trouble.size - 6} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShowApps, modifier = Modifier.heightIn(min = 48.dp)) { Text("Themed apps") }
                    if (report != null && (!report.finished || report.failedCount > 0 || report.partialCount > 0)) {
                        Button(onClick = onDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) { Text("Diagnostics") }
                    } else {
                        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) { Text("Diagnostics") }
                    }
                }
            }
        }
    }
}

/** The facts of the last apply as chips: apps themed, failed, partial, colours written, time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApplyFacts(report: ApplyReport) {
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
            "${report.okCount} of $attempted apps themed",
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

/**
 * The in-place colour sheet of the element tapped on the phone. Its own composable, so the
 * preview it reads while a colour is dragged recomposes only the sheet.
 */
@Composable
private fun GroupPickSheet(viewModel: MainViewModel, ui: UiState, preview: PreviewModel, group: ColorGroup, night: Boolean, onClose: () -> Unit) {
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
    /** Scans the phone in place (or opens the status sheet when nothing is connected). */
    val onScan: () -> Unit,
    /** True while a scan runs. */
    val scanning: Boolean = false,
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
