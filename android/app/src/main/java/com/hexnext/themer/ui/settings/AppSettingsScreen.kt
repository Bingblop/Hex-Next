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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.themer.BuildConfig
import com.hexnext.themer.R
import com.hexnext.themer.billing.EntitlementState
import com.hexnext.themer.billing.PaidFeature
import com.hexnext.themer.billing.ProductIds
import com.hexnext.themer.device.CompatibilityCheck
import com.hexnext.themer.theme.parseHexColor
import com.hexnext.themer.theme.toHex
import com.hexnext.themer.ui.ColorSwatch
import com.hexnext.themer.ui.RADIO_INDENT
import com.hexnext.themer.ui.SectionCard
import com.hexnext.themer.ui.appColorScheme
import com.hexnext.themer.ui.consoleColorScheme
import com.hexnext.themer.ui.effectiveContrast

/** The eight seed swatches of the "Custom colour" palette. */
private val SEED_PRESETS = listOf(
    0xFF3E7BFA, 0xFF00A6A6, 0xFF10B981, 0xFFF4B400,
    0xFFFF7043, 0xFFEC407A, 0xFF7E57C2, 0xFF78909C,
).map { it.toInt() }

private const val GITHUB_URL = "https://github.com/Bingblop/Hex-Next"
private const val PRIVACY_POLICY_URL = "https://bingblop.github.io/hexnext-site/privacy-policy.html"

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
    purchases: EntitlementState,
    canPurchase: Boolean,
    onRestorePurchases: () -> Unit,
) {
    val settings by store.settings.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateStartPadding(LayoutDirection.Ltr),
                end = 16.dp + padding.calculateEndPadding(LayoutDirection.Ltr),
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            appSettingsItems(
                settings = settings,
                store = store,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenAiProviders = onOpenAiProviders,
                onOpenOnboarding = onOpenOnboarding,
                purchases = purchases,
                canPurchase = canPurchase,
                onRestorePurchases = onRestorePurchases,
            )
        }
    }
}

/**
 * The cards of App settings, for the full screen above and for the Settings tab of the shell:
 * appearance and behaviour of Hex Next itself, the AI keys, Diagnostics (when [onOpenDiagnostics]
 * is given), purchases and About.
 */
fun LazyListScope.appSettingsItems(
    settings: AppSettings,
    store: AppSettingsStore,
    onOpenDiagnostics: (() -> Unit)?,
    onOpenAiProviders: () -> Unit,
    onOpenOnboarding: () -> Unit,
    purchases: EntitlementState,
    canPurchase: Boolean,
    onRestorePurchases: () -> Unit,
) {
    item { AppearanceCard(settings, store) }
    item { BehaviourCard(settings, store) }
    item {
        SectionCard(stringResource(R.string.settings_ai_providers)) {
            NavRow(
                title = stringResource(R.string.settings_ai_keys),
                summary = stringResource(R.string.settings_ai_keys_summary),
                onClick = onOpenAiProviders,
            )
        }
    }
    if (onOpenDiagnostics != null) {
        item {
            SectionCard(stringResource(R.string.settings_diagnostics)) {
                NavRow(
                    title = stringResource(R.string.settings_diagnostics_title),
                    summary = stringResource(R.string.settings_diagnostics_summary),
                    onClick = onOpenDiagnostics,
                )
            }
        }
    }
    if (canPurchase) {
        item { PurchasesCard(purchases, onRestorePurchases) }
    }
    item { AboutCard(onOpenOnboarding) }
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

    SectionCard(stringResource(R.string.settings_appearance)) {
        Text(
            stringResource(R.string.settings_appearance_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Label(stringResource(R.string.settings_theme))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { store.setThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    label = {
                        Text(stringResource(when (mode) {
                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                        }))
                    },
                )
            }
        }

        Label(stringResource(R.string.settings_palette))
        ChoiceRow(
            selected = settings.palette == PaletteSource.CONSOLE,
            title = stringResource(R.string.settings_palette_console),
            summary = stringResource(R.string.settings_palette_console_summary),
            onClick = { store.setPalette(PaletteSource.CONSOLE) },
        )
        ChoiceRow(
            selected = settings.palette == PaletteSource.DYNAMIC,
            title = stringResource(R.string.settings_palette_dynamic),
            summary = stringResource(R.string.settings_palette_dynamic_summary),
            onClick = { store.setPalette(PaletteSource.DYNAMIC) },
        )
        ChoiceRow(
            selected = settings.palette == PaletteSource.SEED,
            title = stringResource(R.string.settings_palette_seed),
            summary = stringResource(R.string.settings_palette_seed_summary),
            onClick = { store.setPalette(PaletteSource.SEED) },
        )
        if (settings.palette == PaletteSource.SEED) {
            Column(Modifier.padding(start = RADIO_INDENT), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SEED_PRESETS.forEach { color ->
                        ColorSwatch(color = color, selected = color == settings.seedArgb, onClick = { store.setSeed(color) })
                    }
                }
                if (wallpaperSeed != null) {
                    // The row is the one selectable stop; the dot inside it only draws.
                    val wallpaperSelected = wallpaperSeed == settings.seedArgb
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = wallpaperSelected, role = Role.RadioButton, onClick = { store.setSeed(wallpaperSeed) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SwatchDot(color = wallpaperSeed, selected = wallpaperSelected)
                        Column {
                            Text(stringResource(R.string.settings_wallpaper_colour), style = MaterialTheme.typography.titleSmall)
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
            label = stringResource(R.string.settings_pure_black),
            summary = stringResource(R.string.settings_pure_black_summary),
            checked = settings.pureBlack,
            enabled = settings.themeMode != ThemeMode.LIGHT,
            onCheckedChange = store::setPureBlack,
        )

        Label(stringResource(R.string.settings_contrast))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ContrastLevel.entries.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = settings.contrast == level,
                    onClick = { store.setContrast(level) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ContrastLevel.entries.size),
                    label = {
                        Text(stringResource(when (level) {
                            ContrastLevel.STANDARD -> R.string.settings_contrast_standard
                            ContrastLevel.MEDIUM -> R.string.settings_contrast_medium
                            ContrastLevel.HIGH -> R.string.settings_contrast_high
                        }))
                    },
                )
            }
        }

        // Console styles the editor only, so its swatches show the console scheme the editor draws.
        val contrast = effectiveContrast(context, settings.contrast)
        val preview = remember(settings.palette, settings.seedArgb, settings.pureBlack, contrast, dark) {
            if (settings.palette == PaletteSource.CONSOLE && contrast == ContrastLevel.STANDARD) {
                consoleColorScheme(dark, settings.pureBlack)
            } else {
                appColorScheme(context, settings, dark)
            }
        }
        Label(stringResource(R.string.settings_preview))
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

/** The 36 dp colour dot of the wallpaper row, drawn like [ColorSwatch] but with no semantics of its own. */
@Composable
private fun SwatchDot(color: Int, selected: Boolean) {
    Box(
        Modifier
            .padding(6.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(color))
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            ),
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
        label = { Text(stringResource(R.string.settings_seed_hex)) },
        singleLine = true,
        isError = parseHexColor(text) == null,
        supportingText = { if (parseHexColor(text) == null) Text(stringResource(R.string.settings_seed_hex_error)) },
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
    SectionCard(stringResource(R.string.settings_behaviour)) {
        SwitchRow(
            label = stringResource(R.string.settings_haptics),
            summary = stringResource(R.string.settings_haptics_summary),
            checked = settings.haptics,
            enabled = true,
            onCheckedChange = store::setHaptics,
        )
        SwitchRow(
            label = stringResource(R.string.settings_reduce_motion),
            summary = stringResource(R.string.settings_reduce_motion_summary),
            checked = settings.reduceMotion,
            enabled = true,
            onCheckedChange = store::setReduceMotion,
        )
        Column(Modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.settings_restart_protection), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_restart_protection_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------------------------

/**
 * Theme packs and AI theme creation are one-time purchases on Google Play. "Restore purchases"
 * reads them from Play again, for a new phone, a reinstall or a purchase made on another device.
 */
@Composable
private fun PurchasesCard(purchases: EntitlementState, onRestore: () -> Unit) {
    val error = purchases.error
    val packCount = purchases.ownedProductIds.count { it.startsWith(ProductIds.PACK_PREFIX) }
    val status = when {
        purchases.checking -> stringResource(R.string.settings_purchases_checking)
        error != null -> error
        !purchases.ready -> stringResource(R.string.settings_purchases_unchecked)
        purchases.unlockAll -> stringResource(R.string.settings_purchases_all_unlocked)
        packCount == 0 -> stringResource(R.string.settings_purchases_none_unlocked)
        packCount == 1 -> pluralStringResource(R.plurals.settings_purchases_packs_unlocked, packCount, packCount)
        else -> pluralStringResource(R.plurals.settings_purchases_packs_unlocked, packCount, packCount)
    }
    val ai = when {
        !purchases.ready -> null
        purchases.unlocks(PaidFeature.AI_CREATION) -> stringResource(R.string.settings_ai_creation_unlocked)
        purchases.isPending(PaidFeature.AI_CREATION) -> stringResource(R.string.settings_ai_creation_pending)
        else -> stringResource(R.string.settings_ai_creation_locked)
    }
    SectionCard(stringResource(R.string.settings_purchases)) {
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null && !purchases.checking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ai?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NavRow(
            title = stringResource(R.string.settings_restore_purchases),
            summary = stringResource(R.string.settings_restore_purchases_summary),
            onClick = onRestore,
        )
    }
}

@Composable
private fun AboutCard(onOpenOnboarding: () -> Unit) {
    val uriHandler = rememberSafeUriHandler()
    var showLicences by rememberSaveable { mutableStateOf(false) }

    SectionCard(stringResource(R.string.settings_about)) {
        Column(Modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.settings_product_name), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NavRow(
            title = stringResource(R.string.settings_setup_guide),
            summary = stringResource(R.string.settings_setup_guide_summary),
            onClick = onOpenOnboarding,
        )
        NavRow(
            title = stringResource(R.string.settings_licences),
            summary = stringResource(R.string.settings_licences_summary),
            onClick = { showLicences = true },
        )
        NavRow(
            title = stringResource(R.string.settings_privacy_policy),
            summary = stringResource(R.string.settings_privacy_policy_summary),
            onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
        )
        NavRow(
            title = stringResource(R.string.settings_source_code),
            summary = GITHUB_URL.removePrefix("https://"),
            onClick = { uriHandler.openUri(GITHUB_URL) },
        )
        NavRow(
            title = stringResource(R.string.settings_hex_installer),
            summary = stringResource(R.string.settings_hex_installer_summary),
            onClick = { uriHandler.openUri(CompatibilityCheck.HEX_INSTALLER_URL) },
        )
    }

    if (showLicences) {
        LicencesDialog(onDismiss = { showLicences = false })
    }
}

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
        title = { Text(stringResource(R.string.settings_licences)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_licences_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stringArrayResource(R.array.settings_third_party_libraries).forEach { line ->
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

/** One radio row: title and summary right of the button; the row is the one selectable node, the button only draws. */
@Composable
private fun ChoiceRow(selected: Boolean, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Label and summary left, [Switch] right; the row is the one toggleable node. */
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
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
            Text(title, style = MaterialTheme.typography.titleSmall)
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
