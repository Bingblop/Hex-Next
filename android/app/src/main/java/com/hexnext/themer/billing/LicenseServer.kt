package com.hexnext.themer.billing

/**
 * Pure parts of the license server protocol (server/README.md), shared by the Play billing code
 * and its unit tests.
 */
object LicenseServer {
    const val VERIFY_PATH = "/v1/verify"
    const val VERIFY_BATCH_PATH = "/v1/verify-batch"

    /** Most purchases the server accepts in one [VERIFY_BATCH_PATH] request. */
    const val MAX_BATCH = 25

    /** Retries after HTTP 429 before the answer counts as "unknown". */
    const val MAX_RATE_LIMIT_RETRIES = 3

    /** Longest wait before one retry, whatever Retry-After asks for. */
    const val MAX_RETRY_WAIT_MS = 30_000L
    private const val MIN_RETRY_WAIT_MS = 1_000L
    private const val BASE_RETRY_WAIT_MS = 2_000L

    /**
     * The URL of [path] on the server configured as [configured], which may be the server's base
     * URL or the full URL of either endpoint (older builds were configured with `.../v1/verify`).
     */
    fun endpoint(configured: String, path: String): String {
        val base = configured.trim().trimEnd('/').removeSuffix(VERIFY_BATCH_PATH).removeSuffix(VERIFY_PATH).trimEnd('/')
        return base + path
    }

    /**
     * How long to wait before retry number [attempt] (0 for the first) after HTTP 429: the
     * `Retry-After` seconds when [retryAfter] holds a number, otherwise an exponential backoff from
     * 2 s; always between 1 s and [MAX_RETRY_WAIT_MS].
     */
    fun retryWaitMs(retryAfter: String?, attempt: Int): Long {
        val seconds = retryAfter?.trim()?.toLongOrNull()
        val wanted = if (seconds != null && seconds >= 0) {
            if (seconds > MAX_RETRY_WAIT_MS / 1000) MAX_RETRY_WAIT_MS else seconds * 1000
        } else {
            BASE_RETRY_WAIT_MS shl attempt.coerceIn(0, 10)
        }
        return wanted.coerceIn(MIN_RETRY_WAIT_MS, MAX_RETRY_WAIT_MS)
    }

    /**
     * One batch item's answer: true or false for a definite answer, null when the server could
     * not tell for this purchase (`provider_unavailable`, `internal_error`).
     */
    fun itemVerdict(valid: Boolean, answeredProductId: String?, reason: String?, requestedProductId: String): Boolean? = when {
        valid -> answeredProductId == requestedProductId
        reason == "provider_unavailable" || reason == "internal_error" -> null
        else -> false
    }

    /**
     * One `/v1/verify` body: the same yes / no / unknown split as [itemVerdict]. A body that is
     * not a flat JSON object, or that has no boolean `valid` field, is unknown. Treating that as
     * "not purchased" would drop a paid pack when a proxy or a glitch returned HTTP 200 with
     * something other than the documented answer, and it would skip the Play-signature fallback.
     *
     * The documented body is a flat object (`valid`, `productId`, `reason`, `orderId`). Nested
     * values are treated as unknown rather than guessed.
     */
    fun bodyVerdict(body: String, requestedProductId: String): Boolean? {
        val text = body.trim()
        if (text.length < 2 || text.first() != '{' || text.last() != '}') return null
        if ('{' in text.substring(1) || '[' in text || ']' in text) return null
        val valid = VALID.find(text)?.groupValues?.get(1) ?: return null
        val productId = stringField(text, "productId")
        val reason = stringField(text, "reason")
        return itemVerdict(valid == "true", productId, reason, requestedProductId)
    }

    private val VALID = Regex(""""valid"\s*:\s*(true|false)\b""")

    private fun stringField(json: String, name: String): String? {
        val match = Regex(""""$name"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json) ?: return null
        return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
