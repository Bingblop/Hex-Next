package com.hexnext.themer.ui.preview

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
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
    /** Tabs and the light/dark switch on one row, no tap hint: the short peek of Colours and Icons. */
    compact: Boolean = false,
) {
    val reduceMotion = LocalAppSettings.current.reduceMotion
    val palette = animatedPalette(state.scheme(dark), instant = state.dragging)
    val font = rememberPreviewFontFamily(state.fontFile)
    // Remembered, so the screens below can skip when only an animated colour moved.
    val highlighted = state.highlightedGroup
    val device = state.deviceColors
    val scope = remember(palette, highlighted, onElementTap, font, reduceMotion, device) {
        PreviewScope(
            palette = palette,
            highlighted = highlighted,
            onTap = onElementTap,
            font = font,
            reduceMotion = reduceMotion,
            device = device,
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
        compact = compact,
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
        PreviewTab.CONTACTS -> PreviewContacts(scope)
        PreviewTab.CALCULATOR -> PreviewCalculator(scope)
        PreviewTab.VOICE_RECORDER -> PreviewVoiceRecorder(scope)
        PreviewTab.GALLERY -> PreviewGallery(scope)
        PreviewTab.CLOCK -> PreviewClock(scope)
        PreviewTab.CALENDAR -> PreviewCalendar(scope)
        PreviewTab.NOTES -> PreviewNotes(scope)
        PreviewTab.REMINDER -> PreviewReminder(scope)
        PreviewTab.INTERNET -> PreviewInternet(scope)
        PreviewTab.EMAIL -> PreviewEmail(scope)
        PreviewTab.MUSIC -> PreviewMusic(scope)
        PreviewTab.HEALTH -> PreviewHealth(scope)
        PreviewTab.DEVICE_CARE -> PreviewDeviceCare(scope)
    }
}

/**
 * Only the screen of [tab], without tabs or phone frame, scaled so its 300 dp design width fills
 * the real screen: the device lab screenshots it beside the real One UI screen at the same size.
 */
@Composable
fun BarePreviewScreen(state: StudioPreviewState, tab: PreviewTab, dark: Boolean, modifier: Modifier = Modifier) {
    val palette = animatedPalette(state.scheme(dark), instant = true)
    val font = rememberPreviewFontFamily(state.fontFile)
    val scope = remember(palette, font, state.deviceColors) {
        PreviewScope(palette = palette, highlighted = null, onTap = {}, font = font, reduceMotion = true, device = state.deviceColors)
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val scale = maxWidth.value / DESIGN_WIDTH_DP
        val designHeight = (maxHeight.value / scale).coerceAtLeast(1f)
        androidx.compose.foundation.layout.Box(
            Modifier
                .designSize(DESIGN_WIDTH_DP, designHeight)
                .scaledFromTopStart(scale),
        ) {
            PreviewScreen(tab, state, scope)
        }
    }
}

private const val DESIGN_WIDTH_DP = 300f

private fun Modifier.designSize(w: Float, h: Float): Modifier =
    this.then(Modifier.wrapContentSize(androidx.compose.ui.Alignment.TopStart, unbounded = true))
        .then(Modifier.requiredSize(w.dp, h.dp))

private fun Modifier.scaledFromTopStart(scale: Float): Modifier = this.graphicsLayer {
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0f, 0f)
}
