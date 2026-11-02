package com.hexnext.themer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler

/**
 * [LocalUriHandler] that never throws out of a click. The stock handler turns a missing browser
 * (every https handler disabled, as on many debloated rooted phones) into an
 * IllegalArgumentException that would crash the app; this one shows a short message instead.
 */
@Composable
internal fun rememberSafeUriHandler(): UriHandler {
    val base = LocalUriHandler.current
    val context = LocalContext.current.applicationContext
    return remember(base, context) { SafeUriHandler(base, context) }
}

private class SafeUriHandler(private val base: UriHandler, private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        try {
            base.openUri(uri)
        } catch (e: IllegalArgumentException) {
            notOpened(uri)
        } catch (e: ActivityNotFoundException) {
            notOpened(uri)
        } catch (e: SecurityException) {
            notOpened(uri)
        }
    }

    private fun notOpened(uri: String) {
        Toast.makeText(context, "No app on this phone can open $uri. Turn on a browser and try again.", Toast.LENGTH_LONG).show()
    }
}
