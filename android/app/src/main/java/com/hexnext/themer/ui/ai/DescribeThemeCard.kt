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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.TextButton
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
 * "Describe your theme": a description and Generate, then a chat. Every answer goes straight into
 * the editor (seed, secondary and tertiary accents, light/dark adjustments, pack and name), so
 * [livePreview] shows it at once: one phone with a tab per overlay. Under it the conversation and
 * the message box for the next change ("warmer", "less green"), each answer reworking the current
 * theme. Under that the result and Apply theme, the same apply as the bottom bar. Undo AI
 * changes puts back the editor as it was before the chat.
 *
 * Without an AI key the card folds to one quiet row that leads to the key screen, so the colour
 * controls under it stay the first thing a new user sees. AI creation is paid
 * ([PaidFeature.AI_CREATION]): until it is unlocked the card shows [AiPaywall] instead of the
 * prompt, and [DescribeThemeModel] checks the entitlement again before any request.
 *
 * Host contract: [onApply] and [onRestore] receive a spec the host must put into the editor
 * **and** whose `source.seedArgb` it must push to the seed preference (`MainViewModel.setSeed`),
 * because the apply path reads the seed from there.
 *
 * @param packs unlocked packs only, for the pack-style match: the AI can never pick a locked one.
 * @param spec the editor's current theme, the base each answer is mapped onto.
 * @param busy true while the host is applying or importing; disables Apply theme.
 * @param livePreview the phone preview with its tabs, shown inside the card while a chat is open;
 *   null where the host shows the phone beside the card already.
 * @param onApplyTheme applies the editor's theme to the phone.
 * @param applyBlockedReason why Apply theme is off (no root or Shizuku, or a locked pack in the
 *   theme), shown under the button; null when the theme can be applied.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DescribeThemeCard(
    packs: List<InstalledPack>,
    spec: ThemeSpec,
    busy: Boolean,
    onApply: (ThemeSuggestion, ThemeSpec) -> Unit,
    onOpenProviders: () -> Unit,
    onRestore: (ThemeSpec) -> Unit = {},
    livePreview: (@Composable () -> Unit)? = null,
    onApplyTheme: (() -> Unit)? = null,
    applyBlockedReason: String? = null,
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
            if (suggestion != null) livePreview?.invoke()
            Text("Turn a sentence into a palette", style = MaterialTheme.typography.titleSmall)
            AiPaywall(what = "Describe your theme", state = access)
            ChatExitRow(model, onRestore)
        }
        return
    }

    if (providers.isEmpty()) {
        SectionCard("Describe your theme") {
            if (suggestion != null) livePreview?.invoke()
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
            ChatExitRow(model, onRestore)
        }
        return
    }

    // Live preview: every new answer goes straight into the editor, so the phone preview shows
    // it at once; the phone itself changes only on Apply theme. Each answer is put in once.
    LaunchedEffect(suggestion) {
        val s = suggestion ?: return@LaunchedEffect
        if (model.previewed === s) return@LaunchedEffect
        model.previewed = s
        if (model.baseSpec == null) model.baseSpec = spec
        val newSpec = ThemeSuggester.toThemeSpec(s, spec, packs)
        model.appliedSpec = newSpec
        onApply(s, newSpec)
    }

    SectionCard("Describe your theme") {
        if (suggestion == null) {
            Text(
                "Describe it in a sentence, such as \"warm sunset, cosy, dark\". The result shows in the live preview right away, and you can keep chatting to fine-tune it. The phone changes only when you tap Apply theme.",
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
        }
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
        if (suggestion == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { generate() }, enabled = provider != null && description.isNotBlank() && !working) { Text("Generate") }
                if (working) WorkingLabel()
            }
        }
        if (suggestion == null) ErrorBlock(error, working, model.lastRequest != null, provider != null) { provider?.let { model.retry(entitlements, keys, it) } }
        suggestion?.let { s ->
            // One phone with a tab per overlay, then the chat under it, then the result and Apply.
            livePreview?.invoke()
            ChatHistory(model.turns)
            OutlinedTextField(
                value = model.draft,
                onValueChange = { model.draft = it.take(300) },
                label = { Text("Message the AI") },
                placeholder = { Text("Warmer, less green, make it darker…") },
                minLines = 1,
                maxLines = 3,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { provider?.let { model.refine(entitlements, keys, it) } },
                    enabled = provider != null && model.draft.isNotBlank() && !working,
                ) { Text("Send") }
                if (working) WorkingLabel()
            }
            ErrorBlock(error, working, model.lastRequest != null, provider != null) { provider?.let { model.retry(entitlements, keys, it) } }
            SuggestionPreview(
                suggestion = s,
                packName = ThemeSuggester.matchPack(s.packStyle, packs)?.name,
                applied = applied,
                enabled = !busy && !working,
                applyBlockedReason = if (onApplyTheme == null) "Use Apply theme at the bottom." else applyBlockedReason,
                onUseAgain = {
                    val newSpec = ThemeSuggester.toThemeSpec(s, spec, packs)
                    model.appliedSpec = newSpec
                    onApply(s, newSpec)
                },
                onApplyTheme = { onApplyTheme?.invoke() },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        model.baseSpec?.let(onRestore)
                        model.reset()
                    },
                    enabled = !working,
                ) { Text("Undo AI changes") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { model.reset() }, enabled = !working) { Text("New theme") }
            }
        }
    }
}

/**
 * Undo AI changes and New theme, kept even when the chat itself is hidden (AI locked again, the
 * key removed): the editor still holds the AI's theme, and the user must be able to undo it.
 */
@Composable
private fun ChatExitRow(model: DescribeThemeModel, onRestore: (ThemeSpec) -> Unit) {
    if (model.suggestion == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                model.baseSpec?.let(onRestore)
                model.reset()
            },
            enabled = !model.working && model.baseSpec != null,
        ) { Text("Undo AI changes") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { model.reset() }, enabled = !model.working) { Text("Keep this theme") }
    }
}

/** The last request's error with Retry, if there is one. */
@Composable
private fun ErrorBlock(error: String?, working: Boolean, canRetry: Boolean, hasProvider: Boolean, onRetry: () -> Unit) {
    val message = error ?: return
    ErrorText(message)
    if (!working && canRetry) {
        OutlinedButton(onClick = onRetry, enabled = hasProvider) { Text("Retry") }
    }
}

@Composable
private fun WorkingLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Working…", style = MaterialTheme.typography.bodySmall)
    }
}

/** The messages sent in this session, oldest first, with the theme each one produced. */
@Composable
private fun ChatHistory(turns: List<DescribeThemeModel.Turn>) {
    if (turns.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        turns.forEach { turn ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                turn.result?.let {
                    Text("→ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * The Describe card's chat: the first description, every fine-tune message after it, and the
 * newest theme. Scoped to the screen's ViewModel owner so it outlives the LazyColumn item:
 * scrolling the card away keeps the request running and the conversation shown.
 */
internal class DescribeThemeModel : ViewModel() {
    /** One message and the theme name it produced (null while running or after a failure). */
    data class Turn(val text: String, val result: String? = null)

    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var suggestion by mutableStateOf<ThemeSuggestion?>(null)
        private set
    val turns = mutableStateListOf<Turn>()
    /** The fine-tune field; kept here so it survives the card scrolling away. */
    var draft by mutableStateOf("")
    /** The spec the last automatic or manual apply put into the editor. */
    var appliedSpec by mutableStateOf<ThemeSpec?>(null)
    /** The editor's spec before this chat changed it, for "Undo AI changes". */
    var baseSpec by mutableStateOf<ThemeSpec?>(null)
    /** The suggestion already put into the live preview. */
    var previewed: ThemeSuggestion? = null
    /** The last message sent, for Retry: null for the first description, else the fine-tune text. */
    var lastRequest: Request? = null
        private set

    sealed interface Request {
        data class First(val description: String) : Request
        data class Refine(val text: String) : Request
    }

    /** True while there is a chat to show (a theme, or the first request running). */
    val active: Boolean get() = suggestion != null || working

    /** Starts a new chat from [description]; ignored while a request runs, refused while locked. */
    fun generate(entitlements: PackEntitlements, keys: AiKeyStore, provider: TextProvider, description: String) {
        if (working || !allowed(entitlements)) return
        turns.clear()
        turns.add(Turn(description.trim()))
        run(Request.First(description)) { ThemeSuggester.suggest(keys, provider, description) }
    }

    /** Sends [draft] as a change to the current theme. */
    fun refine(entitlements: PackEntitlements, keys: AiKeyStore, provider: TextProvider) {
        val current = suggestion ?: return
        val text = draft.trim()
        if (working || text.isEmpty() || !allowed(entitlements)) return
        val original = turns.firstOrNull()?.text.orEmpty()
        val earlier = turns.drop(1).map { it.text }
        turns.add(Turn(text))
        draft = ""
        run(Request.Refine(text)) { ThemeSuggester.refine(keys, provider, original, earlier, current, text) }
    }

    /** Sends the last request again after a failure. */
    fun retry(entitlements: PackEntitlements, keys: AiKeyStore, provider: TextProvider) {
        if (working || !allowed(entitlements)) return
        when (val last = lastRequest) {
            is Request.First -> run(last) { ThemeSuggester.suggest(keys, provider, last.description) }
            is Request.Refine -> {
                val current = suggestion ?: return
                val original = turns.firstOrNull()?.text.orEmpty()
                val earlier = turns.drop(1).dropLast(1).map { it.text }
                run(last) { ThemeSuggester.refine(keys, provider, original, earlier, current, last.text) }
            }
            null -> Unit
        }
    }

    /** Ends the chat; the editor keeps what it has unless the caller restored [baseSpec]. */
    fun reset() {
        if (working) return
        turns.clear()
        suggestion = null
        error = null
        draft = ""
        appliedSpec = null
        baseSpec = null
        previewed = null
        lastRequest = null
    }

    /**
     * Refused unless [entitlements] unlocks [PaidFeature.AI_CREATION] at this moment (a refunded
     * purchase must not keep generating from a screen that was open before the refund).
     */
    private fun allowed(entitlements: PackEntitlements): Boolean {
        if (entitlements.state.value.unlocks(PaidFeature.AI_CREATION)) return true
        error = "AI theme creation is locked. Unlock it to generate."
        return false
    }

    private fun run(request: Request, call: suspend () -> ThemeSuggestion) {
        working = true
        error = null
        lastRequest = request
        viewModelScope.launch {
            try {
                val result = call()
                suggestion = result
                val i = turns.lastIndex
                if (i >= 0) turns[i] = turns[i].copy(result = result.name)
            } catch (t: Exception) {
                error = describeFailure(t)
            } finally {
                working = false
            }
        }
    }
}

/**
 * Name, note, seed and accent swatches, mode and pack pills, and Apply theme, which puts what the
 * live preview shows on the phone (the same apply as the bar at the bottom).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionPreview(
    suggestion: ThemeSuggestion,
    packName: String?,
    applied: Boolean,
    enabled: Boolean,
    applyBlockedReason: String?,
    onUseAgain: () -> Unit,
    onApplyTheme: () -> Unit,
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The editor holds this theme already, so Apply theme applies exactly what the preview shows.
            Button(onClick = onApplyTheme, enabled = enabled && applied && applyBlockedReason == null) { Text("Apply theme") }
            // Changed in the editor since (a colour picked by hand, another pack): put the AI theme back.
            if (!applied) OutlinedButton(onClick = onUseAgain, enabled = enabled) { Text("Use AI theme again") }
        }
        Text(
            when {
                !applied -> "You changed the theme in the editor since. Apply theme at the bottom applies your changes, or put the AI theme back first."
                applyBlockedReason != null -> applyBlockedReason
                else -> "Apply theme puts exactly what the preview shows on your phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
