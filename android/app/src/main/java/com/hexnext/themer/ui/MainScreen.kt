package com.hexnext.themer.ui

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.themer.shizuku.ShizukuState
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

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                    "Connected as unprivileged shell (UID ${state.uid}). Drawables and night colours need UID 0 (Root) or UID 1000 (System); day colours go through cmd overlay fabricate."
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

/** Full compiled theme from the last device scan (default theme until the theme editor lands). */
@Composable
private fun CompiledThemeCard(
    enabled: Boolean,
    busy: Boolean,
    summary: String?,
    onApply: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Compiled theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Builds one HexNext overlay per scanned One UI target from the last device scan, " +
                    "disables Samsung's SemWT overlay for it and keeps it off after wallpaper changes. " +
                    "Debloated apps are skipped. Run Diagnostics > Run scan first.",
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
