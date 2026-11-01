package com.hexnext.themer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.hexnext.themer.device.Compatibility
import com.hexnext.themer.device.CompatibilityCheck
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.ui.BlockedScreen
import com.hexnext.themer.ui.HexNextTheme
import com.hexnext.themer.ui.MainScreen
import com.hexnext.themer.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Samsung One UI 7+ on Android 15+; anything else gets [BlockedScreen] and no view model. */
    private val compat: Compatibility by lazy { CompatibilityCheck.check() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (compat == Compatibility.Supported) ShizukuManager.init()
        enableEdgeToEdge()
        setContent {
            HexNextTheme {
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
