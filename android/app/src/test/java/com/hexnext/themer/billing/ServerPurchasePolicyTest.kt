package com.hexnext.themer.billing

import org.junit.Assert.*
import org.junit.Test

class ServerPurchasePolicyTest {
    @Test fun backendMustBeHttpsWithoutEmbeddedCredentialsOrQuery() {
        assertTrue(ServerPurchasePolicy.isConfigured("https://billing.example.test/v1/verify"))
        for (url in listOf("", "http://billing.example.test", "https://", "file:///tmp/license",
            "https://user:secret@billing.example.test", "https://billing.example.test?key=x",
            "https://billing.example.test#fragment")) {
            assertFalse(url, ServerPurchasePolicy.isConfigured(url))
        }
    }

    @Test fun missingBackendCannotUnlockEvenIfAnAnswerWasForgedLocally() {
        assertTrue(ServerPurchasePolicy.allowedProducts(false, true, listOf("pack_agate"),
            mapOf("pack_agate" to true)).isEmpty())
    }

    @Test fun mismatchedPackageAndUnknownEvidenceNeverGrant() {
        assertTrue(ServerPurchasePolicy.allowedProducts(true, false, listOf("pack_agate"),
            mapOf("pack_agate" to true)).isEmpty())
        for (answers in listOf(emptyMap(), mapOf("pack_agate" to null), mapOf("pack_agate" to false))) {
            assertTrue(ServerPurchasePolicy.allowedProducts(true, true, listOf("pack_agate"), answers).isEmpty())
        }
    }

    @Test fun completeServerEvidenceGrantsOnlyItsProducts() {
        assertEquals(setOf("pack_agate"), ServerPurchasePolicy.allowedProducts(
            true, true, listOf("pack_agate", "all_packs"), mapOf("pack_agate" to true, "all_packs" to false)))
    }

    @Test fun partialUnknownEvidenceDoesNotUpgradeIntoABroaderGrant() {
        assertTrue(ServerPurchasePolicy.allowedProducts(true, true, listOf("pack_agate", "all_packs"),
            mapOf("pack_agate" to true)).isEmpty())
    }
}
