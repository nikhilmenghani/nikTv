package com.nikhil.niktv.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

/** Public catalogue identity; no guest grants, signed links or user credentials are persisted. */
data class AbibliPlaybackSpec(val videoId: String)

internal class AbibliSourceResolver(
    private val read: suspend (AlternateSourceHttpRequest, String) -> AlternateSourceHttpResponse,
) {
    suspend fun resolve(target: AlternateSourceTarget): List<AlternateStream> {
        if (target.series && (target.season == null || target.season < 0 || target.episode == null || target.episode < 1))
            throw AlternateSourceException("Choose a season and episode.", AlternateSourceFailure.IDENTITY)
        val search = SITE.toHttpUrl().newBuilder().addPathSegment("search").addQueryParameter("q", target.title.take(120)).build()
        val response = read(AlternateSourceHttpRequest(search.toString(), pageHeaders, 1024 * 1024), "Abibli title search")
        requireSite(response.url)
        val results = Jsoup.parse(response.body, response.url).select("a[href][title]")
            .filter { normalizedTitle(it.attr("title")) == normalizedTitle(target.title) }
            .mapNotNull { node -> runCatching { requireSite(node.absUrl("href")) }.getOrNull() }
            .filter { it.encodedPath.startsWith(if (target.series) "/series/" else "/movie/") }
            .distinct().take(4)
        val matches = mutableListOf<Pair<String, String>>()
        for (result in results) {
            val url = result.newBuilder().apply { if (target.series) setQueryParameter("season", target.season.toString()) }.build()
            val detail = read(AlternateSourceHttpRequest(url.toString(), pageHeaders, 2 * 1024 * 1024), "Abibli title details")
            requireSite(detail.url)
            parseAbibliVideo(detail.body, target)?.let { matches += it to url.toString() }
        }
        // Equal-name remakes without a known year must never silently select one.
        val selected = matches.distinctBy { it.first }.singleOrNull() ?: return emptyList()
        val spec = AbibliPlaybackSpec(selected.first)
        val client = newAbibliClientId()
        val grant = read(AlternateSourceHttpRequest("$ABIBLI_API/mQ7x2P", abibliHeaders(client), 8 * 1024,
            "POST", JSONObject().put("video", spec.videoId).put("client", client).toString()), "Abibli guest session")
        requireAbibliApi(grant.url)
        val session = parseAbibliGuest(grant.body)
        val playlist = read(AlternateSourceHttpRequest(session.master.toString(), abibliHeaders(client), 1024 * 1024), "Abibli playlist")
        requireAbibliApi(playlist.url)
        if (!playlist.body.trimStart().startsWith("#EXTM3U")) throw AlternateSourceException("Abibli did not return a native playlist.")
        rejectDrm(playlist.body)
        // The complete master retains all audio/subtitle groups. Only the selected stream
        // creates a bounded playback bridge; speculative catalogue lookup opens no server.
        return listOf(AlternateStream("abibli:${spec.videoId}", "Auto · Abibli", session.master.toString(),
            format = "hls", sourceVariant = spec.videoId, abibli = spec))
    }

    private fun requireSite(value: String) = requirePublicHttps(value).also {
        if (!sameAlternateOrigin(it, SITE.toHttpUrl())) throw AlternateSourceException("Abibli returned a different site.", AlternateSourceFailure.IDENTITY)
    }
    companion object {
        const val SITE = "https://abibli.com"
        private val pageHeaders = mapOf("Accept" to "text/html", "Referer" to "$SITE/")
    }
}

/** Parse published markup, never execute Alpine/page JavaScript. */
internal fun parseAbibliVideo(html: String, target: AlternateSourceTarget): String? {
    val document = Jsoup.parse(html)
    val expected = if (target.series) "TVSeries" else "Movie"
    val metadata = document.select("script[type=application/ld+json]").mapNotNull {
        runCatching { JSONObject(it.data()) }.getOrNull()
    }.firstOrNull { it.optString("@type") == expected } ?: return null
    if (normalizedTitle(metadata.optString("name")) != normalizedTitle(target.title)) return null
    val year = metadata.optString("dateCreated").take(4).toIntOrNull()
    if (target.year != null && target.year != year) return null
    val actions = document.allElements.mapNotNull { node ->
        val action = node.attr("@click.stop").ifBlank { node.attr("@click") }
        val match = Regex("""streamPlayer\.play\('((?:\\.|[^'])*)',\s*JSON\.parse\('((?:\\.|[^'])*)'\)""").find(action) ?: return@mapNotNull null
        try {
            val raw = JSONTokener("'${match.groupValues[1]}'").nextValue() as? String ?: return@mapNotNull null
            val url = requirePublicHttps(raw)
            if (url.host != "rov.funnyotter.xyz" || url.encodedPath != "/" || url.query != null) return@mapNotNull null
            val id = url.fragment?.takeIf { it.matches(Regex("[A-Za-z0-9]{6}")) } ?: return@mapNotNull null
            val info = JSONObject(JSONTokener("'${match.groupValues[2]}'").nextValue() as String)
            if (normalizedTitle(info.optString("title")) != normalizedTitle(target.title)) return@mapNotNull null
            if (target.series) {
                if (info.optString("type") != "episode") return@mapNotNull null
                val episode = Regex("^S0*(\\d+)E0*(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(info.optString("subtitle")) ?: return@mapNotNull null
                if (episode.groupValues[1].toIntOrNull() != target.season || episode.groupValues[2].toIntOrNull() != target.episode) return@mapNotNull null
            } else if (info.optString("type") != "movie") return@mapNotNull null
            id
        } catch (_: Exception) { null }
    }.distinct()
    return actions.singleOrNull()
}
