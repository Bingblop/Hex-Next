package com.hexnext.themer.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.themer.ai.AiProviders
import com.hexnext.themer.ai.IconProvider
import com.hexnext.themer.ai.ProviderKind
import com.hexnext.themer.ai.TestResult
import com.hexnext.themer.ai.TextProvider
import com.hexnext.themer.ui.ConfirmDialog
import com.hexnext.themer.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > AI providers: one card per provider with its masked key, Set/Change, Clear, Test
 * and "Get a key". Keys go straight into [AiKeyStore]; nothing here keeps a plaintext copy beyond
 * the dialog's text field. Clearing a key asks first, since some providers show a key only once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProvidersScreen(onBack: () -> Unit) {
    val keys = rememberAiKeyStore()
    val configured by keys.configured.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val masked = remember { mutableStateMapOf<String, String>() }
    val tests = remember { mutableStateMapOf<String, TestResult>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    var editing by remember { mutableStateOf<IconProvider?>(null) }
    var clearing by remember { mutableStateOf<IconProvider?>(null) }
    BackHandler(onBack = onBack)

    LaunchedEffect(configured) {
        val fresh = withContext(Dispatchers.IO) { configured.associateWith { id -> keys.masked(id) ?: "" } }
        masked.clear()
        masked.putAll(fresh.filterValues { it.isNotEmpty() })
    }

    fun test(provider: IconProvider) {
        testing[provider.id] = true
        tests.remove(provider.id)
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val key = keys.get(provider.id) ?: return@withContext TestResult(false, "No key stored.")
                    provider.test(key)
                }
            } catch (t: Exception) {
                TestResult(false, describeFailure(t))
            }
            tests[provider.id] = result
            testing[provider.id] = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI providers", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateStartPadding(LayoutDirection.Ltr),
                end = 16.dp + padding.calculateEndPadding(LayoutDirection.Ltr),
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("Bring your own key") {
                    Text(
                        "Generate icons and theme ideas with the AI service of your choice. Keys stay on this phone, " +
                            "encrypted by its hardware key store, and are left out of backups. Each request is billed to your own account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(AiProviders.all, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    maskedKey = masked[provider.id],
                    test = tests[provider.id],
                    testing = testing[provider.id] == true,
                    onSet = { editing = provider },
                    onClear = { clearing = provider },
                    onTest = { test(provider) },
                )
            }
        }
    }

    editing?.let { provider ->
        KeyDialog(
            provider = provider,
            onSave = { value ->
                editing = null
                tests.remove(provider.id)
                scope.launch { withContext(Dispatchers.IO) { keys.set(provider.id, value) } }
            },
            onDismiss = { editing = null },
        )
    }
    clearing?.let { provider ->
        ConfirmDialog(
            title = "Remove the ${provider.displayName} key?",
            text = "Hex Next can no longer generate with ${provider.displayName} until you add a key again.",
            confirmLabel = "Remove",
            onConfirm = {
                clearing = null
                tests.remove(provider.id)
                scope.launch { withContext(Dispatchers.IO) { keys.clear(provider.id) } }
            },
            onDismiss = { clearing = null },
        )
    }
}

/**
 * One provider: what it does, the masked key, and its actions. No card carries a filled primary
 * button, so the six cards read as peers rather than six competing calls to action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    provider: IconProvider,
    maskedKey: String?,
    test: TestResult?,
    testing: Boolean,
    onSet: () -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    SectionCard(provider.displayName) {
        Text(capabilities(provider), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(provider.keyLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    maskedKey ?: "Not set",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (maskedKey == null) FontFamily.Default else FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        test?.let { result ->
            Text(
                result.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(
                onClick = onSet,
                modifier = Modifier.semantics { contentDescription = "${if (maskedKey == null) "Set" else "Change"} ${provider.displayName} key" },
            ) { Text(if (maskedKey == null) "Set key" else "Change") }
            if (maskedKey != null) {
                TextButton(
                    onClick = onTest,
                    enabled = !testing,
                    modifier = Modifier.semantics { contentDescription = "Test ${provider.displayName} key" },
                ) { Text("Test") }
                TextButton(
                    onClick = onClear,
                    enabled = !testing,
                    modifier = Modifier.semantics { contentDescription = "Clear ${provider.displayName} key" },
                ) { Text("Clear") }
            }
            TextButton(
                onClick = { uriHandler.openUri(provider.keyHelpUrl) },
                modifier = Modifier.semantics { contentDescription = "Get a ${provider.displayName} key" },
            ) { Text("Get a key") }
        }
    }
}

/** "Image icons · theme ideas" style summary of what a provider can do here. */
private fun capabilities(provider: IconProvider): String {
    val icon = when (provider.kind) {
        ProviderKind.RASTER -> "Icons as images"
        ProviderKind.VECTOR -> "Icons as shapes drawn by Hex Next"
    }
    val text = if (provider is TextProvider) "theme ideas from a description" else null
    return listOfNotNull(icon, text).joinToString(" · ")
}

/** Enter or replace a key. The field is masked until "Show" is pressed; Save needs a non-empty key (Clear removes one). */
@Composable
private fun KeyDialog(provider: IconProvider, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val trimmed = value.trim()
    val hint = provider.keyPrefix?.takeIf { trimmed.isNotEmpty() && !trimmed.startsWith(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${provider.displayName} ${provider.keyLabel.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste the key from your ${provider.displayName} account. It is stored encrypted on this phone only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(provider.keyLabel) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hint != null) {
                    Text(
                        "Keys for ${provider.displayName} usually start with \"$hint\". You can still save this one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(trimmed) }, enabled = trimmed.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
