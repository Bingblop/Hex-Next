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
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * The one HTTP path every provider uses: HTTPS only, 60 s timeouts, keys in headers only, errors
 * mapped to [AiProviderException] messages fit for the screen. Nothing here logs; a key never
 * appears in a URL, a message or an exception.
 *
 * Every call blocks; run on `Dispatchers.IO`.
 */
internal object AiHttp {
    private const val TIMEOUT_MS = 60_000
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
     * caller sends `Accept: image/*`).
     */
    fun postMultipart(url: String, headers: Map<String, String>, fields: Map<String, String>): ByteArray {
        val boundary = "hexnext-" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()
        for ((name, value) in fields) {
            body.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            body.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
            body.write(value.toByteArray(Charsets.UTF_8))
            body.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        body.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return request(url, "POST", headers + ("Content-Type" to "multipart/form-data; boundary=$boundary"), body.toByteArray(), MAX_IMAGE_BYTES)
    }

    private fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Long): ByteArray {
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
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code in 200..299) {
                return connection.inputStream.use { readAll(it, maxBytes) }
            }
            val errorText = connection.errorStream?.use { runCatching { String(readAll(it, MAX_TEXT_BYTES), Charsets.UTF_8) }.getOrDefault("") }.orEmpty()
            throw AiProviderException(describe(code, errorText, parsed.host))
        } catch (e: AiProviderException) {
            throw e
        } catch (e: IOException) {
            throw AiProviderException("No connection to ${parsed.host}: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection.disconnect()
        }
    }

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

    /** HTTP status → message: 401/403 "Key rejected", 429 "Rate limit", else the provider's message. */
    private fun describe(code: Int, errorText: String, host: String): String = when (code) {
        HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> "Key rejected. Check the key in Settings > AI providers."
        429 -> "Rate limit reached. Wait a moment and try again."
        HttpURLConnection.HTTP_PAYMENT_REQUIRED -> "The provider reports no credit left on this key."
        else -> {
            val detail = providerMessage(errorText)
            if (detail.isNullOrBlank()) "$host answered with HTTP $code." else "$host: $detail"
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
