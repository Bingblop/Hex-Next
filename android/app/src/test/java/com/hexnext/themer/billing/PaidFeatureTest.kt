package com.hexnext.themer.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaidFeatureTest {
    private val ready = EntitlementState(ready = true)

    @Test
    fun productIdsAreFixedBecausePlayCannotRenameThem() {
        assertEquals("ai_lifetime", ProductIds.AI_LIFETIME)
        assertEquals("all_packs", ProductIds.ALL_PACKS)
        assertEquals(listOf("all_packs", "ai_lifetime"), ProductIds.ONE_TIME_EXTRAS)
        assertEquals(ProductIds.AI_LIFETIME, PaidFeature.AI_CREATION.productId)
    }

    @Test
    fun aiIsLockedWithoutAPurchase() {
        assertFalse(ready.unlocks(PaidFeature.AI_CREATION))
        assertFalse(ready.copy(ownedProductIds = setOf(ProductIds.forPack("willemite"))).unlocks(PaidFeature.AI_CREATION))
    }

    @Test
    fun allPacksDoesNotUnlockAi() {
        val state = ready.copy(unlockAll = true, ownedProductIds = setOf(ProductIds.ALL_PACKS))
        assertFalse(state.unlocks(PaidFeature.AI_CREATION))
        assertTrue(state.unlocks("willemite"))
        assertTrue(state.unlocks("a-pack-added-next-year"))
    }

    @Test
    fun aiPurchaseUnlocksAiButNoPack() {
        val state = ready.copy(ownedProductIds = setOf(ProductIds.AI_LIFETIME))
        assertTrue(state.unlocks(PaidFeature.AI_CREATION))
        assertFalse(state.unlocks("willemite"))
    }

    @Test
    fun ownerBuildUnlocksEveryFeature() {
        val state = ready.copy(unlockAll = true, allFeatures = true)
        PaidFeature.entries.forEach { assertTrue(it.name, state.unlocks(it)) }
        assertTrue(state.unlocks("a-pack-added-next-year"))
    }

    @Test
    fun cachedHintOnlyShowsBeforeTheStoreAnswered() {
        val hint = EntitlementState(cachedProductIds = setOf(ProductIds.AI_LIFETIME))
        assertTrue(hint.looksUnlocked(PaidFeature.AI_CREATION))
        assertFalse(hint.unlocks(PaidFeature.AI_CREATION))
        assertFalse(hint.copy(ready = true).looksUnlocked(PaidFeature.AI_CREATION))
    }

    @Test
    fun pendingPaymentIsReportedPerFeature() {
        val state = ready.copy(pendingProductIds = setOf(ProductIds.AI_LIFETIME))
        assertTrue(state.isPending(PaidFeature.AI_CREATION))
        assertFalse(state.unlocks(PaidFeature.AI_CREATION))
        assertFalse(state.isPending("willemite"))
    }

    @Test
    fun pendingAllPacksMarksEveryPackPendingButNotAi() {
        val state = ready.copy(pendingProductIds = setOf(ProductIds.ALL_PACKS))
        assertTrue(state.isPending("willemite"))
        assertFalse(state.isPending(PaidFeature.AI_CREATION))
    }
}
