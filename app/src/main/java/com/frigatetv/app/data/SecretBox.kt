package com.frigatetv.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption under a non-exportable key in the Android Keystore. Output is base64 of
 * IV followed by ciphertext. Used for profile passwords; the key never leaves the device.
 */
object SecretBox {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "frigatetv_profile_secrets"
    private const val KEY_BITS = 256
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)),
        )
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
