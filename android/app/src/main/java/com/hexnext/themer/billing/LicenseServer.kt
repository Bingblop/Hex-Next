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
        val fields = parseFlatObject(body) ?: return null
        val valid = fields["valid"] as? Boolean ?: return null
        return itemVerdict(valid, fields["productId"] as? String, fields["reason"] as? String, requestedProductId)
    }

    /**
     * Strictly parses one flat JSON object whose values are strings, booleans, numbers or null.
     * Returns null for anything else: nesting, trailing garbage, a duplicate key, a bad escape.
     * Numbers are kept as their text; only `valid`, `productId` and `reason` are read.
     */
    internal fun parseFlatObject(body: String): Map<String, Any?>? {
        val p = FlatParser(body)
        return try { p.parse() } catch (_: IllegalArgumentException) { null }
    }

    private class FlatParser(private val s: String) {
        private var i = 0

        fun parse(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            skipWs(); expect('{'); skipWs()
            if (peek() == '}') { i++ } else {
                while (true) {
                    skipWs()
                    val key = string()
                    require(key !in out)
                    skipWs(); expect(':'); skipWs()
                    out[key] = value()
                    skipWs()
                    when (next()) {
                        ',' -> continue
                        '}' -> break
                        else -> throw IllegalArgumentException()
                    }
                }
            }
            skipWs()
            require(i == s.length)
            return out
        }

        private fun value(): Any? = when (peek()) {
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> number()
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)); i += word.length; return v
        }

        private fun number(): String {
            val m = NUMBER.find(s, i)?.takeIf { it.range.first == i } ?: throw IllegalArgumentException()
            i += m.value.length
            return m.value
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> when (val e = next()) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(i + 4 <= s.length)
                            sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                        }
                        else -> throw IllegalArgumentException()
                    }
                    c < ' ' -> throw IllegalArgumentException()
                    else -> sb.append(c)
                }
            }
        }

        private fun skipWs() { while (i < s.length && s[i] in " \t\r\n") i++ }
        private fun peek(): Char { require(i < s.length); return s[i] }
        private fun next(): Char { require(i < s.length); return s[i++] }
        private fun expect(c: Char) { require(next() == c) }
    }

    private val NUMBER = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")
}
