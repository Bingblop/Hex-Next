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
    fun oddTokensAreIgnored() {
        assertEquals(setOf("iolite"), BundleGrant.decode("iolite,not a pack", " "))
        assertEquals(emptySet<String>(), BundleGrant.limit(emptyList(), 2))
    }
}
