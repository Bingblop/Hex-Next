package com.hexnext.themer.ui.ai

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hexnext.themer.ai.AiKeyStore
import com.hexnext.themer.ai.AiProviderException

/*
 * Pieces shared by the AI composables: the key store, a provider picker, notices and PNG decoding.
 */

/** One [AiKeyStore] per composition, bound to the application context. */
@Composable
internal fun rememberAiKeyStore(): AiKeyStore {
    val context = LocalContext.current
    return remember { AiKeyStore(context) }
}

/**
 * A drop-down of (id, name) [options] on an outlined button that shows the selected name, or
 * [label] when nothing is selected. Announced as "[label]: [selection]" so the chooser keeps its
 * name once a provider is picked.
 */
@Composable
internal fun ProviderPicker(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.semantics {
                role = Role.DropdownList
                contentDescription = "$label: ${selectedName ?: "none"}"
            },
        ) {
            Text(selectedName ?: label)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

/** Shown when no provider has a key: one sentence and the way to add one. */
@Composable
internal fun NoProviderNotice(onOpenProviders: () -> Unit) {
    Column {
        Text(
            "No AI key yet. Add one in App settings to generate on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenProviders) { Text("Add key") }
    }
}

/** An error line in the error colour. */
@Composable
internal fun ErrorText(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Decodes a PNG once per [png] instance; null when it cannot be decoded. */
@Composable
internal fun rememberPngBitmap(png: ByteArray): ImageBitmap? = remember(png) {
    runCatching { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }.getOrNull()
}

/** The message of a failure fit for the screen: provider messages as they are, others shortened. */
internal fun describeFailure(t: Throwable): String = when (t) {
    is AiProviderException -> t.message ?: "The request failed."
    else -> "Something went wrong: ${t.message ?: t.javaClass.simpleName}"
}
