package com.nikhil.niktv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from development provisioning and full-backup credentials. Never exported. */
internal class AccountTokenVault(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("niktv_account_auth", Context.MODE_PRIVATE)
    fun token(): String = runCatching {
        val iv = preferences.getString("iv", null) ?: return ""
        val ciphertext = preferences.getString("ciphertext", null) ?: return ""
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            updateAAD("niktv-account-auth-v1".toByteArray())
            doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
    }.getOrDefault("")

    fun save(token: String, accountId: String, login: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD("niktv-account-auth-v1".toByteArray())
        }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("accountId", accountId).putString("login", login).commit()) { "Could not securely save the account connection." }
    }

    fun clear() { check(preferences.edit().remove("ciphertext").remove("iv").remove("accountId").remove("login").commit()) }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    companion object { private const val ALIAS = "niktv_account_token_v1" }
}
