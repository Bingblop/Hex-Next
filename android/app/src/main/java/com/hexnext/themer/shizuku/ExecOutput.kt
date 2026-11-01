package com.hexnext.themer.shizuku

import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.hexnext.themer.IThemeService

/** Reading [IThemeService.exec] results whose output the service spilled to a file. */
object ExecOutput {

    /**
     * The whole output of [result]: the inline text, or, when the service wrote it to
     * [ThemeService.KEY_OUTPUT_PATH], the file read through `openFile` (then deleted). Falls back
     * to the inline head when the file cannot be read.
     */
    fun fullText(service: IThemeService, result: Bundle): String {
        val inline = result.getString(ThemeService.KEY_OUTPUT).orEmpty()
        val path = result.getString(ThemeService.KEY_OUTPUT_PATH) ?: return inline
        val text = runCatching {
            service.openFile(path)?.let { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }.getOrNull()
        runCatching { service.exec("rm -f '$path'") }
        return text ?: inline
    }

    /** [IThemeService.exec] of [command] with the whole output, however long. */
    fun exec(service: IThemeService, command: String): Pair<Int, String> {
        val result = service.exec(command)
        return result.getInt(ThemeService.KEY_CODE, -1) to fullText(service, result)
    }
}
