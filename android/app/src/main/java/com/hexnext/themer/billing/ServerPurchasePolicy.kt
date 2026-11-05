package com.hexnext.themer.billing

import java.net.URI
import java.net.URISyntaxException

/** Fail-closed policy only; the backend, not purchase JSON or a local signature, is authoritative. */
object ServerPurchasePolicy {
    fun isConfigured(endpoint: String): Boolean = try {
        val uri = URI(endpoint.trim())
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    } catch (_: URISyntaxException) {
        false
    }

    fun allowedProducts(
        configured: Boolean,
        packageMatches: Boolean,
        products: Collection<String>,
        serverAnswers: Map<String, Boolean?>,
    ): Set<String> {
        if (!configured || !packageMatches || products.isEmpty()) return emptySet()
        // Missing/unknown evidence must not be upgraded into a local or cached grant.
        if (products.any { it.isBlank() || serverAnswers[it] == null }) return emptySet()
        return products.filterTo(linkedSetOf()) { serverAnswers[it] == true }
    }
}
