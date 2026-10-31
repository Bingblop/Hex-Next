package com.hexnext.themer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.ui.HexNextTheme
import com.hexnext.themer.ui.MainScreen
import com.hexnext.themer.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init()
        enableEdgeToEdge()
        setContent {
            HexNextTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have started Shizuku or changed its permission while we were away.
        ShizukuManager.refresh()
    }
}
