package com.hexnext.themer.theme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.hexnext.themer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the background theme restore ([ThemeRestorer]) as a foreground service.
 *
 * Without one, the restore runs in a process with nothing on screen, and Android freezes or kills
 * it a few dozen seconds in (device lab, lab-012 and lab-015: every restore stopped after 8 of 58
 * apps, mid-log, with no crash). A foreground service keeps it running to the end.
 *
 * Started by [ThemeRestorer] itself, or, when Android does not let a background app start one,
 * through the root shell (`am start-foreground-service`), which it always lets through.
 */
class RestoreService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching { goForeground() }.onFailure { Log.w(TAG, "Could not go foreground", it) }.isSuccess
        if (!ok || running) {
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        scope.launch {
            runCatching { ThemeRestorer.restoreIfNeeded(applicationContext) }
                .onFailure { Log.w(TAG, "Restore failed", it) }
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Theme restore in progress", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Hex Next puts your theme back after a restart."
            },
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.hx_glyph_themes)
            .setContentTitle("Restoring your theme")
            .setContentText("Hex Next is putting your theme back after the restart.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        private const val TAG = "HexNextRestore"
        private const val CHANNEL = "theme_restore_running"
        private const val ID = 4202

        fun intent(context: Context): Intent = Intent(context, RestoreService::class.java)
    }
}
