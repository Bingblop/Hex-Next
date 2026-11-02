package com.hexnext.themer.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.KeyStoreException as AndroidKeyStoreException
import java.security.KeyStore
import java.security.UnrecoverableEntryException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provider API keys, each encrypted with an Android Keystore AES-GCM key (alias
 * `hexnext_ai_keys`, no user authentication) and stored as `base64(iv):base64(ciphertext)` in
 * the `hexnext_ai_keys` preferences file. That file is excluded from backup and transfer by
 * `res/xml/data_extraction_rules.xml`.
 *
 * Keys are never logged or returned masked beyond [masked]; [get] is the only way to read one and
 * callers hand it straight to a provider. Every function blocks on Keystore work; call [get],
 * [set] and [clear] off the main thread. [configured] updates on every change so the UI can react
 * without decrypting anything.
 */
class AiKeyStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _configured = MutableStateFlow(readConfigured())

    /** Provider ids that currently have a key. */
    val configured: StateFlow<Set<String>> = _configured.asStateFlow()

    /** True when [providerId] has a stored key (no decryption). */
    fun has(providerId: String): Boolean = prefs.contains(providerId)

    /**
     * The plaintext key of [providerId], or null when none is stored or it can no longer be
     * decrypted (the entry is then removed). Throws [AiProviderException] when an entry exists but
     * the key store cannot read it right now (busy or briefly unavailable), so callers do not
     * report a stored key as missing.
     */
    fun get(providerId: String): String? {
        val stored = prefs.getString(providerId, null) ?: return null
        val sep = stored.indexOf(':')
        if (sep <= 0) return null
        return try {
            val iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP)
            val ciphertext = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            clearUnreadable(providerId)
        } catch (e: KeyPermanentlyInvalidatedException) {
            clearUnreadable(providerId)
        } catch (e: UnrecoverableKeyException) {
            clearUnreadable(providerId)
        } catch (e: IllegalArgumentException) {
            // Base64 that does not decode: the entry is corrupt.
            clearUnreadable(providerId)
        } catch (e: Exception) {
            // KeyStoreException, ProviderException, IllegalStateException and the like: the key
            // store is busy or briefly unavailable. Keep the entry so a later call can succeed.
            throw AiProviderException(UNAVAILABLE_READ, e)
        }
    }

    /** The Keystore key was invalidated or the entry is corrupt: the key is gone either way. */
    private fun clearUnreadable(providerId: String): String? {
        clear(providerId)
        return null
    }

    /**
     * Encrypts and stores [apiKey] for [providerId]; a blank key clears the entry instead. Throws
     * [AiProviderException] when the key store fails; nothing is stored then.
     */
    fun set(providerId: String, apiKey: String) {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            clear(providerId)
            return
        }
        val stored = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw AiProviderException(UNAVAILABLE_SAVE, e)
        }
        prefs.edit().putString(providerId, stored).apply()
        _configured.value = readConfigured()
    }

    /** Removes the key of [providerId]. */
    fun clear(providerId: String) {
        prefs.edit().remove(providerId).apply()
        _configured.value = readConfigured()
    }

    /**
     * A display form of the stored key: its first four and last four characters around an ellipsis
     * ("sk-a…9f3Q"), or null when none is stored. Throws [AiProviderException] like [get].
     */
    fun masked(providerId: String): String? {
        val key = get(providerId) ?: return null
        return mask(key)
    }

    private fun readConfigured(): Set<String> = prefs.all.keys.toSet()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = try {
            keyStore.getEntry(KEY_ALIAS, null)
        } catch (e: UnrecoverableEntryException) {
            // keystore2 wraps every lookup failure in UnrecoverableKeyException, including
            // transient ones (SYSTEM_ERROR, a busy backend). Deleting the alias then would make
            // every saved key undecryptable, so only drop it when the cause says the key is gone
            // for good; anything else is rethrown as a plain KeyStoreException, which [get] and
            // [set] report as "unavailable" without touching the stored entries.
            if (!isPermanentKeyLoss(e)) throw java.security.KeyStoreException("Keystore lookup failed", e)
            // The alias itself is corrupt and will never load again: drop it and start over.
            // Entries encrypted with it then fail their tag check and are cleared by [get].
            keyStore.deleteEntry(KEY_ALIAS)
            null
        }
        (entry as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * True when the cause chain of [e] shows the alias can never load again: the key was
     * permanently invalidated or keystore2 reports it corrupted. Transient and unknown causes
     * return false so the alias, and with it every stored provider key, is kept.
     */
    private fun isPermanentKeyLoss(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is KeyPermanentlyInvalidatedException) return true
            if (cause is AndroidKeyStoreException) {
                return cause.numericErrorCode == AndroidKeyStoreException.ERROR_KEY_CORRUPTED && !cause.isTransientFailure
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    companion object {
        /** Preferences file name (`hexnext_ai_keys.xml`); referenced by the data extraction rules. */
        const val PREFS_NAME = "hexnext_ai_keys"
        private const val KEY_ALIAS = "hexnext_ai_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val UNAVAILABLE_READ = "The saved key can't be read right now (the phone's key store is busy). Try again in a moment."
        private const val UNAVAILABLE_SAVE = "The key couldn't be saved (the phone's key store is busy or unavailable). Try again in a moment."

        /** "sk-a…9f3Q" for keys of 12+ characters, "••••" otherwise. */
        fun mask(key: String): String =
            if (key.length >= 12) key.take(4) + "…" + key.takeLast(4) else "••••"
    }
}
