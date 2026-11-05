package com.hexnext.themer.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleGrantTest {
    @Test
    fun mineralIdsRoundTripAndAFormerIdIsStoredAsItsCurrentName() {
        val fields = BundleGrant.fields(listOf("willemite", "velvet-rope", "ametrine"))
        assertTrue(fields.profile.length <= BundleGrant.MAX_FIELD)
        assertTrue(fields.account.length <= BundleGrant.MAX_FIELD)
        assertEquals(setOf("ametrine", "iolite", "willemite"), BundleGrant.decode(fields.profile, fields.account))
    }

    @Test
    fun nineLongNamesFitInTheTwoPlayFields() {
        val ids = listOf(
            "chrysocolla", "cordierite", "erythrite", "indicolite", "kornerupine",
            "lepidolite", "scolecite", "willemite", "zoisite",
        )
        val fields = BundleGrant.fields(ids)
        assertTrue(fields.profile.length <= BundleGrant.MAX_FIELD)
        assertTrue(fields.account.length <= BundleGrant.MAX_FIELD)
        assertEquals(ids.toSet(), BundleGrant.decode(fields.profile, fields.account))
    }

    @Test
    fun aBundleUnlocksOnlyAsManyPacksAsItWasSoldFor() {
        val ids = setOf("zoisite", "iolite", "agate", "kyanite")
        assertEquals(setOf("agate", "iolite"), BundleGrant.limit(ids, 2))
        assertEquals(setOf("agate", "iolite", "kyanite"), BundleGrant.choose(setOf("iolite"), ids, 3))
    }

    @Test
    fun nineteenPacksRoundTripWhenTheNamesDoNotFit() {
        val ids = listOf(
            "ametrine", "carnelian", "cerussite", "charoite", "chrysocolla", "cordierite",
            "covellite", "diopside", "erythrite", "fayalite", "indicolite", "kornerupine",
            "lepidolite", "morganite", "pectolite", "scolecite", "variscite", "wavellite", "willemite",
        )
        val fields = BundleGrant.fields(ids)
        assertTrue(fields.profile.startsWith("~"))
        assertTrue(fields.profile.length <= BundleGrant.MAX_FIELD)
        assertTrue(fields.account.length <= BundleGrant.MAX_FIELD)
        assertEquals(ids.toSet(), BundleGrant.decode(fields.profile, fields.account))
    }

    @Test
    fun theBitOrderCoversEveryCataloguePackAndStaysAppendOnly() {
        assertEquals(PaidPacks.IDS, BundleGrant.ORDER.toSet())
        assertEquals(BundleGrant.ORDER.distinct(), BundleGrant.ORDER)
        assertEquals("agate", BundleGrant.ORDER.first())
        assertEquals("zoisite", BundleGrant.ORDER.last())
    }

    @Test
    fun oddTokensAreIgnored() {
        assertEquals(setOf("iolite"), BundleGrant.decode("iolite,not a pack", " "))
        assertEquals(emptySet<String>(), BundleGrant.limit(emptyList(), 2))
    }
}
