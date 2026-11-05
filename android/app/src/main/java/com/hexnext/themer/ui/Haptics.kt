package com.hexnext.themer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * A light tap vibration when App settings has haptic feedback on (it is on until the user
 * turns it off). Screens call the returned function from clicks; it reads the latest setting.
 */
@Composable
internal fun rememberHapticTap(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val enabled = LocalAppSettings.current.haptics
    return remember(haptic, enabled) {
        { if (enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
}
