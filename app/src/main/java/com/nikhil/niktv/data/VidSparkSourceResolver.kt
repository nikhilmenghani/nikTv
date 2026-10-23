package com.nikhil.niktv.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Public website client flow. Its normally issued client header stays in this lookup's memory. */
internal class VidSparkSourceResolver(
    private val read: suspend (AlternateSourceHttpRequest, String) -> AlternateSourceHttpResponse,
) {
    private data class PublicClient(val origin: String, val assetPrefix: String, val label: String, val source: String? = null)

    suspend fun resolve(target: AlternateSourceTarget): List<AlternateStream> {
        var firstFailure: AlternateSourceException? = null
        for (client in clients) {
            try {
                val streams = resolveAt(target, client)
                if (streams.isNotEmpty()) return streams
            } catch (failure: AlternateSourceException) {
                if (failure.kind != AlternateSourceFailure.SOURCE) throw failure
                if (firstFailure == null) firstFailure = failure
            }
        }
        if (firstFailure != null) throw firstFailure
        return emptyList()
    }

    private suspend fun resolveAt(target: AlternateSourceTarget, client: PublicClient): List<AlternateStream> {
        val id = target.tmdbId?.takeIf { it > 0 } ?: return emptyList()
        if (target.series && (target.season == null || target.episode == null)) return emptyList()
        val site = client.origin.toHttpUrlOrNull()!!
        val route = site.newBuilder().apply {
            if (target.series) addPathSegments("tv/$id/${target.season}/${target.episode}") else addPathSegments("movie/$id")
        }.build()
        val page = request(route, emptyMap(), 256 * 1024, "${client.label} video page")
        val script = Regex("""<script\b[^>]*\bsrc\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
            .findAll(page.body).take(32).mapNotNull { match -> route.resolve(match.groupValues[2].replace("&amp;", "&")) }
            .firstOrNull { sameAlternateOrigin(it, site) && it.encodedPath.startsWith(client.assetPrefix) && it.encodedPath.endsWith(".js") }
            ?: throw AlternateSourceException("The public source player format has changed. Try again later.", stage = "${client.label} public player format")
        val bundle = request(script, mapOf("Referer" to route.toString()), 2 * 1024 * 1024, "${client.label} public player")
        val key = issuedPublicClientHeader(bundle.body)
            ?: throw AlternateSourceException("The public source player format has changed. Try again later.", stage = "${client.label} public player format")
        val api = site.newBuilder().addPathSegments("api/vidora/v1").apply {
            if (target.series) addPathSegments("tv/$id/${target.season}/${target.episode}") else addPathSegments("movie/$id")
            client.source?.let { addQueryParameter("source", it) }
        }.build()
        // Never retry a denied API with invented credentials. A later explicit user retry obtains
        // a fresh page/bundle, matching the ordinary website client flow.
        val response = try {
            request(api, mapOf("x-player-key" to key, "Referer" to route.toString(), "Origin" to client.origin, "Accept" to "application/json"), 512 * 1024, "${client.label} source lookup")
        } catch (failure: AlternateSourceException) {
            // This API uses 404 for an unavailable catalog identity. It is a normal no-result
            // outcome, not permission to retry an authorization failure or guess another ID.
            if (failure.httpCode == 404) return emptyList()
            throw failure
        }
        val data = try { JSONObject(response.body) } catch (_: Exception) {
            throw AlternateSourceException("The public source returned an unsupported response.")
        }
        if (!data.optBoolean("result")) return emptyList()
        verifyIdentity(data, target)
        val sources = data.optJSONArray("sources") ?: return emptyList()
        val headers = mapOf("User-Agent" to AlternateSourceResolver.USER_AGENT, "Referer" to "${client.origin}/")
        val streams = mutableListOf<AlternateStream>()
        var firstFailure: AlternateSourceException? = null
        for (index in 0 until minOf(sources.length(), 2)) {
            val source = sources.optJSONObject(index) ?: continue
            val raw = source.optString("url").takeIf { it.isNotBlank() } ?: continue
            val url = requirePublicHttps(raw)
            try {
                val manifest = request(url, headers, 1024 * 1024, "${client.label} playlist")
                streams += alternateHlsStreams(requirePublicHttps(manifest.url), manifest.body, headers, "vidspark:${client.source ?: "default"}:$index", if (sources.length() > 1) "${client.label} ${index + 1}" else client.label)
            } catch (failure: AlternateSourceException) {
                if (failure.kind != AlternateSourceFailure.SOURCE) throw failure
                if (firstFailure == null) firstFailure = failure
            }
        }
        if (streams.isEmpty() && firstFailure != null) throw firstFailure
        return streams
    }

    private suspend fun request(url: HttpUrl, headers: Map<String, String>, maxBytes: Int, phase: String): AlternateSourceHttpResponse {
        val response = read(AlternateSourceHttpRequest(url.toString(), headers, maxBytes), phase)
        // Source-client credentials are valid only for this site's API origin, including redirects.
        if (headers.keys.any { it.equals("x-player-key", true) } && !sameAlternateOrigin(requirePublicHttps(response.url), url)) {
            throw AlternateSourceException("The public source redirected its API request to an unsupported host.", AlternateSourceFailure.AUTHORIZATION)
        }
        return response
    }

    private fun verifyIdentity(data: JSONObject, target: AlternateSourceTarget) {
        val returnedId = data.optString("tmdb_id").toIntOrNull()?.takeIf { it > 0 }
        if (returnedId == null || returnedId != target.tmdbId) {
            throw AlternateSourceException("The public source returned a different title.", AlternateSourceFailure.IDENTITY)
        }
        val type = data.optString("type")
        if (type != if (target.series) "tv" else "movie") {
            throw AlternateSourceException("The public source returned a different media type.", AlternateSourceFailure.IDENTITY)
        }
        if (target.series) {
            listOf("season" to target.season, "episode" to target.episode).forEach { (field, expected) ->
                if (data.has(field) && data.optInt(field, -1) != expected) {
                    throw AlternateSourceException("The public source returned a different episode.", AlternateSourceFailure.IDENTITY)
                }
            }
        } else {
            val year = data.optInt("year", 0).takeIf { it > 0 }
            if (year != null && target.year != null && kotlin.math.abs(year - target.year) > 1) {
                throw AlternateSourceException("The public source returned a different release.", AlternateSourceFailure.IDENTITY)
            }
        }
    }

    private fun issuedPublicClientHeader(script: String): String? {
        // The minified binding name and asset hash change. Derive the variable from the actual
        // request-header map, then accept a plain literal assignment; never execute the bundle.
        val binding = Regex("""["']x-player-key["']\s*:\s*([A-Za-z_$][\w$]*)""").find(script)?.groupValues?.get(1) ?: return null
        val assignment = Regex("""\b${Regex.escape(binding)}\s*=\s*(["'`])([^"'`\r\n]{1,512})\1""")
            .find(script)?.groupValues?.get(2) ?: return null
        return assignment.takeIf { value -> value.none { it.isISOControl() || it == '\\' || it == '$' } }
    }

    companion object {
        // The public frontend's normal Alpha fallback is tried once after an unavailable primary.
        // This is one advertised source, not a fan-out over its other selectable servers.
        private val clients = listOf(
            PublicClient("https://moviesapi.to", "/assets/index-", "VidSpark"),
            PublicClient("https://cdn.vidspark.to", "/assets/main-", "VidSpark Alpha", "scrapify"),
        )
    }
}
