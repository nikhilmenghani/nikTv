package com.nikhil.niktv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun cinejoyWebStream(target: AlternateSourceTarget): AlternateStream {
    val id = requireNotNull(target.tmdbId?.takeIf { it > 0 }) { "A matching TMDB title is required for Cinejoy." }
    val path = if (target.series) {
        val season = requireNotNull(target.season?.takeIf { it >= 0 })
        val episode = requireNotNull(target.episode?.takeIf { it > 0 })
        "tv/$id/$season/$episode"
    } else "movie/$id"
    return AlternateStream("cinejoy-web", "Cinejoy · Resolve native stream", "https://cinejoy.pk/watch/$path",
        format = "website", route = AlternatePlaybackRoute.CINEJOY_WEB)
}

internal fun cnoWebStream(target: AlternateSourceTarget): AlternateStream {
    val id = requireNotNull(target.tmdbId?.takeIf { it > 0 }) { "A matching TMDB title is required for Cno." }
    val path = if (target.series) "show/$id/${requireNotNull(target.season?.takeIf { it >= 0 })}/${requireNotNull(target.episode?.takeIf { it > 0 })}" else "$id"
    return AlternateStream("cno-web", "Cno · Open source player", "https://cno.to/watch/$path",
        format = "website", route = AlternatePlaybackRoute.CNO_WEB)
}

/** Only a manifest observed during this explicit website session is eligible for handoff. */
internal suspend fun validateCinejoyNativeStream(candidate: AlternateStream): List<AlternateStream> = withContext(Dispatchers.IO) {
    withTimeoutOrNull(12_000) {
        val url = requirePublicHttps(candidate.url)
        val headers = candidate.headers.filterKeys { it.lowercase() in setOf("user-agent", "referer", "origin") }
        val response = fetchAlternateSource(AlternateSourceHttpRequest(url.toString(), headers, 256 * 1024))
        require(response.code in 200..299) { "Stream unavailable" }
        alternateHlsStreams(requirePublicHttps(response.url), response.body, headers,
            if (candidate.id.startsWith("cno")) "cno-native" else "cinejoy-native",
            if (candidate.id.startsWith("cno")) "Cno" else "Cinejoy")
            .also { require(it.isNotEmpty()) }
    } ?: throw AlternateSourceException("Cinejoy native validation timed out.")
}
