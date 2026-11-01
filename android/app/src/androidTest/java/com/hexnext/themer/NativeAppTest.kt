package com.hexnext.themer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherShowsNativeDesignAndPersistsAccentAcrossRecreation() {
        compose.onNodeWithText("HexNext — One UI 9.0 RRO").assertIsDisplayed()
        val prefs = compose.activity.getSharedPreferences("hexnext", Context.MODE_PRIVATE)
        val originalSeed = prefs.getInt("seed", 0xFF00E5FF.toInt())
        try {
            compose.onNodeWithTag("theme-list").performScrollToNode(hasText("Accent color"))
            compose.onNodeWithTag("accent-hex").performTextReplacement("#FF7043")
            compose.runOnIdle {
                assertEquals(0xFFFF7043.toInt(), prefs.getInt("seed", 0))
                assertEquals(BuildConfig.APPLICATION_ID, compose.activity.packageName)
            }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("theme-list").performScrollToNode(hasText("Accent color"))
            compose.onNodeWithTag("accent-hex").assertTextContains("#FF7043")
        } finally {
            prefs.edit().putInt("seed", originalSeed).commit()
        }
    }

    @Test
    fun nativePreviewWorksWithoutPrivilegedThemeApplication() {
        assumeFalse((ShizukuManager.state.value as? ShizukuState.Ready)?.isPrivileged == true)
        compose.onNodeWithTag("theme-list").performScrollToNode(hasText("Preview"))
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithTag("theme-list")
            .performScrollToNode(hasText("Build & Apply One UI 9.0 System RROs"))
        compose.onNodeWithText("Build & Apply One UI 9.0 System RROs").assertIsNotEnabled()
        compose.onNodeWithText("Remove").assertIsNotEnabled()
    }
}
