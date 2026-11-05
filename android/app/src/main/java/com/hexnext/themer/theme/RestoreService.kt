package com.hexnext.themer.theme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.hexnext.themer.R
import java.io.Closeable
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

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
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Restore failed (${error.javaClass.simpleName})")
        },
    )
    private val taskOwner = RestoreTaskOwner(scope) {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } finally {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching { goForeground() }.onFailure { Log.w(TAG, "Could not go foreground", it) }.isSuccess
        if (!ok || taskOwner.running) {
            if (!taskOwner.running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val wake = try {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HexNext:restore")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(15 * 60_000L)
                }
        } catch (error: SecurityException) {
            Log.w(TAG, "Wake lock unavailable (${error.javaClass.simpleName})")
            null
        }
        val lease = wake?.let { acquired ->
            Closeable { if (acquired.isHeld) acquired.release() }
        }
        taskOwner.start(lease) {
            withContext(Dispatchers.IO) {
                ThemeRestorer.restoreIfNeeded(applicationContext)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            taskOwner.close()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Restore cleanup failed (${error.javaClass.simpleName})")
        } finally {
            super.onDestroy()
        }
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
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val TAG = "HexNextRestore"
        private const val CHANNEL = "theme_restore_running"
        private const val ID = 4202

        fun intent(context: Context): Intent = Intent(context, RestoreService::class.java)
    }
}
