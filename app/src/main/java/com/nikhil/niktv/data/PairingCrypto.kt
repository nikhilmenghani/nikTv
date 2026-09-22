package com.nikhil.niktv.data

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The pairing secret never enters the relay or the encrypted transfer file. */
object PairingCrypto {
    private const val TRANSFER_VERSION = 1
    private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

    fun newSecret(): String = ByteArray(16).also(SecureRandom()::nextBytes).let(::encode)

    fun seal(configuration: String, secret: String, expiry: Long): String {
        require(expiry > System.currentTimeMillis()) { "The pairing package has expired." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(decodeSecret(secret), "AES"))
        val data = cipher.iv + cipher.doFinal(configuration.toByteArray(Charsets.UTF_8))
        return JSONObject().put("version", TRANSFER_VERSION).put("expiresAt", expiry)
            .put("payload", encode(data)).toString()
    }

    fun open(packageJson: String, secret: String): String {
        require(packageJson.length <= 32_768) { "Pairing package is too large." }
        val envelope = JSONObject(packageJson)
        require(envelope.getInt("version") == TRANSFER_VERSION) { "Unsupported pairing package." }
        val expiry = envelope.getLong("expiresAt")
        val now = System.currentTimeMillis()
        require(expiry > now && expiry - now <= MAX_AGE_MILLIS) { "Pairing package expired or invalid." }
        val data = Base64.decode(envelope.getString("payload"), Base64.NO_WRAP or Base64.URL_SAFE)
        require(data.size in 29..16_384) { "Invalid pairing package." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decodeSecret(secret), "AES"),
            GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }

    private fun decodeSecret(secret: String): ByteArray =
        Base64.decode(secret.trim(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            .also { require(it.size == 16) { "Enter the complete 22-character pairing code." } }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
}
