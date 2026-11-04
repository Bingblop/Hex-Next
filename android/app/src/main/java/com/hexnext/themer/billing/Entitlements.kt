package com.hexnext.themer.billing

import android.app.Activity
import com.hexnext.engine.pack.PackIdAliases
import kotlinx.coroutines.flow.StateFlow

/**
 * Which theme packs this install may use.
 *
 * Shared code only talks to this interface. Each store flavor provides its own
 * [EntitlementsFactory] (src/play and src/owner), so flavor-specific unlock logic never reaches
 * the other build. Free features (default theme, custom colours, custom icons, fonts and
 * backgrounds, AI) are never gated by it.
 */
interface PackEntitlements {
    val state: StateFlow<EntitlementState>

    /** Connects to the store and loads purchases and prices. Safe to call more than once. */
    fun start()

    /** Re-queries purchases and prices, for example when the app returns to the foreground. */
    fun refresh()

    fun isUnlocked(packId: String): Boolean

    /** Starts the store purchase flow for [productId]; the result arrives through [state]. */
    fun launchPurchase(activity: Activity, productId: String)

    fun productIdFor(packId: String): String

    /**
     * The pack ids the app knows about (installed packs and the remote catalogue), so the store
     * can load their prices. [ProductIds.ALL_PACKS] is always loaded. Safe to call often.
     */
    fun setCatalogue(packIds: Collection<String>)

    /** False when this build has no store to buy from (everything is already unlocked). */
    val canPurchase: Boolean
}

data class EntitlementState(
    /** True once purchases were read from the store at least once in this app run. */
    val ready: Boolean = false,
    val unlockAll: Boolean = false,
    /** Ids of the known packs ([PackEntitlements.setCatalogue]) that are unlocked. */
    val ownedPackIds: Set<String> = emptySet(),
    /** Product id to the store's formatted price; prices are never hard-coded. */
    val prices: Map<String, String> = emptyMap(),
    val error: String? = null,
    /** Verified, owned product ids of this app run; recomputed from the store on every start. */
    val ownedProductIds: Set<String> = emptySet(),
    /** Product ids whose payment is still pending; they are not unlocked. */
    val pendingProductIds: Set<String> = emptySet(),
    /**
     * Product ids owned at the end of the last run. Only a hint so the pack list does not flash
     * locks while [ready] is false; it never unlocks a download or an apply.
     */
    val cachedProductIds: Set<String> = emptySet(),
    /** True while purchases are being read from the store. */
    val checking: Boolean = false,
) {
    /**
     * True when pack [packId] is unlocked by a verified purchase (or by this build). A purchase
     * made under a former id of the pack ([ProductIds.allForPack]) still unlocks it.
     */
    fun unlocks(packId: String): Boolean = unlockAll || ProductIds.allForPack(packId).any { it in ownedProductIds }

    /** [unlocks], or before [ready] the [cachedProductIds] hint: for display only. */
    fun looksUnlocked(packId: String): Boolean = unlocks(packId) ||
        (!ready && (ProductIds.ALL_PACKS in cachedProductIds || ProductIds.allForPack(packId).any { it in cachedProductIds }))

    fun isPending(packId: String): Boolean = ProductIds.allForPack(packId).any { it in pendingProductIds }

    /**
     * The store's formatted price of pack [packId], or null while it is unknown: the current
     * product's price, else the price of a product under a former id of the pack.
     */
    fun priceOf(packId: String): String? = ProductIds.allForPack(packId).firstNotNullOfOrNull { prices[it] }
}

object ProductIds {
    /** One-time unlock of every pack, including packs added later. */
    const val ALL_PACKS = "all_packs"

    private val disallowed = Regex("[^a-z0-9._]")

    /** "pack_" + the pack id lowercased, with every character outside [a-z0-9._] replaced by '_'. */
    fun forPack(packId: String): String = "pack_" + packId.lowercase().replace(disallowed, "_")

    /**
     * Every product that unlocks pack [packId]: [forPack] of its current id first (what a new
     * purchase buys), then [forPack] of each former catalogue id ([PackIdAliases]), so a purchase
     * made before the pack was renamed keeps it unlocked.
     */
    fun allForPack(packId: String): List<String> =
        (listOf(forPack(packId)) + PackIdAliases.allIdsOf(packId).map(::forPack)).distinct()
}
