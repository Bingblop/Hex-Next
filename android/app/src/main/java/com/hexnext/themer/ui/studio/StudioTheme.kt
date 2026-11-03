package com.hexnext.themer.ui.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.hexnext.themer.ui.HexNextTypography
import com.hexnext.themer.ui.LocalAppSettings
import com.hexnext.themer.ui.consoleColorScheme
import com.hexnext.themer.ui.settings.PaletteSource

/**
 * The look of the theme editor (the Studio). With the Console palette of App settings (the default)
 * it draws the navy console scheme ([consoleColorScheme]): light or dark as App settings say, pure
 * black when asked. With "Phone colours" or "Custom colour" the editor follows the app scheme of
 * [com.hexnext.themer.ui.HexNextTheme] above it unchanged.
 *
 * Only the editor is wrapped: App settings, Diagnostics, the AI provider keys and the setup guide
 * keep [com.hexnext.themer.ui.HexNextTheme] as before.
 */
@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    val settings = LocalAppSettings.current
    if (settings.palette != PaletteSource.CONSOLE) {
        content()
        return
    }
    val dark = settings.isDark(isSystemInDarkTheme())
    val colors = remember(dark, settings.pureBlack) { consoleColorScheme(dark, settings.pureBlack) }
    MaterialTheme(
        colorScheme = colors,
        typography = HexNextTypography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
