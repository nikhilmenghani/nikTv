package com.nikhil.niktv.sports

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class SportsWebCandidates(val media: List<String>, val frames: List<String>)

internal fun sportsWebCandidates(result: String?): SportsWebCandidates {
    val empty = SportsWebCandidates(emptyList(), emptyList())
    if (result == null || result.length > 600_000) return empty
    return runCatching {
        val envelope = Json.parseToJsonElement(result)
        val doc = if (envelope is JsonPrimitive) Json.parseToJsonElement(envelope.content) else envelope
        val obj = doc as? JsonObject ?: return empty
        fun urls(key: String, limit: Int) = (obj[key] as? JsonArray).orEmpty().take(limit).mapNotNull {
            val text = (it as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            text.toHttpUrlOrNull()?.takeIf { parsed -> parsed.username.isEmpty() && parsed.password.isEmpty() }?.toString()
        }.distinct()
        SportsWebCandidates(urls("media", 32), urls("frames", 8))
    }.getOrDefault(empty)
}

internal fun sportsMediaRequest(url: String): Boolean {
    val path = url.toHttpUrlOrNull()?.encodedPath?.lowercase(java.util.Locale.ROOT) ?: return false
    return listOf(".m3u8", ".mpd", ".mp4", ".webm", ".mkv").any(path::endsWith)
}

/**
 * Configuration credentials belong to the configured origin. Headers observed
 * on a media request belong to that exact target (never to a nested page).
 */
internal fun sportsWebHeaders(
    stream: SportsStream,
    page: String,
    targetUrl: String = page,
    interceptedHeaders: Map<String, String> = emptyMap(),
): Map<String, String> {
    val result = linkedMapOf("User-Agent" to SPORTS_USER_AGENT, "Referer" to page)
    val configured = stream.link.toHttpUrlOrNull()
    val target = targetUrl.toHttpUrlOrNull()
    val targetSafe = target != null && target.username.isEmpty() && target.password.isEmpty()
    val sameOrigin = configured != null && target != null &&
        configured.username.isEmpty() && configured.password.isEmpty() && targetSafe &&
        configured.scheme == target.scheme && configured.host == target.host && configured.port == target.port
    fun merge(headers: Map<String, String>, authenticated: Boolean) = headers.forEach { (key, value) ->
        val lower = key.lowercase(java.util.Locale.ROOT)
        val canonical = when (lower) {
            "user-agent" -> "User-Agent"
            "referer", "referrer" -> "Referer"
            "origin" -> "Origin"
            "authorization" -> "Authorization".takeIf { authenticated }
            "cookie" -> "Cookie".takeIf { authenticated }
            else -> key.takeIf { authenticated && lower.startsWith("x-") && key.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) }
        }
        if (canonical != null && value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            result.keys.firstOrNull { it.equals(canonical, true) }?.let(result::remove)
            result[canonical] = value
        }
    }
    merge(stream.headers, sameOrigin)
    merge(interceptedHeaders, authenticated = targetSafe)
    return result
}
