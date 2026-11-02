package com.hexnext.themer.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * The one HTTP path every provider uses: HTTPS only, 60 s timeouts (5 min reads for image replies),
 * keys in headers only, errors mapped to [AiProviderException] messages fit for the screen. Nothing here logs; a key never
 * appears in a URL, a message or an exception.
 *
 * Every call blocks; run on `Dispatchers.IO`.
 */
internal object AiHttp {
    private const val TIMEOUT_MS = 60_000

    /**
     * Read timeout for image replies. A non-streamed multi-image request (gpt-image-1 with n = 4)
     * sends nothing until every image is done, which often takes longer than a minute.
     */
    private const val IMAGE_READ_TIMEOUT_MS = 300_000
    private const val MAX_ERROR_CHARS = 200

    /** Body cap for text replies (glyph JSON, chat completions, model lists): a few KB of useful content. */
    const val MAX_TEXT_BYTES: Long = 4L * 1024L * 1024L

    /** Body cap for replies that carry base64 or raw images. */
    const val MAX_IMAGE_BYTES: Long = 64L * 1024L * 1024L

    /** Lenient parser for provider responses: unknown fields are ignored. */
    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * POSTs [body] as JSON and parses the JSON reply, refusing bodies over [maxBytes]; image
     * endpoints pass [MAX_IMAGE_BYTES].
     */
    fun postJson(url: String, headers: Map<String, String>, body: JsonObject, maxBytes: Long = MAX_TEXT_BYTES): JsonElement {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val reply = request(url, "POST", headers + ("Content-Type" to "application/json") + ("Accept" to "application/json"), bytes, maxBytes)
        return parseJson(reply)
    }

    /** GETs [url] and parses the JSON reply (text-sized, see [MAX_TEXT_BYTES]). */
    fun getJson(url: String, headers: Map<String, String>): JsonElement =
        parseJson(request(url, "GET", headers + ("Accept" to "application/json"), null, MAX_TEXT_BYTES))

    /**
     * POSTs [fields] as `multipart/form-data` and returns the raw reply body (an image when the
     * caller sends an image Accept header). On a 2xx reply [checkHeaders] gets a lookup for the
     * response headers before the body is read, and may throw [AiProviderException] to reject it.
     */
    fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        checkHeaders: ((String) -> String?) -> Unit = {},
    ): ByteArray {
        val boundary = "hexnext-" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()
        for ((name, value) in fields) {
            body.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            body.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
            body.write(value.toByteArray(Charsets.UTF_8))
            body.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        body.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return request(url, "POST", headers + ("Content-Type" to "multipart/form-data; boundary=$boundary"), body.toByteArray(), MAX_IMAGE_BYTES, checkHeaders)
    }

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBytes: Long,
        checkHeaders: ((String) -> String?) -> Unit = {},
    ): ByteArray {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw AiProviderException("The provider address is invalid.", e)
        }
        if (parsed.protocol != "https") throw AiProviderException("Only https endpoints are allowed.")
        val connection = try {
            parsed.openConnection() as? HttpsURLConnection ?: throw AiProviderException("The provider address is not an https endpoint.")
        } catch (e: IOException) {
            throw AiProviderException("No connection to ${parsed.host}.", e)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = if (maxBytes >= MAX_IMAGE_BYTES) IMAGE_READ_TIMEOUT_MS else TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            for ((name, value) in headers) setHeader(connection, name, value)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code in 200..299) {
                checkHeaders { name -> connection.getHeaderField(name) }
                return connection.inputStream.use { readAll(it, maxBytes) }
            }
            val errorText = connection.errorStream?.use { runCatching { String(readAll(it, MAX_TEXT_BYTES), Charsets.UTF_8) }.getOrDefault("") }.orEmpty()
            throw AiProviderException(describe(code, errorText, parsed.host))
        } catch (e: AiProviderException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiProviderException(
                "${parsed.host} did not answer in time. The provider may still finish and bill the request, " +
                    "so check your usage there before trying again.",
                e,
            )
        } catch (e: IOException) {
            throw AiProviderException("No connection to ${parsed.host}: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Sets one header. The platform HTTP stack rejects control and non-ASCII characters with an
     * IllegalArgumentException whose message quotes the whole value, key included; check first and
     * throw a message that names no value. A stray line break or zero-width space from copy and
     * paste is the usual cause.
     */
    private fun setHeader(connection: HttpsURLConnection, name: String, value: String) {
        if (value.any { it < ' ' || it > '~' }) throw AiProviderException(BAD_HEADER_MESSAGE)
        try {
            connection.setRequestProperty(name, value)
        } catch (e: IllegalArgumentException) {
            // No cause: its message would carry the value.
            throw AiProviderException(BAD_HEADER_MESSAGE)
        }
    }

    private const val BAD_HEADER_MESSAGE =
        "The saved key contains a hidden or unsupported character, often from copy and paste. " +
            "Paste the key again in Settings > AI providers."

    private fun readAll(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw AiProviderException("The reply is larger than ${maxBytes / (1024 * 1024)} MB.")
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private fun parseJson(bytes: ByteArray): JsonElement = try {
        json.parseToJsonElement(String(bytes, Charsets.UTF_8))
    } catch (e: Exception) {
        throw AiProviderException("The provider sent a reply that is not JSON.", e)
    }

    /**
     * HTTP status → message. 401 is "Key rejected". 403 and 429 keep the provider's own message,
     * because a 403 is often a model or organisation restriction on a working key (gpt-image-1
     * needs a verified organisation) and a 429 can mean no quota left (OpenAI `insufficient_quota`),
     * where waiting never helps. Everything else shows the provider's message.
     */
    private fun describe(code: Int, errorText: String, host: String): String {
        val detail = providerMessage(errorText)?.takeIf { it.isNotBlank() }
        return when (code) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> "Key rejected. Check the key in Settings > AI providers."
            HttpURLConnection.HTTP_FORBIDDEN ->
                if (detail == null) "$host refused the request (HTTP 403). The key may not have access to this model or feature."
                else "$host refused the request: $detail"
            429 -> when {
                isQuotaExhausted(errorText) ->
                    "The provider reports no credit or quota left on this key." + (detail?.let { " $host: $it" } ?: "")
                detail == null -> "Rate limit reached. Wait a moment and try again."
                else -> "Rate limit reached: $detail"
            }
            HttpURLConnection.HTTP_PAYMENT_REQUIRED ->
                "The provider reports no credit left on this key." + (detail?.let { " $host: $it" } ?: "")
            else -> if (detail == null) "$host answered with HTTP $code." else "$host: $detail"
        }
    }

    /** True when an error body's `error.code` or `error.type` says the quota or credit is used up. */
    private fun isQuotaExhausted(errorText: String): Boolean {
        val error = (runCatching { json.parseToJsonElement(errorText) }.getOrNull() as? JsonObject)?.obj("error") ?: return false
        return listOfNotNull(error.string("code"), error.string("type")).any {
            it.equals("insufficient_quota", ignoreCase = true) || it.equals("billing_hard_limit_reached", ignoreCase = true)
        }
    }

    /** Pulls `error.message`, `error` (string), `message` or `errors[0]` out of an error body. */
    private fun providerMessage(errorText: String): String? {
        if (errorText.isBlank()) return null
        val element = runCatching { json.parseToJsonElement(errorText) }.getOrNull()
        val text = when {
            element is JsonObject -> {
                val error = element["error"]
                when {
                    error is JsonObject -> error.string("message")
                    error is JsonPrimitive -> error.contentOrNull()
                    else -> element.string("message")
                        ?: (element["errors"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull() }
                        ?: element.string("detail")
                }
            }
            else -> errorText
        }
        return text?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX_ERROR_CHARS)
    }
}

// ---- small JSON tree helpers (the app module has no serialization plugin; trees are enough) ----

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull()

internal fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
