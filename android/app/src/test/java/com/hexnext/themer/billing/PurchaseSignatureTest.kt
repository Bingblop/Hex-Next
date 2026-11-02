package com.hexnext.themer.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class PurchaseSignatureTest {
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey: String = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val json = """{"orderId":"GPA.0000-0000-0000-00000","packageName":"com.hexnext.themer","productId":"pack_zoo","purchaseState":0}"""

    private fun sign(data: String, pair: KeyPair = keys): String {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(pair.private)
        signer.update(data.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    @Test
    fun validSignatureVerifies() {
        assertTrue(PurchaseSignature.verify(publicKey, json, sign(json)))
    }

    @Test
    fun keyWithLineBreaksStillParses() {
        val wrapped = publicKey.chunked(64).joinToString("\n")
        assertNotNull(PurchaseSignature.publicKey(wrapped))
        assertTrue(PurchaseSignature.verify(wrapped, json, sign(json)))
    }

    @Test
    fun tamperedDataFails() {
        val signature = sign(json)
        assertFalse(PurchaseSignature.verify(publicKey, json.replace("pack_zoo", "all_packs"), signature))
    }

    @Test
    fun signatureFromAnotherKeyFails() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(PurchaseSignature.verify(publicKey, json, sign(json, other)))
    }

    @Test
    fun emptyOrMalformedInputFails() {
        assertNull(PurchaseSignature.publicKey(""))
        assertNull(PurchaseSignature.publicKey("not base64 !!"))
        assertNull(PurchaseSignature.publicKey(Base64.getEncoder().encodeToString("not a key".toByteArray())))
        assertFalse(PurchaseSignature.verify("", json, sign(json)))
        assertFalse(PurchaseSignature.verify(publicKey, json, ""))
        assertFalse(PurchaseSignature.verify(publicKey, json, "%%%"))
        assertFalse(PurchaseSignature.verify(publicKey, "", sign("")))
    }
}
