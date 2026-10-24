package com.nikhil.niktv.data

import android.util.Log
import com.nikhil.niktv.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

enum class AlternateSourceProvider { AUTO, MOVY, CINEJOY, CNO, STREAMING_UNITY, RIVE_VANGUARD, RIVE_PRIMEVIDS, RIVE_CITADEL, ATLANTIC_ATHENA, ONLYFLIX }
internal val nativeSourceProviders = listOf(AlternateSourceProvider.AUTO, AlternateSourceProvider.MOVY,
    AlternateSourceProvider.STREAMING_UNITY, AlternateSourceProvider.ATLANTIC_ATHENA,
    AlternateSourceProvider.RIVE_PRIMEVIDS, AlternateSourceProvider.RIVE_CITADEL, AlternateSourceProvider.ONLYFLIX)
enum class AlternatePlaybackRoute { NATIVE, VIDY_EMBEDDED, CINEJOY_WEB, CNO_WEB }

@kotlinx.serialization.Serializable
data class AlternateSourceTarget(
    val title: String,
    val series: Boolean,
    val tmdbId: Int? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val provider: AlternateSourceProvider = AlternateSourceProvider.AUTO,
    val preferredHeight: Int? = null,
)

data class AlternateStream(
    val id: String,
    val label: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val format: String = "hls",
    val height: Int? = null,
    val route: AlternatePlaybackRoute = AlternatePlaybackRoute.NATIVE,
    val sourceVariant: String? = null,
)

/** Injectable transport keeps source parsing deterministic and never requires a provider profile. */
data class AlternateSourceHttpRequest(val url: String, val headers: Map<String, String>, val maxBytes: Int)
data class AlternateSourceHttpResponse(val url: String, val code: Int, val body: String)
enum class AlternateSourceFailure { SOURCE, AUTHORIZATION, CHALLENGE, RATE_LIMIT, PROTECTED_MEDIA, IDENTITY }
class AlternateSourceException(
    message: String,
    val kind: AlternateSourceFailure = AlternateSourceFailure.SOURCE,
    val stage: String? = null,
    val httpCode: Int? = null,
) : IOException(message)

/**
 * Resolves a user-selected title using ordinary public source pages. No page JavaScript is executed,
 * no challenge is bypassed, and no Brodys configuration, account, keys or unlock service is used.
 * Signed links are ephemeral; only a small, two-minute in-memory result cache is retained.
 */
class AlternateSourceResolver(
    private val fetch: suspend (AlternateSourceHttpRequest) -> AlternateSourceHttpResponse = ::fetchAlternateSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 25_000L,
    private val identityLookup: suspend (AlternateSourceTarget) -> AlternateSourceTarget = { it },
    private val movyLookup: (suspend (AlternateSourceTarget) -> List<AlternateStream>)? = null,
) {
    private data class Cached(val expiresAt: Long, val streams: List<AlternateStream>)
    private val cache = object : LinkedHashMap<AlternateSourceTarget, Cached>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AlternateSourceTarget, Cached>?) = size > 32
    }

    suspend fun resolve(target: AlternateSourceTarget): List<AlternateStream> = withContext(Dispatchers.IO) {
        if (target.provider !in nativeSourceProviders) throw AlternateSourceException("This browser source is no longer supported. Choose a native source.")
        if (target.title.isBlank()) throw AlternateSourceException("This title has no searchable name.")
        if (target.series && (target.season == null || target.season < 0 || target.episode == null || target.episode < 1)) {
            throw AlternateSourceException("Choose a season and episode before looking for an alternate source.")
        }
        synchronized(cache) { cache[target]?.takeIf { now() < it.expiresAt } }?.let {
            return@withContext it.streams
        }
        withTimeoutOrNull(timeoutMillis.coerceIn(1L, 25_000L)) {
            concurrentLookups.withPermit {
                val identified = if (target.tmdbId?.let { it > 0 } == true) target else try {
                    identityLookup(target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AlternateSourceException("Could not identify this title. Try again later.")
                }
                if (identified.series != target.series || identified.season != target.season || identified.episode != target.episode) {
                    throw AlternateSourceException("The alternate source identity did not match the selected episode.", AlternateSourceFailure.IDENTITY)
                }
                val streams = lookup(identified)
                if (streams.isNotEmpty()) {
                    val timestamp = now()
                    val signedExpiry = streams.mapNotNull { stream ->
                        val url = stream.url.toHttpUrlOrNull() ?: return@mapNotNull null
                        listOf("expires", "exp", "expiry").mapNotNull { name -> url.queryParameter(name)?.toLongOrNull() }
                            .filter { it > 1_000_000_000L }.minOrNull()?.let { if (it < 1_000_000_000_000L) it * 1000 else it }
                    }.minOrNull()
                    val expiry = minOf(timestamp + CACHE_MILLIS, signedExpiry?.minus(15_000L) ?: Long.MAX_VALUE)
                    if (expiry > timestamp) synchronized(cache) { cache[target] = Cached(expiry, streams) }
                }
                streams
            }
        } ?: throw AlternateSourceException("The alternate source took too long. Try again later.")
    }

    /** Explicit retry discards an unplayable or revoked signed URL before resolving it again. */
    fun invalidate(target: AlternateSourceTarget) { synchronized(cache) { cache.remove(target) } }

    private suspend fun lookup(target: AlternateSourceTarget): List<AlternateStream> {
        if (target.provider !in nativeSourceProviders) throw AlternateSourceException("This browser source is no longer supported. Choose a native source.")
        if (target.provider != AlternateSourceProvider.AUTO) return lookupNative(target)
        // Two independent, bounded lookups. A failed source cannot discard another's results.
        return coroutineScope {
            val slots = Semaphore(2)
            val providers = listOf(AlternateSourceProvider.MOVY, AlternateSourceProvider.AUTO,
                AlternateSourceProvider.ATLANTIC_ATHENA, AlternateSourceProvider.RIVE_PRIMEVIDS, AlternateSourceProvider.RIVE_CITADEL, AlternateSourceProvider.ONLYFLIX)
            val searches = providers.map { provider ->
                async {
                    slots.withPermit {
                    try {
                        withTimeoutOrNull(7_000) { lookupNative(target.copy(provider = provider)) }
                            ?.let { it to null } ?: (emptyList<AlternateStream>() to AlternateSourceException("Source lookup timed out.", stage = "source timeout"))
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (failure: AlternateSourceException) { emptyList<AlternateStream>() to failure }
                    }
                }
            }.map { it.await() }
            val streams = searches.flatMap { it.first.take(8) }.distinctBy { it.url }.take(40)
            if (streams.isEmpty() && searches.any { it.second != null }) {
                val summary = providers.zip(searches).joinToString("\n") { (provider, result) ->
                    val name = when (provider) {
                        AlternateSourceProvider.MOVY -> "Movy"
                        AlternateSourceProvider.AUTO -> "Unity / fallback"
                        AlternateSourceProvider.ATLANTIC_ATHENA -> "Athena"
                        AlternateSourceProvider.RIVE_PRIMEVIDS -> "Rive Primevids"
                        AlternateSourceProvider.RIVE_CITADEL -> "Rive Citadel"
                        AlternateSourceProvider.ONLYFLIX -> "OnlyFlix"
                        else -> provider.name
                    }
                    val failure = result.second
                    val status = when {
                        failure == null -> "No results"
                        failure.httpCode != null -> "HTTP ${failure.httpCode}"
                        failure.stage == "source timeout" -> "Timed out"
                        failure.kind == AlternateSourceFailure.CHALLENGE -> "Browser verification required"
                        failure.kind == AlternateSourceFailure.AUTHORIZATION -> "Access refused"
                        failure.kind == AlternateSourceFailure.PROTECTED_MEDIA -> "Protected media"
                        failure.kind == AlternateSourceFailure.IDENTITY -> "Title mismatch"
                        else -> "Lookup failed"
                    }
                    "$name: $status"
                }
                throw AlternateSourceException("Auto found no playable sources after checking ${providers.size} source groups.\n$summary")
            }
            streams
        }
    }

    private suspend fun lookupNative(target: AlternateSourceTarget): List<AlternateStream> {
        var remainingRequests = 12
        suspend fun read(url: HttpUrl, headers: Map<String, String> = emptyMap(), maxBytes: Int = MAX_PAGE_BYTES, phase: String = "source"): AlternateSourceHttpResponse {
            if (--remainingRequests < 0) throw AlternateSourceException("The source required too many requests.")
            requirePublicHttps(url.toString())
            val response = try {
                fetch(AlternateSourceHttpRequest(url.toString(), mapOf("User-Agent" to USER_AGENT) + headers, maxBytes))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (safe: AlternateSourceException) {
                throw safe
            } catch (failure: Exception) {
                alternateSourceTrace("$phase transport ${failure.javaClass.simpleName}")
                // Transport exceptions can contain a full signed URL. Never expose it in UI/logs.
                throw AlternateSourceException("Could not connect to the alternate source. Try again later.", stage = phase)
            }
            requirePublicHttps(response.url)
            val byteCount = response.body.toByteArray(Charsets.UTF_8).size
            alternateSourceTrace("$phase HTTP ${response.code} bytes $byteCount")
            if (byteCount > maxBytes) throw AlternateSourceException("The alternate source response was too large.")
            when (response.code) {
                401, 403 -> throw AlternateSourceException("The alternate source requires authorization or blocked the $phase request (HTTP ${response.code}).", AlternateSourceFailure.AUTHORIZATION, phase, response.code)
                429 -> throw AlternateSourceException("The alternate source is busy. Try again later.", AlternateSourceFailure.RATE_LIMIT, phase, response.code)
            }
            if (response.code !in 200..299) throw AlternateSourceException("The $phase request is unavailable (HTTP ${response.code}).", stage = phase, httpCode = response.code)
            val lower = response.body.lowercase(Locale.ROOT)
            if (listOf("<title>just a moment", "cf-chl-", "/cdn-cgi/challenge-platform", "verify you are human", "captcha-container").any(lower::contains)) {
                throw AlternateSourceException("The alternate source requires a browser verification and cannot be played here.", AlternateSourceFailure.CHALLENGE, phase, response.code)
            }
            return response
        }
        if (target.provider == AlternateSourceProvider.MOVY) {
            movyLookup?.let { return it(target) }
            return MovySourceResolver(read = { request, phase ->
                    read(requirePublicHttps(request.url), request.headers, request.maxBytes, phase)
                }).resolve(target)
        }
        if (target.provider in setOf(AlternateSourceProvider.RIVE_VANGUARD, AlternateSourceProvider.RIVE_PRIMEVIDS, AlternateSourceProvider.RIVE_CITADEL)) {
            return RiveSourceResolver { request, phase -> read(requirePublicHttps(request.url), request.headers, request.maxBytes, phase) }.resolve(target)
        }
        if (target.provider == AlternateSourceProvider.ATLANTIC_ATHENA) {
            return AthenaSourceResolver { request, phase -> read(requirePublicHttps(request.url), request.headers, request.maxBytes, phase) }.resolve(target)
        }
        if (target.provider == AlternateSourceProvider.ONLYFLIX) {
            return OnlyFlixSourceResolver { request, phase -> read(requirePublicHttps(request.url), request.headers, request.maxBytes, phase) }.resolve(target)
        }
        var preferredIssue: AlternateSourceException? = null
        var preferredEmpty = false
        if (target.provider != AlternateSourceProvider.STREAMING_UNITY && target.tmdbId?.let { it > 0 } == true) {
            try {
                val preferred = VidSparkSourceResolver { request, phase ->
                    read(requirePublicHttps(request.url), request.headers, request.maxBytes, phase)
                }.resolve(target)
                if (preferred.isNotEmpty()) return preferred
                preferredEmpty = true
                alternateSourceTrace("VidSpark source lookup no available title")
            } catch (failure: AlternateSourceException) {
                // A different public source is useful after a transient outage, but never conceal
                // an authorization/challenge/identity failure or retry it with invented credentials.
                if (failure.kind != AlternateSourceFailure.SOURCE) throw failure
                preferredIssue = failure
                alternateSourceTrace("VidSpark fallback phase ${safeAlternatePhase(failure.stage)} kind ${failure.kind} HTTP ${failure.httpCode ?: 0}")
            }
        }
        suspend fun legacyLookup(): List<AlternateStream> {
            val searchUrl = SITE.toHttpUrlOrNull()!!.newBuilder().addPathSegments("it/search")
                .addQueryParameter("q", target.title.trim()).build()
            val search = read(searchUrl, mapOf("Accept" to "application/json"), 512 * 1024, phase = "search")
            val results = try { JSONObject(search.body).optJSONArray("data") } catch (_: Exception) { null }
                ?: throw AlternateSourceException("The alternate source returned an unsupported search response.")
            val wantedName = normalizedTitle(target.title)
            data class Match(val data: JSONObject, val score: Int)
            val matches = (0 until minOf(results.length(), 80)).mapNotNull { i ->
                val data = results.optJSONObject(i) ?: return@mapNotNull null
                val type = data.optString("type")
                if (type != if (target.series) "tv" else "movie") return@mapNotNull null
                val names = listOf(data.optString("name"), data.optString("title"), data.optString("original_title"))
                    .filter(String::isNotBlank).map(::normalizedTitle)
                if (wantedName !in names || data.optInt("id") <= 0) return@mapNotNull null
                val publicTmdbId = data.optString("tmdb_id").toIntOrNull()?.takeIf { it > 0 }
                if (target.tmdbId?.let { it > 0 } == true && publicTmdbId != null && publicTmdbId != target.tmdbId) return@mapNotNull null
                val sourceYear = listOf("release_date", "first_air_date", "last_air_date").firstNotNullOfOrNull { field ->
                    data.optString(field).take(4).toIntOrNull()
                }
                if (!target.series && target.year != null && sourceYear != null && kotlin.math.abs(target.year - sourceYear) > 1) return@mapNotNull null
                val yearScore = when {
                    sourceYear == null || target.year == null -> 0
                    sourceYear == target.year -> 20
                    kotlin.math.abs(sourceYear - target.year) == 1 -> 10
                    else -> 0
                }
                val identityScore = if (publicTmdbId != null && publicTmdbId == target.tmdbId) 200 else 0
                Match(data, 100 + yearScore + identityScore)
            }.sortedByDescending { it.score }.distinctBy { it.data.optInt("id") }
            val bestScore = matches.firstOrNull()?.score ?: return emptyList()
            // Exact names alone can identify several remakes. Never pick an arbitrary first result.
            val selected = matches.filter { it.score == bestScore }.singleOrNull()?.data ?: return emptyList()
            val id = selected.getInt("id")
            val frameUrl = if (target.series) {
                val slug = selected.optString("slug").takeIf { it.isNotBlank() } ?: return emptyList()
                val seasonUrl = SITE.toHttpUrlOrNull()!!.newBuilder().addPathSegments("it/titles")
                    .addPathSegment("$id-$slug").addPathSegment("season-${target.season}").build()
                val seasonPage = read(seasonUrl, phase = "season")
                val encoded = Regex("""\bdata-page\s*=\s*(["'])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(seasonPage.body)?.groupValues?.get(2) ?: return emptyList()
                val episodes = try { JSONObject(decodeHtml(encoded)).optJSONObject("props")?.optJSONObject("loadedSeason")?.optJSONArray("episodes") }
                    catch (_: Exception) { null } ?: return emptyList()
                val episodeId = (0 until minOf(episodes.length(), 2000)).firstNotNullOfOrNull { i ->
                    episodes.optJSONObject(i)?.takeIf { it.optInt("number", -1) == target.episode }
                        ?.optInt("id")?.takeIf { it > 0 }
                } ?: return emptyList()
                SITE.toHttpUrlOrNull()!!.newBuilder().addPathSegments("it/iframe").addPathSegment(id.toString())
                    .addQueryParameter("episode_id", episodeId.toString()).addQueryParameter("next_episode", "1").build()
            } else SITE.toHttpUrlOrNull()!!.newBuilder().addPathSegments("it/iframe").addPathSegment(id.toString()).build()
            val frame = read(frameUrl, phase = "video page")
            val frameHtml = decodeHtml(frame.body).replace("\\/", "/")
            val embedString = Regex("""https://vixcloud\.(?:co|cc)/embed/[^\s"'<>]+""").find(frameHtml)?.value ?: return emptyList()
            val embedUrl = requirePublicHttps(embedString)
            if (embedUrl.host !in setOf("vixcloud.co", "vixcloud.cc")) return emptyList()
            val embedHeaders = mapOf("Referer" to "$SITE/")
            val page = read(embedUrl, embedHeaders, phase = "embedded player")
            val masterObject = assignedObject(page.body, "masterPlaylist") ?: return emptyList()
            val rawMaster = literalValue(objectField(masterObject, "url") ?: return emptyList()) ?: return emptyList()
            val master = requirePublicHttps(embedUrl.resolve(unescapeLiteral(rawMaster))?.toString() ?: return emptyList())
            val paramsRegion = objectField(masterObject, "params").orEmpty()
            val builder = master.newBuilder()
            literalProperties(paramsRegion).forEach { (key, value) ->
                if (value.isNotEmpty()) builder.setQueryParameter(key, unescapeLiteral(value))
            }
            // Match the source's ordinary browser request. In particular, h=1 is an optional FHD
            // entitlement hint: inventing it causes a valid standard-quality playlist to return 403.
            val signedMaster = builder.build()
            // Public master/variant/segment probes work with these headers alone. Do not add an
            // unnecessary Origin: VLC cannot set it and would otherwise require an engine fallback.
            val mediaHeaders = mapOf("Referer" to embedUrl.toString(), "User-Agent" to USER_AGENT)
            val manifest = read(signedMaster, mediaHeaders, 256 * 1024, phase = "playlist")
            return alternateHlsStreams(requirePublicHttps(manifest.url), manifest.body, mediaHeaders, "unity", "StreamingUnity")
        }
        return try {
            legacyLookup()
        } catch (failure: AlternateSourceException) {
            if (preferredIssue == null && !preferredEmpty) throw failure
            val preferred = preferredIssue?.let { issue ->
                issue.httpCode?.let { "VidSpark returned HTTP $it" } ?: "VidSpark could not provide a playable source"
            } ?: "VidSpark has no source for this title"
            val fallback = failure.httpCode?.let { "${safeAlternatePhase(failure.stage)} request returned HTTP $it" }
                ?: "source could not be loaded"
            throw AlternateSourceException("$preferred. The fallback $fallback. Try another title or retry later.", failure.kind, failure.stage, failure.httpCode)
        }
    }

    companion object {
        private const val SITE = "https://streamingunity.fun"
        private const val CACHE_MILLIS = 120_000L
        private const val MAX_PAGE_BYTES = 1024 * 1024
        private val concurrentLookups = Semaphore(2)
        internal const val USER_AGENT = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

private fun normalizedTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .lowercase(Locale.ROOT).replace(Regex("""\p{M}+"""), "")
    .replace(Regex("""^(?:the|an|a)\s+"""), "").filter(Char::isLetterOrDigit)

private val propertyPattern = Regex("""["']([A-Za-z0-9_]+)["']\s*:\s*(?:'((?:\\.|[^'\\])*)'|"((?:\\.|[^"\\])*)")""")
private fun literalProperties(text: String): List<Pair<String, String>> = propertyPattern.findAll(text).take(32).map {
    it.groupValues[1] to (it.groups[2]?.value ?: it.groups[3]?.value.orEmpty())
}.toList()
/** Reads only a bounded literal object; braces inside quoted strings do not end it. */
private fun assignedObject(text: String, name: String): String? {
    val start = Regex("""\b${Regex.escape(name)}\b\s*(?:=|:)\s*\{""").find(text)?.range?.last ?: return null
    val bounded = text.substring(start).take(24_000)
    var depth = 0
    var quote: Char? = null
    var escaped = false
    bounded.forEachIndexed { index, c ->
        if (quote != null) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
        } else when (c) {
            '\'', '"' -> quote = c
            '{' -> depth++
            '}' -> if (--depth == 0) return bounded.substring(0, index + 1)
        }
    }
    return null
}

/** Splits only the object's own fields, rather than matching params or URL from another object. */
private fun objectField(text: String, name: String): String? {
    if (!text.startsWith('{') || !text.endsWith('}')) return null
    val inside = text.substring(1, text.lastIndex)
    val fields = mutableListOf<String>()
    var start = 0
    var depth = 0
    var quote: Char? = null
    var escaped = false
    inside.forEachIndexed { index, c ->
        if (quote != null) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
        } else when (c) {
            '\'', '"' -> quote = c
            '{', '[' -> depth++
            '}', ']' -> depth--
            ',' -> if (depth == 0) { fields += inside.substring(start, index); start = index + 1 }
        }
    }
    fields += inside.substring(start)
    val property = Regex("""^\s*(?:["']${Regex.escape(name)}["']|${Regex.escape(name)})\s*:\s*([\s\S]*)$""")
    return fields.firstNotNullOfOrNull { property.find(it)?.groupValues?.get(1)?.trim() }
}
private fun literalValue(text: String): String? = Regex("""^\s*(?:'((?:\\.|[^'\\])*)'|"((?:\\.|[^"\\])*)")\s*$""")
    .find(text)?.let { it.groups[1]?.value ?: it.groups[2]?.value }
private fun unescapeLiteral(text: String): String = text.replace("\\/", "/").replace("\\'", "'").replace("\\\"", "\"").replace("\\u0026", "&").replace("\\x26", "&")
private fun decodeHtml(text: String): String = Regex("""&(?:quot|apos|amp|lt|gt|#\d+|#x[0-9a-fA-F]+);""").replace(text) { found ->
    when (found.value) {
        "&quot;" -> "\""; "&apos;" -> "'"; "&amp;" -> "&"; "&lt;" -> "<"; "&gt;" -> ">"
        else -> found.value.removePrefix("&#").removeSuffix(";").let { code ->
            val value = if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            value?.takeIf { it in 1..0x10ffff }?.let { String(Character.toChars(it)) } ?: found.value
        }
    }
}

internal fun requirePublicHttps(value: String): HttpUrl {
    val url = value.toHttpUrlOrNull() ?: throw AlternateSourceException("The alternate source returned an invalid link.")
    val host = url.host.lowercase(Locale.ROOT)
    if (url.scheme != "https" || url.username.isNotBlank() || url.password.isNotBlank() || host == "localhost" || host.endsWith(".local") || !host.contains('.') || host.contains(':') || Regex("[0-9.]+").matches(host)) {
        throw AlternateSourceException("The alternate source returned an unsupported link.")
    }
    return url
}

internal fun sameAlternateOrigin(first: HttpUrl, second: HttpUrl): Boolean =
    first.scheme == second.scheme && first.host == second.host && first.port == second.port

internal fun resolveVariant(master: HttpUrl, raw: String): HttpUrl? {
    val variant = master.resolve(raw) ?: return null
    val safe = try { requirePublicHttps(variant.toString()) } catch (_: AlternateSourceException) { return null }
    // Relative variants on the same origin may share the page's signed access parameters. Never
    // propagate them to another host, or overwrite an explicitly supplied child parameter.
    if (raw.startsWith("//") || raw.toHttpUrlOrNull() != null || !sameAlternateOrigin(safe, master)) return safe
    return safe.newBuilder().apply {
        master.queryParameterNames.forEach { name ->
            if (name !in safe.queryParameterNames) master.queryParameterValues(name).forEach { addQueryParameter(name, it) }
        }
    }.build()
}

internal fun rejectDrm(manifest: String) {
    if (manifest.lineSequence().any { line ->
        (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) &&
            (line.contains("SAMPLE-AES", ignoreCase = true) || (line.contains("KEYFORMAT=", ignoreCase = true) && !line.contains("KEYFORMAT=\"identity\"", ignoreCase = true)))
    }) throw AlternateSourceException("The alternate source uses protected media and cannot be played here.", AlternateSourceFailure.PROTECTED_MEDIA)
}

private val alternateHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()

internal suspend fun fetchAlternateSource(request: AlternateSourceHttpRequest): AlternateSourceHttpResponse {
    var url = requirePublicHttps(request.url)
    repeat(5) { redirects ->
        val networkRequest = Request.Builder().url(url).apply { request.headers.forEach { (name, value) -> header(name, value) } }.build()
        val result = alternateHttpClient.newCall(networkRequest).readCancellable { response ->
            if (response.code in 300..399) {
                val location = response.header("Location") ?: throw AlternateSourceException("The alternate source returned an invalid redirect.")
                val next = url.resolve(location) ?: throw AlternateSourceException("The alternate source returned an invalid redirect.")
                requirePublicHttps(next.toString())
                val original = requirePublicHttps(request.url)
                val originScoped = request.headers.keys.any { it.equals("x-player-key", true) } ||
                    (original.host == "api.wecollege.net" && original.queryParameter("seed") != null)
                if (originScoped && !sameAlternateOrigin(next, original)) {
                    throw AlternateSourceException("The public source redirected its API request to an unsupported host.", AlternateSourceFailure.AUTHORIZATION)
                }
                next to null
            } else {
                val body = response.body ?: throw AlternateSourceException("The alternate source returned an empty response.")
                if (body.contentLength() > request.maxBytes) throw AlternateSourceException("The alternate source response was too large.")
                val output = ByteArrayOutputStream(minOf(request.maxBytes, 8192))
                val buffer = ByteArray(8192)
                body.byteStream().use { input ->
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, request.maxBytes - output.size() + 1))
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        if (output.size() > request.maxBytes) throw AlternateSourceException("The alternate source response was too large.")
                    }
                }
                null to AlternateSourceHttpResponse(response.request.url.toString(), response.code, output.toString(Charsets.UTF_8.name()))
            }
        }
        result.second?.let { return it }
        if (redirects == 4) throw AlternateSourceException("The alternate source redirected too many times.")
        url = result.first!!
    }
    throw AlternateSourceException("The alternate source is unavailable.")
}

/** Keep the master when selecting a child would drop externally referenced audio/subtitles. */
internal fun alternateHlsStreams(master: HttpUrl, body: String, headers: Map<String, String>, sourceId: String, sourceLabel: String): List<AlternateStream> {
    if (!body.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) {
        throw AlternateSourceException("The alternate source did not provide a playable HLS stream.")
    }
    rejectDrm(body)
    val lines = body.lineSequence().map(String::trim).toList()
    fun attribute(line: String, name: String): String? = Regex("""(?:^|,)${Regex.escape(name)}=(?:"([^"]*)"|([^,]*))""")
        .find(line.substringAfter(':'))?.let { it.groups[1]?.value ?: it.groups[2]?.value }
    val externalGroups = lines.filter { it.startsWith("#EXT-X-MEDIA:") && attribute(it, "URI") != null }
        .mapNotNull { line -> attribute(line, "TYPE")?.takeIf { it == "AUDIO" || it == "SUBTITLES" }?.let { type -> attribute(line, "GROUP-ID")?.let { type to it } } }.toSet()
    val streams = mutableListOf(AlternateStream("$sourceId:auto", "Auto · $sourceLabel", master.toString(), headers))
    lines.forEachIndexed { index, line ->
        if (!line.startsWith("#EXT-X-STREAM-INF:") || streams.size >= 9) return@forEachIndexed
        if (listOf("AUDIO", "SUBTITLES").any { type -> attribute(line, type)?.let { type to it } in externalGroups }) return@forEachIndexed
        val codecs = attribute(line, "CODECS")
        // A declared video-only child is not a substitute for the complete master.
        if (codecs != null && !Regex("mp4a|ac-3|ec-3|opus|flac|vorbis", RegexOption.IGNORE_CASE).containsMatchIn(codecs)) return@forEachIndexed
        val raw = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return@forEachIndexed
        val variant = resolveVariant(master, raw) ?: return@forEachIndexed
        if (streams.any { it.url == variant.toString() }) return@forEachIndexed
        val height = attribute(line, "RESOLUTION")?.substringAfter('x')?.toIntOrNull()
        val number = streams.size
        streams += AlternateStream("$sourceId:quality:$number", "${height?.let { "${it}p" } ?: "Quality $number"} · $sourceLabel", variant.toString(), headers, height = height)
    }
    return streams.take(1) + streams.drop(1).sortedByDescending { it.height ?: 0 }
}

private fun safeAlternatePhase(value: String?): String = value?.takeIf { it.length <= 48 && it.all { c -> c.isLetter() || c == ' ' } } ?: "source"
private fun alternateSourceTrace(event: String) {
    // Only fixed phase names, status/counts and enum values are passed here. No URLs, headers,
    // provider bodies, client values or signed parameters are logged, including debug builds.
    if (BuildConfig.DEBUG) Log.d("NikTvAlternateSource", event.take(160))
}
