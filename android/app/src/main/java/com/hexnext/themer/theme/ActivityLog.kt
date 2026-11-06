package com.hexnext.themer.theme

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The on-screen activity log, also kept on disk (`files/activity/activity.log`) for the beta
 * report: pack picks, downloads and their errors, apply and remove steps. Before this only build
 * logs were saved, so a pack pick that never reached an apply left no trace (2.3.1: Kyle picked
 * Carnelian, the apply log said Willemite). Appends run on one background thread; past
 * [maxBytes] the file keeps its newer half.
 */
class ActivityLog(private val file: File, private val maxBytes: Long = MAX_BYTES) {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "hexnext-activity-log").apply { isDaemon = true } }

    fun append(line: LogLine, now: Long = System.currentTimeMillis()) {
        val text = "${stamp(now)} ${line.level} ${line.text.replace('\n', ' ')}\n"
        writer.execute { runCatching { write(text) } }
    }

    /** Writes [text] now, on the calling thread (tests and the executor). */
    internal fun write(text: String) {
        file.parentFile?.mkdirs()
        file.appendText(text)
        if (file.length() > maxBytes) {
            val kept = file.readText().let { it.substring(it.length / 2) }
            val tail = kept.substringAfter('\n', kept)
            File(file.parentFile, file.name + ".tmp").apply { writeText(tail) }.renameTo(file)
        }
    }

    private fun stamp(now: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))

    companion object {
        const val MAX_BYTES: Long = 256L * 1024

        fun file(filesDir: File): File = File(File(filesDir, "activity"), "activity.log")
    }
}
