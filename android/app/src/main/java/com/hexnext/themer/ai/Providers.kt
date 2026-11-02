package com.hexnext.themer.ai

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The six bring-your-own-key providers. Raster providers return finished PNGs; vector providers
 * are text models that answer with glyph JSON which GlyphRasterizer draws. Endpoint and model
 * identifiers below are the strings each API requires, nothing more.
 */

/** Every provider in display order; the registry the UI and [ThemeSuggester] read. */
object AiProviders {
    val all: List<IconProvider> = listOf(
        OpenAiProvider,
        GeminiProvider,
        AnthropicProvider,
        GrokProvider,
        StabilityProvider,
        PerplexityProvider,
    )

    /** Providers that can answer a text prompt (for "Describe your theme"). */
    val text: List<TextProvider> = all.filterIsInstance<TextProvider>()

    fun byId(id: String): IconProvider? = all.firstOrNull { it.id == id }

    fun textById(id: String): TextProvider? = text.firstOrNull { it.id == id }
}

// ---- raster -----------------------------------------------------------------------------------

/** `POST https://api.openai.com/v1/images/generations` (`gpt-image-1`), plus chat for text. */
object OpenAiProvider : IconProvider, TextProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    override val keyLabel = "API key"
    override val keyHelpUrl = "https://platform.openai.com/api-keys"
    override val keyPrefix: String? = "sk-"
    override val kind = ProviderKind.RASTER

    private const val BASE = "https://api.openai.com/v1"

    private fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun generate(request: IconRequest, apiKey: String): List<GeneratedIcon> {
        val body = buildJsonObject {
            put("model", "gpt-image-1")
            put("prompt", request.fullPrompt())
            put("n", request.count)
            put("size", "1024x1024")
            put("background", "transparent")
            put("output_format", "png")
        }
        val reply = AiHttp.postJson("$BASE/images/generations", headers(apiKey), body, maxBytes = AiHttp.MAX_IMAGE_BYTES).asObjectOrNull()
            ?: throw AiProviderException("Unexpected reply from $displayName.")
        val data = reply.array("data") ?: throw AiProviderException("$displayName returned no images.")
        return data.mapNotNull { (it as? JsonObject)?.string("b64_json") }
            .map { GeneratedIcon(decodeBase64Png(it, request.sizePx), id, request.prompt) }
            .ifEmpty { throw AiProviderException("$displayName returned no images.") }
    }

    override suspend fun test(apiKey: String): TestResult {
        val format = formatCheck(this, apiKey)
        if (!format.ok) return format
        val reply = AiHttp.getJson("$BASE/models", headers(apiKey)).asObjectOrNull()
        val count = reply?.array("data")?.size ?: 0
        return TestResult(true, "Key accepted. $count models available.")
    }

    override suspend fun complete(system: String, user: String, apiKey: String): String {
        val body = buildJsonObject {
            put("model", "gpt-4o-mini")
            put("temperature", 0.7)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
        }
        val reply = AiHttp.postJson("$BASE/chat/completions", headers(apiKey), body).asObjectOrNull()
        return chatChoiceText(reply) ?: throw AiProviderException("$displayName returned no text.")
    }
}

/** `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent`, plus a text model. */
object GeminiProvider : IconProvider, TextProvider {
    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val keyLabel = "API key"
    override val keyHelpUrl = "https://aistudio.google.com/app/apikey"
    override val keyPrefix: String? = "AIza"
    override val kind = ProviderKind.RASTER

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    private fun headers(apiKey: String) = mapOf("x-goog-api-key" to apiKey)

    override suspend fun generate(request: IconRequest, apiKey: String): List<GeneratedIcon> {
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", request.fullPrompt()) }) } })
            }
            putJsonObject("generationConfig") { putJsonArray("responseModalities") { add("IMAGE") } }
        }
        // The image model returns one picture per call.
        return keepPaidImages(request.count) {
            val reply = AiHttp.postJson("$BASE/models/gemini-2.5-flash-image:generateContent", headers(apiKey), body, maxBytes = AiHttp.MAX_IMAGE_BYTES).asObjectOrNull()
                ?: throw AiProviderException("Unexpected reply from $displayName.")
            val data = inlineImages(reply).firstOrNull()
                ?: throw AiProviderException(blockedReason(reply) ?: "$displayName returned no image.")
            GeneratedIcon(decodeBase64Png(data, request.sizePx), id, request.prompt)
        }
    }

    override suspend fun test(apiKey: String): TestResult {
        val format = formatCheck(this, apiKey)
        if (!format.ok) return format
        val reply = AiHttp.getJson("$BASE/models?pageSize=50", headers(apiKey)).asObjectOrNull()
        val count = reply?.array("models")?.size ?: 0
        return TestResult(true, "Key accepted. $count models available.")
    }

    override suspend fun complete(system: String, user: String, apiKey: String): String {
        val body = buildJsonObject {
            putJsonObject("system_instruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") {
                add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", user) }) } })
            }
            putJsonObject("generationConfig") { put("responseMimeType", "application/json"); put("temperature", 0.7) }
        }
        val reply = AiHttp.postJson("$BASE/models/gemini-2.5-flash:generateContent", headers(apiKey), body).asObjectOrNull()
            ?: throw AiProviderException("Unexpected reply from $displayName.")
        return parts(reply).mapNotNull { it.string("text") }.joinToString("").ifBlank {
            throw AiProviderException(blockedReason(reply) ?: "$displayName returned no text.")
        }
    }

    /** `candidates[].content.parts[]`. */
    private fun parts(reply: JsonObject): List<JsonObject> =
        reply.array("candidates").orEmpty().mapNotNull { it as? JsonObject }
            .flatMap { it.obj("content")?.array("parts").orEmpty().mapNotNull { p -> p as? JsonObject } }

    /** `candidates[].content.parts[].inlineData.data` (base64 PNG or JPEG). */
    private fun inlineImages(reply: JsonObject): List<String> =
        parts(reply).mapNotNull { it.obj("inlineData")?.string("data") ?: it.obj("inline_data")?.string("data") }

    private fun blockedReason(reply: JsonObject): String? {
        val finish = reply.array("candidates").orEmpty().firstOrNull()?.let { (it as? JsonObject)?.string("finishReason") }
        val blocked = reply.obj("promptFeedback")?.string("blockReason")
        return when {
            blocked != null -> "$displayName declined the prompt ($blocked). Try different wording."
            finish != null && finish != "STOP" -> "$displayName stopped early ($finish). Try different wording."
            else -> null
        }
    }
}

/** `POST https://api.x.ai/v1/images/generations` (`grok-2-image`). */
object GrokProvider : IconProvider {
    override val id = "xai"
    override val displayName = "xAI Grok"
    override val keyLabel = "API key"
    override val keyHelpUrl = "https://console.x.ai/"
    override val keyPrefix: String? = "xai-"
    override val kind = ProviderKind.RASTER

    private const val BASE = "https://api.x.ai/v1"

    private fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun generate(request: IconRequest, apiKey: String): List<GeneratedIcon> {
        val body = buildJsonObject {
            put("model", "grok-2-image")
            put("prompt", request.fullPrompt())
            put("n", request.count)
            put("response_format", "b64_json")
        }
        val reply = AiHttp.postJson("$BASE/images/generations", headers(apiKey), body, maxBytes = AiHttp.MAX_IMAGE_BYTES).asObjectOrNull()
            ?: throw AiProviderException("Unexpected reply from $displayName.")
        val data = reply.array("data") ?: throw AiProviderException("$displayName returned no images.")
        return data.mapNotNull { (it as? JsonObject)?.string("b64_json") }
            .map { GeneratedIcon(decodeBase64Png(it, request.sizePx), id, request.prompt) }
            .ifEmpty { throw AiProviderException("$displayName returned no images.") }
    }

    override suspend fun test(apiKey: String): TestResult {
        val format = formatCheck(this, apiKey)
        if (!format.ok) return format
        val reply = AiHttp.getJson("$BASE/models", headers(apiKey)).asObjectOrNull()
        val count = reply?.array("data")?.size ?: 0
        return TestResult(true, "Key accepted. $count models available.")
    }
}

/** `POST multipart https://api.stability.ai/v2beta/stable-image/generate/core`, reply body is the PNG. */
object StabilityProvider : IconProvider {
    override val id = "stability"
    override val displayName = "Stability AI"
    override val keyLabel = "API key"
    override val keyHelpUrl = "https://platform.stability.ai/account/keys"
    override val keyPrefix: String? = "sk-"
    override val kind = ProviderKind.RASTER

    private const val BASE = "https://api.stability.ai"

    private fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun generate(request: IconRequest, apiKey: String): List<GeneratedIcon> {
        val fields = mapOf(
            "prompt" to request.fullPrompt(),
            "output_format" to "png",
            "aspect_ratio" to "1:1",
        )
        // One image per call.
        return keepPaidImages(request.count) {
            // A moderated output still comes back as HTTP 200 with a deliberately blurred image;
            // only the finish-reason header says so.
            val png = AiHttp.postMultipart(
                "$BASE/v2beta/stable-image/generate/core",
                headers(apiKey) + ("Accept" to "image/*"),
                fields,
            ) { header ->
                if (header("finish-reason").equals("CONTENT_FILTERED", ignoreCase = true)) {
                    throw AiProviderException(
                        "$displayName's content filter blurred the image, so it was not kept. " +
                            "The request may still be billed. Try different wording.",
                    )
                }
            }
            GeneratedIcon(normalizePng(png, request.sizePx), id, request.prompt)
        }
    }

    override suspend fun test(apiKey: String): TestResult {
        val format = formatCheck(this, apiKey)
        if (!format.ok) return format
        val reply = AiHttp.getJson("$BASE/v1/user/account", headers(apiKey)).asObjectOrNull()
        val email = reply?.string("email")
        return TestResult(true, if (email.isNullOrBlank()) "Key accepted." else "Key accepted for ${AiKeyStore.mask(email)}.")
    }
}

// ---- vector (text models answering with glyph JSON) ------------------------------------------

/** `POST https://api.anthropic.com/v1/messages`. */
object AnthropicProvider : IconProvider, TextProvider {
    override val id = "anthropic"
    override val displayName = "Anthropic Claude"
    override val keyLabel = "API key"
    override val keyHelpUrl = "https://console.anthropic.com/settings/keys"
    override val keyPrefix: String? = "sk-ant-"
    override val kind = ProviderKind.VECTOR

    private const val BASE = "https://api.anthropic.com/v1"

    private fun headers(apiKey: String) = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")

    override suspend fun generate(request: IconRequest, apiKey: String): List<GeneratedIcon> =
        glyphIcons(this, request) { prompt -> complete(GlyphRasterizer.SYSTEM_PROMPT, prompt, apiKey) }

    override suspend fun test(apiKey: String): TestResult {
        val format = formatCheck(this, apiKey)
        if (!format.ok) return format
        val reply = AiHttp.getJson("$BASE/models?limit=50", headers(apiKey)).asObjectOrNull()
        val count = reply?.array("data")?.size ?: 0
        return TestResult(true, "Key accepted. $count models available.")
    }

    override suspend fun complete(system: String, user: String, apiKey: String): String {
        val body = buildJsonObject {
            put("model", "claude-sonnet-4-5")
            put("max_tokens", 2000)
            put("system", system)
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", user) }) }
        }
        val reply = AiHttp.postJson("$BASE/messages", headers(apiKey), body).asObjectOrNull()
            ?: throw AiProviderException("Unexpected reply from $displayName.")
        val text = reply.array("content").orEmpty()
            .mapNotNull { (it as? JsonObject)?.takeIf { c -> c.string("type") == "text" }?.string("text") }
            .joinToString("")
        return text.ifBlank { throw AiProviderException("$displayName returned no text.") }
    }
}

/** `POST https://api.perplexity.ai/chat/completions` (`sonar`). */
object PerplexityProvider : IconProvider, TextProvider {
    override val id = "perplexity"
    override val displayName = "Perplexity"
    override val keyLabel = "API key"
    override val keyHelpUrl = "https://www.perplexity.ai/settings/api"
    override val keyPrefix: String? = "pplx-"
    override val kind = ProviderKind.VECTOR

    private const val BASE = "https://api.perplexity.ai"

    private fun headers(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun generate(request: IconRequest, apiKey: String): List<GeneratedIcon> =
        glyphIcons(this, request) { prompt -> complete(GlyphRasterizer.SYSTEM_PROMPT, prompt, apiKey) }

    /** The API has no model list, so only the key format is checked. */
    override suspend fun test(apiKey: String): TestResult {
        val format = formatCheck(this, apiKey)
        return if (format.ok) TestResult(true, "Format looks right. $displayName has no free check call, so the key is tried on first use.") else format
    }

    override suspend fun complete(system: String, user: String, apiKey: String): String {
        val body = buildJsonObject {
            put("model", "sonar")
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
        }
        val reply = AiHttp.postJson("$BASE/chat/completions", headers(apiKey), body).asObjectOrNull()
        return chatChoiceText(reply) ?: throw AiProviderException("$displayName returned no text.")
    }
}

// ---- shared pieces -----------------------------------------------------------------------------

/** `choices[0].message.content` of an OpenAI-style chat reply. */
private fun chatChoiceText(reply: JsonObject?): String? {
    val choice = reply?.array("choices")?.firstOrNull() as? JsonObject ?: return null
    val content = choice.obj("message")?.get("content") ?: return null
    return when (content) {
        is JsonPrimitive -> content.contentOrNull()?.takeIf { it.isNotBlank() }
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("").takeIf { it.isNotBlank() }
        else -> null
    }
}

/**
 * Asks a text model [count] times for a glyph and rasterises each answer. Variants carry a
 * "variation n" suffix so the model does not repeat itself. A glyph that cannot be drawn is
 * skipped and the next variant is tried, but a failed request (timeout, rejected key, rate
 * limit, empty credit) stops the run: the next request would only fail again, could still be
 * billed, and keeps the sheet busy. The first failure is thrown only when no icon came back.
 */
private suspend fun glyphIcons(provider: IconProvider, request: IconRequest, ask: suspend (String) -> String): List<GeneratedIcon> {
    val icons = ArrayList<GeneratedIcon>(request.count)
    var firstError: AiProviderException? = null
    for (n in 1..request.count) {
        val prompt = if (request.count == 1) request.fullPrompt() else "${request.fullPrompt()} (variation $n of ${request.count}, make it distinct)"
        val reply = try {
            ask(prompt)
        } catch (e: AiProviderException) {
            if (firstError == null) firstError = e
            break
        }
        try {
            icons += GeneratedIcon(GlyphRasterizer.rasterize(reply, request.sizePx), provider.id, request.prompt)
        } catch (e: AiProviderException) {
            if (firstError == null) firstError = e
        }
    }
    if (icons.isEmpty()) throw firstError ?: AiProviderException("${provider.displayName} returned no glyph.")
    return icons
}

/**
 * Runs [one] up to [count] times for providers that bill one image per call. A failure stops the
 * run (a rate limit or empty credit would only fail again), but the images already made and paid
 * for are kept; the failure is thrown only when none came back.
 */
private inline fun keepPaidImages(count: Int, one: () -> GeneratedIcon): List<GeneratedIcon> {
    val icons = ArrayList<GeneratedIcon>(count)
    while (icons.size < count) {
        try {
            icons += one()
        } catch (e: AiProviderException) {
            if (icons.isEmpty()) throw e
            break
        }
    }
    return icons
}

/** Decodes base64 image data and normalises it to an ARGB PNG of at most [maxPx] per side. */
private fun decodeBase64Png(base64: String, maxPx: Int): ByteArray {
    val bytes = try {
        Base64.decode(base64, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        throw AiProviderException("The provider sent image data that could not be decoded.", e)
    }
    return normalizePng(bytes, maxPx)
}
