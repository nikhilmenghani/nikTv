package com.nikhil.niktv.data

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** The ordinary anonymous Medmedia API linked by OnlyFlix; no iframe or page scripts. */
internal class OnlyFlixSourceResolver(
    private val read: suspend (AlternateSourceHttpRequest, String) -> AlternateSourceHttpResponse,
) {
    suspend fun resolve(target: AlternateSourceTarget): List<AlternateStream> {
        val id = target.tmdbId?.takeIf { it > 0 } ?: return emptyList()
        if (target.series && (target.season == null || target.season < 0 || target.episode == null || target.episode < 1))
            throw AlternateSourceException("Choose a season and episode.", AlternateSourceFailure.IDENTITY)
        val path = if (target.series) "tv/$id/${target.season}/${target.episode}" else "movie/$id"
        val origin = "https://2.medmedia03.wiki".toHttpUrl()
        val headers = mapOf("User-Agent" to AlternateSourceResolver.USER_AGENT, "Referer" to "${origin}embed/$path")
        val providers = if (target.series) listOf("vaplayer") else listOf("onetouchtv", "vaplayer")
        var issue: AlternateSourceException? = null
        for (provider in providers) {
            try {
                val api = origin.resolve("/embed-api/source/$provider/$path")!!
                val response = read(AlternateSourceHttpRequest(api.toString(), headers, 512 * 1024), "OnlyFlix source lookup")
                val root = try { JSONObject(response.body) } catch (_: Exception) {
                    throw AlternateSourceException("OnlyFlix returned an unsupported response.")
                }
                if (root.optString("provider") != provider)
                    throw AlternateSourceException("OnlyFlix returned a different source.", AlternateSourceFailure.IDENTITY)
                val sources = root.optJSONArray("streams") ?: continue
                val ordered = (0 until minOf(sources.length(), 3)).mapNotNull { index ->
                    sources.optJSONObject(index)?.let { index to it }
                }.sortedBy { (_, source) ->
                    if (!source.has("verified")) 1 else if (source.optBoolean("verified")) 0 else 2
                }
                for ((index, source) in ordered) {
                    try {
                        // Follow only media routes, never a browser/embed fallback.
                        if (source.optString("route") != "/m3u8-proxy") continue
                        val raw = source.optString("url").takeIf(String::isNotBlank) ?: continue
                        val url = requirePublicHttps(origin.resolve(raw)?.toString() ?: continue)
                        if (!sameAlternateOrigin(url, origin)) continue
                        val manifest = read(AlternateSourceHttpRequest(url.toString(), headers, 1024 * 1024), "OnlyFlix playlist")
                        // Return the first usable backend and its declared quality choices.
                        // Extra mirror lookups must not delay a ready source until the outer timeout.
                        return alternateHlsStreams(requirePublicHttps(manifest.url), manifest.body, headers,
                            "onlyflix:$provider:$index", "OnlyFlix ${provider.replaceFirstChar(Char::uppercaseChar)} ${index + 1}")
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (failure: AlternateSourceException) {
                        if (failure.kind != AlternateSourceFailure.SOURCE) throw failure
                        issue = failure
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: AlternateSourceException) {
                if (failure.kind != AlternateSourceFailure.SOURCE) throw failure
                issue = failure
            }
        }
        issue?.let { throw it }
        return emptyList()
    }
}
