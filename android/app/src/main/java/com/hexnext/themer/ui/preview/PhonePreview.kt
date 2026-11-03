package com.hexnext.themer.ui.preview

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val palette = animatedPalette(state.scheme(dark), instant = state.dragging)
    val font = rememberPreviewFontFamily(state.fontFile)
    // Remembered, so the screens below can skip when only an animated colour moved.
    val highlighted = state.highlightedGroup
    val scope = remember(palette, highlighted, onElementTap, font, reduceMotion) {
        PreviewScope(
            palette = palette,
            highlighted = highlighted,
            onTap = onElementTap,
            font = font,
            reduceMotion = reduceMotion,
        )
    }
    PreviewFrame(
        state = state,
        tab = tab,
        onTabChange = onTabChange,
        dark = dark,
        onDarkChange = onDarkChange,
        scope = scope,
        modifier = modifier,
    ) {
        if (reduceMotion) {
            PreviewScreen(tab, state, scope)
        } else {
            Crossfade(targetState = tab, animationSpec = tween(durationMillis = 180), label = "previewTab") { shown ->
                PreviewScreen(shown, state, scope)
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
