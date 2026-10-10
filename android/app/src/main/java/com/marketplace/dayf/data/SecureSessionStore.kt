package com.marketplace.dayf.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.openid.appauth.AuthState
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the AppAuth state encrypted with a device-held Android Keystore key.
 * This is a public OAuth client: no client secret is stored in the APK.
 */
class SecureSessionStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Volatile
    private var cachedState: AuthState? = readEncryptedState()

    @Synchronized
    fun save(state: AuthState) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(state.jsonSerializeString().toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit()
            .putString(STATE_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        cachedState = state
    }

    fun authState(): AuthState? = cachedState

    fun accessToken(): String? {
        val state = cachedState ?: return null
        val token = state.accessToken ?: return null
        val expiresAt = state.accessTokenExpirationTime ?: return null
        return token.takeIf { expiresAt > System.currentTimeMillis() + TOKEN_SAFETY_WINDOW_MS }
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(STATE_KEY).commit()
        cachedState = null
    }

    private fun readEncryptedState(): AuthState? = try {
        val encoded = preferences.getString(STATE_KEY, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_LENGTH)
        val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val json = cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
        AuthState.jsonDeserialize(json)
    } catch (_: Exception) {
        preferences.edit().remove(STATE_KEY).commit()
        null
    }

    private fun encryptionKey(): SecretKey {
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

    private companion object {
        const val PREFERENCES = "dayf_secure_session"
        const val STATE_KEY = "encrypted_auth_state"
        const val KEY_ALIAS = "dayf.oauth.auth-state.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val TOKEN_SAFETY_WINDOW_MS = 30_000L
    }
}
