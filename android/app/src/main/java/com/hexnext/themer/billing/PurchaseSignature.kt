package com.hexnext.themer.billing

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Checks a Play purchase signature: SHA1withRSA over the purchase's original JSON, against the
 * app's base64 X.509 RSA public key from Play Console (Monetization setup > Licensing).
 *
 * Pure JVM, so it is unit-tested; the Play build calls it when no license server is configured.
 */
object PurchaseSignature {
    private const val KEY_ALGORITHM = "RSA"
    private const val SIGNATURE_ALGORITHM = "SHA1withRSA"

    /** Parses [base64PublicKey]; null when it is empty or not an X.509 RSA public key. */
    fun publicKey(base64PublicKey: String): PublicKey? {
        val trimmed = base64PublicKey.filterNot { it.isWhitespace() }
        if (trimmed.isEmpty()) return null
        return try {
            val bytes = Base64.getDecoder().decode(trimmed)
            KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** True only when [base64Signature] is a valid signature of [signedData] by [key]. */
    fun verify(key: PublicKey, signedData: String, base64Signature: String): Boolean {
        if (signedData.isEmpty() || base64Signature.isBlank()) return false
        return try {
            val signature = Base64.getDecoder().decode(base64Signature.trim())
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(signedData.toByteArray(Charsets.UTF_8))
                verify(signature)
            }
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    /** [verify] with the key given as base64; false when the key cannot be parsed. */
    fun verify(base64PublicKey: String, signedData: String, base64Signature: String): Boolean {
        val key = publicKey(base64PublicKey) ?: return false
        return verify(key, signedData, base64Signature)
    }
}
