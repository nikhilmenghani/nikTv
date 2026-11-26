package com.nikhil.niktv.torrent

import com.nikhil.niktv.data.TmdbClient
import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.discovery.discovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class TorrentSearchProvider(val label: String) {
    TORRENTCLAW("TorrentClaw"), APIBAY("The Pirate Bay"), BITSEARCH("Bitsearch"), CSV("Torrents.csv")
}

internal data class RawTorrentListing(val title: String, val hash: String?, val size: Long?, val seeds: Int?,
    val magnet: String? = null, val torrent: String? = null)

internal class TorrentSourceAccessRequired : IOException("This source requires an API key for playback links")

/** Metadata comes from TMDB; source discovery is independent of IPTV sessions and user-sync state. */
internal class TorrentSearchRepository(
    private val tmdb: TmdbClient = TmdbClient(),
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun titles(query: String, series: Boolean): List<DiscoveryCard> {
        check(tmdb.configured) { "TMDB is not configured. Connect your account and try again." }
        val term = query.trim().take(120)
        if (term.isEmpty()) {
            val titles = if (series) tmdb.trendingSeries(20).map { it.discovery() }
                else tmdb.trendingMovies(20).map { it.discovery() }
            val today = torrentCurrentDate()
            return titles.filter { it.torrentPublished(today) }
        }
        return withContext(Dispatchers.Default) {
            val root = tmdb.discoveryJson(if (series) "/search/tv" else "/search/movie",
                mapOf("query" to term, "include_adult" to "false"))
            root["results"]?.jsonArray.orEmpty().mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                val id = row.int("id") ?: return@mapNotNull null
                if (row["adult"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
                val title = row.text(if (series) "name" else "title") ?: return@mapNotNull null
                DiscoveryCard(id, series, title, row.text("overview"), artwork(row.text("poster_path")),
                    artwork(row.text("backdrop_path"), "w1280"), row.text(if (series) "first_air_date" else "release_date"),
                    row["vote_average"]?.jsonPrimitive?.doubleOrNull, originalLanguage = row.text("original_language"))
            }.filter { it.torrentPublished() }
        }
    }

    suspend fun seasons(card: DiscoveryCard): List<Int> = tmdb.episodeSeasons(card.id).filter { it > 0 }.sorted()
    suspend fun episodes(card: DiscoveryCard, season: Int): List<TmdbEpisode> {
        val today = torrentCurrentDate()
        return tmdb.seasonEpisodes(card.id, season).filter {
            it.airDate.isNullOrBlank() || torrentPublicationDateAvailable(it.airDate, today)
        }
    }

    suspend fun searchSources(card: DiscoveryCard, episode: TmdbEpisode? = null,
        onUpdate: (TorrentSearchSnapshot) -> Unit = {}): TorrentSearchSnapshot = coroutineScope {
        require(!card.series || episode != null) { "Choose an episode before searching for streams." }
        require(!card.adult) { "This title is not available in this portal." }
        // TorrentClaw's documented endpoint in NikBot is movie-specific. Do not
        // advertise it as a working TV adapter until a TV response contract exists.
        val providers = TorrentSearchProvider.entries.filter { !card.series || it != TorrentSearchProvider.TORRENTCLAW }
        var snapshot = TorrentSearchSnapshot(pending = providers.map { it.label }.toSet())
        val updates = Mutex()
        onUpdate(snapshot)
        val queries = if (card.series) listOf(
            card.title + " S%02dE%02d".format(episode!!.seasonNumber, episode.episodeNumber),
            card.title + " S%02d".format(episode.seasonNumber)
        ) else listOf(card.title + card.date?.take(4)?.let { " $it" }.orEmpty())
        val jobs = providers.map { provider -> launch {
            var failure: String? = null
            val results = try {
                // Episode-specific and season-pack searches run together. A
                // popular season cannot push the selected episode off page one.
                val fetched = coroutineScope { queries.map { query -> async {
                    try { Result.success(fetch(provider, query, card)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure<String>(error) }
                } }.awaitAll() }
                if (fetched.all { it.isFailure }) throw requireNotNull(fetched.first().exceptionOrNull())
                if (fetched.any { it.isFailure }) failure = "Some queries did not complete"
                withContext(Dispatchers.Default) {
                    val listings = fetched.mapNotNull { it.getOrNull() }.flatMap { body -> when (provider) {
                        TorrentSearchProvider.TORRENTCLAW -> parseTorrentClaw(json.parseToJsonElement(body), card)
                        TorrentSearchProvider.APIBAY -> parseApiBay(json.parseToJsonElement(body))
                        TorrentSearchProvider.CSV -> parseTorrentsCsv(json.parseToJsonElement(body))
                        TorrentSearchProvider.BITSEARCH -> parseBitsearch(body)
                    } }
                    listings.mapNotNull { sourceFromListing(it, card, episode, provider.label) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failure = when (error) {
                    is TorrentSourceAccessRequired -> error.message
                    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "Timed out"
                    is IOException -> "Could not reach this source"
                    else -> "Source returned an unsupported response"
                }
                emptyList()
            }
            updates.withLock {
                snapshot = snapshot.copy(results = mergeTorrentSources(snapshot.results + results),
                    pending = snapshot.pending - provider.label,
                    errors = if (failure == null) snapshot.errors else snapshot.errors + (provider.label to failure!!))
                onUpdate(snapshot)
            }
        } }
        jobs.forEach { it.join() }
        snapshot
    }

    private suspend fun fetch(provider: TorrentSearchProvider, query: String, card: DiscoveryCard): String {
        val (base, params) = when (provider) {
            TorrentSearchProvider.TORRENTCLAW -> "https://torrentclaw.com/api/v1/search" to mapOf("q" to card.title, "contentType" to "movie")
            TorrentSearchProvider.APIBAY -> "https://apibay.org/q.php" to mapOf("q" to query, "cat" to if (card.series) "205,208" else "200")
            TorrentSearchProvider.BITSEARCH -> "https://bitsearch.eu/search" to mapOf("q" to query)
            TorrentSearchProvider.CSV -> "https://torrents-csv.com/service/search" to mapOf("q" to query)
        }
        val url = base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("User-Agent", "NikTV/1.0")
            .header("Accept", if (provider == TorrentSearchProvider.BITSEARCH) "text/html" else "application/json").build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw IOException("Source HTTP ${it.code}")
                            val body = it.body
                            if (body.contentLength() > MAX_RESPONSE) throw IOException("Source response too large")
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_RESPONSE) throw IOException("Source response too large")
                                    output.write(buffer, 0, count)
                                }
                                output.toString(Charsets.UTF_8.name())
                            }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            })
        }
    }

    private fun artwork(path: String?, size: String = "w500") = path?.takeIf { it.startsWith('/') }?.let { "https://image.tmdb.org/t/p/$size$it" }
    companion object { private const val MAX_RESPONSE = 4 * 1024 * 1024 }
}

internal fun sourceFromListing(row: RawTorrentListing, card: DiscoveryCard, episode: TmdbEpisode?, provider: String): TorrentSource? {
    val hash = validatedTorrentHash(row.hash) ?: return null
    val size = row.size?.takeIf { it > 0 } ?: return null
    val seeds = row.seeds?.takeIf { it > 0 } ?: return null
    if (!torrentListingMatches(row.title, card, episode)) return null
    val magnet = if (row.magnet.isNullOrBlank()) torrentMagnet(hash, row.title) else matchingTorrentMagnet(row.magnet, hash) ?: return null
    val descriptor = row.torrent?.takeIf {
        runCatching { val uri = java.net.URI(it); uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() }.getOrDefault(false)
    }
    return TorrentSource(hash, row.title.take(400), magnet, descriptor, size, seeds, torrentQuality(row.title), provider,
        episode != null && torrentSeasonPack(row.title, episode.seasonNumber))
}

internal fun parseApiBay(root: JsonElement): List<RawTorrentListing> {
    require(root is JsonArray) { "Expected source result array" }
    return root.mapNotNull { (it as? JsonObject)?.listing("info_hash", "size") }
}

internal fun parseTorrentsCsv(root: JsonElement): List<RawTorrentListing> {
    val array = (root as? JsonObject)?.get("torrents") as? JsonArray ?: error("Expected torrent list")
    return array.mapNotNull { (it as? JsonObject)?.listing("infohash", "size_bytes") }
}

internal fun parseTorrentClaw(root: JsonElement, card: DiscoveryCard): List<RawTorrentListing> {
    val response = root as? JsonObject ?: error("Expected torrent results")
    val array = response["results"] as? JsonArray ?: error("Expected torrent results")
    if (response.text("_notice")?.contains("API key", ignoreCase = true) == true &&
        array.none { movie -> ((movie as? JsonObject)?.get("torrents") as? JsonArray).orEmpty().any {
            (it as? JsonObject)?.text("infoHash") != null
        } }) throw TorrentSourceAccessRequired()
    return array.flatMap { item ->
        val movie = item as? JsonObject ?: return@flatMap emptyList()
        if (movie.int("tmdbId") != card.id || movie.text("year") != card.date?.take(4) ||
            torrentTitleKey(movie.text("title").orEmpty()) != torrentTitleKey(card.title)) return@flatMap emptyList()
        (movie["torrents"] as? JsonArray).orEmpty().mapNotNull { row ->
            val torrent = row as? JsonObject ?: return@mapNotNull null
            RawTorrentListing(torrent.text("rawTitle") ?: "${card.title} ${card.date?.take(4)} ${torrent.text("quality").orEmpty()}",
                torrent.text("infoHash"), torrent.long("sizeBytes"), torrent.int("seeders"), torrent.text("magnetUrl"), torrent.text("torrentUrl"))
        }
    }
}

internal fun parseBitsearch(html: String): List<RawTorrentListing> {
    val document = Jsoup.parse(html, "https://bitsearch.eu")
    check(!Regex("(?i)(just a moment|access denied|verify you are human|captcha)").containsMatchIn(document.title())) {
        "Source requires an interactive browser"
    }
    return document.select(".torrent-result-card").mapNotNull { card ->
    val title = card.selectFirst("a[href^=/torrent/]")?.text()?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    val magnet = card.selectFirst("a[href^=magnet:]")?.attr("href")
    val descriptor = card.selectFirst("a[href^=/download/torrent/]")?.absUrl("href")
    val hash = Regex("(?i)urn:btih:([a-f0-9]{40})(?:&|$)").find(magnet.orEmpty())?.groupValues?.get(1)
        ?: Regex("(?i)/download/torrent/([a-f0-9]{40})").find(descriptor.orEmpty())?.groupValues?.get(1)
    val sizePattern = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB|TB)")
    // Inline spans need not insert whitespace in Element.text(); parse the
    // actual size span so a neighbouring title/count cannot eat its boundary.
    val sizeMatch = card.select("span").firstNotNullOfOrNull { sizePattern.matchEntire(it.text().trim()) }
        ?: Regex("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB|TB)\\b").find(card.text())
    val size = sizeMatch?.let {
        val power = listOf("B", "KB", "MB", "GB", "TB").indexOf(it.groupValues[2].uppercase())
        (it.groupValues[1].toDouble() * Math.pow(1024.0, power.toDouble())).toLong()
    }
    val seeds = card.select("span").firstOrNull { it.text().equals("seeders", true) }
        ?.previousElementSibling()?.text()?.replace(",", "")?.toIntOrNull()
    RawTorrentListing(title, hash, size, seeds, magnet, descriptor)
}
}

private fun JsonObject.listing(hashKey: String, sizeKey: String) = text("name")?.let {
    RawTorrentListing(it, text(hashKey), long(sizeKey), int("seeders"))
}
private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
