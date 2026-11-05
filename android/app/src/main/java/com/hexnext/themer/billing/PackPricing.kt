package com.hexnext.themer.billing

import com.hexnext.engine.pack.PackIdAliases

/**
 * What Google Play is asked to charge for packs. The Play Console owns the real price (it can
 * differ by country); these are the prices the products are created at, and the words the UI
 * uses until Play has answered.
 *
 * One pack is [ProductIds.forPack] at [SINGLE_LABEL]. Every pack, including packs added later,
 * is [ProductIds.ALL_PACKS] at [ALL_LABEL]. A theme that applies several locked packs is one
 * purchase: [SINGLE_CENTS] for the first pack and [STEP_CENTS] for each further pack
 * ($0.99, $1.99, $2.99, …). From [ALL_FROM] packs that sum reaches [ALL_LABEL] ($19.99), so Apply
 * buys [ProductIds.ALL_PACKS] instead. Each bundle size has [SLOTS] products so the same size can be
 * bought again for a different set of packs; a purchase is not consumed, and the pack ids travel
 * with it ([BundleGrant]), so those packs stay unlocked after a reinstall.
 */
object PackPricing {
    const val SINGLE_CENTS = 99
    const val STEP_CENTS = 100
    const val SINGLE_LABEL = "$0.99"
    const val ALL_LABEL = "$19.99"

    /** How many times one bundle size can be bought. */
    const val SLOTS = 4

    /**
     * A bundle of this many locked packs costs the same as [ALL_LABEL], so Apply buys
     * [ProductIds.ALL_PACKS] and every pack unlocks.
     */
    const val ALL_FROM = 20

    /** Cents for [count] locked packs, before the [ALL_FROM] cap. */
    fun cents(count: Int): Int {
        require(count >= 1) { "count" }
        return SINGLE_CENTS + (count - 1) * STEP_CENTS
    }

    /** "$0.99", "$1.99", "$2.99", … for [count] packs. */
    fun label(count: Int): String {
        val total = cents(count)
        val frac = (total % 100).toString().padStart(2, '0')
        return "$${total / 100}.$frac"
    }

    /** Play product ids for a bundle of [count] packs, first slot first. [count] is 2 until [ALL_FROM]. */
    fun productIds(count: Int): List<String> {
        require(count in 2 until ALL_FROM) { "count" }
        return List(SLOTS) { slot -> if (slot == 0) "packs_$count" else "packs_${count}_${slot + 1}" }
    }

    /** Every bundle product id ([productIds] of every size). */
    fun allProductIds(): List<String> = (2 until ALL_FROM).flatMap(::productIds)

    private val idPattern = Regex("^packs_(\\d+)(?:_(\\d+))?$")

    /** The pack count a bundle product sells, or null when [productId] is not one. */
    fun countOf(productId: String): Int? {
        val match = idPattern.matchEntire(productId) ?: return null
        val count = match.groupValues[1].toIntOrNull() ?: return null
        if (count !in 2 until ALL_FROM) return null
        val slot = match.groupValues[2].toIntOrNull() ?: 1
        if (slot !in 1..SLOTS) return null
        return count
    }

    /** The first bundle product of [count] that [owned] does not already contain. */
    fun freeSlot(count: Int, owned: Set<String>): String? = productIds(count).firstOrNull { it !in owned }

    /**
     * The price to show for unlocking [count] packs. Play's formatted price wins when [prices]
     * has it; otherwise the catalogue price. [singlePackId] is the pack when [count] is 1.
     */
    fun labelForOffer(count: Int, prices: Map<String, String>, singlePackId: String?): String = when {
        count <= 1 -> {
            val id = singlePackId?.let { ProductIds.forPack(PackIdAliases.current(it)) }
            id?.let { prices[it] } ?: SINGLE_LABEL
        }
        count >= ALL_FROM -> prices[ProductIds.ALL_PACKS] ?: ALL_LABEL
        else -> productIds(count).firstNotNullOfOrNull { prices[it] } ?: label(count)
    }
}
