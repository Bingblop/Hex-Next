package com.hexnext.themer.billing

import com.hexnext.engine.pack.PackIdAliases

/**
 * The pack ids a bundle purchase unlocks, carried on the Play purchase itself
 * (`obfuscatedProfileId` and `obfuscatedAccountId`, 64 characters each) so a reinstall still
 * knows which packs were bought. Ids are the current catalogue ids ([PackIdAliases]), sorted,
 * joined with commas and split across the two fields. A former id is stored as its current id.
 */
object BundleGrant {
    const val MAX_FIELD = 64

    /** The two Play fields for [packIds]. Either may be empty. */
    data class Fields(val profile: String, val account: String)

    /** Encodes [packIds] into [Fields]. Ids that do not fit in the two fields are dropped from the end. */
    fun fields(packIds: Collection<String>): Fields {
        val joined = packIds.map { PackIdAliases.current(it) }.filter { it.isNotBlank() }.distinct().sorted().joinToString(",")
        if (joined.isEmpty()) return Fields("", "")
        if (joined.length <= MAX_FIELD) return Fields(joined, "")
        val cut = splitAt(joined, MAX_FIELD)
        val profile = joined.substring(0, cut).trimEnd(',')
        var rest = joined.substring(cut).trimStart(',')
        if (rest.length > MAX_FIELD) rest = rest.substring(0, splitAt(rest, MAX_FIELD)).trimEnd(',')
        return Fields(profile, rest)
    }

    /** Pack ids written by [fields], as current ids. Blank and odd tokens are ignored. */
    fun decode(profile: String?, account: String?): Set<String> {
        val raw = listOfNotNull(profile, account).flatMap { it.split(',') }
        return raw.map { it.trim() }
            .filter { token -> token.isNotEmpty() && token.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } }
            .map { PackIdAliases.current(it) }
            .toSet()
    }

    /**
     * At most [count] current ids, in a stable order. A bundle product unlocks only as many packs
     * as it was sold for.
     */
    fun limit(ids: Collection<String>, count: Int): Set<String> {
        if (count <= 0) return emptySet()
        return ids.map { PackIdAliases.current(it) }.filter { it.isNotBlank() }.distinct().sorted().take(count).toSet()
    }

    /** The longer of [decoded] and [remembered], then [limit]. */
    fun choose(decoded: Set<String>, remembered: Set<String>, count: Int): Set<String> {
        val best = if (remembered.size > decoded.size) remembered else decoded
        return limit(best, count)
    }

    /** A cut index at or before [max] that falls on a comma, or [max] when the first id is longer. */
    private fun splitAt(text: String, max: Int): Int {
        if (text.length <= max) return text.length
        val comma = text.lastIndexOf(',', max - 1)
        return if (comma > 0) comma else max
    }
}
