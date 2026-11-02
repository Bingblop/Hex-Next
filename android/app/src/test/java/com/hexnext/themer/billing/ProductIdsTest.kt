package com.hexnext.themer.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductIdsTest {
    @Test
    fun packIdIsLowercasedAndSanitised() {
        assertEquals("pack_velvet_rope", ProductIds.forPack("Velvet Rope"))
        assertEquals("pack_particle_zoo", ProductIds.forPack("particle-zoo"))
        assertEquals("pack_fire.fly_2", ProductIds.forPack("Fire.Fly_2"))
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
        val owned = EntitlementState(ready = true, ownedProductIds = setOf("pack_velvet_rope"))
        assertTrue(owned.unlocks("Velvet Rope"))
        assertFalse(owned.unlocks("particle-zoo"))
        assertTrue(EntitlementState(ready = true, unlockAll = true).unlocks("anything"))
        assertFalse(EntitlementState().unlocks("velvet_rope"))
    }

    @Test
    fun pendingPurchaseDoesNotUnlock() {
        val state = EntitlementState(ready = true, pendingProductIds = setOf("pack_zoo"))
        assertTrue(state.isPending("zoo"))
        assertFalse(state.unlocks("zoo"))
        assertFalse(state.looksUnlocked("zoo"))
    }

    @Test
    fun cachedHintOnlyCountsForDisplayBeforeTheStoreAnswers() {
        val starting = EntitlementState(ready = false, cachedProductIds = setOf("pack_zoo"))
        assertTrue(starting.looksUnlocked("zoo"))
        assertFalse(starting.unlocks("zoo"))
        // Once purchases were read, a hint without a verified purchase (a refund) no longer counts.
        assertFalse(starting.copy(ready = true).looksUnlocked("zoo"))
        assertTrue(EntitlementState(cachedProductIds = setOf(ProductIds.ALL_PACKS)).looksUnlocked("zoo"))
    }

    @Test
    fun priceComesFromTheStoreMap() {
        val state = EntitlementState(prices = mapOf("pack_zoo" to "€1.09"))
        assertEquals("€1.09", state.priceOf("Zoo"))
        assertEquals(null, state.priceOf("other"))
    }
}
