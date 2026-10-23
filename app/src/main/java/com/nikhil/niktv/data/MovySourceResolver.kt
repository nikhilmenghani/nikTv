package com.nikhil.niktv.data

import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * Movy's ordinary anonymous website flow: a short-lived, per-title guest seed followed by a
 * source response. Neither the seed nor its signed media URLs are persisted or logged here.
 * The caller owns the shared request, timeout, concurrency and signed-URL cache budgets.
 */
internal class MovySourceResolver(
    private val read: suspend (AlternateSourceHttpRequest, String) -> AlternateSourceHttpResponse,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun resolve(target: AlternateSourceTarget): List<AlternateStream> {
        val id = target.tmdbId?.takeIf { it > 0 } ?: return emptyList()
        if (target.series && (target.season == null || target.season < 0 || target.episode == null || target.episode < 1)) {
            throw AlternateSourceException("Choose a season and episode before looking for an alternate source.", AlternateSourceFailure.IDENTITY)
        }
        val seedResponse = request(API.newBuilder().addPathSegment("seed").addQueryParameter("mediaId", id.toString()).build(), apiHeaders, 8 * 1024, "Movy guest session", api = true)
        val issued = json(seedResponse.body)
        val seed = issued.optString("seed").takeIf { it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) }
            ?: throw changed()
        val ttl = issued.optLong("ttlMs", 30_000).takeIf { it in 1..120_000 } ?: throw changed()
        val expiresAt = now() + ttl
        var probesLeft = 4
        var firstFailure: AlternateSourceException? = null
        for (server in listOf("miami", "boise")) {
            if (now() >= expiresAt) throw AlternateSourceException("The Movy session expired. Retry to obtain a new session.", stage = "Movy guest session")
            try {
                val url = API.newBuilder().addPathSegments("$server/sources")
                    // Match the public frontend, which passes encodeURIComponent(title) as a
                    // query value. HttpUrl performs the second, transport-level encoding.
                    .addQueryParameter("title", URLEncoder.encode(target.title, "UTF-8").replace("+", "%20"))
                    .addQueryParameter("mediaType", if (target.series) "tv" else "movie")
                    .addQueryParameter("tmdbId", id.toString())
                    .addQueryParameter("seasonId", (target.season ?: 1).toString())
                    .addQueryParameter("episodeId", (target.episode ?: 1).toString())
                    .apply { target.year?.let { addQueryParameter("year", it.toString()) } }
                    .addQueryParameter("enc", "2").addQueryParameter("seed", seed).build()
                val response = request(url, apiHeaders, 1024 * 1024, "Movy source lookup", api = true)
                val data = json(decodeMovyPayload(response.body, seed, id))
                verifyIdentity(data, target)
                val sources = data.optJSONArray("sources") ?: continue
                val streams = mutableListOf<AlternateStream>()
                // Source lists can contain several quality URLs. Probe at most four across
                // both advertised servers; never fan out over all eighteen site adapters.
                val records = (0 until minOf(sources.length(), 16)).mapNotNull(sources::optJSONObject)
                    .distinctBy { it.optString("url") }.take(probesLeft)
                for ((index, source) in records.withIndex()) {
                    if (probesLeft-- <= 0) break
                    val raw = source.optString("url").takeIf(String::isNotBlank) ?: continue
                    val media = requirePublicHttps(raw)
                    val headers = mapOf("User-Agent" to AlternateSourceResolver.USER_AGENT, "Referer" to "$SITE/")
                    val label = source.optString("quality").takeIf { it.matches(Regex("[0-9]{3,4}p", RegexOption.IGNORE_CASE)) }
                    try {
                        val manifest = request(media, headers, 1024 * 1024, "Movy playlist")
                        val finalUrl = requirePublicHttps(manifest.url)
                        val body = manifest.body.trimStart('\uFEFF', ' ', '\r', '\n')
                        val sourceId = "movy:$server:$index"
                        when {
                            body.startsWith("#EXTM3U") -> {
                                val parsed = alternateHlsStreams(finalUrl, body, headers, sourceId, "Movy ${server.replaceFirstChar(Char::uppercaseChar)}")
                                // A media playlist is already a fixed quality; preserve its
                                // advertised label. A master retains native audio/subtitle groups.
                                streams += if (parsed.size == 1 && !body.contains("#EXT-X-STREAM-INF:") && label != null) {
                                    listOf(parsed.single().copy(label = "$label · Movy ${server.replaceFirstChar(Char::uppercaseChar)}", height = label.dropLast(1).toIntOrNull()))
                                } else parsed
                            }
                            Regex("<(?:[A-Za-z0-9_]+:)?MPD(?:\\s|>)").containsMatchIn(body.take(4096)) -> {
                                if (Regex("<(?:[A-Za-z0-9_]+:)?ContentProtection\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                                    throw AlternateSourceException("The alternate source uses protected media and cannot be played here.", AlternateSourceFailure.PROTECTED_MEDIA)
                                }
                                streams += AlternateStream(sourceId, "${label ?: "Auto"} · Movy ${server.replaceFirstChar(Char::uppercaseChar)}", finalUrl.toString(), headers, "dash", label?.dropLast(1)?.toIntOrNull())
                            }
                            else -> throw AlternateSourceException("Movy did not provide a supported native stream. Try its embedded player.", stage = "Movy playlist")
                        }
                    } catch (failure: AlternateSourceException) {
                        if (failure.kind != AlternateSourceFailure.SOURCE) throw failure
                        if (firstFailure == null) firstFailure = failure
                    }
                }
                if (streams.isNotEmpty()) return streams.distinctBy { it.url }.take(12)
                if (probesLeft <= 0) break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AlternateSourceException) {
                // Authentication, challenges, protected media and identity failures are terminal.
                // The browser fallback may use its own supported flow; never invent credentials.
                if (failure.kind != AlternateSourceFailure.SOURCE) throw failure
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
        return emptyList()
    }

    private suspend fun request(url: HttpUrl, headers: Map<String, String>, maxBytes: Int, phase: String, api: Boolean = false): AlternateSourceHttpResponse {
        requirePublicHttps(url.toString())
        val response = read(AlternateSourceHttpRequest(url.toString(), headers, maxBytes), phase)
        if (api && !sameAlternateOrigin(requirePublicHttps(response.url), API)) {
            throw AlternateSourceException("Movy redirected its session to an unsupported host.", AlternateSourceFailure.AUTHORIZATION, phase)
        }
        return response
    }

    private fun verifyIdentity(data: JSONObject, target: AlternateSourceTarget) {
        fun mismatch(): Nothing = throw AlternateSourceException("Movy returned a different title or episode.", AlternateSourceFailure.IDENTITY)
        for (field in listOf("tmdbId", "tmdb_id")) if (data.has(field) && data.optInt(field, -1) != target.tmdbId) mismatch()
        for (field in listOf("mediaType", "type")) if (data.has(field) && data.optString(field) != if (target.series) "tv" else "movie") mismatch()
        if (target.series) {
            for ((field, expected) in listOf("season" to target.season, "seasonId" to target.season, "episode" to target.episode, "episodeId" to target.episode)) {
                if (data.has(field) && data.optInt(field, -1) != expected) mismatch()
            }
        }
    }

    private fun json(body: String): JSONObject = try { JSONObject(body) } catch (_: Exception) { throw changed() }

    companion object {
        private const val SITE = "https://www.movy.sx"
        private val API = "https://api.wecollege.net".toHttpUrlOrNull()!!
        private val apiHeaders = mapOf("Origin" to SITE, "Referer" to "$SITE/", "Accept" to "application/json")
        private fun changed() = AlternateSourceException("Movy's public source format has changed. Try its embedded player.", stage = "Movy public format")
    }
}

/** The public frontend's version-2 response codec, not a media encryption/DRM implementation. */
internal fun decodeMovyPayload(payload: String, seed: String, mediaId: Int): String {
    fun bad(): Nothing = throw AlternateSourceException("Movy's source response could not be read. Try its embedded player.", stage = "Movy public format")
    if (payload.length > 1024 * 1024 || payload.isBlank()) bad()
    val encoded = payload.trim()
    if (!encoded.matches(Regex("[A-Za-z0-9_+/=-]+"))) bad()
    val bytes = try { Base64.getUrlDecoder().decode(encoded.replace('+', '-').replace('/', '_')) } catch (_: IllegalArgumentException) { bad() }
    fun mix(value: Int): Int {
        var n = value xor (value ushr 16)
        n *= 0x85ebca6b.toInt()
        n = n xor (n ushr 13)
        n *= 0xc2b2ae35.toInt()
        return n xor (n ushr 16)
    }
    fun slot(value: Int): Int = ((value.toLong() and 0xffffffffL) % 61).toInt()
    val golden = 0x9e3779b9.toInt()
    var hash = 0x811c9dc5.toInt()
    seed.forEach { hash = (hash xor it.code) * 0x1000193 }
    var state = mix(mix(hash) xor mix(mediaId xor golden))
    val values = IntArray(61)
    val present = BooleanArray(61)
    repeat(8) { index ->
        val at = slot(state)
        state = Integer.rotateLeft(state + golden, 7 + (index and 7))
        values[at] = state xor mix(state)
        present[at] = true
        state = mix(state + at)
    }
    var accumulator = mix(0xa5a5a5a5.toInt() xor state)
    var offset = 0
    while (offset < bytes.size) {
        val at = slot(accumulator)
        val v = values[at] xor (golden * (offset / 4 + 1))
        val combined = (accumulator xor v) or if (present[at]) accumulator and v else 0
        accumulator = mix((Integer.rotateLeft(combined + accumulator, at) xor Integer.rotateLeft(accumulator, at * 7)) + golden)
        values[at] = accumulator
        present[at] = true
        repeat(minOf(4, bytes.size - offset)) { index -> bytes[offset + index] = (bytes[offset + index].toInt() xor (accumulator ushr (index * 8))).toByte() }
        offset += 4
    }
    if (bytes.size < 4 || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(109, 118, 109, 49))) bad()
    return bytes.copyOfRange(4, bytes.size).toString(Charsets.UTF_8)
}
