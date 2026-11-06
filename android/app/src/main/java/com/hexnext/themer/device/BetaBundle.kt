package com.hexnext.themer.device

import android.content.Context
import android.os.Build
import com.hexnext.themer.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One zip a beta tester can send back: phone and app info, the latest scan report, the last
 * apply report, the newest build logs and the activity log (pack picks, downloads, errors). It
 * holds no keys or account data, only what Hex Next itself wrote under `files/reports`,
 * `files/logs` and `files/activity`.
 */
object BetaBundle {

    /** Newest build logs kept in the bundle (each apply writes one). */
    const val MAX_LOGS: Int = 5

    /** Bundles are written here and replace the previous one. */
    fun outputFile(context: Context): File = File(File(context.filesDir, "reports"), "hexnext-beta-report.zip")

    fun create(context: Context): File? {
        val reports = File(context.filesDir, "reports")
        val logs = File(context.filesDir, "logs")
        val out = outputFile(context)
        return write(out, deviceInfo(context), reports, logs, activity = com.hexnext.themer.theme.ActivityLog.file(context.filesDir))
    }

    /** Builds the zip at [out] from the two folders; null when it could not be written. */
    fun write(out: File, info: String, reports: File, logs: File, maxLogs: Int = MAX_LOGS, activity: File? = null): File? = runCatching {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("device.txt"))
            zip.write(info.toByteArray())
            zip.closeEntry()
            selectReports(reports, out.name).forEach { zip.add("reports/${it.name}", it) }
            selectLogs(logs, maxLogs).forEach { zip.add("logs/${it.name}", it) }
            activity?.takeIf { it.isFile }?.let { zip.add("activity.log", it) }
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        out
    }.getOrNull()

    /** Every report file except earlier bundles and half-written temporaries. */
    internal fun selectReports(dir: File, bundleName: String): List<File> =
        (dir.listFiles() ?: emptyArray()).filter {
            it.isFile && it.name != bundleName && !it.name.endsWith(".tmp") && !it.name.endsWith(".zip")
        }.sortedBy { it.name }

    internal fun selectLogs(dir: File, max: Int): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedByDescending { it.lastModified() }.take(max)

    private fun ZipOutputStream.add(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    private fun deviceInfo(context: Context): String {
        val oneUi = runCatching {
            Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
        }.getOrNull()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("Hex Next ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${context.packageName}")
            appendLine("Created $stamp")
            appendLine("Model ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("One UI platform ${oneUi ?: "unknown"}")
            appendLine("Build ${Build.DISPLAY}")
        }
    }
}
