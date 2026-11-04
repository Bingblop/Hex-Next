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
 * backgrounds) are never gated by it. AI creation is a [PaidFeature] ([EntitlementState.unlocks]).
 */
interface PackEntitlements {
    val state: StateFlow<EntitlementState>

    /** Connects to the store and loads purchases and prices. Safe to call more than once. */
    fun start()

    /** Re-queries purchases and prices, for example when the app returns to the foreground. */
    fun refresh()

    fun isUnlocked(packId: String): Boolean

    /**
     * Starts the store purchase flow for [productId]; the result arrives through [state].
     * [grantPackIds] is the packs a bundle product ([com.hexnext.themer.billing.PackPricing]) unlocks
     * when the purchase completes. Empty for a single pack, for [ProductIds.ALL_PACKS] and for AI.
     */
    fun launchPurchase(activity: Activity, productId: String, grantPackIds: Set<String> = emptySet())

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
    /** Product id to the store's formatted price. The UI falls back to [PackPricing] until this is filled. */
    val prices: Map<String, String> = emptyMap(),
    val error: String? = null,
    /** Verified, owned product ids of this app run; recomputed from the store on every start. */
    val ownedProductIds: Set<String> = emptySet(),
    /**
     * Packs unlocked by a verified bundle purchase ([PackPricing]), in addition to [ownedProductIds].
     * Recomputed from those purchases, so a refund locks them again. Empty until the store has answered.
     */
    val bundlePackIds: Set<String> = emptySet(),
    /**
     * True when [ProductIds.EVERYTHING] is owned (or this build unlocks everything): every pack,
     * including packs added later, and every [PaidFeature], including features added later.
     */
    val everything: Boolean = false,
    /**
     * True while the last subscription query gave no answer: subscriptions may be owned but are
     * not known, so [looksUnlocked] still trusts [cachedProductIds] for them.
     */
    val subscriptionsStale: Boolean = false,
    /** Product ids whose payment is still pending; they are not unlocked. */
    val pendingProductIds: Set<String> = emptySet(),
    /**
     * Product ids owned at the end of the last run. Only a hint so the pack list does not flash
     * locks while [ready] is false; it never unlocks a download or an apply.
     */
    val cachedProductIds: Set<String> = emptySet(),
    /**
     * Packs a bundle purchase unlocked at the end of the last run. Display only, like
     * [cachedProductIds]: it never unlocks an apply.
     */
    val cachedBundlePackIds: Set<String> = emptySet(),
    /** True while purchases are being read from the store. */
    val checking: Boolean = false,
) {
    /**
     * True when pack [packId] is unlocked by a verified purchase (or by this build). A purchase
     * made under a former id of the pack ([ProductIds.allForPack]) still unlocks it.
     */
    fun unlocks(packId: String): Boolean = unlockAll || everything ||
        ProductIds.allForPack(packId).any { it in ownedProductIds } ||
        PackIdAliases.current(packId) in bundlePackIds

    /** [unlocks], or before [ready] the cached hint: for display only. */
    fun looksUnlocked(packId: String): Boolean = unlocks(packId) ||
        (!ready && (
            ProductIds.ALL_PACKS in cachedProductIds ||
                ProductIds.EVERYTHING in cachedProductIds ||
                ProductIds.allForPack(packId).any { it in cachedProductIds } ||
                PackIdAliases.current(packId) in cachedBundlePackIds
            ))

    /** True when [feature] may be used: a verified product of [PaidFeature.productIds], or [everything]. */
    fun unlocks(feature: PaidFeature): Boolean = everything || feature.productIds.any { it in ownedProductIds }

    /** [unlocks], or before [ready] the [cachedProductIds] hint: for display only. */
    fun looksUnlocked(feature: PaidFeature): Boolean = unlocks(feature) ||
        ((!ready || subscriptionsStale) && (ProductIds.EVERYTHING in cachedProductIds || feature.productIds.any { it in cachedProductIds }))

    fun isPending(feature: PaidFeature): Boolean =
        ProductIds.EVERYTHING in pendingProductIds || feature.productIds.any { it in pendingProductIds }

    /** A pending purchase of the pack itself, or of a product that will cover it (all packs, everything). */
    fun isPending(packId: String): Boolean = ProductIds.ALL_PACKS in pendingProductIds ||
        ProductIds.EVERYTHING in pendingProductIds || ProductIds.allForPack(packId).any { it in pendingProductIds }

    /**
     * The store's formatted price of pack [packId], or null while it is unknown: the current
     * product's price, else the price of a product under a former id of the pack.
     */
    fun priceOf(packId: String): String? = ProductIds.allForPack(packId).firstNotNullOfOrNull { prices[it] }
}

/**
 * A paid app feature (not a pack). [ProductIds.EVERYTHING] unlocks every entry, including entries
 * added later, so a new paid feature only needs an entry here and its gate in the UI.
 */
enum class PaidFeature(val productIds: List<String>) {
    /** "Describe your theme" and AI icon generation with the user's own AI key. */
    AI_CREATION(listOf(ProductIds.AI_MONTHLY, ProductIds.AI_LIFETIME)),
}

object ProductIds {
    /** One-time unlock of every pack, including packs added later. */
    const val ALL_PACKS = "all_packs"

    /** Monthly auto-renewing subscription (Play SUBS product) to [PaidFeature.AI_CREATION]. */
    const val AI_MONTHLY = "ai_monthly"

    /** One-time, forever unlock of [PaidFeature.AI_CREATION]. */
    const val AI_LIFETIME = "ai_lifetime"

    /**
     * One-time, forever unlock of everything: every pack and every [PaidFeature], including
     * packs and features added later.
     */
    const val EVERYTHING = "everything"

    /** The products sold as subscriptions; every other product is a one-time (INAPP) product. */
    val SUBSCRIPTIONS: Set<String> = setOf(AI_MONTHLY)

    /** The one-time products that are not packs. */
    val ONE_TIME_EXTRAS: List<String> = listOf(ALL_PACKS, AI_LIFETIME, EVERYTHING)

    private val disallowed = Regex("[^a-z0-9._]")

    /** The prefix of every per-pack product id ([forPack]). */
    const val PACK_PREFIX = "pack_"

    /** "pack_" + the pack id lowercased, with every character outside [a-z0-9._] replaced by '_'. */
    fun forPack(packId: String): String = PACK_PREFIX + packId.lowercase().replace(disallowed, "_")

    /**
     * Every product that unlocks pack [packId]: [forPack] of its current id first (what a new
     * purchase buys), then [forPack] of each former catalogue id ([PackIdAliases]), so a purchase
     * made before the pack was renamed keeps it unlocked.
     */
    fun allForPack(packId: String): List<String> =
        (listOf(forPack(packId)) + PackIdAliases.allIdsOf(packId).map(::forPack)).distinct()
}
