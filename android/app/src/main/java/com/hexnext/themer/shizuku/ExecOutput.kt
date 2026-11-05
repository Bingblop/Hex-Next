package com.hexnext.themer.shizuku

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.hexnext.themer.IThemeService
import java.io.IOException

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
        if (!ProcessOutputPolicy.isSpillPath(path, MonitorScript.DIR)) {
            Log.w("HexNextExec", "Rejected an unexpected command-output path")
            result.putInt(ThemeService.KEY_CODE, 125)
            return inline
        }
        return try {
            service.openFile(path)?.let { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ProcessOutputPolicy.readUtf8(it) }
            } ?: inline.also { result.putInt(ThemeService.KEY_CODE, 125) }
        } catch (error: IOException) {
            Log.w("HexNextExec", "Could not read bounded command output (${error.javaClass.simpleName})")
            result.putInt(ThemeService.KEY_CODE, 125)
            inline
        } catch (error: RemoteException) {
            Log.w("HexNextExec", "Command-output service disconnected (${error.javaClass.simpleName})")
            result.putInt(ThemeService.KEY_CODE, 125)
            inline
        } finally {
            try {
                service.exec("rm -f -- '$path'")
            } catch (error: RemoteException) {
                Log.w("HexNextExec", "Output cleanup deferred after service disconnect (${error.javaClass.simpleName})")
            }
        }
    }

    /** [IThemeService.exec] of [command] with the whole output, however long. */
    fun exec(service: IThemeService, command: String): Pair<Int, String> {
        val result = service.exec(command)
        val output = fullText(service, result)
        return result.getInt(ThemeService.KEY_CODE, -1) to output
    }
}
