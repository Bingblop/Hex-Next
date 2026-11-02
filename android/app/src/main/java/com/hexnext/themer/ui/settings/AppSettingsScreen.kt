package com.hexnext.themer.ui.settings

import android.app.WallpaperManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.themer.BuildConfig
import com.hexnext.themer.device.CompatibilityCheck
import com.hexnext.themer.theme.parseHexColor
import com.hexnext.themer.theme.toHex
import com.hexnext.themer.ui.SectionCard
import com.hexnext.themer.ui.appColorScheme

/** The eight seed swatches of the "Custom colour" palette. */
private val SEED_PRESETS = listOf(
    0xFF3E7BFA, 0xFF00A6A6, 0xFF10B981, 0xFFF4B400,
    0xFFFF7043, 0xFFEC407A, 0xFF7E57C2, 0xFF78909C,
).map { it.toInt() }

private const val GITHUB_URL = "https://github.com/Bingblop/Hex-Next"

/**
 * "App settings": appearance of the app itself (Material 3, live), behaviour, and the doors to the
 * AI provider keys, Diagnostics, the setup guide and About. Self-contained: it reads and writes
 * [store] and only calls back for navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    store: AppSettingsStore,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAiProviders: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    val settings by store.settings.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
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
            item { AppearanceCard(settings, store) }
            item { BehaviourCard(settings, store) }
            item {
                SectionCard("AI providers") {
                    NavRow(
                        title = "API keys",
                        summary = "Add your own keys to generate icons from a short description.",
                        onClick = onOpenAiProviders,
                    )
                }
            }
            item {
                SectionCard("Diagnostics") {
                    NavRow(
                        title = "Scan, reports and safety",
                        summary = "Check the phone, share a report and repair the applied theme.",
                        onClick = onOpenDiagnostics,
                    )
                }
            }
            item { AboutCard(onOpenOnboarding) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Appearance
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppearanceCard(settings: AppSettings, store: AppSettingsStore) {
    val context = LocalContext.current
    val dark = settings.isDark(isSystemInDarkTheme())
    val wallpaperSeed = remember { wallpaperColor(context) }

    SectionCard("Appearance") {
        Text(
            "How Hex Next itself looks. The theme you apply to the phone is set on the main screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Label("Theme")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { store.setThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    label = { Text(mode.label) },
                )
            }
        }

        Label("Colour palette")
        ChoiceRow(
            selected = settings.palette == PaletteSource.DYNAMIC,
            title = PaletteSource.DYNAMIC.label,
            summary = "Follow the wallpaper, or the theme applied by Hex Next.",
            onClick = { store.setPalette(PaletteSource.DYNAMIC) },
        )
        ChoiceRow(
            selected = settings.palette == PaletteSource.SEED,
            title = PaletteSource.SEED.label,
            summary = "Build the app's colours from one colour of your choice.",
            onClick = { store.setPalette(PaletteSource.SEED) },
        )
        if (settings.palette == PaletteSource.SEED) {
            Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SEED_PRESETS.forEach { color ->
                        Swatch(color = color, selected = color == settings.seedArgb, onClick = { store.setSeed(color) })
                    }
                }
                if (wallpaperSeed != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { store.setSeed(wallpaperSeed) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Swatch(color = wallpaperSeed, selected = wallpaperSeed == settings.seedArgb, onClick = { store.setSeed(wallpaperSeed) })
                        Column {
                            Text("Wallpaper colour", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                wallpaperSeed.toHex(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                SeedHexField(seed = settings.seedArgb, onSeed = store::setSeed)
            }
        }

        SwitchRow(
            label = "Pure black",
            summary = "Use true black backgrounds in dark theme. Saves power on AMOLED screens.",
            checked = settings.pureBlack,
            enabled = settings.themeMode != ThemeMode.LIGHT,
            onCheckedChange = store::setPureBlack,
        )

        Label("Contrast")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ContrastLevel.entries.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = settings.contrast == level,
                    onClick = { store.setContrast(level) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ContrastLevel.entries.size),
                    label = { Text(level.label) },
                )
            }
        }

        val preview = remember(settings.palette, settings.seedArgb, settings.pureBlack, settings.contrast, dark) {
            appColorScheme(context, settings, dark)
        }
        Label("Preview")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                preview.primary,
                preview.primaryContainer,
                preview.secondaryContainer,
                preview.tertiaryContainer,
                preview.surfaceContainer,
                preview.surface,
            ).forEach { color ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(color)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                )
            }
        }
    }
}

/** The phone's current wallpaper key colour, or null when the system has none to give. */
private fun wallpaperColor(context: android.content.Context): Int? = runCatching {
    WallpaperManager.getInstance(context)
        .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        ?.primaryColor
        ?.toArgb()
        ?.or(0xFF000000.toInt())
}.getOrNull()

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(color))
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
            .clickable(onClick = onClick)
    )
}

/** A `#RRGGBB` field for the seed, written like the editor's hex field. */
@Composable
private fun SeedHexField(seed: Int, onSeed: (Int) -> Unit) {
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

// ---------------------------------------------------------------------------------------------
// Behaviour
// ---------------------------------------------------------------------------------------------

@Composable
private fun BehaviourCard(settings: AppSettings, store: AppSettingsStore) {
    SectionCard("Behaviour") {
        SwitchRow(
            label = "Vibration feedback",
            summary = "Vibrate when a theme is applied, succeeds or fails.",
            checked = settings.haptics,
            enabled = true,
            onCheckedChange = store::setHaptics,
        )
        SwitchRow(
            label = "Reduce motion",
            summary = "Skip card and screen animations.",
            checked = settings.reduceMotion,
            enabled = true,
            onCheckedChange = store::setReduceMotion,
        )
        Column(Modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            Text("Restart protection", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Always on. If the phone restarts twice within 10 minutes of an unconfirmed apply, " +
                    "Hex Next turns its overlays off and shows a notice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------------------------

@Composable
private fun AboutCard(onOpenOnboarding: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var showLicences by rememberSaveable { mutableStateOf(false) }

    SectionCard("About") {
        Column(Modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            Text("Hex Next", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NavRow(
            title = "Setup guide",
            summary = "See the welcome steps again: compatibility, root or Shizuku, and a first pack.",
            onClick = onOpenOnboarding,
        )
        NavRow(
            title = "Open-source licences",
            summary = "Third-party libraries used by Hex Next.",
            onClick = { showLicences = true },
        )
        NavRow(
            title = "Source code on GitHub",
            summary = GITHUB_URL.removePrefix("https://"),
            onClick = { uriHandler.openUri(GITHUB_URL) },
        )
        NavRow(
            title = "Hex Installer for older phones",
            summary = "Android 14 and earlier, One UI 6 and earlier. Opens the Play Store.",
            onClick = { uriHandler.openUri(CompatibilityCheck.HEX_INSTALLER_URL) },
        )
    }

    if (showLicences) {
        LicencesDialog(onDismiss = { showLicences = false })
    }
}

/** Library · licence, one per line. Kept as plain text so it ships without a licence plugin. */
private val THIRD_PARTY_LIBRARIES = listOf(
    "Hex Next · MIT License",
    "Kotlin standard library and coroutines (JetBrains) · Apache License 2.0",
    "kotlinx.serialization (JetBrains) · Apache License 2.0",
    "Jetpack Compose, Material 3 and Material Icons (AndroidX) · Apache License 2.0",
    "AndroidX Core, Activity and Lifecycle · Apache License 2.0",
    "Material Components for Android, colour utilities (Google) · Apache License 2.0",
    "Shizuku API and provider (RikkaApps) · see github.com/RikkaApps/Shizuku-API",
    "HiddenApiBypass (LSPosed) · Apache License 2.0",
)

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Open-source licences") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Hex Next is built with these open-source projects. Each is used under the licence named.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                THIRD_PARTY_LIBRARIES.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Shared rows, written like the main screen's PackRow / SwitchRow
// ---------------------------------------------------------------------------------------------

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One radio row: title and summary right of the button, the whole row clickable. */
@Composable
private fun ChoiceRow(selected: Boolean, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Label and summary left, [Switch] right, the whole row clickable. */
@Composable
private fun SwitchRow(
    label: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = { onCheckedChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A row that opens another screen or a link: title and summary, chevron right. */
@Composable
private fun NavRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
