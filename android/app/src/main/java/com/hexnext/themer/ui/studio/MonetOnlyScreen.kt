package com.hexnext.themer.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.parseHexColor
import com.hexnext.themer.theme.toHex
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.Screen
import com.hexnext.themer.ui.SectionCard
import com.hexnext.themer.ui.StatusCard
import com.hexnext.themer.ui.UiState

/**
 * A secure-settings editor, not the root Studio with disabled tabs. Reuses native Material
 * tokens. At phone width the sheet has equal 16 dp gutters; wider screens cap it at 640 dp.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MonetOnlyScreen(viewModel: MainViewModel, ui: UiState, shizuku: ShizukuState) {
    var hex by rememberSaveable { mutableStateOf(ui.seed.toHex()) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var connection by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val parsed = parseHexColor(hex)
    val busy = ui.busy
    LaunchedEffect(ui.seed) { hex = ui.seed.toHex() }
    LaunchedEffect(ui.notice?.id) {
        ui.notice?.let { notice ->
            try { snackbar.showSnackbar(notice.message) } finally { viewModel.consumeNotice(notice.id) }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Hex Next", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { viewModel.navigate(Screen.SETTINGS) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "App settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("System palette", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("Shizuku · UID 2000", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Choose an accent for Android's Monet palette. Palette-aware apps decide how to use it.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    SectionCard("Accent colour") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(40.dp).background(Color(ui.seed), RoundedCornerShape(12.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(ui.seed.toHex(), style = MaterialTheme.typography.titleMedium)
                                Text("Selected seed", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { picker = true }, enabled = !busy) { Text("Pick colour") }
                        }
                        OutlinedTextField(
                            value = hex,
                            onValueChange = { value ->
                                hex = value
                                parseHexColor(value)?.let(viewModel::setSeed)
                            },
                            label = { Text("Hex colour") },
                            singleLine = true,
                            enabled = !busy,
                            isError = parsed == null,
                            supportingText = { Text(if (parsed == null) "Enter a colour such as #00E5FF" else "Use a #RRGGBB value") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0xFF00E5FF, 0xFF4285F4, 0xFF4CAF50, 0xFFFF9800, 0xFFE91E63).forEach { preset ->
                                TextButton(onClick = { viewModel.setSeed(preset.toInt()) }, enabled = !busy) {
                                    Box(Modifier.padding(end = 6.dp).size(16.dp).background(Color(preset), RoundedCornerShape(4.dp)))
                                    Text(preset.toInt().toHex())
                                }
                            }
                        }
                        Button(onClick = viewModel::applyMonetPalette, enabled = !busy && parsed != null,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (ui.serviceBusy) "Working…" else "Apply palette")
                        }
                        OutlinedButton(onClick = viewModel::restoreMonetPalette, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) { Text("Restore previous palette") }
                        ui.monetMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium,
                                color = if (ui.monetError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                item {
                    SectionCard("Monet only") {
                        Text("This connection writes the palette seed through secure settings only. It does not create, enable, disable or remove One UI app overlays.",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Packs, app previews, icons, backgrounds, fonts and per-app colours are not available in UID 2000 mode.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    SectionCard("Need full theming?") {
                        Text("Connect as UID 1000 through an existing root provider or Shizuku for persistent overlays. UID 0 root is the fallback; its overlays may disappear after reboot.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { connection = true }, enabled = !busy) { Text("Connection options") }
                    }
                }
            }
        }
    }
    if (picker) ColorPickerSheet(
        title = "System accent", initial = ui.seed, shades = emptyMap(),
        onPreview = { _, _ -> },
        onPick = { color, _ -> viewModel.setSeed(color); picker = false },
        onDismiss = { picker = false },
        subtitle = "A Monet seed only, not a One UI app overlay.",
    )
    if (connection) ModalBottomSheet(
        onDismissRequest = { connection = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item { StatusCard(shizuku, ui.capability, ui.versionLine, viewModel::requestPermission) }
        }
    }
}
