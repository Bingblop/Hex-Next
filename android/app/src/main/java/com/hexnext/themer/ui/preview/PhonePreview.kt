package com.hexnext.themer.ui.preview

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.ui.LocalAppSettings
import com.hexnext.themer.ui.studio.PreviewTab
import com.hexnext.themer.ui.studio.StudioPreviewState

/**
 * The interactive phone mockup of the Studio: tabs for every [PreviewTab], a light/dark switch,
 * drawn only from [state]. Tapping a themed element calls [onElementTap] with its group; the
 * elements of [StudioPreviewState.highlightedGroup] get a cyan outline.
 *
 * The caller owns [tab] and [dark]. Colours animate between edits unless reduce motion is on.
 */
@Composable
fun PhonePreview(
    state: StudioPreviewState,
    tab: PreviewTab,
    onTabChange: (PreviewTab) -> Unit,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onElementTap: (ColorGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAppSettings.current.reduceMotion
    val palette = animatedPalette(state.scheme(dark))
    val modeState = state.copy(
        settingsIcons = state.settingsIcons(dark),
        myFilesIcons = state.myFilesIcons(dark),
    )
    val font = rememberPreviewFontFamily(state.fontFile)
    val scope = PreviewScope(
        palette = palette,
        highlighted = state.highlightedGroup,
        onTap = onElementTap,
        font = font,
        reduceMotion = reduceMotion,
    )
    PreviewFrame(
        state = modeState,
        tab = tab,
        onTabChange = onTabChange,
        dark = dark,
        onDarkChange = onDarkChange,
        scope = scope,
        modifier = modifier,
    ) {
        if (reduceMotion) {
            PreviewScreen(tab, modeState, scope)
        } else {
            Crossfade(targetState = tab, animationSpec = tween(durationMillis = 180), label = "previewTab") { shown ->
                PreviewScreen(shown, modeState, scope)
            }
        }
    }
}

/** The screen of [tab]. */
@Composable
private fun PreviewScreen(tab: PreviewTab, state: StudioPreviewState, scope: PreviewScope) {
    when (tab) {
        PreviewTab.SETTINGS -> PreviewSettings(state, scope)
        PreviewTab.QUICK_PANEL -> PreviewQuickPanel(state, scope)
        PreviewTab.NOTIFICATIONS -> PreviewNotifications(state, scope)
        PreviewTab.MY_FILES -> PreviewMyFiles(state, scope)
        PreviewTab.DIALER -> PreviewDialer(scope)
        PreviewTab.KEYBOARD -> PreviewKeyboard(scope)
        PreviewTab.POWER_MENU -> PreviewPowerMenu(state, scope)
    }
}
