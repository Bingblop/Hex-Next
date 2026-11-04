package com.hexnext.themer.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaidFeatureTest {
    private val ready = EntitlementState(ready = true)

    @Test
    fun productIdsAreFixedBecausePlayCannotRenameThem() {
        assertEquals("ai_monthly", ProductIds.AI_MONTHLY)
        assertEquals("ai_lifetime", ProductIds.AI_LIFETIME)
        assertEquals("everything", ProductIds.EVERYTHING)
        assertEquals(setOf("ai_monthly"), ProductIds.SUBSCRIPTIONS)
        assertTrue(ProductIds.ONE_TIME_EXTRAS.containsAll(listOf("all_packs", "ai_lifetime", "everything")))
        assertFalse(ProductIds.ONE_TIME_EXTRAS.any { it in ProductIds.SUBSCRIPTIONS })
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
    }

    @Test
    fun subscriptionOrLifetimeUnlocksAiButNoPack() {
        for (id in listOf(ProductIds.AI_MONTHLY, ProductIds.AI_LIFETIME)) {
            val state = ready.copy(ownedProductIds = setOf(id))
            assertTrue(id, state.unlocks(PaidFeature.AI_CREATION))
            assertFalse(id, state.unlocks("willemite"))
        }
    }

    @Test
    fun everythingUnlocksEveryFeatureAndEveryPack() {
        val state = ready.copy(everything = true, ownedProductIds = setOf(ProductIds.EVERYTHING))
        PaidFeature.entries.forEach { assertTrue(it.name, state.unlocks(it)) }
        assertTrue(state.unlocks("a-pack-added-next-year"))
    }

    @Test
    fun cachedHintOnlyShowsBeforeTheStoreAnswered() {
        val hint = EntitlementState(cachedProductIds = setOf(ProductIds.AI_MONTHLY))
        assertTrue(hint.looksUnlocked(PaidFeature.AI_CREATION))
        assertFalse(hint.unlocks(PaidFeature.AI_CREATION))
        assertFalse(hint.copy(ready = true).looksUnlocked(PaidFeature.AI_CREATION))
    }

    @Test
    fun pendingPaymentIsReportedPerFeature() {
        val state = ready.copy(pendingProductIds = setOf(ProductIds.AI_LIFETIME))
        assertTrue(state.isPending(PaidFeature.AI_CREATION))
        assertFalse(state.unlocks(PaidFeature.AI_CREATION))
    }

    @Test
    fun staleSubscriptionsKeepTheHintAfterTheStoreAnswered() {
        val state = EntitlementState(ready = true, subscriptionsStale = true, cachedProductIds = setOf(ProductIds.AI_MONTHLY))
        assertTrue(state.looksUnlocked(PaidFeature.AI_CREATION))
        assertFalse(state.unlocks(PaidFeature.AI_CREATION))
    }

    @Test
    fun pendingEverythingOrAllPacksMarksEveryPackPending() {
        for (id in listOf(ProductIds.EVERYTHING, ProductIds.ALL_PACKS)) {
            assertTrue(id, ready.copy(pendingProductIds = setOf(id)).isPending("willemite"))
        }
        assertFalse(ready.copy(pendingProductIds = setOf(ProductIds.AI_MONTHLY)).isPending("willemite"))
    }
}
