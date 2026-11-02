package com.hexnext.themer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.themer.device.Compatibility
import com.hexnext.themer.device.CompatibilityCheck
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.ui.BlockedScreen
import com.hexnext.themer.ui.HexNextTheme
import com.hexnext.themer.ui.MainScreen
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.settings.AppSettingsStore

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Samsung One UI 7+ on Android 15+; anything else gets [BlockedScreen] and no view model. */
    private val compat: Compatibility by lazy { CompatibilityCheck.check() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (compat == Compatibility.Supported) ShizukuManager.init()
        enableEdgeToEdge()
        val settingsStore = AppSettingsStore.get(this)
        setContent {
            val settings by settingsStore.settings.collectAsStateWithLifecycle()
            // A forced light or dark theme must flip the status and navigation bar icons too.
            val dark = settings.isDark(isSystemInDarkTheme())
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            HexNextTheme(settings) {
                when (val gate = compat) {
                    Compatibility.Supported -> MainScreen(viewModel)
                    else -> BlockedScreen(gate)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have started Shizuku or changed its permission while we were away.
        if (compat == Compatibility.Supported) ShizukuManager.refresh()
    }
}
