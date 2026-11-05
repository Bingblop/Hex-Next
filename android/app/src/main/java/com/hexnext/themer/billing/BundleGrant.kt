package com.hexnext.themer.billing

import com.hexnext.engine.pack.PackIdAliases
import java.math.BigInteger

/**
 * The pack ids a bundle purchase unlocks, carried on the Play purchase itself
 * (`obfuscatedProfileId` and `obfuscatedAccountId`, 64 characters each) so a reinstall still
 * knows which packs were bought. Ids are the current catalogue ids ([PackIdAliases]). A former
 * id is stored as its current id. Names that fit are written sorted and comma-separated. A larger
 * set (a 19-pack bundle does not fit as names) is a bitset over [ORDER]: `~` plus hex. [ORDER] is
 * append-only. A new catalogue pack is added at the end; reordering or removing an entry would
 * point an old purchase at the wrong pack.
 */
object BundleGrant {
    const val MAX_FIELD = 64

    /**
     * Catalogue pack ids in the order their bits were assigned. Append a new id. Do not sort,
     * insert, or remove.
     */
    val ORDER: List<String> = listOf(
        "agate",
        "ametrine",
        "apatite",
        "axinite",
        "bixbite",
        "calcite",
        "carnelian",
        "cerussite",
        "charoite",
        "chrysocolla",
        "citrine",
        "cordierite",
        "covellite",
        "diopside",
        "ekanite",
        "epidote",
        "erythrite",
        "euclase",
        "fayalite",
        "fuchsite",
        "garnet",
        "idocrase",
        "indicolite",
        "iolite",
        "kornerupine",
        "kunzite",
        "kyanite",
        "larimar",
        "lazulite",
        "lepidolite",
        "morganite",
        "okenite",
        "olivine",
        "pectolite",
        "peridot",
        "scolecite",
        "sodalite",
        "topaz",
        "variscite",
        "wavellite",
        "willemite",
        "xenotime",
        "zircon",
        "zoisite",
    )

    private val indexOf: Map<String, Int> = ORDER.withIndex().associate { it.value to it.index }
    private const val BITSET_MARK = "~"

    /** The two Play fields for [packIds]. Either may be empty. */
    data class Fields(val profile: String, val account: String)

    /**
     * Encodes [packIds] into [Fields]. Names are used when they fit in the two fields. Otherwise
     * a bitset of the ids that are in [ORDER] is used, so a large bundle does not drop packs.
     */
    fun fields(packIds: Collection<String>): Fields {
        val ids = packIds.map { PackIdAliases.current(it) }.filter { it.isNotBlank() }.distinct().sorted()
        if (ids.isEmpty()) return Fields("", "")
        val named = namedFields(ids.joinToString(","))
        if (decode(named.profile, named.account) == ids.toSet()) return named
        val known = ids.filter { it in indexOf }
        if (known.isEmpty()) return named
        return bitsetFields(known)
    }

    /** Pack ids written by [fields], as current ids. Blank and odd tokens are ignored. */
    fun decode(profile: String?, account: String?): Set<String> {
        val combined = listOfNotNull(profile, account).joinToString("").trim()
        if (combined.startsWith(BITSET_MARK)) return decodeBitset(combined)
        val raw = listOfNotNull(profile, account).flatMap { it.split(',') }
        return raw.map { it.trim() }
            .filter { token -> token.isNotEmpty() && token.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } }
            .map { PackIdAliases.current(it) }
            .toSet()
    }

    private fun namedFields(joined: String): Fields {
        if (joined.length <= MAX_FIELD) return Fields(joined, "")
        val cut = splitAt(joined, MAX_FIELD)
        val profile = joined.substring(0, cut).trimEnd(',')
        var rest = joined.substring(cut).trimStart(',')
        if (rest.length > MAX_FIELD) rest = rest.substring(0, splitAt(rest, MAX_FIELD)).trimEnd(',')
        return Fields(profile, rest)
    }

    private fun bitsetFields(ids: Collection<String>): Fields {
        var bits = BigInteger.ZERO
        ids.forEach { id -> indexOf[id]?.let { bits = bits.setBit(it) } }
        val body = BITSET_MARK + bits.toString(16)
        if (body.length <= MAX_FIELD) return Fields(body, "")
        return Fields(body.substring(0, MAX_FIELD), body.substring(MAX_FIELD).take(MAX_FIELD))
    }

    private fun decodeBitset(combined: String): Set<String> {
        val hex = combined.removePrefix(BITSET_MARK).filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (hex.isEmpty()) return emptySet()
        val bits = BigInteger(hex, 16)
        return ORDER.filterIndexedTo(LinkedHashSet()) { index, _ -> bits.testBit(index) }
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
