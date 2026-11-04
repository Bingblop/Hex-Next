package com.hexnext.themer.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.themer.ai.AiKeyStore
import com.hexnext.themer.ai.AiProviders
import com.hexnext.themer.ai.TextProvider
import com.hexnext.themer.ai.ThemeSuggester
import com.hexnext.themer.ai.ThemeSuggestion
import com.hexnext.themer.billing.PackEntitlements
import com.hexnext.themer.billing.PaidFeature
import com.hexnext.themer.theme.InstalledPack
import com.hexnext.themer.theme.toHex
import com.hexnext.themer.ui.SectionCard
import kotlinx.coroutines.launch

/**
 * "Describe your theme": a text field and Generate, then the proposed palette as swatches with
 * "Apply to editor". Applying only changes the editor (seed, secondary and tertiary accents,
 * light/dark adjustments, pack and name); the phone changes when the user presses the normal
 * Apply.
 *
 * Without an AI key the card folds to one quiet row that leads to the key screen, so the colour
 * controls under it stay the first thing a new user sees. AI creation is paid
 * ([PaidFeature.AI_CREATION]): until it is unlocked the card shows [AiPaywall] instead of the
 * prompt, and [DescribeThemeModel.generate] checks the entitlement again before any request.
 *
 * Host contract: [onApply] receives the suggestion and the spec from
 * [ThemeSuggester.toThemeSpec]; the host must put the spec into the editor **and** push
 * `spec.source.seedArgb` to the seed preference (`MainViewModel.setSeed`), because the apply path
 * reads the seed from there.
 *
 * @param packs installed packs, for the pack-style match.
 * @param spec the editor's current theme, the base the suggestion is mapped onto.
 * @param busy true while the host is applying or importing; disables Apply.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DescribeThemeCard(
    packs: List<InstalledPack>,
    spec: ThemeSpec,
    busy: Boolean,
    onApply: (ThemeSuggestion, ThemeSpec) -> Unit,
    onOpenProviders: () -> Unit,
) {
    val keys = rememberAiKeyStore()
    val configured by keys.configured.collectAsStateWithLifecycle()
    val entitlements = rememberEntitlements()
    val access = rememberEntitlementState()
    // The request and its result live in a ViewModel, not in the item: the main screen is a
    // LazyColumn, which disposes this card when it scrolls away, and that must neither cancel
    // a paid request nor drop the suggestion.
    val model: DescribeThemeModel = viewModel()
    var description by rememberSaveable { mutableStateOf("") }
    var providerId by rememberSaveable { mutableStateOf<String?>(null) }
    val working = model.working
    val error = model.error
    val suggestion = model.suggestion
    // "Applied" only while the editor still holds the spec this suggestion produced: changing
    // the pack or anything else in the editor afterwards turns the button back into a plain
    // Apply, so the suggestion can be put back without paying for a new generation.
    val applied = model.appliedSpec == spec

    val providers = remember(configured) { AiProviders.text.filter { it.id in configured } }
    LaunchedEffect(providers) {
        if (providers.none { it.id == providerId }) providerId = providers.firstOrNull()?.id
    }
    val provider = providers.firstOrNull { it.id == providerId }

    fun generate() {
        val chosen = provider ?: return
        model.generate(entitlements, keys, chosen, description)
    }

    if (!access.unlocks(PaidFeature.AI_CREATION)) {
        SectionCard("Describe your theme") {
            Text("Turn a sentence into a palette", style = MaterialTheme.typography.titleSmall)
            AiPaywall(what = "Describe your theme", state = access)
        }
        return
    }

    if (providers.isEmpty()) {
        SectionCard("Describe your theme") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenProviders),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Turn a sentence into a palette", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Tap here to add an AI key, then describe the theme you want.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    SectionCard("Describe your theme") {
        Text(
            "Describe it in a sentence, such as \"warm sunset, cosy, dark\". You get colours, and maybe a pack and a name, in the editor. The phone changes only when you tap Apply theme.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(300) },
            label = { Text("Theme") },
            placeholder = { Text("Deep ocean at night, calm, a little neon") },
            minLines = 2,
            maxLines = 4,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Provider", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            ProviderPicker(
                label = "Choose",
                options = providers.map { it.id to it.displayName },
                selectedId = providerId,
                enabled = !working,
                onSelect = { providerId = it },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { generate() }, enabled = provider != null && description.isNotBlank() && !working) {
                Text(if (suggestion == null) "Generate" else "Generate again")
            }
            if (working) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
        }
        error?.let { message ->
            ErrorText(message)
            if (!working) OutlinedButton(onClick = { generate() }, enabled = provider != null && description.isNotBlank()) { Text("Retry") }
        }
        suggestion?.let { s ->
            SuggestionPreview(
                suggestion = s,
                packName = ThemeSuggester.matchPack(s.packStyle, packs)?.name,
                applied = applied,
                enabled = !busy && !working,
                onApply = {
                    val newSpec = ThemeSuggester.toThemeSpec(s, spec, packs)
                    model.appliedSpec = newSpec
                    onApply(s, newSpec)
                },
            )
        }
    }
}

/**
 * The Describe card's request and result. Scoped to the screen's ViewModel owner so they outlive
 * the LazyColumn item: scrolling the card away keeps the request running and its answer shown.
 */
internal class DescribeThemeModel : ViewModel() {
    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var suggestion by mutableStateOf<ThemeSuggestion?>(null)
        private set
    /** The spec the last Apply put into the editor; null until then and after a new generation. */
    var appliedSpec by mutableStateOf<ThemeSpec?>(null)

    /**
     * Asks [provider] for a theme from [description]; ignored while a request is running, and
     * refused unless [entitlements] unlocks [PaidFeature.AI_CREATION] at this moment (a lapsed
     * subscription must not keep generating from a screen that was open before it lapsed).
     */
    fun generate(entitlements: PackEntitlements, keys: AiKeyStore, provider: TextProvider, description: String) {
        if (working) return
        if (!entitlements.state.value.unlocks(PaidFeature.AI_CREATION)) {
            error = "AI theme creation is locked. Subscribe or unlock it to generate."
            return
        }
        working = true
        error = null
        appliedSpec = null
        viewModelScope.launch {
            try {
                suggestion = ThemeSuggester.suggest(keys, provider, description)
            } catch (t: Exception) {
                error = describeFailure(t)
            } finally {
                working = false
            }
        }
    }
}

/** Name, note, seed and accent swatches, mode and pack pills, and the Apply button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionPreview(
    suggestion: ThemeSuggestion,
    packName: String?,
    applied: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(suggestion.name, style = MaterialTheme.typography.titleSmall)
        if (suggestion.notes.isNotBlank()) {
            Text(suggestion.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Suggested colours: accent ${suggestion.seedArgb.toHex()}" +
                    suggestion.accents.joinToString("") { ", ${it.toHex()}" }
            },
        ) {
            Swatch(suggestion.seedArgb, size = 44.dp, outlined = true)
            suggestion.accents.forEach { Swatch(it, size = 32.dp, outlined = false) }
        }
        Text(
            buildString {
                append("Accent ")
                append(suggestion.seedArgb.toHex())
                if (suggestion.accents.isNotEmpty()) {
                    append(" · secondary ")
                    append(suggestion.accents[0].toHex())
                }
                if (suggestion.accents.size > 1) {
                    append(" · tertiary ")
                    append(suggestion.accents[1].toHex())
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            modeLabel(suggestion.mode)?.let { InfoPill(it) }
            InfoPill(if (packName != null) "Pack: $packName" else "Pack: keep current")
        }
        // Never disabled once applied: re-applying is free and puts the suggestion back after
        // later edits (the accent picker, for one, changes the seed outside the spec).
        Button(onClick = onApply, enabled = enabled) { Text(if (applied) "Apply again" else "Apply to editor") }
        if (applied) {
            Text(
                "Applied to the editor. Tap Apply theme at the bottom to put it on the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A read-only pill, drawn like the status pill: full-contrast text, not a disabled chip. */
@Composable
private fun InfoPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun Swatch(argb: Int, size: Dp, outlined: Boolean) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(argb))
            .then(if (outlined) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
    )
}

/**
 * What the suggested mode really changes: only the background lightness of that mode's
 * surfaces (the other mode goes back to Samsung's default). The phone's own light/dark setting
 * is never switched, so the pill must not promise a mode; "system" keeps default surfaces and
 * gets no pill.
 */
private fun modeLabel(mode: String): String? = when (mode) {
    "dark" -> "Darker dark-mode surfaces"
    "light" -> "Brighter light-mode surfaces"
    else -> null
}
