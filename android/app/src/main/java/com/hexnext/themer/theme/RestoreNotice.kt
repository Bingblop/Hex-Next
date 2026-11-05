package com.hexnext.themer.theme

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification
import com.hexnext.themer.MainActivity
import com.hexnext.themer.R

/** The quiet notification [ThemeRestorer] posts when it put a theme back (or could not). */
internal object RestoreNotice {
    private const val CHANNEL = "theme_restore"
    private const val ID = 4201

    fun post(context: Context, success: Boolean, themeName: String) {
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Theme restore", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tells you when Hex Next put your theme back after a restart."
            },
        )
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = themeName.ifBlank { "Your theme" }
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.hx_glyph_themes)
            .setContentTitle(if (success) "Theme restored" else "Couldn't restore your theme")
            .setContentText(if (success) "\"$name\" is back after the restart." else "Open Hex Next and apply \"$name\" again.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(ID, notification)
    }
}
