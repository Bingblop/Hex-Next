package com.hexnext.themer.ui.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.hexnext.themer.ui.ConsoleShapes
import com.hexnext.themer.ui.ConsoleTypography
import com.hexnext.themer.ui.LocalAppSettings
import com.hexnext.themer.ui.LocalStatusPalette
import com.hexnext.themer.ui.StatusPalette
import com.hexnext.themer.ui.consoleColorScheme
import com.hexnext.themer.ui.effectiveContrast
import com.hexnext.themer.ui.settings.ContrastLevel
import com.hexnext.themer.ui.settings.PaletteSource

/**
 * The look of every screen after the setup guide: the shell, App settings, Diagnostics, the AI
 * provider keys, the resource editor and the blocked screen (Studio v2 phase 2 § 3).
 *
 * With the Console palette of App settings (the default) it draws the navy console scheme
 * ([consoleColorScheme]): light or dark as App settings say, pure black when asked. With "Phone
 * colours" or "Custom colour" the colours of [com.hexnext.themer.ui.HexNextTheme] above it are
 * kept. The console type scale ([ConsoleTypography], system font only) and shapes apply to every
 * palette, so text stays readable whichever colours the user picked.
 *
 * The setup guide is not wrapped: it keeps [com.hexnext.themer.ui.HexNextTheme] unchanged.
 */
@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    val settings = LocalAppSettings.current
    val console = settings.palette == PaletteSource.CONSOLE
    val dark = settings.isDark(isSystemInDarkTheme())
    val inherited = MaterialTheme.colorScheme
    val contrast = effectiveContrast(LocalContext.current, settings.contrast)
    val colors = if (console && contrast == ContrastLevel.STANDARD) {
        remember(dark, settings.pureBlack, contrast) { consoleColorScheme(dark, settings.pureBlack) }
    } else {
        inherited
    }
    // The status colours follow what is drawn, not the setting: a light phone scheme gets the dark values.
    val status = if (colors.background.luminance() > 0.5f) StatusPalette.Light else StatusPalette.Dark
    CompositionLocalProvider(LocalStatusPalette provides status) {
        MaterialTheme(
            colorScheme = colors,
            typography = ConsoleTypography,
            shapes = ConsoleShapes,
            content = content,
        )
    }
}
