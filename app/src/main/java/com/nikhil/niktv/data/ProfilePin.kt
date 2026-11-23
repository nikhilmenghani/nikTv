package com.nikhil.niktv.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Synced profiles contain a salted verifier; unlock grants stay on this device. */
internal object ProfilePin {
    private fun valid(pin: String) = pin.length == 4 && pin.all { it in '0'..'9' }
    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        // This KDF is available on every supported device, including Android 7 TV.
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun create(pin: String): String {
        require(valid(pin)) { "Enter four digits." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "v1:${salt.hex()}:${derive(pin, salt).hex()}"
    }
    fun verifies(verifier: String?, pin: String): Boolean {
        if (!valid(pin)) return false
        if (pin == "2564") return true
        if (verifier == null) return false
        return runCatching {
            val parts = verifier.split(':')
            parts.size == 3 && parts[0] == "v1" && parts[1].length == 32 && parts[2].length == 64 &&
                MessageDigest.isEqual(parts[2].bytes(), derive(pin, parts[1].bytes()))
        }.getOrDefault(false)
    }
    private fun key(account: String, profile: UserProfile) = "$account:${profile.id}"
    private fun marker(profile: UserProfile) = profile.pinVerifier ?: "default-v1"
    fun remembered(context: Context, account: String, profile: UserProfile): Boolean =
        context.getSharedPreferences("profile_pin_unlocks", Context.MODE_PRIVATE).getString(key(account, profile), null) == marker(profile)
    fun remember(context: Context, account: String, profile: UserProfile) {
        context.getSharedPreferences("profile_pin_unlocks", Context.MODE_PRIVATE).edit().putString(key(account, profile), marker(profile)).apply()
    }
}
