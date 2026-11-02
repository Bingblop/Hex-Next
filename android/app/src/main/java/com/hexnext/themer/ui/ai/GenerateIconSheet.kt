package com.hexnext.themer.ui.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.themer.ai.AiProviders
import com.hexnext.themer.ai.GeneratedIcon
import com.hexnext.themer.ai.IconGenerator
import com.hexnext.themer.ai.IconRequest
import com.hexnext.themer.ai.IconStyle
import com.hexnext.themer.ai.ProviderKind
import com.hexnext.themer.theme.CustomAssetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.BitmapFactory as AndroidBitmapFactory

/**
 * "Generate with AI": a bottom sheet that turns a word prompt into icon candidates and moves the
 * chosen one into the custom icons through [IconGenerator.importAsCustomIcon].
 *
 * Self-contained: it owns its key store, request state and results. The host only learns about
 * the imported asset through [onUse] (typically `viewModel.refreshAssets()` followed by
 * `viewModel.assignIcon(slotKey, asset.fileName)` when the sheet was opened for one slot).
 *
 * @param slotLabel the slot the icon is for ("Connections"), shown as context; null for none.
 * @param paletteHint opaque colours of the selected pack for [IconStyle.MATCH_PACK]; empty when
 *   no pack is selected (the chip then falls back to a plain flat style).
 * @param onUse called on the main thread after a result was stored as a custom icon.
 * @param onOpenProviders opens the AI providers settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GenerateIconSheet(
    slotLabel: String?,
    paletteHint: List<Int>,
    onUse: (CustomAssetStore.Asset) -> Unit,
    onOpenProviders: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val keys = rememberAiKeyStore()
    val configured by keys.configured.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state = remember { GenerateIconState() }

    val providers = remember(configured) { AiProviders.all.filter { it.id in configured } }
    LaunchedEffect(providers) {
        if (providers.none { it.id == state.providerId }) state.providerId = providers.firstOrNull()?.id
    }
    val provider = providers.firstOrNull { it.id == state.providerId }
    val canGenerate = provider != null && state.prompt.isNotBlank() && !state.busy

    fun generate() {
        val chosen = provider ?: return
        state.busy = true
        state.error = null
        state.results.clear()
        state.used.clear()
        state.saved.clear()
        scope.launch {
            try {
                val request = IconRequest(
                    prompt = state.prompt,
                    style = state.style,
                    count = state.count,
                    paletteHint = if (state.style == IconStyle.MATCH_PACK) paletteHint else emptyList(),
                )
                val icons = IconGenerator.generate(keys, chosen, request)
                state.results.addAll(icons)
            } catch (t: Exception) {
                state.error = describeFailure(t)
            } finally {
                state.busy = false
            }
        }
    }

    /** Stores [icon] (or, with [file], the gallery file read on IO) as a custom icon and reports it. */
    fun use(index: Int, icon: GeneratedIcon?, file: File? = null) {
        if (state.busy) return
        state.busy = true
        state.error = null
        scope.launch {
            try {
                val asset = withContext(Dispatchers.IO) {
                    val source = icon ?: GeneratedIcon(
                        png = requireNotNull(file).readBytes(),
                        providerId = "gallery",
                        prompt = file.nameWithoutExtension.removePrefix("ai-").substringBeforeLast('-'),
                    )
                    IconGenerator.importAsCustomIcon(context, source)
                }
                if (index >= 0) state.used.add(index)
                onUse(asset)
            } catch (t: Exception) {
                state.error = describeFailure(t)
            } finally {
                state.busy = false
            }
        }
    }

    fun save(index: Int, icon: GeneratedIcon) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { IconGenerator.saveToGallery(context, icon) }
                state.saved.add(index)
                state.galleryVersion++
            } catch (t: Exception) {
                state.error = describeFailure(t)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Generate with AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (slotLabel != null) "Describe the icon for $slotLabel. The result is stored with your custom icons."
                else "Describe an icon in a few words. The result is stored with your custom icons.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (providers.isEmpty()) {
                NoProviderNotice(onOpenProviders)
            } else {
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = { state.prompt = it.take(200) },
                    label = { Text("Icon") },
                    placeholder = { Text("A paper plane") },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Style", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconStyle.entries.forEach { style ->
                        FilterChip(
                            selected = state.style == style,
                            onClick = { state.style = style },
                            label = { Text(style.label) },
                            enabled = !state.busy && (style != IconStyle.MATCH_PACK || paletteHint.isNotEmpty()),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Provider", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    ProviderPicker(
                        label = "Choose",
                        options = providers.map { it.id to it.displayName },
                        selectedId = state.providerId,
                        enabled = !state.busy,
                        onSelect = { state.providerId = it },
                    )
                }
                if (provider != null && provider.kind == ProviderKind.VECTOR) {
                    Text(
                        "This provider describes the icon as shapes; Hex Next draws it. Best for flat and outline styles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text("Variants", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    (1..IconRequest.MAX_COUNT).forEachIndexed { index, n ->
                        SegmentedButton(
                            selected = state.count == n,
                            onClick = { state.count = n },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = IconRequest.MAX_COUNT),
                            enabled = !state.busy,
                        ) { Text("$n") }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { generate() }, enabled = canGenerate) {
                        Text(if (state.results.isEmpty()) "Generate" else "Generate again")
                    }
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Working…", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenProviders) { Text("Providers") }
                }

                state.error?.let { message ->
                    ErrorText(message)
                    if (state.results.isEmpty() && !state.busy) {
                        OutlinedButton(onClick = { generate() }, enabled = provider != null && state.prompt.isNotBlank()) { Text("Retry") }
                    }
                }

                if (state.results.isNotEmpty()) {
                    Text("Results", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.results.forEachIndexed { index, icon ->
                            ResultTile(
                                bitmap = rememberPngBitmap(icon.png),
                                used = index in state.used,
                                saved = index in state.saved,
                                busy = state.busy,
                                onUse = { use(index, icon) },
                                onSave = { save(index, icon) },
                            )
                        }
                    }
                }

                GallerySection(
                    version = state.galleryVersion,
                    busy = state.busy,
                    onUse = { file -> use(-1, null, file) },
                )
            }
        }
    }
}

/** Sheet state that survives recomposition but not the sheet closing. */
private class GenerateIconState {
    var prompt by mutableStateOf("")
    var style by mutableStateOf(IconStyle.FLAT)
    var providerId by mutableStateOf<String?>(null)
    var count by mutableIntStateOf(1)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val results = mutableStateListOf<GeneratedIcon>()
    val used = mutableStateListOf<Int>()
    val saved = mutableStateListOf<Int>()
    var galleryVersion by mutableIntStateOf(0)
}

/** One candidate: a 120 dp preview over "Use" and "Save". */
@Composable
private fun ResultTile(
    bitmap: ImageBitmap?,
    used: Boolean,
    saved: Boolean,
    busy: Boolean,
    onUse: () -> Unit,
    onSave: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(120.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, modifier = Modifier.size(104.dp), contentScale = ContentScale.Fit)
            } else {
                Text("Preview unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onUse, enabled = !busy && !used) { Text(if (used) "Added" else "Use") }
            TextButton(onClick = onSave, enabled = !saved) { Text(if (saved) "Saved" else "Save") }
        }
    }
}

/** Icons saved earlier under `files/custom/generated`, each with a "Use" button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GallerySection(version: Int, busy: Boolean, onUse: (File) -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(version) {
        files = withContext(Dispatchers.IO) { IconGenerator.listGallery(context).take(MAX_GALLERY) }
    }
    if (files.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Saved icons", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show ${files.size}") }
    }
    if (!expanded) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        files.forEach { file ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val bitmap = rememberFileThumbnail(file)
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(64.dp), contentScale = ContentScale.Fit)
                }
                Text(
                    file.nameWithoutExtension.removePrefix("ai-").substringBeforeLast('-'),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(72.dp),
                )
                TextButton(onClick = { onUse(file) }, enabled = !busy) { Text("Use") }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** Decodes [file] at about 144 px off the main thread. */
@Composable
private fun rememberFileThumbnail(file: File): ImageBitmap? {
    var bitmap by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = AndroidBitmapFactory.Options().apply { inJustDecodeBounds = true }
                AndroidBitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 144) sample *= 2
                AndroidBitmapFactory.decodeFile(file.path, AndroidBitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

private const val MAX_GALLERY = 24
