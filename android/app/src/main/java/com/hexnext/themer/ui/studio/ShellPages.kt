package com.hexnext.themer.ui.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.themer.R
import com.hexnext.themer.theme.Capability
import com.hexnext.themer.ui.BackgroundsCard
import com.hexnext.themer.ui.CustomIconsCard
import com.hexnext.themer.ui.FontCard
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.Screen
import com.hexnext.themer.ui.UiState
import com.hexnext.themer.ui.ai.DescribeThemeCard
import com.hexnext.themer.ui.preview.PhonePreview
import com.hexnext.themer.ui.rememberHapticTap
import kotlinx.coroutines.launch

// ---- Shared pieces ----

/**
 * The sub-tabs of a destination: one row of short segments, each a 48 dp tab. When every label
 * fits, the segments share the row in proportion to their labels (none narrower than 48 dp);
 * otherwise the row scrolls sideways, so a label is never cut off.
 */
@Composable
internal fun <T> SubTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(12.dp)
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 12 dp of padding each side of a label, 4 dp between segments, 4 dp around the row.
        val widths = with(density) { options.map { (measurer.measure(label(it), style).size.width.toDp() + 24.dp).coerceAtLeast(48.dp) } }
        val needed = widths.fold(0.dp) { sum, w -> sum + w } + 4.dp * (options.size - 1) + 8.dp
        val fits = needed <= maxWidth
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("subtabs")
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option == selected
                Box(
                    Modifier
                        .then(if (fits) Modifier.weight(widths[index].value) else Modifier)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = {
                            if (!isSelected) tap()
                            onSelect(option)
                        })
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = style,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The whole phone with its tabs and light/dark switch, for the preview sheet and the wide panes. */
@Composable
private fun FullPhone(preview: PreviewModel, onElementTap: (ColorGroup) -> Unit, modifier: Modifier = Modifier) {
    PhonePreview(
        state = preview.state,
        tab = preview.tab,
        onTabChange = preview.onTab,
        dark = preview.dark,
        onDarkChange = preview.onDark,
        onElementTap = onElementTap,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Until the phone has been scanned once, a compact prompt: colours preview already, icons need
 * the scan. Runs the scan in place and shows that it is running. Nothing once a scan exists.
 */
@Composable
internal fun ScanPrompt(preview: PreviewModel) {
    if (preview.state.hasScan) return
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (preview.scanning) "Scanning your phone…" else "Scan your phone once and the previews show its real icons too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (preview.scanning) {
            CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)
        } else {
            FilledTonalButton(onClick = preview.onScan, modifier = Modifier.heightIn(min = 40.dp)) { Text("Scan phone") }
        }
    }
}

/** After a scan, the preview's note on missing icons, if any (the wide left pane shows it under the phone). */
@Composable
private fun PreviewNote(preview: PreviewModel) {
    if (!preview.state.hasScan) {
        ScanPrompt(preview)
        return
    }
    preview.state.note?.let { note ->
        Text(
            note.replace("device scan", "phone scan"),
            style = MaterialTheme.typography.bodySmall,
            color = if (note.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Height of the clipped phone in [PreviewPeek]: the status bar, the title and the first rows. */
private val PEEK_HEIGHT = 220.dp

/**
 * The live preview at the top of Theme, Packs and Library: the top of the phone with the same tab
 * and mode everywhere. A tap on the phone edits that element in place; the open-full button shows
 * the whole phone in a sheet; the chevron collapses the peek to its header row.
 */
@Composable
internal fun PreviewPeek(
    preview: PreviewModel,
    onElementTap: (ColorGroup) -> Unit,
    collapsed: Boolean,
    onCollapsed: (Boolean) -> Unit,
) {
    var sheet by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Text(
                preview.state.highlightedGroup?.let { "Editing: ${it.label}" } ?: "Live preview · tap to edit",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            IconButton(
                onClick = { sheet = true },
                modifier = Modifier.semantics { contentDescription = "Open the live preview full size" },
            ) {
                Icon(painterResource(R.drawable.hx_ic_open_full), contentDescription = null)
            }
            IconButton(
                onClick = { onCollapsed(!collapsed) },
                modifier = Modifier.semantics { contentDescription = if (collapsed) "Show live preview" else "Hide live preview" },
            ) {
                Icon(if (collapsed) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp, contentDescription = null)
            }
        }
        if (!collapsed) {
            val fade = MaterialTheme.colorScheme.surfaceContainer
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PEEK_HEIGHT)
                    .clipToBounds()
                    .padding(horizontal = 12.dp),
            ) {
                PhonePreview(
                    state = preview.state,
                    tab = preview.tab,
                    onTabChange = preview.onTab,
                    dark = preview.dark,
                    onDarkChange = preview.onDark,
                    onElementTap = onElementTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                    compact = true,
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, fade))),
                )
            }
        }
    }
    if (sheet) PreviewSheet(preview, onElementTap, onDismiss = { sheet = false })
}

/**
 * A destination with the preview: on phones a list headed by the [PreviewPeek], the scan prompt
 * and the sticky sub-tabs; from 840 dp the whole phone in a sticky left pane and the list on the
 * right. The list's item indices on phones: 0 peek, 1 (scan prompt, before the first scan), then
 * the tabs, then [content]; [firstContentIndex] tells the pages where their content starts.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewedPage(
    preview: PreviewModel,
    onElementTap: (ColorGroup) -> Unit,
    peekCollapsed: Boolean,
    onPeekCollapsed: (Boolean) -> Unit,
    wide: Boolean,
    listState: LazyListState,
    tabs: (@Composable () -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    if (wide) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paneWidth = (maxWidth * 0.42f).coerceIn(320.dp, 460.dp)
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(paneWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FullPhone(preview, onElementTap)
                    PreviewNote(preview)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).testTag("page"),
                    contentPadding = PAGE_PADDING,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (tabs != null) item(key = "tabs") { tabs() }
                    content()
                }
            }
        }
    } else {
        // Once the peek has scrolled away, a floating button shows the phone in a sheet, so a pick
        // far down a list (a pack, an option) can be checked without scrolling back to the top.
        val peekOffScreen by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        var sheet by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("page"),
                contentPadding = PREVIEWED_PAGE_PADDING,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "peek") { PreviewPeek(preview, onElementTap, peekCollapsed, onPeekCollapsed) }
                if (!preview.state.hasScan) item(key = "scan") { ScanPrompt(preview) }
                if (tabs != null) {
                    stickyHeader(key = "tabs") {
                        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 4.dp)) { tabs() }
                    }
                }
                content()
            }
            AnimatedVisibility(
                visible = peekOffScreen,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { sheet = true },
                    icon = { Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) },
                    text = { Text("Preview") },
                )
            }
        }
        if (sheet) PreviewSheet(preview, onElementTap, onDismiss = { sheet = false })
    }
}

/** The index of the first content item of a [PreviewedPage] list, after the peek, scan prompt and tabs. */
private fun firstContentIndex(preview: PreviewModel, wide: Boolean, hasTabs: Boolean): Int {
    var index = 0
    if (!wide) {
        index++ // peek
        if (!preview.state.hasScan) index++ // scan prompt
    }
    if (hasTabs) index++
    return index
}

/** [PAGE_PADDING] with room under the last card for the floating Preview button. */
internal val PREVIEWED_PAGE_PADDING = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp)

/** The whole phone in a bottom sheet; a tap on an element closes it and opens that element. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewSheet(preview: PreviewModel, onElementTap: (ColorGroup) -> Unit, onDismiss: () -> Unit) {
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
        ) {
            FullPhone(preview, onElementTap = { group ->
                onDismiss()
                onElementTap(group)
            })
        }
    }
}

// ---- Theme ----

/**
 * Theme: the live preview, then Palette (the colour engine and every shade), Elements, Quick
 * panel and AI under it. Everything edits in place; "All shades" scrolls to the shade grid on the
 * same page rather than switching tabs.
 */
@Composable
internal fun ThemePage(
    viewModel: MainViewModel,
    ui: UiState,
    preview: PreviewModel,
    tab: ThemeTab,
    onTab: (ThemeTab) -> Unit,
    onElementTap: (ColorGroup) -> Unit,
    elementsTick: Int,
    elementsTickHandled: Int,
    onElementsTickHandled: (Int) -> Unit,
    peekCollapsed: Boolean,
    onPeekCollapsed: (Boolean) -> Unit,
    wide: Boolean,
    applyBlockedReason: String?,
) {
    val counts by viewModel.groupCounts.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val first = firstContentIndex(preview, wide, hasTabs = true)
    // A tap on the phone while Elements shows lands on the element's row (the first content
    // item). Only a tap newer than the last one handled scrolls, so a later visit keeps its own
    // scroll and the peek stays in view.
    val jumpToElements = elementsTick > elementsTickHandled
    LaunchedEffect(elementsTick) {
        if (elementsTick > elementsTickHandled) {
            try {
                listState.animateScrollToItem(first)
            } finally {
                onElementsTickHandled(elementsTick)
            }
        }
    }
    // "Describe your theme" may only pick packs this install can apply.
    val unlockedPacks = remember(ui.packs, ui.entitlement, ui.bundledPackIds, ui.officialPackIds) { ui.packs.filter { ui.isPackUnlocked(it.id) } }
    val putInEditor: (ThemeSpec) -> Unit = { newSpec ->
        // The apply path reads the seed from the preference, so both must move.
        viewModel.setSeed(newSpec.source.seedArgb)
        viewModel.replaceSpec(newSpec)
    }
    PreviewedPage(
        preview = preview,
        onElementTap = onElementTap,
        peekCollapsed = peekCollapsed,
        onPeekCollapsed = onPeekCollapsed,
        wide = wide,
        listState = listState,
        tabs = { SubTabs(ThemeTab.entries, tab, { it.label }, onTab) },
    ) {
        when (tab) {
            ThemeTab.PALETTE -> {
                item(key = "engine") {
                    ColorEngineCard(
                        viewModel, ui.spec, ui.seed, preview.state,
                        onOpenShades = { scope.launch { listState.animateScrollToItem(first + 1) } },
                    )
                }
                item(key = "shades") { ShadesCard(viewModel, ui.spec, preview.state) }
            }
            ThemeTab.ELEMENTS -> item(key = "elements") {
                ElementColorsCard(
                    viewModel = viewModel,
                    spec = ui.spec,
                    preview = preview.state,
                    counts = counts,
                    bringHighlightIntoView = jumpToElements,
                )
            }
            ThemeTab.QUICK_PANEL -> {
                item(key = "quickPanel") { QuickPanelCard(viewModel, ui.spec) }
                item(key = "quickPanelGroups") {
                    ElementColorsCard(
                        viewModel = viewModel,
                        spec = ui.spec,
                        preview = preview.state,
                        counts = counts,
                        groups = QUICK_PANEL_GROUPS,
                        title = "Quick panel colours",
                        subtitle = "Backdrop, tiles, icons and text change only the Quick panel. Notification cards also change in other One UI apps.",
                        eyebrow = "Quick panel · ${QUICK_PANEL_GROUPS.size} groups",
                    )
                }
            }
            ThemeTab.AI -> item(key = "describe") {
                DescribeThemeCard(
                    packs = unlockedPacks,
                    spec = ui.spec,
                    busy = ui.busy,
                    onApply = { _, newSpec -> putInEditor(newSpec) },
                    onOpenProviders = { viewModel.navigate(Screen.AI_PROVIDERS) },
                    onRestore = putInEditor,
                    livePreview = null,
                    onApplyTheme = viewModel::applyTheme,
                    applyBlockedReason = applyBlockedReason,
                )
            }
        }
    }
}

// ---- Library ----

/** Library: Icons (style and custom icons), Fonts and Backgrounds under the preview peek. */
@Composable
internal fun LibraryPage(
    viewModel: MainViewModel,
    ui: UiState,
    preview: PreviewModel,
    tab: LibraryTab,
    onTab: (LibraryTab) -> Unit,
    onElementTap: (ColorGroup) -> Unit,
    peekCollapsed: Boolean,
    onPeekCollapsed: (Boolean) -> Unit,
    wide: Boolean,
) {
    val listState = rememberLazyListState()
    val needsRoot = ui.capability == Capability.SHELL
    // A locked pack lends its colours to the preview too; Apply still asks for the unlock.
    val paletteHint = remember(ui.packs, ui.spec.simplePackId) {
        ui.packs.firstOrNull { it.id == ui.spec.simplePackId }
            ?.pack?.paletteArgb?.values?.map { it or OPAQUE_ARGB }?.take(6).orEmpty()
    }
    PreviewedPage(
        preview = preview,
        onElementTap = onElementTap,
        peekCollapsed = peekCollapsed,
        onPeekCollapsed = onPeekCollapsed,
        wide = wide,
        listState = listState,
        tabs = { SubTabs(LibraryTab.entries, tab, { it.label }, onTab) },
    ) {
        when (tab) {
            LibraryTab.ICONS -> {
                item(key = "iconStyle") { IconStyleCard(viewModel, ui.spec, preview.state, ui.busy, ui.capability) }
                item(key = "customIcons") {
                    CustomIconsCard(
                        assets = ui.customIcons,
                        slots = ui.iconSlots,
                        slotsError = ui.slotsError,
                        spec = ui.spec,
                        busy = ui.busy,
                        needsRoot = needsRoot,
                        paletteHint = paletteHint,
                        fileOf = viewModel::assetFile,
                        onImport = viewModel::importCustomIcon,
                        onGenerated = viewModel::noteGeneratedIcon,
                        onAssign = viewModel::assignIcon,
                        onDelete = viewModel::deleteAsset,
                        onSynthesize = viewModel::setSynthesizeMissingIcons,
                        onOpenProviders = { viewModel.navigate(Screen.AI_PROVIDERS) },
                        onScan = preview.onScan,
                        // The Style card carries the synthesis switch.
                        showSynthesize = false,
                    )
                }
            }
            LibraryTab.FONTS -> item(key = "fonts") {
                FontCard(
                    fonts = ui.customFonts,
                    spec = ui.spec,
                    busy = ui.busy,
                    needsRoot = needsRoot,
                    fileOf = viewModel::assetFile,
                    onImport = viewModel::importFont,
                    onFont = viewModel::setFont,
                    onBold = viewModel::setFontBold,
                    onDelete = viewModel::deleteAsset,
                )
            }
            LibraryTab.BACKGROUNDS -> item(key = "backgrounds") {
                BackgroundsCard(
                    assets = ui.customBackgrounds,
                    slots = ui.backgroundSlots,
                    slotsError = ui.slotsError,
                    spec = ui.spec,
                    busy = ui.busy,
                    needsRoot = needsRoot,
                    fileOf = viewModel::assetFile,
                    onImport = viewModel::importCustomBackground,
                    onAssign = viewModel::assignBackground,
                    onDelete = viewModel::deleteAsset,
                    onScan = preview.onScan,
                )
            }
        }
    }
}

/**
 * A page list with the preview for destinations without sub-tabs (Packs): the same peek, scan
 * prompt and floating Preview button, and [content] right under them.
 */
@Composable
internal fun PreviewedList(
    preview: PreviewModel,
    onElementTap: (ColorGroup) -> Unit,
    peekCollapsed: Boolean,
    onPeekCollapsed: (Boolean) -> Unit,
    wide: Boolean,
    listState: LazyListState,
    content: LazyListScope.() -> Unit,
) {
    PreviewedPage(
        preview = preview,
        onElementTap = onElementTap,
        peekCollapsed = peekCollapsed,
        onPeekCollapsed = onPeekCollapsed,
        wide = wide,
        listState = listState,
        tabs = null,
        content = content,
    )
}
