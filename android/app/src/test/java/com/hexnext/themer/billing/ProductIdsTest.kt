package com.hexnext.themer.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductIdsTest {
    @Test
    fun packIdIsLowercasedAndSanitised() {
        assertEquals("pack_blue_moon", ProductIds.forPack("Blue Moon"))
        assertEquals("pack_some_pack", ProductIds.forPack("some-pack"))
        assertEquals("pack_gem.stone_2", ProductIds.forPack("Gem.Stone_2"))
    }

    @Test
    fun everyCharacterOutsideTheAllowedSetBecomesAnUnderscore() {
        assertEquals("pack_a_b_c_d", ProductIds.forPack("a/b\\c@d"))
        assertEquals("pack____", ProductIds.forPack("é ü"))
        assertEquals("pack_", ProductIds.forPack(""))
        val id = ProductIds.forPack("Some Pack! (v2) – ünïcode")
        assertTrue(id, id.matches(Regex("pack_[a-z0-9._]*")))
    }

    @Test
    fun allPacksIdIsFixed() {
        assertEquals("all_packs", ProductIds.ALL_PACKS)
    }

    @Test
    fun stateUnlocksByProductIdOrUnlockAll() {
        val owned = EntitlementState(ready = true, ownedProductIds = setOf("pack_blue_moon"))
        assertTrue(owned.unlocks("Blue Moon"))
        assertFalse(owned.unlocks("some-pack"))
        assertTrue(EntitlementState(ready = true, unlockAll = true).unlocks("anything"))
        assertFalse(EntitlementState().unlocks("blue_moon"))
    }

    @Test
    fun pendingPurchaseDoesNotUnlock() {
        val state = EntitlementState(ready = true, pendingProductIds = setOf("pack_opal"))
        assertTrue(state.isPending("opal"))
        assertFalse(state.unlocks("opal"))
        assertFalse(state.looksUnlocked("opal"))
    }

    @Test
    fun pendingAllPacksMarksEveryPackPending() {
        val state = EntitlementState(ready = true, pendingProductIds = setOf(ProductIds.ALL_PACKS))
        assertTrue(state.isPending("opal"))
        assertTrue(state.isPending("a-pack-added-later"))
        assertFalse(state.unlocks("opal"))
    }

    @Test
    fun cachedHintOnlyCountsForDisplayBeforeTheStoreAnswers() {
        val starting = EntitlementState(ready = false, cachedProductIds = setOf("pack_opal"))
        assertTrue(starting.looksUnlocked("opal"))
        assertFalse(starting.unlocks("opal"))
        // Once purchases were read, a hint without a verified purchase (a refund) no longer counts.
        assertFalse(starting.copy(ready = true).looksUnlocked("opal"))
        assertTrue(EntitlementState(cachedProductIds = setOf(ProductIds.ALL_PACKS)).looksUnlocked("opal"))
    }

    @Test
    fun priceComesFromTheStoreMap() {
        val state = EntitlementState(prices = mapOf("pack_opal" to "€1.09"))
        assertEquals("€1.09", state.priceOf("Opal"))
        assertEquals(null, state.priceOf("other"))
    }
}
