package com.example.gcodusadmin

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("admin_secret", Context.MODE_PRIVATE)
    private val alias = "gcodus_admin_token"

    private fun key() = run {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
             .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
             .build())
            generator.generateKey()
        }
        ks.getKey(alias, null)
    }

    fun get(): String? = try {
        val ivText = prefs.getString("iv", null) ?: return null
        val payloadText = prefs.getString("payload", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivText, Base64.DEFAULT)))
        String(cipher.doFinal(Base64.decode(payloadText, Base64.DEFAULT)), Charsets.UTF_8)
    } catch (_: Exception) { null }

    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        prefs.edit().putString("iv", iv).putString("payload", payload).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}