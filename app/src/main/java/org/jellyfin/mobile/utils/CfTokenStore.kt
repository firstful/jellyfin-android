package org.jellyfin.mobile.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the minted Cloudflare Access service-token pair (client_id / client_secret)
 * encrypted at rest with an Android Keystore AES key. The Keystore key is
 * non-exportable and the ciphertext lives in private SharedPreferences — a dump
 * of the APK or app data yields nothing usable without the device's Keystore.
 *
 * Runtime-minting standard (app-secret-minting.md): the APK contains NO token at
 * build time. On first run at home the app calls mint.homelab.internal (LAN-only)
 * and stores the returned per-device token here.
 */
class CfTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("cf_token_store", Context.MODE_PRIVATE)
    private val keyAlias = "cf_token_aes"

    fun store(clientId: String, clientSecret: String) {
        val json = "{\"clientId\":\"$clientId\",\"clientSecret\":\"$clientSecret\"}"
        prefs.edit().putString("token", encrypt(json)).apply()
    }

    fun load(): Pair<String, String>? {
        val enc = prefs.getString("token", null) ?: return null
        return try {
            val json = decrypt(enc)
            val clientId = Regex("\"clientId\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
            val clientSecret = Regex("\"clientSecret\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
            clientId to clientSecret
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove("token").apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(ciphertext, 0, blob, iv.size, ciphertext.size)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = blob.copyOfRange(0, 12)
        val ciphertext = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
