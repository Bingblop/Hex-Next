package com.hexnext.themer.device

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Shares a report or log file from `files/` through the app's FileProvider (`${applicationId}.files`). */
object ReportSharer {

    fun authority(context: Context): String = "${context.packageName}.files"

    /** Returns false when the file is missing or outside the provider's paths. */
    fun share(context: Context, file: File): Boolean {
        if (!file.isFile) return false
        val uri = runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrNull() ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Hex Next ${file.name}")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share ${file.name}").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser) }.isSuccess
    }
}
