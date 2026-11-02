package com.hexnext.themer.ai

import com.hexnext.themer.theme.toHex

/** How a provider delivers an icon: a finished PNG, or glyph JSON the app rasterises itself. */
enum class ProviderKind { RASTER, VECTOR }

/** Visual direction of a generated icon; [promptPhrase] is spliced into the prompt template. */
enum class IconStyle(val label: String, val promptPhrase: String) {
    /** Flat glyph tinted with the selected pack's palette (see [IconRequest.paletteHint]). */
    MATCH_PACK("Match pack", "flat, minimal"),
    FLAT("Flat", "flat, solid shapes, minimal, bold silhouette"),
    OUTLINE("Outline", "thin uniform stroke, outline only, monochrome"),
    GLASS("Glass", "glossy frosted glass, soft gradients, subtle highlights"),
    PIXEL("Pixel", "pixel art, crisp square pixels, limited palette"),
}

/**
 * One generation request.
 *
 * @property prompt what the icon shows, in the user's words ("a paper plane").
 * @property sizePx edge of the stored PNG; larger provider output is scaled down to it.
 * @property count how many variants to ask for (1–4).
 * @property paletteHint opaque ARGB colours of the selected pack, used by [IconStyle.MATCH_PACK]
 *   and mentioned to every provider when given.
 */
data class IconRequest(
    val prompt: String,
    val style: IconStyle = IconStyle.FLAT,
    val sizePx: Int = DEFAULT_SIZE_PX,
    val count: Int = 1,
    val paletteHint: List<Int> = emptyList(),
) {
    init {
        require(count in 1..MAX_COUNT) { "count must be 1..$MAX_COUNT" }
    }

    /** The prompt handed to the provider: template plus optional palette hint. */
    fun fullPrompt(): String = buildString {
        append(style.promptPhrase)
        append(" app icon glyph of ")
        append(prompt.trim())
        append(", single subject, centred, transparent or flat background, no text, no watermark, 1:1")
        if (paletteHint.isNotEmpty()) {
            append(", palette: ")
            append(paletteHint.take(6).joinToString(", ") { it.toHex() })
        }
    }

    companion object {
        const val DEFAULT_SIZE_PX = 512
        const val MAX_COUNT = 4
    }
}

/** A finished icon: an ARGB PNG of at most [IconRequest.sizePx] on each side. */
class GeneratedIcon(
    val png: ByteArray,
    val providerId: String,
    val prompt: String,
)

/** Result of [IconProvider.test]: [ok] with a one-line [message] fit for the screen. */
data class TestResult(val ok: Boolean, val message: String)

/**
 * Thrown by providers with a message fit for the screen ("Key rejected", "Rate limit reached",
 * or the provider's own message). Never contains the key.
 */
class AiProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One icon-generation service. Providers hold no state and never store the key: the caller
 * reads it from [AiKeyStore] and hands it to each call.
 */
interface IconProvider {
    /** Stable id used as the [AiKeyStore] key and in [GeneratedIcon.providerId]. */
    val id: String

    /** Name shown in the UI. */
    val displayName: String

    /** Label of the key field ("API key", "Secret key"). */
    val keyLabel: String

    /** Where to create a key. */
    val keyHelpUrl: String

    /** Expected start of a valid key, used only for a format hint; null when there is none. */
    val keyPrefix: String?

    val kind: ProviderKind

    /** Generates [IconRequest.count] icons. Call on `Dispatchers.IO`. */
    suspend fun generate(request: IconRequest, apiKey: String): List<GeneratedIcon>

    /**
     * Checks [apiKey] as cheaply as the API allows: a model-list GET where one exists, otherwise a
     * format check only (the message says which). Call on `Dispatchers.IO`.
     */
    suspend fun test(apiKey: String): TestResult
}

/** A provider that can also answer a text prompt with text (used by [ThemeSuggester]). */
interface TextProvider {
    val id: String
    val displayName: String

    /** Sends [system] and [user] and returns the assistant text. Call on `Dispatchers.IO`. */
    suspend fun complete(system: String, user: String, apiKey: String): String
}

/** Key format check shared by every provider: present, no whitespace, plausible prefix. */
internal fun formatCheck(provider: IconProvider, apiKey: String): TestResult {
    val key = apiKey.trim()
    if (key.isEmpty()) return TestResult(false, "Enter a key first.")
    if (key.any { it.isWhitespace() }) return TestResult(false, "The key contains spaces or line breaks.")
    if (key.length < 16) return TestResult(false, "The key is too short.")
    val prefix = provider.keyPrefix
    if (prefix != null && !key.startsWith(prefix)) {
        return TestResult(false, "Keys for ${provider.displayName} usually start with \"$prefix\".")
    }
    return TestResult(true, "Format looks right.")
}
