package com.marketplace.android.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the short-lived public-client access token encrypted with an Android
 * Keystore AES/GCM key. No client secret is present on the device. The server
 * does not issue refresh tokens for this OAuth public client.
 */
class SecureTokenStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("dayf_secure_session", Context.MODE_PRIVATE)

    @Synchronized
    fun save(accessToken: String, expiresAtMillis: Long) {
        val plaintext = "$accessToken\n$expiresAtMillis".toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val packed = cipher.iv + ciphertext
        preferences.edit().putString(TOKEN_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun readValidToken(): String? {
        val encoded = preferences.getString(TOKEN_KEY, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val plaintext = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            val separator = plaintext.lastIndexOf('\n')
            require(separator > 0)
            val token = plaintext.substring(0, separator)
            val expiry = plaintext.substring(separator + 1).toLong()
            if (token.isBlank() || expiry <= System.currentTimeMillis() + EXPIRY_SAFETY_MILLIS) {
                clear()
                null
            } else {
                token
            }
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(TOKEN_KEY).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dayf-oauth-access-token-v1"
        private const val TOKEN_KEY = "encrypted_access_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val EXPIRY_SAFETY_MILLIS = 30_000L
    }
}
