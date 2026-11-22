package com.nikhil.niktv.sports

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val PLAYFY_DEFAULT_BASE = "https://easypdfmaker.online/"

/** Playfy endpoints are never candidates for the Cricfy catalogue protocol. */
internal class PlayfyRepository(
    override val transport: SportsTransport = SportsHttpTransport(),
    private val endpointStore: SportsEndpointStore? = null,
    private val country: () -> String = { java.util.Locale.getDefault().country },
    private val credential: (String) -> String = com.nikhil.niktv.data.RemoteCredentials::get,
    private val now: () -> Long = System::currentTimeMillis,
) : SportsCatalogueRepository {
    private val lock = Mutex()
    private var region: String? = null
    private var history = emptyList<SportsConfiguration>()
    private var working: SportsConfiguration? = null
    private var preferred: String? = null
    private var active: String? = null
    private val health = mutableMapOf<String, SportsServerHealth>()
    private val mutableServers = MutableStateFlow(SportsServersState())
    override val servers = mutableServers.asStateFlow()
    private data class Cached(val at: Long, val document: JsonElement)
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Cached>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 16
    })

    override suspend fun events(refresh: Boolean) = request("cats/live-events.json", refresh, PlayfyCatalogue::events)
        .filter { sportsGeoAvailable(it.geo, country()) }.map { event ->
            val formats = event.formats.filter { sportsGeoAvailable(it.geo, country()) }
            event.copy(formats = formats, choices = formats.map { it.title })
        }
    override suspend fun eventFilters(refresh: Boolean) = request("cats/live-events.json", refresh, PlayfyCatalogue::eventFilters)
    override suspend fun categories(refresh: Boolean) = request("cc.json", refresh, PlayfyCatalogue::categories)
        .filter { sportsGeoAvailable(it.geo, country()) }
    override suspend fun highlights(refresh: Boolean) = request("cats/highlights.json", refresh, PlayfyCatalogue::events)
        .filter { sportsGeoAvailable(it.geo, country()) }.map { it.asChannel().copy(category = "Highlights") }

    override suspend fun channels(category: SportsCategory, refresh: Boolean): List<SportsChannel> {
        if (!sportsGeoAvailable(category.geo, country())) throw SportsException("This category is not available in your region.")
        val rows = if (category.isPlaylist) {
            if (!sportsNetworkUrl(category.link)) throw SportsException("This playlist address is unsupported.")
            val body = transport.get(category.link, mapOf("User-Agent" to SPORTS_USER_AGENT)).body
            withContext(Dispatchers.Default) { SportsPlaylist.parse(body, category) }
        } else request(PlayfyCatalogue.categoryPath(category.title), refresh) { PlayfyCatalogue.channels(it, category.title) }
        return rows.filter { sportsGeoAvailable(it.geo, country()) }.map {
            it.copy(formats = it.formats.filter { format -> sportsGeoAvailable(format.geo, country()) })
        }
    }

    override suspend fun detail(slug: String, refresh: Boolean): SportsChannelDetail {
        val id = PlayfyCatalogue.channelId(slug) ?: throw SportsException("This Playfy channel is invalid.")
        val result = request("channels/$id.json", refresh, PlayfyCatalogue::detail)
        return result.copy(streams = result.streams.filter { sportsGeoAvailable(it.geo, country()) }.map { stream ->
            stream.copy(contentId = "playfy:$id:${stream.title}",
                headers = if (stream.headers.keys.any { it.equals("User-Agent", true) }) stream.headers
                    else stream.headers + ("User-Agent" to SPORTS_USER_AGENT))
        })
    }

    private fun validBase(value: String): String? = value.trim().toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }?.toString()?.trimEnd('/')?.plus("/")

    private suspend fun configure() = lock.withLock {
        val selected = sportsCountry(country())
        if (region == selected) return@withLock
        working = withContext(Dispatchers.IO) { endpointStore?.load(selected) }?.takeIf { validBase(it.primary) != null }
        history = withContext(Dispatchers.IO) { endpointStore?.known(selected).orEmpty() }.filter { validBase(it.primary) != null }
        preferred = withContext(Dispatchers.IO) { endpointStore?.preferred(selected) }?.let(::validBase)
        region = selected
        active = null; health.clear(); cache.clear(); publish()
    }

    private fun candidates(refresh: Boolean = false): List<SportsConfiguration> {
        val advertised = SportsConfiguration(validBase(credential("NIKTV_PLAYFY_API_URL")) ?: PLAYFY_DEFAULT_BASE)
        val pinned = preferred?.let { SportsConfiguration(it) }
        return (listOfNotNull(pinned) + if (refresh) listOf(advertised) + listOfNotNull(working) + history
            else listOfNotNull(working) + advertised + history) .plus(SportsConfiguration(PLAYFY_DEFAULT_BASE)).distinctBy { it.primary }
    }

    private fun publish(checking: Boolean = mutableServers.value.checking) {
        mutableServers.value = SportsServersState(candidates().map { SportsServer(it.primary, health[it.primary] ?: SportsServerHealth.UNCHECKED) },
            preferred, active, checking)
    }

    private suspend fun <T> request(path: String, refresh: Boolean, parse: (JsonElement) -> T): T {
        configure()
        val selected = sportsCountry(country())
        val key = "$selected:${credential("NIKTV_PLAYFY_API_URL")}:$path"
        cache[key]?.takeIf { !refresh && now() - it.at in 0 until 120_000 }?.let {
            return withContext(Dispatchers.Default) { parse(it.document) }
        }
        var failure: IOException = SportsException("The Playfy service is unavailable.")
        for (candidate in lock.withLock { candidates(refresh) }) {
            try {
                val body = transport.get(candidate.primary + path, mapOf("User-Agent" to candidate.userAgent) +
                    if (refresh) mapOf("Cache-Control" to "no-cache") else emptyMap()).body
                val document = withContext(Dispatchers.Default) { PlayfyEnvelopeDecoder.document(body, credential) }
                val result = withContext(Dispatchers.Default) { parse(document) }
                lock.withLock {
                    if (region == selected) {
                        val changed = working != candidate
                        working = candidate; active = candidate.primary; health[candidate.primary] = SportsServerHealth.AVAILABLE
                        history = (listOf(candidate) + history).distinctBy { it.primary }.take(8)
                        cache[key] = Cached(now(), document); publish()
                        if (changed) withContext(Dispatchers.IO) { runCatching { endpointStore?.save(selected, candidate) } }
                    }
                }
                return result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IOException) {
                failure = error
                lock.withLock { if (region == selected) { health[candidate.primary] = SportsServerHealth.UNAVAILABLE; publish() } }
            }
        }
        throw failure
    }

    override suspend fun checkServers() {
        configure()
        val selected = sportsCountry(country())
        val pending = lock.withLock { publish(checking = true); candidates(refresh = true) }
        try {
            for (candidate in pending) {
                lock.withLock { if (region == selected) { health[candidate.primary] = SportsServerHealth.CHECKING; publish() } }
                val status = try {
                    val body = transport.get(candidate.primary + "cc.json", mapOf("User-Agent" to candidate.userAgent, "Cache-Control" to "no-cache")).body
                    withContext(Dispatchers.Default) { PlayfyCatalogue.categories(PlayfyEnvelopeDecoder.document(body, credential)) }
                    SportsServerHealth.AVAILABLE
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { SportsServerHealth.UNAVAILABLE }
                lock.withLock { if (region == selected) { health[candidate.primary] = status; publish() } }
            }
        } finally {
            withContext(NonCancellable) { lock.withLock {
                if (region == selected) {
                    health.replaceAll { _, value -> if (value == SportsServerHealth.CHECKING) SportsServerHealth.UNCHECKED else value }
                    publish(checking = false)
                }
            } }
        }
    }

    override suspend fun selectServer(base: String?) {
        configure()
        lock.withLock {
            require(base == null || candidates().any { it.primary == base }) { "This Playfy server is no longer listed." }
            withContext(Dispatchers.IO) { endpointStore?.prefer(checkNotNull(region), base) }
            preferred = base; cache.clear(); publish()
        }
    }
}
