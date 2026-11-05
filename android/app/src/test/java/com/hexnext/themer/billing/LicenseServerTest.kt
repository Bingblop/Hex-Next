package com.hexnext.themer.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseServerTest {
    @Test
    fun endpointAcceptsTheBaseUrlOrEitherFullEndpoint() {
        val batch = "https://licence.example.test/v1/verify-batch"
        val single = "https://licence.example.test/v1/verify"
        for (configured in listOf(
            "https://licence.example.test",
            "https://licence.example.test/",
            " https://licence.example.test/v1/verify ",
            "https://licence.example.test/v1/verify/",
            "https://licence.example.test/v1/verify-batch",
        )) {
            assertEquals(configured, batch, LicenseServer.endpoint(configured, LicenseServer.VERIFY_BATCH_PATH))
            assertEquals(configured, single, LicenseServer.endpoint(configured, LicenseServer.VERIFY_PATH))
        }
    }

    @Test
    fun retryAfterSecondsAreHonouredWithinBounds() {
        assertEquals(7_000L, LicenseServer.retryWaitMs("7", attempt = 0))
        assertEquals(7_000L, LicenseServer.retryWaitMs(" 7 ", attempt = 2))
        assertEquals(1_000L, LicenseServer.retryWaitMs("0", attempt = 0))
        assertEquals(LicenseServer.MAX_RETRY_WAIT_MS, LicenseServer.retryWaitMs("3600", attempt = 0))
        assertEquals(LicenseServer.MAX_RETRY_WAIT_MS, LicenseServer.retryWaitMs("86400", attempt = 0))
    }

    @Test
    fun withoutRetryAfterTheWaitBacksOffExponentially() {
        assertEquals(2_000L, LicenseServer.retryWaitMs(null, attempt = 0))
        assertEquals(4_000L, LicenseServer.retryWaitMs(null, attempt = 1))
        assertEquals(8_000L, LicenseServer.retryWaitMs("Wed, 21 Oct 2026 07:28:00 GMT", attempt = 2))
        assertEquals(LicenseServer.MAX_RETRY_WAIT_MS, LicenseServer.retryWaitMs(null, attempt = 40))
        assertEquals(2_000L, LicenseServer.retryWaitMs("-5", attempt = 0))
    }

    @Test
    fun batchItemsMapToYesNoOrUnknown() {
        assertTrue(LicenseServer.itemVerdict(true, "pack_neon", null, "pack_neon")!!)
        assertFalse(LicenseServer.itemVerdict(true, "pack_other", null, "pack_neon")!!)
        assertFalse(LicenseServer.itemVerdict(false, "pack_neon", "purchase_not_found", "pack_neon")!!)
        assertNull(LicenseServer.itemVerdict(false, "pack_neon", "provider_unavailable", "pack_neon"))
        assertNull(LicenseServer.itemVerdict(false, "pack_neon", "internal_error", "pack_neon"))
    }

    @Test
    fun aSingleVerifyBodyThatIsNotJsonIsUnknown() {
        assertNull(LicenseServer.bodyVerdict("", "pack_neon"))
        assertNull(LicenseServer.bodyVerdict("not json", "pack_neon"))
        assertNull(LicenseServer.bodyVerdict("[]", "pack_neon"))
        assertNull(LicenseServer.bodyVerdict("{}", "pack_neon"))
        assertNull(LicenseServer.bodyVerdict("""{"valid":false,"reason":"internal_error","productId":"pack_neon"}""", "pack_neon"))
    }

    @Test
    fun aSingleVerifyBodyMapsTheDocumentedAnswers() {
        assertEquals(true, LicenseServer.bodyVerdict("""{"valid":true,"productId":"pack_neon","orderId":"GPA.1"}""", "pack_neon"))
        assertEquals(false, LicenseServer.bodyVerdict("""{"valid":true,"productId":"pack_other"}""", "pack_neon"))
        assertEquals(false, LicenseServer.bodyVerdict("""{"valid":false,"reason":"purchase_not_found","productId":"pack_neon"}""", "pack_neon"))
        assertEquals(false, LicenseServer.bodyVerdict("""{"valid":false,"reason":"purchase_cancelled","productId":"pack_neon"}""", "pack_neon"))
    }

    @Test
    fun garbledBodiesAreUnknownEvenWithRecognizableFields() {
        val bad = listOf(
            """{"valid":true,"productId":"pack_neon", garbage}""",
            """{"valid":true,"productId":"pack_neon"} trailing""",
            """{"valid":true,"productId":"pack_neon",}""",
            """{"valid":true "productId":"pack_neon"}""",
            """{"valid":"true","productId":"pack_neon"}""",
            """{"valid":true,"valid":false,"productId":"pack_neon"}""",
            """{"valid":true,"productId":"pack_neon","x":{"y":1}}""",
            """{"valid":true,"productId":"pack_neon","x":[1]}""",
            """{"valid":true,"productId":"pack_\qneon"}""",
            """{"valid":truex,"productId":"pack_neon"}""",
            """{"note":"\"valid\":true","productId":"pack_neon"}""",
        )
        for (body in bad) assertNull(body, LicenseServer.bodyVerdict(body, "pack_neon"))
    }

    @Test
    fun wellFormedBodiesWithEscapesAndNumbersParse() {
        assertEquals(true, LicenseServer.bodyVerdict(" { \"valid\" : true , \"productId\" : \"pack_\\u006eeon\", \"n\": -1.5e3, \"z\": null } ", "pack_neon"))
    }

    @Test
    fun repliesMissingWhatTheirVerdictNeedsAreUnknown() {
        for (body in listOf(
            """{"valid":false}""",
            """{"valid":true}""",
            """{"valid":true,"productId":123}""",
            """{"valid":false,"reason":7}""",
            """{"valid":true,"productId":"pack_\u+06eeon"}""",
        )) assertNull(body, LicenseServer.bodyVerdict(body, "pack_neon"))
    }
}
