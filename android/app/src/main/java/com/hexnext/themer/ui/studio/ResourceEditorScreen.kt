package com.hexnext.themer.ui.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hexnext.engine.compile.ColorPin
import com.hexnext.engine.compile.ResourceColor
import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ShadeScale
import com.hexnext.themer.ui.EmptyState
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.UiState

/** The list filters of the resource editor. */
private enum class ResourceFilter(val label: String) {
    ALL("All"),
    PINNED("Pinned"),
    GROUPED("In a group"),
    FIXED("Kept fixed"),
}

/** One resource name with its day and night rows (either may be missing). */
private data class ResourceEntry(val name: String, val day: ResourceColor?, val night: ResourceColor?) {
    val pin: ColorPin? get() = day?.pin ?: night?.pin
    val groupLabel: String? get() = (day?.group ?: night?.group)?.label
    val keptFixed: Boolean get() = day?.planned == null && night?.planned == null
    val typeName: String get() = name.substringAfter(':')
}

private fun entriesOf(items: List<ResourceColor>): List<ResourceEntry> =
    items.groupBy { it.resourceName }.map { (name, rows) ->
        ResourceEntry(name, rows.firstOrNull { it.configuration != "night" }, rows.firstOrNull { it.configuration == "night" })
    }

private fun ResourceEntry.matches(filter: ResourceFilter, query: String): Boolean {
    val inFilter = when (filter) {
        ResourceFilter.ALL -> true
        ResourceFilter.PINNED -> pin != null
        ResourceFilter.GROUPED -> groupLabel != null
        ResourceFilter.FIXED -> keptFixed
    }
    if (!inFilter) return false
    if (query.isBlank()) return true
    val q = query.trim()
    return typeName.contains(q, ignoreCase = true) || groupLabel?.contains(q, ignoreCase = true) == true
}

/** Short label of a pin: `#22D3EE` or `accent1 500`. */
private fun pinLabel(pin: ColorPin): String = when (pin) {
    is ColorPin.Fixed -> hexOf(pin.argb)
    is ColorPin.Shade -> "${pin.role.systemName} ${pin.shade}"
}

/** The [ColorPin.Shade] of a short shade key (`accent1_500`), null when it is not one. */
private fun shadePinOf(key: String): ColorPin.Shade? {
    val role = PaletteRole.fromSystemName(key.substringBefore('_')) ?: return null
    val shade = key.substringAfter('_').toIntOrNull() ?: return null
    if (ShadeScale.indexOf(shade) < 0) return null
    return runCatching { ColorPin.Shade(role, shade) }.getOrNull()
}

/**
 * The per-app resource editor (Studio v2 § 5): every colour of one scanned One UI app with the
 * value Samsung ships, the value the theme writes in light and dark mode, its element group and
 * the user's pin. Pins and the target switch go through [MainViewModel]; this screen only reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceEditorScreen(viewModel: MainViewModel, ui: UiState) {
    val model: ResourceEditorModel = viewModel()
    val state by model.state.collectAsState()
    val preview by viewModel.studioPreview.collectAsState()
    BackHandler { viewModel.navigateBack() }
    DisposableEffect(model) { onDispose { model.release() } }
    LaunchedEffect(ui.spec) { model.setSpec(ui.spec) }
    // A Targets row opens the editor on its app: select it once the scanned app list is in.
    val preselect by viewModel.resourcesTarget.collectAsState()
    LaunchedEffect(preselect, state.targets, state.hasScan) {
        val pkg = preselect ?: return@LaunchedEffect
        when {
            state.targets.any { it.packageName == pkg } -> {
                model.select(pkg)
                viewModel.consumeResourcesTarget()
            }
            // Not part of the last scan (or no scan at all): keep the editor's own choice.
            !state.hasScan || state.targets.isNotEmpty() -> viewModel.consumeResourcesTarget()
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ResourceFilter.ALL) }
    var pinning by rememberSaveable { mutableStateOf<String?>(null) }

    val entries = remember(state.items) { entriesOf(state.items) }
    val shown = remember(entries, filter, query) { entries.filter { it.matches(filter, query) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App resources", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (!state.hasScan) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Search,
                    text = "Scan your phone first. The editor lists the colours the scan found in each One UI app.",
                    actionLabel = "Scan phone",
                    onAction = viewModel::openScan,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                ResourceHeader(
                    viewModel = viewModel,
                    ui = ui,
                    state = state,
                    entries = entries,
                    onSelect = model::select,
                )
            }
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("Search color/name or group") },
                        textStyle = monoStyle(MaterialTheme.typography.bodyMedium),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ResourceFilter.entries.forEach { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(f.label) },
                            )
                        }
                    }
                }
            }
            when {
                state.loading -> item(key = "loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Reading colours…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.error != null -> item(key = "error") {
                    EmptyState(icon = Icons.Outlined.Warning, text = state.error.orEmpty())
                }
                shown.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        text = if (entries.isEmpty()) "The scan found no colours in this app." else "No colour matches.",
                    )
                }
                else -> items(shown, key = { it.name }) { entry ->
                    ResourceRow(
                        entry = entry,
                        onPin = { pinning = entry.name },
                        onUnpin = { viewModel.setColorPin(entry.name, null) },
                    )
                }
            }
        }
    }

    val editing = pinning?.let { name -> entries.firstOrNull { it.name == name } }
    if (editing != null) {
        PinSheet(
            entry = editing,
            shades = preview.light.shades,
            onPin = { pin ->
                viewModel.setColorPin(editing.name, pin)
                pinning = null
            },
            onDismiss = { pinning = null },
        )
    }
}

/** The app picker, its theme switch and the counts of the open app. */
@Composable
private fun ResourceHeader(
    viewModel: MainViewModel,
    ui: UiState,
    state: ResourceEditorState,
    entries: List<ResourceEntry>,
    onSelect: (String) -> Unit,
) {
    val selected = state.selected
    StudioCard(
        title = "Individual resources",
        eyebrow = "Resources · per app",
        subtitle = "Every colour the scan found, what Samsung ships and what your theme writes. Pin one to override its group.",
    ) {
        StudioDropdown(
            label = "App",
            selectedLabel = selected ?: "No app",
            modifier = Modifier.fillMaxWidth(),
            enabled = state.targets.isNotEmpty(),
        ) { close ->
            state.targets.forEach { target ->
                StudioDropdownItem(
                    text = target.packageName,
                    onClick = {
                        close()
                        onSelect(target.packageName)
                    },
                    trailing = {
                        Text(
                            when {
                                target.debloated -> "removed"
                                !target.installed -> "absent"
                                target.colors > 0 -> "${target.colors}"
                                else -> ""
                            },
                            style = monoStyle(MaterialTheme.typography.labelSmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
        if (selected != null) {
            StudioSwitchRow(
                title = "Theme this app",
                summary = "Off leaves the app's colours as Samsung ships them",
                checked = ui.spec.isTargetEnabled(selected),
                enabled = !ui.busy,
                onCheckedChange = { viewModel.setTargetEnabled(selected, it) },
            )
        }
        if (!state.loading && entries.isNotEmpty()) {
            val grouped = entries.count { it.groupLabel != null }
            val pinned = entries.count { it.pin != null }
            Text(
                "${entries.size} colours · $grouped in a group · $pinned pinned",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One resource: name, group, light and dark values (shipped → written) and its pin. */
@Composable
private fun ResourceRow(entry: ResourceEntry, onPin: () -> Unit, onUnpin: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val pin = entry.pin
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, if (pin != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                entry.typeName,
                style = monoStyle(MaterialTheme.typography.bodyMedium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StudioPill(
                entry.groupLabel ?: "No group",
                container = if (entry.groupLabel != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                content = if (entry.groupLabel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ConfigLine("Light", entry.day)
        ConfigLine("Dark", entry.night)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    pin != null -> "Pinned to ${pinLabel(pin)}"
                    entry.keptFixed -> "Kept as Samsung ships it"
                    else -> "Follows its group"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (pin != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (pin != null) TextButton(onClick = onUnpin) { Text("Unpin") }
            TextButton(onClick = onPin) { Text(if (pin != null) "Edit pin" else "Pin") }
        }
    }
}

/** `Light  ■ #FFFFFF → ■ #0B1220` for one configuration, or "not defined". */
@Composable
private fun ConfigLine(label: String, row: ResourceColor?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        if (row == null) {
            Text("not defined", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Row
        }
        Swatch(row.observed, "$label, ships ${hexOf(row.observed)}")
        Text(hexOf(row.observed), style = monoStyle(MaterialTheme.typography.labelSmall))
        Text("→", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val planned = row.planned
        if (planned == null) {
            Text("kept", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Swatch(planned, "$label, theme writes ${hexOf(planned)}")
            Text(hexOf(planned), style = monoStyle(MaterialTheme.typography.labelSmall))
        }
    }
}

@Composable
private fun Swatch(argb: Int, description: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(20.dp)
            .clip(shape)
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics { contentDescription = description },
    )
}

private enum class PinMode(val label: String) { FIXED("Fixed colour"), SHADE("Palette shade") }

/**
 * Pins one resource: a fixed colour (same in light and dark) or a palette shade (taken from the
 * light palette in light mode and the dark palette in dark mode).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheet(entry: ResourceEntry, shades: Map<String, Int>, onPin: (ColorPin) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val existing = entry.pin
    val inferred = (entry.day ?: entry.night)?.inferred
    val startShade = when {
        existing is ColorPin.Shade -> shortShadeKey(existing.role, existing.shade)
        inferred != null && inferred.role.systemName != null -> shortShadeKey(inferred.role, inferred.shade)
        else -> null
    }
    var mode by rememberSaveable { mutableStateOf(if (existing is ColorPin.Shade) PinMode.SHADE else PinMode.FIXED) }
    var color by rememberSaveable {
        mutableIntStateOf((existing as? ColorPin.Fixed)?.argb ?: (entry.day ?: entry.night)?.observed ?: 0)
    }
    var shadeKey by rememberSaveable { mutableStateOf(startShade) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Pin colour", style = MaterialTheme.typography.titleLarge)
                Text(entry.typeName, style = monoStyle(MaterialTheme.typography.bodySmall), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SegmentedChoice(
                options = PinMode.entries,
                selected = mode,
                label = { it.label },
                onSelect = { mode = it },
            )
            when (mode) {
                PinMode.FIXED -> {
                    Text(
                        "One colour in light and dark mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ColorPickerContent(color = color, shades = shades, onColor = { color = it })
                }
                PinMode.SHADE -> {
                    Text(
                        "Follows your palette: the light shade in light mode, the same shade of the dark palette in dark mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ShadeRibbon(shades = shades, onShade = { shadeKey = it }, selectedKey = shadeKey)
                    Text(
                        shadeKey?.let { "Selected: ${shadeLabel(it)}" } ?: "Tap a shade.",
                        style = monoStyle(MaterialTheme.typography.labelSmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val pin: ColorPin? = when (mode) {
                PinMode.FIXED -> ColorPin.Fixed(color or OPAQUE_ARGB)
                PinMode.SHADE -> shadeKey?.let(::shadePinOf)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { pin?.let(onPin) }, enabled = pin != null) { Text("Pin") }
            }
        }
    }
}
