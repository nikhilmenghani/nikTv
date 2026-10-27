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

internal fun sportsWebHeaders(stream: SportsStream, page: String): Map<String, String> {
    val result = linkedMapOf("User-Agent" to SPORTS_USER_AGENT, "Referer" to page)
    stream.headers.forEach { (key, value) ->
        val canonical = when (key.lowercase(java.util.Locale.ROOT)) {
            "user-agent" -> "User-Agent"; "referer", "referrer" -> "Referer"; "origin" -> "Origin"; else -> null
        }
        if (canonical != null && value.none { it == '\r' || it == '\n' }) result[canonical] = value
    }
    return result
}
