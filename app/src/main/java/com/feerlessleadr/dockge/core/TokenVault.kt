package com.feerlessleadr.dockge.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores one session token per instance. Only the token is stored, never the password or a TOTP seed. */
interface TokenVault {
    fun get(instanceId: String): String?
    fun put(instanceId: String, token: String)
    fun remove(instanceId: String)
}

/**
 * Tokens are encrypted with a non-exportable AES-GCM key in the Android Keystore. The instance id is bound to the
 * ciphertext as associated data, so a blob cannot be moved to another instance. Anything that fails to decrypt is
 * deleted and treated as "no token".
 */
class KeystoreTokenVault(context: Context) : TokenVault {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(instanceId: String): String? {
        val stored = prefs.getString(key(instanceId), null) ?: return null
        return try {
            val (iv, ct) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(instanceId.toByteArray())
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            remove(instanceId)
            null
        }
    }

    override fun put(instanceId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(instanceId.toByteArray())
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(key(instanceId), blob).apply()
    }

    override fun remove(instanceId: String) {
        prefs.edit().remove(key(instanceId)).apply()
    }

    private fun key(instanceId: String) = "t_$instanceId"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "vault"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dockge_vault_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
