package com.nikhil.niktv.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Uses only the ordinary anonymous API advertised by the Rive website. */
internal class RiveSourceResolver(private val read: suspend (AlternateSourceHttpRequest, String) -> AlternateSourceHttpResponse) {
    suspend fun resolve(target: AlternateSourceTarget): List<AlternateStream> {
        val id = target.tmdbId?.takeIf { it > 0 } ?: return emptyList()
        val provider = when (target.provider) {
            AlternateSourceProvider.RIVE_VANGUARD -> "vanguard"
            AlternateSourceProvider.RIVE_PRIMEVIDS -> "primevids"
            AlternateSourceProvider.RIVE_CITADEL -> "citadel"
            else -> throw AlternateSourceException("Unsupported native provider.")
        }
        if (target.series && (target.season == null || target.season < 0 || target.episode == null || target.episode < 1))
            throw AlternateSourceException("Choose a season and episode.", AlternateSourceFailure.IDENTITY)
        val headers = mapOf("Referer" to "https://www.rivestream.app/", "User-Agent" to AlternateSourceResolver.USER_AGENT)
        val api = "https://scrapper.rivestream.app/api/provider".toHttpUrl().newBuilder()
            .addQueryParameter("provider", provider).addQueryParameter("id", id.toString()).apply {
                if (target.series) { addQueryParameter("season", target.season.toString()); addQueryParameter("episode", target.episode.toString()) }
            }.build()
        val response = read(AlternateSourceHttpRequest(api.toString(), headers, 512 * 1024), "Rive source lookup")
        val root = try { JSONObject(response.body) } catch (_: Exception) { throw AlternateSourceException("Rive returned an unsupported response.") }
        val data = root.optJSONObject("data") ?: return emptyList()
        if (data.has("tmdbId") && data.optInt("tmdbId") != id) throw AlternateSourceException("Rive returned a different title.", AlternateSourceFailure.IDENTITY)
        for ((key, expected) in listOf("season" to target.season, "episode" to target.episode)) {
            if (target.series && data.has(key) && data.optInt(key) != expected) throw AlternateSourceException("Rive returned a different episode.", AlternateSourceFailure.IDENTITY)
        }
        val sources = data.optJSONArray("sources") ?: return emptyList()
        val streams = mutableListOf<AlternateStream>()
        for (i in 0 until minOf(sources.length(), 3)) {
            val source = sources.optJSONObject(i) ?: continue
            if (source.optString("format").lowercase() !in setOf("hls", "m3u8")) continue
            val url = requirePublicHttps(source.optString("url"))
            val manifest = read(AlternateSourceHttpRequest(url.toString(), headers, 1024 * 1024), "Rive playlist")
            val language = source.optString("quality").substringAfter('|', "").trim().take(24)
                .takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == ' ' || c == '+' } }
            val label = "Rive ${provider.replaceFirstChar(Char::uppercaseChar)}" + (language?.let { " · $it" } ?: "")
            val parsed = alternateHlsStreams(requirePublicHttps(manifest.url), manifest.body, headers, "rive:$provider:$i", label)
            // Fixed-quality media playlists may have an advertised height; never invent one.
            val height = Regex("(?:^|[^0-9])(2160|1080|720|480|360)p\\b", RegexOption.IGNORE_CASE)
                .find(source.optString("quality"))?.groupValues?.get(1)?.toIntOrNull()
            val mirror = source.optString("quality").substringBefore('|').trim().takeIf {
                it.matches(Regex("[a-z][a-z0-9_-]{1,23}", RegexOption.IGNORE_CASE)) &&
                    it.lowercase() !in setOf("auto", "hls", "unknown", "hd", "fhd", "uhd")
            }
            val choices = if (height != null && !manifest.body.contains("#EXT-X-STREAM-INF:"))
                parsed.map { it.copy(height = height, label = "${height}p · $label") } else parsed
            streams += choices.map { it.copy(sourceVariant = mirror) }
        }
        return streams.distinctBy { it.url }.take(16)
    }
}
