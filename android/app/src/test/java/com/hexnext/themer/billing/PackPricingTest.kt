package com.hexnext.themer.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackPricingTest {
    @Test
    fun onePackIsNinetyNineCentsAndEachFurtherPackAddsADollar() {
        assertEquals(99, PackPricing.cents(1))
        assertEquals(199, PackPricing.cents(2))
        assertEquals(299, PackPricing.cents(3))
        assertEquals("$0.99", PackPricing.label(1))
        assertEquals("$1.99", PackPricing.label(2))
        assertEquals("$2.99", PackPricing.label(3))
        assertEquals("$19.99", PackPricing.ALL_LABEL)
    }

    @Test
    fun twentyPacksCostTheSameAsUnlockingEveryPack() {
        assertEquals("$18.99", PackPricing.label(19))
        assertEquals(1999, PackPricing.cents(PackPricing.ALL_FROM))
        assertEquals(
            PackPricing.ALL_LABEL,
            PackPricing.labelForOffer(PackPricing.ALL_FROM, emptyMap(), null),
        )
    }

    @Test
    fun bundleProductsAreNamedByCountAndSlot() {
        assertEquals(listOf("packs_2", "packs_2_2", "packs_2_3", "packs_2_4"), PackPricing.productIds(2))
        assertEquals(2, PackPricing.countOf("packs_2"))
        assertEquals(3, PackPricing.countOf("packs_3_4"))
        assertNull(PackPricing.countOf("pack_iolite"))
        assertEquals(10, PackPricing.countOf("packs_10"))
        assertEquals(19, PackPricing.countOf("packs_19"))
        assertNull(PackPricing.countOf("packs_20"))
        assertNull(PackPricing.countOf("packs_2_5"))
    }

    @Test
    fun theNextFreeSlotSkipsProductsAlreadyOwned() {
        assertEquals("packs_2", PackPricing.freeSlot(2, emptySet()))
        assertEquals("packs_2_2", PackPricing.freeSlot(2, setOf("packs_2")))
        assertNull(PackPricing.freeSlot(2, PackPricing.productIds(2).toSet()))
    }

    @Test
    fun theShownPricePrefersTheStoreAndFallsBackToTheCataloguePrice() {
        assertEquals("$0.99", PackPricing.labelForOffer(1, emptyMap(), "iolite"))
        assertEquals("€1.09", PackPricing.labelForOffer(1, mapOf("pack_iolite" to "€1.09"), "iolite"))
        assertEquals("$1.99", PackPricing.labelForOffer(2, emptyMap(), null))
        assertEquals("€1.99", PackPricing.labelForOffer(2, mapOf("packs_2" to "€1.99"), null))
        assertTrue(PackPricing.allProductIds().containsAll(PackPricing.productIds(9)))
    }
}
