package com.nikhil.niktv.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Atlantic's ordinary public Athena JSON request; no browser, login or response decryption. */
internal class AthenaSourceResolver(private val read: suspend (AlternateSourceHttpRequest, String) -> AlternateSourceHttpResponse) {
    suspend fun resolve(target: AlternateSourceTarget): List<AlternateStream> {
        val id = target.tmdbId?.takeIf { it > 0 } ?: return emptyList()
        if (target.series && (target.season == null || target.season < 0 || target.episode == null || target.episode < 1))
            throw AlternateSourceException("Choose a season and episode.", AlternateSourceFailure.IDENTITY)
        val api = "https://zcdn.bluevelvet.space/cdn".toHttpUrl().newBuilder()
            .addQueryParameter("tmdb", id.toString()).addQueryParameter("type", if (target.series) "tv" else "movie")
            .apply { if (target.series) { addQueryParameter("season", target.season.toString()); addQueryParameter("episode", target.episode.toString()) } }.build()
        val headers = mapOf("User-Agent" to AlternateSourceResolver.USER_AGENT, "Referer" to "https://atlantic.st/")
        val response = read(AlternateSourceHttpRequest(api.toString(), headers, 256 * 1024), "Athena source lookup")
        val data = try { JSONObject(response.body) } catch (_: Exception) { throw AlternateSourceException("Athena returned an unsupported response.") }
        if (!data.optBoolean("ready")) return emptyList()
        if (data.has("tmdb") && data.optInt("tmdb") != id) throw AlternateSourceException("Athena returned a different title.", AlternateSourceFailure.IDENTITY)
        val url = requirePublicHttps(data.optString("url"))
        val manifest = read(AlternateSourceHttpRequest(url.toString(), headers, 1024 * 1024), "Athena playlist")
        return alternateHlsStreams(requirePublicHttps(manifest.url), manifest.body, headers, "atlantic:athena", "Atlantic Athena")
    }
}
