package com.hexnext.themer.billing

import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.pack.PackIdAliases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packs renamed in the catalogue keep their purchases, stay paid and stay locked under old ids. */
class LegacyPackIdsTest {
    private val noOfficial = emptySet<String>()

    @Test
    fun aPurchaseUnderTheFormerIdUnlocksTheCurrentId() {
        val bought = EntitlementState(ready = true, ownedProductIds = setOf("pack_velvet_rope"))
        assertTrue(bought.unlocks("iolite"))
        assertTrue(bought.unlocks("velvet-rope"))
        assertFalse(bought.unlocks("charoite"))
        assertTrue(PackAccess.isUnlocked(bought, "iolite", emptySet(), noOfficial))
        assertTrue(PackAccess.isUnlocked(bought, "velvet-rope", emptySet(), noOfficial))
        // A purchase under the current id unlocks a zip that still carries the former id.
        val boughtNew = EntitlementState(ready = true, ownedProductIds = setOf("pack_iolite"))
        assertTrue(PackAccess.isUnlocked(boughtNew, "velvet-rope", emptySet(), noOfficial))
    }

    @Test
    fun newPurchasesUseTheCurrentProductAndLegacyProductsAreQueriedToo() {
        assertEquals("pack_iolite", ProductIds.forPack("iolite"))
        assertEquals(listOf("pack_iolite", "pack_velvet_rope"), ProductIds.allForPack("iolite"))
        assertEquals(listOf("pack_dusk_and_dawn", "pack_olivine"), ProductIds.allForPack("dusk-and-dawn"))
        assertEquals(listOf("pack_my_own"), ProductIds.allForPack("my-own"))
    }

    @Test
    fun pendingHintAndPriceFollowTheLegacyProduct() {
        val pending = EntitlementState(ready = true, pendingProductIds = setOf("pack_particle_zoo"))
        assertTrue(pending.isPending("willemite"))
        assertFalse(pending.unlocks("willemite"))
        val starting = EntitlementState(ready = false, cachedProductIds = setOf("pack_particle_zoo"))
        assertTrue(starting.looksUnlocked("willemite"))
        assertFalse(starting.unlocks("willemite"))
        val legacyPriceOnly = EntitlementState(prices = mapOf("pack_particle_zoo" to "€1.09"))
        assertEquals("€1.09", legacyPriceOnly.priceOf("willemite"))
        val both = EntitlementState(prices = mapOf("pack_particle_zoo" to "€1.09", "pack_willemite" to "€0.99"))
        assertEquals("€0.99", both.priceOf("willemite"))
    }

    @Test
    fun aFormerIdIsPaid() {
        for (former in PackIdAliases.FORMER_TO_CURRENT.keys) {
            assertTrue(former, PaidPacks.isPaid(former))
            assertTrue(former, PackAccess.isPaid(former, noOfficial))
            assertFalse(former, former in PaidPacks.IDS)
        }
        assertFalse(PackAccess.isPaid("my-own-pack", noOfficial))
        // An id seen in a fetched catalogue is paid.
        assertTrue(PackAccess.isPaid("not-shipped-yet", setOf("not-shipped-yet")))
    }

    @Test
    fun aSpecWithAFormerIdIsLockedUnlessEntitled() {
        val spec = ThemeSpec.default().copy(mode = ThemeMode.SIMPLE, simplePackId = "velvet-rope")
        val nothing = EntitlementState(ready = true)
        assertEquals(setOf("velvet-rope"), PackAccess.lockedPacksIn(spec) { PackAccess.isUnlocked(nothing, it, emptySet(), noOfficial) })
        val legacy = EntitlementState(ready = true, ownedProductIds = setOf("pack_velvet_rope"))
        assertEquals(emptySet<String>(), PackAccess.lockedPacksIn(spec) { PackAccess.isUnlocked(legacy, it, emptySet(), noOfficial) })
        val current = EntitlementState(ready = true, ownedProductIds = setOf("pack_iolite"))
        assertEquals(emptySet<String>(), PackAccess.lockedPacksIn(spec) { PackAccess.isUnlocked(current, it, emptySet(), noOfficial) })
        val other = EntitlementState(ready = true, ownedProductIds = setOf("pack_charoite"))
        assertEquals(setOf("velvet-rope"), PackAccess.lockedPacksIn(spec) { PackAccess.isUnlocked(other, it, emptySet(), noOfficial) })
        // A theme file saved with the former id loads with the current id, and is locked the same way.
        val loaded = ThemeSpec.fromJson(spec.toJson())
        assertEquals("iolite", loaded.simplePackId)
        assertEquals(setOf("iolite"), PackAccess.lockedPacksIn(loaded) { PackAccess.isUnlocked(nothing, it, emptySet(), noOfficial) })
        assertEquals(emptySet<String>(), PackAccess.lockedPacksIn(loaded) { PackAccess.isUnlocked(legacy, it, emptySet(), noOfficial) })
    }

    @Test
    fun unlockAllStillUnlocksEverything() {
        val all = EntitlementState(ready = true, unlockAll = true)
        assertTrue(PackAccess.isUnlocked(all, "velvet-rope", emptySet(), noOfficial))
        assertTrue(PackAccess.isUnlocked(all, "iolite", emptySet(), noOfficial))
    }
}
