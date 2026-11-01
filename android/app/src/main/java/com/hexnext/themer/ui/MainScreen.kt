package com.hexnext.themer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.engine.compile.Element
import com.hexnext.engine.compile.Elements
import com.hexnext.engine.compile.LiveOption
import com.hexnext.engine.compile.PackOptions
import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.pack.Pack
import com.hexnext.engine.pack.PackOption
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.CatalogueEntry
import com.hexnext.themer.theme.InstalledPack
import com.hexnext.themer.theme.LogLevel
import com.hexnext.themer.theme.LogLine
import com.hexnext.themer.theme.parseHexColor
import com.hexnext.themer.theme.toHex
import android.graphics.Color as AndroidColor

private val PRESETS = listOf(
    0xFF00E5FF, 0xFF3E7BFA, 0xFF7E57C2, 0xFFEC407A, 0xFFE53935, 0xFFFF7043,
    0xFFF4B400, 0xFF10B981, 0xFF34A853, 0xFF00A6A6, 0xFF8D6E63, 0xFF78909C,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizuku.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val ready = shizuku as? ShizukuState.Ready
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    if (showDiagnostics) {
        DiagnosticsScreen(
            shizuku = shizuku,
            accent = ui.seed,
            onBack = { showDiagnostics = false },
        )
        return
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("HexNext — One UI 9.0 RRO", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = { showDiagnostics = true }) { Text("Diagnostics") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.safeModeBanner?.let { banner ->
                item { SafeModeBanner(banner, onOpenDiagnostics = { showDiagnostics = true }, onDismiss = viewModel::dismissSafeMode) }
            }
            item { StatusCard(shizuku, ui.oneUiVersion, viewModel::requestPermission) }
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
            item { PreviewCard(ui.preview) }
            item {
                CompiledThemeCard(
                    enabled = ready != null && !ui.busy,
                    busy = ui.busy,
                    summary = ui.lastApplySummary,
                    onApply = viewModel::applyTheme,
                    onRemove = viewModel::removeTheme,
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
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "The device restarted twice within 10 minutes of an unconfirmed apply. Check Diagnostics, " +
                    "repair or re-apply, then tap \"It works\" when the result is stable.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** The shared card: title (with an optional [trailing] control at its right) over [content]. */
@Composable
private fun SectionCard(
    title: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (trailing == null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    trailing()
                }
            }
            content()
        }
    }
}

@Composable
private fun StatusCard(state: ShizukuState, oneUi: String?, onRequestPermission: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SectionCard("Privilege Gate (No Theme Park / No High Contrast Overlays)") {
        val body = when (state) {
            ShizukuState.NotRunning ->
                "Opening Requirement: Start Shizuku as UID 0 (Root) or UID 1000 (System), or grant direct SU access. Theme Park is NOT required."
            ShizukuState.TooOld -> "This Shizuku is too old. Update it to version 11 or later."
            ShizukuState.PermissionNeeded -> "HexNext needs permission to use Shizuku (UID 0 / UID 1000)."
            ShizukuState.PermissionDenied ->
                "Permission was denied. Open Shizuku and allow HexNext under authorized apps."
            is ShizukuState.Ready -> when {
                state.isSystemUid1000 ->
                    "Connected as System (UID 1000). Apply theme builds one HexNext_<target> overlay per scanned app (owner 'android') and takes each target over from Samsung's SemWT_* overlay."
                state.isRoot ->
                    "Connected as Root (UID 0). Apply theme builds one HexNext_<target> overlay per scanned app and takes each target over from Samsung's SemWT_* overlay."
                else ->
                    "Connected as unprivileged shell (UID ${state.uid}). Applying a theme needs UID 0 (Root) or UID 1000 (System): start Shizuku with root, or restart it if it came up as shell after a reboot."
            }
        }
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(MainViewModel.androidVersion, oneUi, "Precedence > SemWT_*").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state) {
            ShizukuState.NotRunning, ShizukuState.TooOld ->
                OutlinedButton(onClick = { uriHandler.openUri("https://shizuku.rikka.app/download/") }) {
                    Text("Get Shizuku")
                }
            ShizukuState.PermissionNeeded -> Button(onClick = onRequestPermission) { Text("Allow access") }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorCard(seed: Int, onSeed: (Int) -> Unit) {
    SectionCard("Accent color") {
        Text(
            "Seed of the compiled theme and of the Diagnostics Settings test.",
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PRESETS.forEach { color ->
                val selected = color == seed
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .then(
                            if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable { onSeed(color) }
                )
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
@OptIn(ExperimentalMaterial3Api::class)
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
            "Simple uses one pack for everything. Advanced picks a pack per part of One UI.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (advanced) {
            Text("Default pack", style = MaterialTheme.typography.labelLarge)
        }
        PackRow(selected = spec.simplePackId == null, enabled = !busy, onClick = { onSelect(null) }) {
            Text("Colours only", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        packs.forEach { pack ->
            val selected = spec.simplePackId == pack.id
            PackRow(selected = selected, enabled = !busy, onClick = { onSelect(pack.id) }) {
                Column(Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        packSummary(pack),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onDelete(pack.id) }, enabled = !busy) { Text("Remove") }
            }
            if (selected && !advanced) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !busy,
            ) { Text("Import pack file") }
            OutlinedButton(onClick = { showCatalogue = true }, enabled = !busy) { Text("Get packs") }
        }
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

/** One radio row of [PackCard]; [content] fills the space right of the button. */
@Composable
private fun PackRow(selected: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
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

/** A switch row written like [PackRow]: label left, [Switch] right, the whole row clickable. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = { onCheckedChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
    Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { live ->
            val current = PackOptions.value(spec, pack, live.option, elementId)
            if (live.option.type == PackOption.SELECT) {
                Text(live.option.label, style = MaterialTheme.typography.labelLarge)
                live.option.keys.forEachIndexed { index, key ->
                    PackRow(selected = current == key, enabled = enabled, onClick = { onOption(pack, live, key, elementId) }) {
                        Text(
                            live.option.choices.getOrNull(index)?.takeIf { it.isNotBlank() } ?: key,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(checked = enabled, onCheckedChange = { on -> onElementEnabled(element.id, on) }, enabled = !busy)
        Column(Modifier.weight(1f)) {
            Text(element.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { showDialog = true }, enabled = !busy) { Text("Change") }
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
                        Text("Same as default pack", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                item {
                    PackRow(selected = !enabled, enabled = !busy, onClick = { onElementEnabled(element.id, false) }) {
                        Text("Colours only", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                items(covering) { pack ->
                    PackRow(selected = enabled && setId == pack.id, enabled = !busy, onClick = { onElementPack(element.id, pack.id) }) {
                        Column(Modifier.weight(1f)) {
                            Text(pack.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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

/** Settings dashboard icons of the theme to apply, rendered from the last device scan. Nothing is clickable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCard(preview: PreviewState) {
    SectionCard("Preview") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Settings dashboard icons as they will apply, rendered from the last device scan.",
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
        if (preview.icons.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                preview.icons.forEach { icon ->
                    Column(
                        modifier = Modifier.width(72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val bitmap = icon.bitmap
                        if (bitmap != null) {
                            Image(bitmap.asImageBitmap(), contentDescription = icon.label, modifier = Modifier.size(48.dp))
                        } else {
                            Box(Modifier.size(48.dp))
                        }
                        Text(
                            icon.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        preview.note?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = if (note.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The "Get packs" dialog: catalogue URL, Load, and one row per index entry. */
@Composable
private fun CatalogueDialog(
    state: CatalogueState,
    busy: Boolean,
    onUrl: (String) -> Unit,
    onLoad: () -> Unit,
    onDownload: (CatalogueEntry) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = onUrl,
                        label = { Text("Catalogue URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onLoad, enabled = !state.loading && !busy) { Text("Load") }
                        if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                state.error?.let { error ->
                    item { Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                if (!state.loading && state.error == null && state.entries.isEmpty()) {
                    item {
                        Text(
                            "No packs listed yet. Tap Load, or enter another catalogue URL.",
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
            Text(entry.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
            else -> TextButton(onClick = { onDownload(entry) }, enabled = !busy) { Text("Download") }
        }
    }
}

/** Applies the compiled theme to every scanned target, or removes it. */
@Composable
private fun CompiledThemeCard(
    enabled: Boolean,
    busy: Boolean,
    summary: String?,
    onApply: () -> Unit,
    onRemove: () -> Unit,
) {
    SectionCard("Apply") {
        Text(
            "Builds one HexNext overlay per scanned One UI app, takes each over from Samsung's SemWT overlay, " +
                "and keeps it that way after wallpaper changes. Debloated apps are skipped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (summary != null) {
            Text("Last apply: $summary", style = MaterialTheme.typography.bodySmall)
        }
        Actions(
            enabled = enabled,
            busy = busy,
            applyLabel = "Apply theme",
            onApply = onApply,
            onRemove = onRemove,
            removeLabel = "Remove theme",
        )
    }
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
        Button(onClick = onApply, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(applyLabel)
            }
        }
        OutlinedButton(onClick = onRemove, enabled = enabled, modifier = Modifier.height(52.dp)) {
            Text(removeLabel)
        }
    }
}

@Composable
private fun LogCard(lines: List<LogLine>, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
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
