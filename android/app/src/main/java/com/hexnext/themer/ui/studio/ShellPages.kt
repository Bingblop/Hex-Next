package com.hexnext.themer.ui.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.theme.Capability
import com.hexnext.themer.ui.BackgroundsCard
import com.hexnext.themer.ui.CustomIconsCard
import com.hexnext.themer.ui.EmptyState
import com.hexnext.themer.ui.FontCard
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.PackCard
import com.hexnext.themer.ui.PackLocks
import com.hexnext.themer.ui.Screen
import com.hexnext.themer.ui.UiState
import com.hexnext.themer.ui.ai.DescribeThemeCard
import com.hexnext.themer.ui.findActivity
import com.hexnext.themer.ui.preview.PhonePreview

// ---- Shared pieces ----

/**
 * The sub-tabs of a destination (§ 4.8): one row of at most four 1 to 2 word segments that
 * scrolls sideways when the screen is narrow. Each segment is a 48 dp tab.
 */
@Composable
internal fun <T> SubTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The whole phone with its tabs and light/dark switch, for the Studio and the wide panes. */
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

/** Before the first scan, the scan prompt; afterwards the note on missing icons, if any. */
@Composable
private fun PreviewScanNote(preview: StudioPreviewState, onScan: () -> Unit) {
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

/**
 * The preview peek of Colours and Icons (§ 1.3): the top 200 dp of the live phone with the same
 * tab and mode as Studio, "Expand" for a full-height sheet, or collapsed to a 56 dp row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewPeek(
    preview: PreviewModel,
    onElementTap: (ColorGroup) -> Unit,
    collapsed: Boolean,
    onCollapsed: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
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
                "Live preview · ${preview.tab.label} · ${if (preview.dark) "Dark" else "Light"}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            if (!collapsed) {
                TextButton(onClick = { expanded = true }) { Text("Expand") }
            }
            TextButton(
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
                    .height(200.dp)
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
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
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
                    expanded = false
                    onElementTap(group)
                })
            }
        }
    }
}

/**
 * A destination with the preview: on phones a list headed by the [PreviewPeek] and the sub-tabs;
 * from 840 dp the whole phone in a sticky left pane and the list on the right.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewedPage(
    preview: PreviewModel,
    onElementTap: (ColorGroup) -> Unit,
    peekCollapsed: Boolean,
    onPeekCollapsed: (Boolean) -> Unit,
    wide: Boolean,
    tabs: @Composable () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
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
                ) {
                    FullPhone(preview, onElementTap)
                    PreviewScanNote(preview.state, preview.onScan)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PAGE_PADDING,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "tabs") { tabs() }
                    content()
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PAGE_PADDING,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "peek") { PreviewPeek(preview, onElementTap, peekCollapsed, onPeekCollapsed) }
            stickyHeader(key = "tabs") {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 4.dp)) { tabs() }
            }
            content()
        }
    }
}

// ---- Studio ----

/**
 * Studio (§ 1.2, 1): the whole live phone with every tab and the light/dark switch; a tap on an
 * element opens its colour sheet right here. Under it the scan prompt or preview note and
 * "Describe your theme" (unlocked packs only). From 840 dp the phone sits left, the rest right.
 */
@Composable
internal fun StudioPage(viewModel: MainViewModel, ui: UiState, preview: PreviewModel, onElementTap: (ColorGroup) -> Unit, wide: Boolean) {
    // "Describe your theme" may only pick packs this install can apply.
    val unlockedPacks = remember(ui.packs, ui.entitlement, ui.bundledPackIds, ui.officialPackIds) { ui.packs.filter { ui.isPackUnlocked(it.id) } }
    val phoneCard: @Composable () -> Unit = {
        StudioCard(
            title = "Live preview",
            eyebrow = "Real-time One UI preview",
            subtitle = "Tap any part of the phone to change its colour.",
        ) {
            FullPhone(preview, onElementTap)
            PreviewScanNote(preview.state, preview.onScan)
        }
    }
    val describe: @Composable () -> Unit = {
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
    val listState = rememberLazyListState()
    if (wide) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paneWidth = (maxWidth * 0.48f).coerceIn(360.dp, 520.dp)
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(paneWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 4.dp, bottom = 24.dp),
                ) { phoneCard() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PAGE_PADDING,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "describe") { describe() }
                }
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PAGE_PADDING,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "phone") { phoneCard() }
            item(key = "describe") { describe() }
        }
    }
}

// ---- Skins ----

/**
 * Skins (§ 1.2, 2): the pack list with search, palette dots, lock state and price, Unlock all,
 * Import, the catalogue, Simple or Advanced and the per-element pack rows and options.
 */
@Composable
internal fun SkinsPage(viewModel: MainViewModel, ui: UiState) {
    val locks = PackLocks.of(ui)
    val activity = LocalContext.current.findActivity()
    val onUnlock: (String) -> Unit = { productId -> activity?.let { viewModel.unlock(it, productId) } }
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PAGE_PADDING,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
}

// ---- Colours ----

/** Colours (§ 1.2, 3): Engine, Elements, Quick panel and Shades under the preview peek. */
@Composable
internal fun ColoursPage(
    viewModel: MainViewModel,
    ui: UiState,
    preview: PreviewModel,
    tab: ColoursTab,
    onTab: (ColoursTab) -> Unit,
    onElementTap: (ColorGroup) -> Unit,
    elementsTick: Int,
    elementsTickHandled: Int,
    onElementsTickHandled: (Int) -> Unit,
    peekCollapsed: Boolean,
    onPeekCollapsed: (Boolean) -> Unit,
    wide: Boolean,
) {
    val counts by viewModel.groupCounts.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val state = preview.state
    // A tap on the phone lands on the element rows: the card after the peek and the tabs. Only a
    // tap newer than the last one handled scrolls, so a sub-tab switch or a later visit (which
    // recreates this page) keeps its own scroll and the preview peek stays in view. A second tap
    // while the first is still scrolling is newer too, so it is not lost.
    val jumpToElements = elementsTick > elementsTickHandled
    LaunchedEffect(elementsTick) {
        if (elementsTick > elementsTickHandled) {
            try {
                listState.animateScrollToItem(if (wide) 1 else 2)
            } finally {
                onElementsTickHandled(elementsTick)
            }
        }
    }
    PreviewedPage(
        preview = preview,
        onElementTap = onElementTap,
        peekCollapsed = peekCollapsed,
        onPeekCollapsed = onPeekCollapsed,
        wide = wide,
        listState = listState,
        tabs = { SubTabs(ColoursTab.entries, tab, { it.label }, onTab) },
    ) {
        when (tab) {
            ColoursTab.ENGINE -> item(key = "engine") {
                ColorEngineCard(viewModel, ui.spec, ui.seed, state, onOpenShades = { onTab(ColoursTab.SHADES) })
            }
            ColoursTab.ELEMENTS -> item(key = "elements") {
                ElementColorsCard(
                    viewModel = viewModel,
                    spec = ui.spec,
                    preview = state,
                    counts = counts,
                    bringHighlightIntoView = jumpToElements,
                    onOpenResources = { viewModel.openResources() },
                )
            }
            ColoursTab.QUICK_PANEL -> {
                item(key = "quickPanelGroups") {
                    ElementColorsCard(
                        viewModel = viewModel,
                        spec = ui.spec,
                        preview = state,
                        counts = counts,
                        groups = QUICK_PANEL_GROUPS,
                        bringHighlightIntoView = jumpToElements,
                        title = "Quick panel colours",
                        subtitle = "The panel backdrop, tiles, sliders and notification cards, for light and dark.",
                        eyebrow = "System UI · ${QUICK_PANEL_GROUPS.size} groups",
                    )
                }
                item(key = "quickPanel") { QuickPanelCard(viewModel, ui.spec, state) }
            }
            ColoursTab.SHADES -> item(key = "shades") { ShadesCard(viewModel, ui.spec, state) }
        }
    }
}

// ---- Icons ----

/** Icons (§ 1.2, 4): Style, Custom icons, Fonts and Backgrounds under the preview peek. */
@Composable
internal fun IconsPage(
    viewModel: MainViewModel,
    ui: UiState,
    preview: PreviewModel,
    tab: IconsTab,
    onTab: (IconsTab) -> Unit,
    onElementTap: (ColorGroup) -> Unit,
    peekCollapsed: Boolean,
    onPeekCollapsed: (Boolean) -> Unit,
    wide: Boolean,
) {
    val listState = rememberLazyListState()
    val needsRoot = ui.capability == Capability.SHELL
    // A locked pack (left in the theme by an import or a refund) lends no colours either.
    val paletteHint = remember(ui.packs, ui.spec.simplePackId, ui.entitlement, ui.bundledPackIds, ui.officialPackIds) {
        ui.packs.firstOrNull { it.id == ui.spec.simplePackId && ui.packLooksUnlocked(it.id) }
            ?.pack?.paletteArgb?.values?.map { it or OPAQUE_ARGB }?.take(6).orEmpty()
    }
    PreviewedPage(
        preview = preview,
        onElementTap = onElementTap,
        peekCollapsed = peekCollapsed,
        onPeekCollapsed = onPeekCollapsed,
        wide = wide,
        listState = listState,
        tabs = { SubTabs(IconsTab.entries, tab, { it.label }, onTab) },
    ) {
        when (tab) {
            IconsTab.STYLE -> item(key = "iconStyle") { IconStyleCard(viewModel, ui.spec, preview.state, ui.busy, ui.capability) }
            IconsTab.CUSTOM -> item(key = "customIcons") {
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
                    onScan = viewModel::openScan,
                    // The Style tab carries the synthesis switch.
                    showSynthesize = false,
                )
            }
            IconsTab.FONTS -> item(key = "fonts") {
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
            IconsTab.BACKGROUNDS -> item(key = "backgrounds") {
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
                    onScan = viewModel::openScan,
                )
            }
        }
    }
}
