package ru.pubchat.app.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("pubchat_session", Context.MODE_PRIVATE)

    val deviceUid: String
        get() = prefs.getString(KEY_DEVICE_UID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_UID, it).apply()
        }

    fun accessToken(): String? = decrypt(prefs.getString(KEY_ACCESS_TOKEN, null))

    fun refreshCookie(): String? = decrypt(prefs.getString(KEY_REFRESH_COOKIE, null))

    fun saveSession(accessToken: String, refreshCookie: String?) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, encrypt(accessToken))
            .apply {
                if (refreshCookie.isNullOrBlank()) remove(KEY_REFRESH_COOKIE)
                else putString(KEY_REFRESH_COOKIE, encrypt(refreshCookie))
            }
            .apply()
    }

    fun updateAccessToken(accessToken: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, encrypt(accessToken)).apply()
    }

    fun clearSession() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_COOKIE).apply()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$ciphertext"
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val parts = value.split(':', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val KEY_ALIAS = "pubchat_session_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_DEVICE_UID = "device_uid"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_COOKIE = "refresh_cookie"
    }
}
