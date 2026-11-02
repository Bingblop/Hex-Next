package com.hexnext.themer.theme

import android.content.Context
import android.util.Log
import com.hexnext.engine.compile.ThemeSpec
import java.io.File
import java.io.FileOutputStream

/**
 * Persists the engine [ThemeSpec] as JSON in `files/theme.json`.
 *
 * The accent seed is not owned here: `MainViewModel` keeps it in its preference and copies it
 * into the spec it applies. Only pack choices (and later the editor's settings) live in this file.
 */
class ThemeStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * The stored spec; [ThemeSpec.default] when the file is missing or unreadable. An unreadable
     * file (empty, truncated, malformed) is logged and moved aside to [BACKUP_NAME] first, so the
     * next [save] of the default cannot destroy the user's only copy of their choices.
     */
    @Synchronized
    fun load(): ThemeSpec {
        if (!file.isFile) return ThemeSpec.default()
        return runCatching { ThemeSpec.fromJson(file.readText()) }.getOrElse { e ->
            val backup = File(file.parentFile, BACKUP_NAME)
            val kept = file.renameTo(backup) || runCatching { file.copyTo(backup, overwrite = true) }.isSuccess
            Log.w(TAG, "Unreadable $FILE_NAME (${e.message}); using the default theme" + if (kept) ", old file kept as $BACKUP_NAME" else "")
            ThemeSpec.default()
        }
    }

    /** Writes [spec] (synced to disk before the rename); a failed write leaves the previous file in place. */
    @Synchronized
    fun save(spec: ThemeSpec) {
        val bytes = spec.toJson().toByteArray(Charsets.UTF_8)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        writeSynced(tmp, bytes)
        if (!tmp.renameTo(file)) {
            writeSynced(file, bytes)
            tmp.delete()
        }
    }

    private fun writeSynced(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    /** Loads, transforms with [block] and saves atomically with respect to other callers. */
    @Synchronized
    fun update(block: (ThemeSpec) -> ThemeSpec): ThemeSpec = block(load()).also { save(it) }

    companion object {
        const val FILE_NAME = "theme.json"
        const val BACKUP_NAME = "theme.json.bad"
        private const val TAG = "ThemeStore"
    }
}
