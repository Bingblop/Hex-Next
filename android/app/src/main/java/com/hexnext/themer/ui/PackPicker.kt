package com.hexnext.themer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.pack.PackIdAliases
import com.hexnext.themer.R
import com.hexnext.themer.billing.ProductIds
import com.hexnext.themer.theme.CatalogueEntry
import com.hexnext.themer.theme.InstalledPack

/**
 * Every extra pack in one menu, instead of a row per pack. A pack that is not on the phone yet
 * has a download icon (locked packs included, so they can be previewed). Once it is downloaded
 * and still locked, that icon becomes a lock; tapping the lock opens Play billing for the pack.
 * Tapping the name selects the pack, downloading it first when it is not on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PackPicker(
    packs: List<InstalledPack>,
    catalogue: CatalogueState,
    spec: ThemeSpec,
    busy: Boolean,
    locks: PackLocks,
    onSelect: (String?) -> Unit,
    onDownload: (CatalogueEntry) -> Unit,
    onPreview: (CatalogueEntry) -> Unit,
    onUnlock: (String) -> Unit,
) {
    val tap = rememberHapticTap()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val installedById = remember(packs) { packs.associateBy { PackIdAliases.current(it.id) } }
    val rows = remember(packs, catalogue.entries, query, locks) {
        packMenuRows(packs, catalogue.entries, installedById, query, locks)
    }
    val selectedId = spec.simplePackId?.let(PackIdAliases::current)
    val selectedName = when (selectedId) {
        null -> "Colours only"
        else -> installedById[selectedId]?.name
            ?: catalogue.entries.firstOrNull { PackIdAliases.current(it.id) == selectedId }?.name
            ?: selectedId.replaceFirstChar { it.titlecase() }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            if (!busy) {
                if (open) tap()
                expanded = open
            }
        },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Pack") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .semantics { contentDescription = "Pack, $selectedName" },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (packs.size + catalogue.entries.size > 6) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("Search packs") },
                        label = { Text("Search") },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Colours only", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Your colours with Samsung's own icons",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        tap()
                        expanded = false
                        onSelect(null)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
                if (catalogue.loading && rows.isEmpty()) {
                    Text(
                        "Loading packs…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (!catalogue.loading && rows.isEmpty() && query.isNotBlank()) {
                    Text(
                        "No pack name matches \"$query\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                rows.forEach { row ->
                    val progress = catalogue.downloading[row.id]
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable(enabled = !busy && progress == null) {
                                    tap()
                                    expanded = false
                                    val entry = row.entry
                                    when {
                                        row.installed != null -> onSelect(row.installed.id)
                                        entry != null -> onPreview(entry)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                row.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detail = when {
                                progress != null -> "Downloading…"
                                !row.onPhone -> "Not on this phone yet"
                                row.locked -> "On this phone · locked"
                                else -> "On this phone"
                            }
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        when {
                            progress != null -> LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.width(56.dp).padding(end = 12.dp),
                            )
                            !row.onPhone && row.entry != null -> IconButton(
                                onClick = {
                                    tap()
                                    onDownload(row.entry)
                                },
                                enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = "Download ${row.name}" },
                            ) {
                                Icon(painterResource(R.drawable.hx_glyph_myfiles_downloads), contentDescription = null)
                            }
                            row.onPhone && row.locked && locks.canPurchase -> IconButton(
                                onClick = {
                                    tap()
                                    onUnlock(ProductIds.forPack(row.id))
                                },
                                enabled = !busy && !locks.pending(row.id),
                                modifier = Modifier.semantics {
                                    contentDescription = "Unlock ${row.name} for ${locks.singlePrice(row.id)}"
                                },
                            ) {
                                if (locks.pending(row.id)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Outlined.Lock, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One line of [PackPicker]: a catalogue skin, a pack already on the phone, or both. */
private data class PackMenuRow(
    val id: String,
    val name: String,
    val installed: InstalledPack?,
    val entry: CatalogueEntry?,
    val onPhone: Boolean,
    val locked: Boolean,
)

private fun packMenuRows(
    packs: List<InstalledPack>,
    entries: List<CatalogueEntry>,
    installedById: Map<String, InstalledPack>,
    query: String,
    locks: PackLocks,
): List<PackMenuRow> {
    val needle = query.trim()
    val seen = HashSet<String>()
    val rows = ArrayList<PackMenuRow>()
    fun add(id: String, name: String, installed: InstalledPack?, entry: CatalogueEntry?) {
        val current = PackIdAliases.current(id)
        if (current in seen) return
        if (needle.isNotEmpty() && !name.contains(needle, ignoreCase = true)) return
        seen += current
        rows += PackMenuRow(
            id = current,
            name = name,
            installed = installed,
            entry = entry,
            onPhone = installed != null,
            locked = locks.locked(current),
        )
    }
    entries.forEach { entry ->
        val current = PackIdAliases.current(entry.id)
        add(current, installedById[current]?.name ?: entry.name, installedById[current], entry)
    }
    packs.forEach { pack -> add(pack.id, pack.name, pack, entries.firstOrNull { PackIdAliases.current(it.id) == PackIdAliases.current(pack.id) }) }
    return rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
