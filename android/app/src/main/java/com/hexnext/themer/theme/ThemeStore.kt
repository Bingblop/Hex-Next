package com.hexnext.themer.theme

import android.content.Context
import com.hexnext.engine.compile.ThemeSpec
import java.io.File

/**
 * Persists the engine [ThemeSpec] as JSON in `files/theme.json`.
 *
 * The accent seed is not owned here: `MainViewModel` keeps it in its preference and copies it
 * into the spec it applies. Only pack choices (and later the editor's settings) live in this file.
 */
class ThemeStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    /** The stored spec; [ThemeSpec.default] when the file is missing or unreadable. */
    fun load(): ThemeSpec = runCatching {
        if (file.isFile) ThemeSpec.fromJson(file.readText()) else null
    }.getOrNull() ?: ThemeSpec.default()

    /** Writes [spec]; a failed write leaves the previous file in place. */
    @Synchronized
    fun save(spec: ThemeSpec) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(spec.toJson())
        if (!tmp.renameTo(file)) {
            file.writeText(spec.toJson())
            tmp.delete()
        }
    }

    /** Loads, transforms with [block] and saves atomically with respect to other callers. */
    @Synchronized
    fun update(block: (ThemeSpec) -> ThemeSpec): ThemeSpec = block(load()).also { save(it) }

    companion object {
        const val FILE_NAME = "theme.json"
    }
}
