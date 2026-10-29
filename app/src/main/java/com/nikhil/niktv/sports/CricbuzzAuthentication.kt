package com.nikhil.niktv.sports

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class CricbuzzCredentials(
    val authKey: String,
    val mode: String = "AK",
    val appVersion: String = "6.16.04",
) {
    val configured get() = appVersion.matches(Regex("[0-9.]{1,24}")) && when (mode) {
        "AK" -> authKey.matches(Regex("(?:[0-9a-fA-F]{2}){16,128}"))
        "CB", "CF" -> authKey.length in 16..1024 && authKey.none(Char::isISOControl)
        else -> false
    }
    val cacheIdentity get() = MessageDigest.getInstance("SHA-256")
        .digest("$mode\u0000$appVersion\u0000$authKey".toByteArray()).joinToString("") { "%02x".format(it) }
    override fun toString() = "CricbuzzCredentials(configured=$configured)"
}

/** Signature configuration is supplied at runtime; no provider secret is compiled into NikTV. */
internal object CricbuzzAuthentication {
    private val hosts = setOf("api.cricbuzz.com", "api2.cricbuzz.com", "api3.cricbuzz.com")

    fun header(url: String, credentials: CricbuzzCredentials, nowMillis: Long, clockOffsetSeconds: Long = 0): String {
        val parsed = url.toHttpUrlOrNull() ?: throw SportsException("Invalid Cricbuzz API address.")
        if (!parsed.isHttps || parsed.host !in hosts || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty())
            throw SportsException("Cricbuzz authentication can only be sent to its API servers.")
        if (!credentials.configured) throw SportsException("Import your Cricbuzz API configuration to connect the Canadian catalogue.")
        val seconds = nowMillis / 1000 + clockOffsetSeconds.coerceIn(-86_400, 86_400)
        return when (credentials.mode) {
            "AK" -> {
                val payload = "exp=${seconds + 300}~acl=/*"
                val key = credentials.authKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                "$payload~hmac=${mac("HmacSHA1", key, payload).joinToString("") { "%02x".format(it) }}"
            }
            else -> {
                val timestamp = seconds - 600
                val file = parsed.encodedPath + parsed.encodedQuery?.let { "?$it" }.orEmpty()
                val signature = Base64.getEncoder().encodeToString(mac("HmacSHA256", credentials.authKey.toByteArray(StandardCharsets.UTF_8), "$file$timestamp"))
                "$timestamp-$signature"
            }
        }
    }

    private fun mac(algorithm: String, key: ByteArray, message: String): ByteArray = Mac.getInstance(algorithm).run {
        init(SecretKeySpec(key, algorithm)); doFinal(message.toByteArray(StandardCharsets.UTF_8))
    }
}
