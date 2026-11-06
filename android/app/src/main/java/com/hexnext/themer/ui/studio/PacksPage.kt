package com.hexnext.themer.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.LiveOption
import com.hexnext.engine.compile.OptionReach
import com.hexnext.engine.compile.PackOptions
import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.pack.Pack
import com.hexnext.engine.pack.PackIdAliases
import com.hexnext.themer.R
import com.hexnext.themer.billing.ProductIds
import com.hexnext.themer.theme.CatalogueEntry
import com.hexnext.themer.theme.InstalledPack
import com.hexnext.themer.ui.CatalogueDialog
import com.hexnext.themer.ui.ConfirmDialog
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.OptionList
import com.hexnext.themer.ui.PackBadges
import com.hexnext.themer.ui.PackLocks
import com.hexnext.themer.ui.PaletteDots
import com.hexnext.themer.ui.PartPackRow
import com.hexnext.themer.ui.RADIO_INDENT
import com.hexnext.themer.ui.UiState
import com.hexnext.themer.ui.UnlockAllRow
import com.hexnext.themer.ui.coveredParts
import com.hexnext.themer.ui.findActivity
import com.hexnext.themer.ui.packSummary
import com.hexnext.themer.ui.rememberHapticTap

/**
 * Packs: every pack of the catalogue and every pack on the phone in one list under the preview
 * peek, with search. A tap on a row selects that pack (downloading it first when it is not on
 * the phone yet) and the live preview shows it at once; the selected row opens to its badges and
 * options. Locked packs preview too; Apply then asks for the unlock. "One pack" uses the chosen
 * pack everywhere; "Pack per part" keeps it as the default and lets each part of One UI differ.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PacksPage(
    viewModel: MainViewModel,
    ui: UiState,
    preview: PreviewModel,
    onElementTap: (ColorGroup) -> Unit,
    peekCollapsed: Boolean,
    onPeekCollapsed: (Boolean) -> Unit,
    wide: Boolean,
) {
    val locks = PackLocks.of(ui)
    val optionReach by viewModel.optionReach.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val onUnlock: (String) -> Unit = { productId -> activity?.let { viewModel.unlock(it, productId) } }
    // The gallery lists the whole catalogue, not only the packs on the phone: load it once.
    LaunchedEffect(Unit) {
        if (ui.catalogue.entries.isEmpty() && !ui.catalogue.loading) viewModel.loadCatalogue()
    }
    val previewOnly = remember(ui.spec, ui.entitlement, ui.bundledPackIds, ui.officialPackIds) { ui.previewOnlyPacksInTheme() }
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var showCatalogue by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPack(uri)
    }
    val advanced = ui.spec.mode == ThemeMode.ADVANCED
    val installedById = remember(ui.packs) { ui.packs.associateBy { PackIdAliases.current(it.id) } }
    val rows = remember(ui.packs, ui.catalogue.entries, query, locks) {
        packRows(ui.packs, ui.catalogue.entries, installedById, query, locks)
    }
    val selectedId = ui.spec.simplePackId?.let(PackIdAliases::current)
    val busy = ui.busy

    fun LazyListScope.galleryRows(rows: List<PackRowData>) {
        items(rows, key = { "pack:${it.id}" }) { row ->
            val selected = row.id == selectedId
            PackGalleryRow(
                row = row,
                selected = selected,
                expanded = selected && row.installed != null && !advanced,
                busy = busy,
                progress = ui.catalogue.downloading[row.id],
                locks = locks,
                spec = ui.spec,
                optionReach = optionReach,
                onSelect = {
                    when {
                        row.installed != null -> viewModel.selectPack(row.installed.id)
                        row.entry != null -> viewModel.previewCataloguePack(row.entry)
                    }
                },
                onDownload = { row.entry?.let { viewModel.downloadPack(it) } },
                onUnlock = { onUnlock(ProductIds.forPack(row.id)) },
                onRemove = { confirmDelete = row.installed?.id ?: row.id },
                onOption = viewModel::setOption,
            )
        }
    }

    val content: LazyListScope.() -> Unit = {
        item(key = "mode") {
            StudioCard(
                title = if (advanced) "Default pack" else "Theme pack",
                eyebrow = "Packs · ${ui.packs.size} on this phone",
                subtitle = if (advanced) {
                    "Every part of One UI uses this pack unless a part below picks another."
                } else {
                    "One pack for everything: its icons, images and colours across One UI."
                },
            ) {
                SegmentedChoice(
                    options = listOf(ThemeMode.SIMPLE, ThemeMode.ADVANCED),
                    selected = ui.spec.mode,
                    label = { if (it == ThemeMode.ADVANCED) "Pack per part" else "One pack" },
                    onSelect = viewModel::setMode,
                    enabled = !busy,
                )
                if (previewOnly.isNotEmpty()) {
                    val name = ui.packDisplayName(previewOnly.first())
                    StatusChip("Preview only · unlock $name to apply", StatusTone.WARN)
                }
                if (locks.anyStillLocked(ui.packs, ui.catalogue.entries)) {
                    UnlockAllRow(locks = locks, busy = busy, onUnlock = onUnlock)
                }
            }
        }
        if (advanced) {
            val parts = coveredParts(ui.packs)
            if (parts.isNotEmpty()) {
                item(key = "parts") {
                    StudioCard(
                        title = "Parts",
                        eyebrow = "Pack per part · ${parts.size} parts",
                        subtitle = "Each part of One UI can take another pack, or switch off to keep your colours only. The list below picks the default.",
                    ) {
                        parts.forEach { (element, covering) ->
                            PartPackRow(
                                element = element,
                                covering = covering,
                                packs = ui.packs,
                                spec = ui.spec,
                                busy = busy,
                                locks = locks,
                                onOption = viewModel::setOption,
                                onElementPack = viewModel::setElementPack,
                                onElementEnabled = viewModel::setElementEnabled,
                                optionReach = optionReach,
                            )
                        }
                    }
                }
            }
        }
        if (ui.packs.size + ui.catalogue.entries.size > 6) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Search packs") },
                    trailingIcon = if (query.isNotEmpty()) {
                        { TextButton(onClick = { query = "" }) { Text("Clear") } }
                    } else null,
                )
            }
        }
        if (query.isBlank()) {
            item(key = "colours-only") {
                PackRowSurface(
                    selected = selectedId == null,
                    enabled = !busy,
                    onClick = { viewModel.selectPack(null) },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Colours only", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Your colours with Samsung's own icons",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (ui.catalogue.loading && rows.isEmpty()) {
            item(key = "loading") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading packs…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!ui.catalogue.loading && rows.isEmpty()) {
            item(key = "none") {
                Text(
                    when {
                        query.isNotBlank() -> "No pack name matches \"$query\"."
                        ui.catalogue.error != null -> "The catalogue could not be loaded: ${ui.catalogue.error}"
                        else -> "No extra packs yet. Your colours still apply; import a pack file below."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        val onPhone = rows.filter { it.onPhone }
        val toDownload = rows.filter { !it.onPhone }
        if (onPhone.isNotEmpty() && toDownload.isNotEmpty()) {
            item(key = "label-phone") { SectionLabel("On this phone · ${onPhone.size}", Modifier.padding(horizontal = 8.dp)) }
        }
        galleryRows(onPhone)
        if (toDownload.isNotEmpty()) {
            item(key = "label-download") {
                SectionLabel(
                    if (onPhone.isEmpty()) "Packs to download · ${toDownload.size}" else "More packs to download · ${toDownload.size}",
                    Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        galleryRows(toDownload)
        item(key = "more") {
            StudioCard(
                title = "More packs",
                subtitle = "Add a pack file from your phone, or point Hex Next at another catalogue.",
            ) {
                ui.packErrors.forEach { (file, why) ->
                    Text("$file: $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Import pack file") }
                    TextButton(onClick = { showCatalogue = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Catalogue source") }
                }
            }
        }
    }
    PreviewedList(
        preview = preview,
        onElementTap = onElementTap,
        peekCollapsed = peekCollapsed,
        onPeekCollapsed = onPeekCollapsed,
        wide = wide,
        listState = listState,
        content = content,
    )
    confirmDelete?.let { id ->
        val name = ui.packs.firstOrNull { it.id == id }?.name ?: id
        ConfirmDialog(
            title = "Remove $name from this phone?",
            text = "The pack file is deleted. Parts that use it go back to colours only. You can download it again any time.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmDelete = null
                viewModel.deletePack(id)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    if (showCatalogue) {
        CatalogueDialog(
            state = ui.catalogue,
            busy = busy,
            onUrl = viewModel::setCatalogueUrl,
            onLoad = viewModel::loadCatalogue,
            onDismiss = { showCatalogue = false },
        )
    }
}

/** One line of the gallery: a catalogue pack, a pack already on the phone, or both. */
internal data class PackRowData(
    val id: String,
    val name: String,
    val installed: InstalledPack?,
    val entry: CatalogueEntry?,
    val locked: Boolean,
) {
    val onPhone: Boolean get() = installed != null
}

/** The gallery rows: catalogue and installed packs merged by id, filtered by [query]; packs on the phone first, then by name. */
internal fun packRows(
    packs: List<InstalledPack>,
    entries: List<CatalogueEntry>,
    installedById: Map<String, InstalledPack>,
    query: String,
    locks: PackLocks,
): List<PackRowData> {
    val needle = query.trim()
    val seen = HashSet<String>()
    val rows = ArrayList<PackRowData>()
    fun add(id: String, name: String, installed: InstalledPack?, entry: CatalogueEntry?) {
        val current = PackIdAliases.current(id)
        if (current in seen) return
        if (needle.isNotEmpty() && !name.contains(needle, ignoreCase = true)) return
        seen += current
        rows += PackRowData(id = current, name = name, installed = installed, entry = entry, locked = locks.locked(current))
    }
    entries.forEach { entry ->
        val current = PackIdAliases.current(entry.id)
        add(current, installedById[current]?.name ?: entry.name, installedById[current], entry)
    }
    packs.forEach { pack -> add(pack.id, pack.name, pack, entries.firstOrNull { PackIdAliases.current(it.id) == PackIdAliases.current(pack.id) }) }
    return rows.sortedWith(compareBy<PackRowData> { !it.onPhone }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/**
 * The surface of one gallery row: a radio button and [content]; the whole row is the one
 * selectable node. Selected rows are tinted and outlined in the primary colour.
 */
@Composable
private fun PackRowSurface(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    expanded: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = {
                    if (!selected) tap()
                    onClick()
                })
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            content()
            trailing?.invoke()
        }
        if (expanded != null) {
            Column(
                Modifier.padding(start = RADIO_INDENT, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { expanded() }
        }
    }
}

/**
 * One pack of the gallery: name, lock state, palette dots and what the pack holds; a download
 * icon when it is not on the phone, a lock when it is locked (opens the unlock), and an overflow
 * menu with "Remove from phone". While [expanded] (selected, on the phone, one-pack mode) the
 * badges and the pack's options sit under the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackGalleryRow(
    row: PackRowData,
    selected: Boolean,
    expanded: Boolean,
    busy: Boolean,
    progress: Float?,
    locks: PackLocks,
    spec: ThemeSpec,
    optionReach: OptionReach?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onUnlock: () -> Unit,
    onRemove: () -> Unit,
    onOption: (Pack, LiveOption, String, String?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val installed = row.installed
    val detail = when {
        progress != null -> "Downloading…"
        installed == null -> catalogueSummary(row.entry)
        else -> packSummary(installed)
    }
    PackRowSurface(
        selected = selected,
        enabled = !busy && progress == null,
        onClick = onSelect,
        trailing = {
            when {
                progress != null -> LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.width(56.dp).padding(end = 12.dp),
                )
                installed == null && row.entry != null -> IconButton(
                    onClick = onDownload,
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "Download ${row.name}" },
                ) {
                    Icon(painterResource(R.drawable.hx_glyph_myfiles_downloads), contentDescription = null)
                }
                row.locked && locks.canPurchase -> IconButton(
                    onClick = onUnlock,
                    enabled = !busy && !locks.pending(row.id),
                    modifier = Modifier.semantics { contentDescription = "Unlock ${row.name} for ${locks.singlePrice(row.id)}" },
                ) {
                    if (locks.pending(row.id)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                    }
                }
            }
            if (installed != null && progress == null) {
                Box {
                    IconButton(
                        onClick = { menu = true },
                        enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "More for ${row.name}" },
                    ) {
                        Icon(painterResource(R.drawable.hx_ic_more), contentDescription = null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Remove from phone") },
                            onClick = {
                                menu = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        },
        expanded = if (expanded && installed != null) {
            {
                PackBadges(installed, locked = row.locked, pending = locks.pending(row.id), price = locks.singlePrice(row.id))
                val options = remember(installed.pack, optionReach) { PackOptions.all(installed.pack, optionReach) }
                if (options.isNotEmpty()) {
                    Text("Options", style = MaterialTheme.typography.labelLarge)
                    OptionList(
                        pack = installed.pack,
                        options = options,
                        spec = spec,
                        elementId = null,
                        enabled = !busy,
                        onOption = onOption,
                        indent = 0.dp,
                    )
                }
            }
        } else null,
    ) {
        Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (installed != null) PaletteDots(installed)
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.locked) {
                Text(
                    when {
                        locks.pending(row.id) -> "Locked · payment pending"
                        else -> "Locked · ${locks.singlePrice(row.id)} · previews now, unlock to apply"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What a pack that is not on the phone yet offers, from its catalogue entry: features and download size. */
internal fun catalogueSummary(entry: CatalogueEntry?): String = listOfNotNull(
    "Not on this phone",
    if (entry?.hasSettingsIcons == true) "Settings icons" else null,
    if (entry?.dayNight == true) "Day and night" else null,
    entry?.bytes?.takeIf { it > 0 }?.let { "${maxOf(1L, (it + 512 * 1024) / (1024 * 1024))} MB" },
).joinToString(" · ")
