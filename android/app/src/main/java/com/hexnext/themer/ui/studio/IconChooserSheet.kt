package com.hexnext.themer.ui.studio

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hexnext.themer.theme.CustomAssetStore
import com.hexnext.themer.theme.ThemePreviewer
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The sheet for an icon tapped on the Studio phone: the same icon from every unlocked pack, the
 * user's own images, a file picker (the image is cropped and sized for the slot, or the reason it
 * cannot be used is shown) and a way back to the pack's own art.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconChooserSheet(
    viewModel: MainViewModel,
    ui: UiState,
    slotKey: String,
    label: String,
    night: Boolean,
    onClose: () -> Unit,
) {
    var choices by remember(slotKey) { mutableStateOf<List<ThemePreviewer.IconChoice>?>(null) }
    var generating by remember(slotKey) { mutableStateOf(false) }
    if (generating) {
        val paletteHint = remember(ui.packs, ui.spec.simplePackId) {
            ui.packs.firstOrNull { it.id == ui.spec.simplePackId }
                ?.pack?.paletteArgb?.values?.map { it or 0xFF000000.toInt() }?.take(6).orEmpty()
        }
        // Uses the default agent (AI providers > Default agent); the sheet can still switch.
        com.hexnext.themer.ui.ai.GenerateIconSheet(
            slotLabel = label,
            paletteHint = paletteHint,
            onUse = { asset ->
                viewModel.assignIcon(slotKey, asset.fileName)
                generating = false
                onClose()
            },
            onOpenProviders = {
                generating = false
                onClose()
                viewModel.navigate(com.hexnext.themer.ui.Screen.AI_PROVIDERS)
            },
            onDismiss = { generating = false },
        )
        return
    }
    LaunchedEffect(slotKey, night) { viewModel.loadIconChoices(slotKey, night) { choices = it } }
    val assigned = ui.spec.customIcons[slotKey]
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importIconFor(slotKey, label, uri)
            onClose()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text("$label icon", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Text("From your packs", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val list = choices
            when {
                list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Drawing this icon from each pack…", style = MaterialTheme.typography.bodyMedium)
                }
                list.isEmpty() -> Text(
                    "None of your unlocked packs has art for this icon.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.packId }) { choice ->
                        val image = remember(choice) { choice.bitmap.asImageBitmap() }
                        ChoiceTile(image, choice.packName, selected = false) {
                            viewModel.useIconChoice(slotKey, label, choice)
                            onClose()
                        }
                    }
                }
            }

            if (ui.customIcons.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Your images", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val context = LocalContext.current
                val store = remember(context) { CustomAssetStore(context) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ui.customIcons, key = { it.fileName }) { asset ->
                        val image = rememberIconThumb(store.file(CustomAssetStore.Kind.ICON, asset.fileName))
                        ChoiceTile(image, asset.displayName, selected = asset.fileName == assigned) {
                            viewModel.assignIcon(slotKey, asset.fileName)
                            onClose()
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Choose from files")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { generating = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Generate with AI")
            }
            Text(
                "PNG, JPEG or WebP. Empty margins are cropped and the image is sized for this spot; " +
                    "you'll see why if a file can't be used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (assigned != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { viewModel.assignIcon(slotKey, null); onClose() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Use the pack's own icon")
                }
            }
        }
    }
}

@Composable
private fun ChoiceTile(image: ImageBitmap?, caption: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(84.dp)
            .clickable(onClickLabel = "Use $caption", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(colors.surfaceContainerHighest, RoundedCornerShape(16.dp))
                .border(if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(0.dp, colors.surfaceContainerHighest), RoundedCornerShape(16.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(caption, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun rememberIconThumb(file: File): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, key1 = file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 144) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}
