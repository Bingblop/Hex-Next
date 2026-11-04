package com.hexnext.themer

import android.app.Application
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.theme.ThemeRestorer

/**
 * Starts the Shizuku watch with the process, not only with the main screen: Shizuku starts the
 * app's process when its server comes up (it hands every client its binder), so a theme Android
 * dropped on a restart comes back without opening Hex Next ([ThemeRestorer]).
 */
class HexNextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.init()
        ThemeRestorer.watch(this)
    }
}
