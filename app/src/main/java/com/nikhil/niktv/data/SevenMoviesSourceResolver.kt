package com.nikhil.niktv.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Ordinary per-title anonymous grant -> Vidrift boot -> native HLS. No page scripts or keys. */
internal class SevenMoviesSourceResolver(
    private val read: suspend (AlternateSourceHttpRequest, String) -> AlternateSourceHttpResponse,
) {
    suspend fun resolve(target: AlternateSourceTarget): List<AlternateStream> {
        val id = target.tmdbId?.takeIf { it > 0 } ?: return emptyList()
        if (target.series && (target.season == null || target.season < 0 || target.episode == null || target.episode < 1))
            throw AlternateSourceException("Choose a season and episode.", AlternateSourceFailure.IDENTITY)
        val type = if (target.series) "tv" else "movie"
        val grant = read(AlternateSourceHttpRequest("$SITE/api/playback-token",
            mapOf("Origin" to SITE, "Referer" to "$SITE/", "Accept" to "application/json"), 8 * 1024,
            method = "POST", jsonBody = JSONObject().put("tmdbId", id).put("type", type)
                .put("season", target.season ?: 1).put("episode", target.episode ?: 1).toString()), "7Movies guest session")
        if (!sameAlternateOrigin(requirePublicHttps(grant.url), SITE.toHttpUrl())) throw identity()
        val token = json(grant.body).optString("token").takeIf {
            it.length in 20..2048 && it.none(Char::isISOControl)
        } ?: throw changed()
        val path = if (target.series) "tv/$id/${target.season}/${target.episode}" else "movie/$id"
        val bootUrl = PLAYER.toHttpUrl().newBuilder().addPathSegments("api/boot/$path")
            .addQueryParameter("shell", "1").addQueryParameter("source", "0")
            .addQueryParameter("provider", "").addQueryParameter("token", token).build()
        val boot = read(AlternateSourceHttpRequest(bootUrl.toString(), mapOf(
            "Referer" to "$PLAYER/embed2/play", "x-embed-parent" to SITE, "Accept" to "application/json"),
            512 * 1024), "7Movies source lookup")
        if (!sameAlternateOrigin(requirePublicHttps(boot.url), PLAYER.toHttpUrl())) throw identity()
        val meta = json(boot.body).optJSONObject("meta") ?: throw changed()
        if (meta.optString("tmdbId") != id.toString() || meta.optString("type") != type) throw identity()
        if (target.series && (meta.optInt("season", -1) != target.season || meta.optInt("episode", -1) != target.episode)) throw identity()
        if (meta.optBoolean("unreleased", false)) return emptyList()
        val raw = meta.optString("bigbangUrl").takeIf(String::isNotBlank) ?: return emptyList()
        val media = requirePublicHttps(raw)
        // Only the media URL is retained. The short-lived boot grant is never a media header.
        val headers = mapOf("User-Agent" to AlternateSourceResolver.USER_AGENT,
            "Referer" to "$PLAYER/")
        val playlist = read(AlternateSourceHttpRequest(media.toString(), headers, 1024 * 1024), "7Movies playlist")
        return alternateHlsStreams(requirePublicHttps(playlist.url), playlist.body, headers,
            "7movies:earth", "7Movies Earth")
    }

    private fun json(body: String) = try { JSONObject(body) } catch (_: Exception) { throw changed() }
    private fun changed() = AlternateSourceException("7Movies did not return a supported native source. Retry or choose another source.", stage = "7Movies public format")
    private fun identity() = AlternateSourceException("7Movies returned a different title or episode.", AlternateSourceFailure.IDENTITY)

    companion object {
        private const val SITE = "https://7movies.ac"
        private const val PLAYER = "https://embed.vidrift.net"
    }
}
