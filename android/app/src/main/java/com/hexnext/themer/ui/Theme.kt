package com.hexnext.themer.ui

import android.app.UiModeManager
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.hexnext.themer.ui.settings.AppSettings
import com.hexnext.themer.ui.settings.ContrastLevel
import com.hexnext.themer.ui.settings.PaletteSource

/**
 * The [AppSettings] the theme was built with, for screens that need haptics or reduce-motion
 * without a store reference. Defaults to [AppSettings] when no [HexNextTheme] is above.
 */
val LocalAppSettings: ProvidableCompositionLocal<AppSettings> = staticCompositionLocalOf { AppSettings() }

/**
 * Material 3 theme of the app itself, driven by [settings]: light/dark, the phone's dynamic
 * colours or a seeded scheme, pure black, contrast. Every change re-composes live.
 */
@Composable
fun HexNextTheme(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = settings.isDark(isSystemInDarkTheme())
    val colors = remember(settings.palette, settings.seedArgb, settings.pureBlack, settings.contrast, dark) {
        appColorScheme(context, settings, dark)
    }
    CompositionLocalProvider(LocalAppSettings provides settings) {
        MaterialTheme(
            colorScheme = colors,
            typography = HexNextTypography,
            // One UI leans on large, soft corners.
            shapes = Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(26.dp),
            ),
            content = content,
        )
    }
}

/**
 * The scheme [settings] describe, for [dark]. Shared with the settings screen so its swatch
 * preview matches what the app will draw.
 */
fun appColorScheme(context: Context, settings: AppSettings, dark: Boolean): ColorScheme {
    // "Standard" follows the contrast the user chose in Android's accessibility settings, so a
    // phone set to high contrast system-wide is not quietly shown at standard contrast here.
    val contrast = if (settings.contrast == ContrastLevel.STANDARD) systemContrast(context) else settings.contrast
    val scheme = when {
        settings.palette == PaletteSource.SEED ->
            seededColorScheme(settings.seedArgb, dark, contrast)
        contrast != ContrastLevel.STANDARD ->
            // The framework exposes no contrast variant of the dynamic scheme, so rebuild from the
            // phone's own key colour at the requested level.
            seededColorScheme(context.getColor(android.R.color.system_accent1_500), dark, contrast)
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    return if (dark && settings.pureBlack) scheme.pureBlack() else scheme
}

/** The system-wide contrast (Settings > Accessibility) as one of the app's levels; standard when it cannot be read. */
private fun systemContrast(context: Context): ContrastLevel {
    val value = runCatching { context.getSystemService(UiModeManager::class.java)?.contrast }.getOrNull() ?: return ContrastLevel.STANDARD
    return when {
        value >= 0.9f -> ContrastLevel.HIGH
        value >= 0.4f -> ContrastLevel.MEDIUM
        else -> ContrastLevel.STANDARD
    }
}

/** The tonal-spot scheme of [seed] at [contrast], mapped onto Material 3 roles. */
fun seededColorScheme(seed: Int, dark: Boolean, contrast: ContrastLevel): ColorScheme {
    val scheme: DynamicScheme = SchemeTonalSpot(Hct.fromInt(seed), dark, contrast.level)
    val c = MaterialDynamicColors()
    fun role(pick: MaterialDynamicColors.() -> com.google.android.material.color.utilities.DynamicColor): Color =
        Color(c.pick().getArgb(scheme))
    return if (dark) {
        darkColorScheme(
            primary = role { primary() },
            onPrimary = role { onPrimary() },
            primaryContainer = role { primaryContainer() },
            onPrimaryContainer = role { onPrimaryContainer() },
            inversePrimary = role { inversePrimary() },
            secondary = role { secondary() },
            onSecondary = role { onSecondary() },
            secondaryContainer = role { secondaryContainer() },
            onSecondaryContainer = role { onSecondaryContainer() },
            tertiary = role { tertiary() },
            onTertiary = role { onTertiary() },
            tertiaryContainer = role { tertiaryContainer() },
            onTertiaryContainer = role { onTertiaryContainer() },
            background = role { background() },
            onBackground = role { onBackground() },
            surface = role { surface() },
            onSurface = role { onSurface() },
            surfaceVariant = role { surfaceVariant() },
            onSurfaceVariant = role { onSurfaceVariant() },
            surfaceTint = role { surfaceTint() },
            inverseSurface = role { inverseSurface() },
            inverseOnSurface = role { inverseOnSurface() },
            error = role { error() },
            onError = role { onError() },
            errorContainer = role { errorContainer() },
            onErrorContainer = role { onErrorContainer() },
            outline = role { outline() },
            outlineVariant = role { outlineVariant() },
            scrim = role { scrim() },
            surfaceBright = role { surfaceBright() },
            surfaceDim = role { surfaceDim() },
            surfaceContainer = role { surfaceContainer() },
            surfaceContainerHigh = role { surfaceContainerHigh() },
            surfaceContainerHighest = role { surfaceContainerHighest() },
            surfaceContainerLow = role { surfaceContainerLow() },
            surfaceContainerLowest = role { surfaceContainerLowest() },
        )
    } else {
        lightColorScheme(
            primary = role { primary() },
            onPrimary = role { onPrimary() },
            primaryContainer = role { primaryContainer() },
            onPrimaryContainer = role { onPrimaryContainer() },
            inversePrimary = role { inversePrimary() },
            secondary = role { secondary() },
            onSecondary = role { onSecondary() },
            secondaryContainer = role { secondaryContainer() },
            onSecondaryContainer = role { onSecondaryContainer() },
            tertiary = role { tertiary() },
            onTertiary = role { onTertiary() },
            tertiaryContainer = role { tertiaryContainer() },
            onTertiaryContainer = role { onTertiaryContainer() },
            background = role { background() },
            onBackground = role { onBackground() },
            surface = role { surface() },
            onSurface = role { onSurface() },
            surfaceVariant = role { surfaceVariant() },
            onSurfaceVariant = role { onSurfaceVariant() },
            surfaceTint = role { surfaceTint() },
            inverseSurface = role { inverseSurface() },
            inverseOnSurface = role { inverseOnSurface() },
            error = role { error() },
            onError = role { onError() },
            errorContainer = role { errorContainer() },
            onErrorContainer = role { onErrorContainer() },
            outline = role { outline() },
            outlineVariant = role { outlineVariant() },
            scrim = role { scrim() },
            surfaceBright = role { surfaceBright() },
            surfaceDim = role { surfaceDim() },
            surfaceContainer = role { surfaceContainer() },
            surfaceContainerHigh = role { surfaceContainerHigh() },
            surfaceContainerHighest = role { surfaceContainerHighest() },
            surfaceContainerLow = role { surfaceContainerLow() },
            surfaceContainerLowest = role { surfaceContainerLowest() },
        )
    }
}

/**
 * The console scheme of the theme editor ([PaletteSource.CONSOLE]): navy surfaces with cyan
 * (primary), coral (secondary) and amber (tertiary) accents when [dark]; a cool paper-white
 * variant otherwise. [pureBlack] blackens the dark backgrounds like the other schemes.
 */
fun consoleColorScheme(dark: Boolean, pureBlack: Boolean = false): ColorScheme {
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF22D3EE),
            onPrimary = Color(0xFF032A33),
            primaryContainer = Color(0xFF0E3A47),
            onPrimaryContainer = Color(0xFFA5F3FC),
            inversePrimary = Color(0xFF0891B2),
            secondary = Color(0xFFFF5A6E),
            onSecondary = Color(0xFF3A0710),
            secondaryContainer = Color(0xFF4A1622),
            onSecondaryContainer = Color(0xFFFFD1D8),
            tertiary = Color(0xFFFBBF24),
            onTertiary = Color(0xFF2E2000),
            tertiaryContainer = Color(0xFF3D2E07),
            onTertiaryContainer = Color(0xFFFDE68A),
            background = Color(0xFF0B1220),
            onBackground = Color(0xFFE6EDF7),
            surface = Color(0xFF0B1220),
            onSurface = Color(0xFFE6EDF7),
            surfaceVariant = Color(0xFF162238),
            onSurfaceVariant = Color(0xFF8A9BB4),
            surfaceTint = Color(0xFF22D3EE),
            inverseSurface = Color(0xFFE6EDF7),
            inverseOnSurface = Color(0xFF111A2B),
            error = Color(0xFFF87171),
            onError = Color(0xFF450A0A),
            errorContainer = Color(0xFF5B1A1A),
            onErrorContainer = Color(0xFFFECACA),
            outline = Color(0xFF3A4A66),
            outlineVariant = Color(0xFF24324A),
            scrim = Color(0xFF000000),
            surfaceBright = Color(0xFF1F2C44),
            surfaceDim = Color(0xFF0B1220),
            surfaceContainer = Color(0xFF111A2B),
            surfaceContainerHigh = Color(0xFF162238),
            surfaceContainerHighest = Color(0xFF1C2A42),
            surfaceContainerLow = Color(0xFF0E1626),
            surfaceContainerLowest = Color(0xFF080E1A),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0891B2),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFCFF5FC),
            onPrimaryContainer = Color(0xFF083344),
            inversePrimary = Color(0xFF22D3EE),
            secondary = Color(0xFFE11D48),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFE0E6),
            onSecondaryContainer = Color(0xFF4C0519),
            tertiary = Color(0xFFB45309),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFEF3C7),
            onTertiaryContainer = Color(0xFF451A03),
            background = Color(0xFFF4F7FB),
            onBackground = Color(0xFF0B1220),
            surface = Color(0xFFF4F7FB),
            onSurface = Color(0xFF0B1220),
            surfaceVariant = Color(0xFFE5EBF3),
            onSurfaceVariant = Color(0xFF51627D),
            surfaceTint = Color(0xFF0891B2),
            inverseSurface = Color(0xFF162238),
            inverseOnSurface = Color(0xFFE6EDF7),
            error = Color(0xFFDC2626),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFEE2E2),
            onErrorContainer = Color(0xFF7F1D1D),
            outline = Color(0xFF9AA8BE),
            outlineVariant = Color(0xFFD5DDE8),
            scrim = Color(0xFF000000),
            surfaceBright = Color(0xFFFFFFFF),
            surfaceDim = Color(0xFFDCE3ED),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFEEF2F8),
            surfaceContainerHighest = Color(0xFFE5EBF3),
            surfaceContainerLow = Color(0xFFF8FAFD),
            surfaceContainerLowest = Color(0xFFFFFFFF),
        )
    }
    return if (dark && pureBlack) scheme.pureBlack() else scheme
}

/** Pure black backgrounds for AMOLED screens; the same roles the compiled theme blackens. */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
)

/**
 * Material 3 defaults with the slightly wider display/headline tracking One UI uses. Card titles
 * (titleMedium) and list-row titles (titleSmall) carry their semi-bold weight here, so call
 * sites need no per-call weight to read as titles.
 */
val HexNextTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(letterSpacing = 0.sp),
        displayMedium = base.displayMedium.copy(letterSpacing = 0.25.sp),
        displaySmall = base.displaySmall.copy(letterSpacing = 0.25.sp),
        headlineLarge = base.headlineLarge.copy(letterSpacing = 0.25.sp, fontWeight = FontWeight.Medium),
        headlineMedium = base.headlineMedium.copy(letterSpacing = 0.25.sp, fontWeight = FontWeight.Medium),
        headlineSmall = base.headlineSmall.copy(letterSpacing = 0.2.sp, fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}
