package com.nikhil.niktv.sports

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val SPORTS_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
internal data class SportsResponse(val url: String, val body: String)
internal fun interface SportsTransport {
    suspend fun get(url: String, headers: Map<String, String>): SportsResponse
    suspend fun post(url: String, headers: Map<String, String>, form: Map<String, String>): SportsResponse =
        throw SportsException("This source requires a token service that is unavailable.")
}

internal class SportsHttpTransport : SportsTransport {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    override suspend fun get(url: String, headers: Map<String, String>): SportsResponse = fetch(url, headers, null)
    override suspend fun post(url: String, headers: Map<String, String>, form: Map<String, String>): SportsResponse = fetch(url, headers, form)
    private suspend fun fetch(url: String, headers: Map<String, String>, form: Map<String, String>?): SportsResponse = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull() ?: throw SportsException("The sports service returned an invalid address.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw SportsException("The sports service returned an invalid address.")
        val request = Request.Builder().url(parsed).header("User-Agent", SPORTS_USER_AGENT).apply {
            headers.forEach { (key, value) -> if ('\r' !in value && '\n' !in value) header(key, value) }
            if (form != null) post(FormBody.Builder().apply { form.forEach { (key, value) -> add(key, value) } }.build())
        }.build()
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(SportsException("Could not connect to the sports service.", e)) }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        response.use {
            if (!it.isSuccessful) throw SportsException("The sports service returned HTTP ${it.code}.")
            val body = it.body ?: throw SportsException("The sports service returned an empty response.")
            val max = 4L * 1024 * 1024
            if (body.contentLength() > max) throw SportsException("The sports response is too large.")
            val source = body.source()
            source.request(max + 1)
            if (source.buffer.size > max) throw SportsException("The sports response is too large.")
            val bytes = source.buffer.readByteArray()
            SportsResponse(it.request.url.toString(), bytes.toString(Charsets.UTF_8))
        }
    }
}

internal class SportsRepository(
    override val transport: SportsTransport = SportsHttpTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val country: () -> String = { java.util.Locale.getDefault().country },
    private val endpointStore: SportsEndpointStore? = null,
) : SportsCatalogueRepository {
    private val configuration = Mutex()
    private var config = SportsConfiguration()
    private var advertisedConfig = config
    private var lastKnownWorking: SportsConfiguration? = null
    private var knownEndpoints = emptyList<SportsConfiguration>()
    private var preferredBase: String? = null
    private var activeBase: String? = null
    private val health = mutableMapOf<String, SportsServerHealth>()
    private val serverState = MutableStateFlow(SportsServersState())
    override val servers = serverState.asStateFlow()
    private var preferWorking = false
    private var configuredAt = 0L
    private var configuredCountry: String? = null
    private data class CachedEvents(val time: Long, val country: String, val events: List<SportsEvent>)
    private var cachedEvents: CachedEvents? = null
    private data class CachedBody(val at: Long, val body: String)
    private val bodies = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, CachedBody>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedBody>?) = size > 16
    })

    override suspend fun events(refresh: Boolean): List<SportsEvent> {
        val region = sportsCountry(country())
        cachedEvents?.takeIf { !refresh && it.country == region && now() - it.time in 0 until 120_000 }?.let { return it.events }
        val result = catalogue("categories/live-events.txt", refresh, decode = SportsCatalogue::events).filter { sportsGeoAvailable(it.geo, region) }.map { event ->
            val formats = event.formats.filter { sportsGeoAvailable(it.geo, region) }
            event.copy(formats = formats, choices = formats.map { it.title })
        }
        cachedEvents = CachedEvents(now(), region, result)
        return result
    }

    suspend fun streams(slug: String): List<SportsStream> {
        return detail(slug).streams
    }

    override suspend fun detail(slug: String, refresh: Boolean): SportsChannelDetail {
        require(sportsSlug(slug))
        val detail = catalogue("channels/${slug.lowercase(java.util.Locale.ROOT)}.txt", refresh, decode = SportsCatalogue::detail)
        val region = sportsCountry(country())
        if (!sportsGeoAvailable(detail.geo, region)) throw SportsException("This channel is not available in your region.")
        val available = detail.streams.filter { sportsGeoAvailable(it.geo, region) }.map { stream ->
            if (stream.headers.keys.any { it.equals("User-Agent", true) }) stream else stream.copy(headers = stream.headers + ("User-Agent" to config.userAgent))
        }
        if (detail.streams.isNotEmpty() && available.isEmpty()) throw SportsException("No streams are available for your region${region.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()}.")
        return detail.copy(streams = available, related = detail.related.filter { sportsGeoAvailable(it.geo, region) })
    }

    override suspend fun categories(refresh: Boolean): List<SportsCategory> = catalogue("cats.txt", refresh, cached = true, decode = SportsCatalogue::categories)
        .filter { sportsGeoAvailable(it.geo, country()) }

    override suspend fun eventFilters(refresh: Boolean): List<SportsEventFilter> = catalogue("eventCats.txt", refresh, cached = true, decode = SportsCatalogue::filters)

    override suspend fun channels(category: SportsCategory, refresh: Boolean): List<SportsChannel> {
        if (!sportsGeoAvailable(category.geo, country())) throw SportsException("This category is not available in your region.")
        val channels = if (category.isPlaylist) {
            configure(false)
            if (!sportsNetworkUrl(category.link)) throw SportsException("This playlist address is unsupported.")
            val key = "external:${sportsCountry(country())}:${config.userAgent}:${category.link}"
            val body = cachedBody(key, refresh) {
                val body = transport.get(category.link, mapOf("User-Agent" to config.userAgent)).body
                withContext(Dispatchers.Default) { SportsPlaylist.parse(body, category) }
                body
            }
            withContext(Dispatchers.Default) { SportsPlaylist.parse(body, category) }
        } else catalogue(category.path, refresh, cached = true, decode = SportsCatalogue::channels)
        return withContext(Dispatchers.Default) {
            channels
                .filter { sportsGeoAvailable(it.geo, country()) }
                .map { channel -> channel.copy(formats = channel.formats.filter { sportsGeoAvailable(it.geo, country()) }) }
        }
    }

    private suspend fun cachedBody(key: String, refresh: Boolean, fetch: suspend () -> String): String {
        bodies[key]?.takeIf { !refresh && now() - it.at in 0 until 120_000 }?.let { return it.body }
        return fetch().also { bodies[key] = CachedBody(now(), it) }
    }

    private suspend fun <T> catalogue(path: String, refresh: Boolean, cached: Boolean = false, decode: (String) -> T): T {
        configure(refresh)
        val region = sportsCountry(country())
        val cacheKey = configuration.withLock { "$region:${advertisedConfig.primary}:${advertisedConfig.userAgent}:$path" }
        bodies[cacheKey]?.takeIf { cached && !refresh && now() - it.at in 0 until 120_000 }?.let {
            return withContext(Dispatchers.Default) { decode(it.body) }
        }
        var failure: IOException = SportsException("The sports service is unavailable.")
        val tried = mutableSetOf<String>()
        repeat(2) { attempt ->
            val candidates = configuration.withLock { candidates() }
            for (candidate in candidates.distinctBy { it.primary }) {
                if (!tried.add(candidate.primary)) continue
                val headers = mapOf("User-Agent" to candidate.userAgent) +
                    if (refresh) mapOf("Cache-Control" to "no-cache") else emptyMap()
                try {
                    val body = transport.get(candidate.primary + path, headers).body
                    // An HTTP 200 error page or malformed catalogue is not a working endpoint.
                    val decoded = withContext(Dispatchers.Default) { decode(body) }
                    rememberWorking(region, candidate.copy(secondary = null))
                    if (cached) bodies[cacheKey] = CachedBody(now(), body)
                    return decoded
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: IOException) {
                    failure = error
                    configuration.withLock {
                        if (configuredCountry == region) { health[candidate.primary] = SportsServerHealth.UNAVAILABLE; publishServers() }
                    }
                }
            }
            if (attempt == 0) configure(true)
        }
        throw failure
    }

    private suspend fun rememberWorking(region: String, working: SportsConfiguration) = configuration.withLock {
        if (configuredCountry != region) return@withLock
        config = working
        activeBase = working.primary
        preferWorking = true
        health[working.primary] = SportsServerHealth.AVAILABLE
        knownEndpoints = (listOf(working) + knownEndpoints).distinctBy { it.primary }.take(8)
        val changed = lastKnownWorking != working
        lastKnownWorking = working
        publishServers()
        if (!changed) return@withLock
        withContext(Dispatchers.IO) { runCatching { endpointStore?.save(region, working) } }
    }

    private suspend fun configure(force: Boolean) = configuration.withLock {
        val region = sportsCountry(country())
        if (configuredCountry != region) {
            lastKnownWorking = withContext(Dispatchers.IO) { runCatching { endpointStore?.load(region) }.getOrNull() }
            knownEndpoints = withContext(Dispatchers.IO) { runCatching { endpointStore?.known(region).orEmpty() }.getOrDefault(emptyList()) }
            preferredBase = withContext(Dispatchers.IO) { runCatching { endpointStore?.preferred(region) }.getOrNull() }
            activeBase = null
            health.clear()
            config = lastKnownWorking ?: SportsConfiguration()
            advertisedConfig = config
            preferWorking = false
            configuredCountry = region
            configuredAt = 0L
            publishServers(checking = false)
        }
        if (!force && configuredCountry == region && configuredAt != 0L && now() - configuredAt < 15 * 60_000L) return@withLock
        try {
            val response = transport.get("https://pub-9610baca6bf84734bf9d40546dc754a6.r2.dev/config.txt",
                if (force) mapOf("Cache-Control" to "no-cache") else emptyMap())
            advertisedConfig = sportsConfiguration(response.body, region)
            // Give newly fetched configuration a chance without discarding the working endpoint.
            preferWorking = false
            configuredAt = now()
            publishServers()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { /* Keep the previously working regional configuration. */ }
    }

    /** Caller holds configuration; all addresses come from validated configuration or saved history. */
    private fun candidates(): List<SportsConfiguration> {
        val advertised = listOfNotNull(advertisedConfig, advertisedConfig.secondary?.let { advertisedConfig.copy(primary = it, secondary = null) })
        val working = listOfNotNull(lastKnownWorking)
        val preferred = preferredBase?.let { base ->
            (working + knownEndpoints + advertised).firstOrNull { it.primary == base } ?: SportsConfiguration(base)
        }
        return (listOfNotNull(preferred) + (if (preferWorking) working + advertised else advertised + working) +
            knownEndpoints + SportsConfiguration()).distinctBy { it.primary }
    }

    private fun publishServers(checking: Boolean = serverState.value.checking) {
        serverState.value = SportsServersState(candidates().map { SportsServer(it.primary, health[it.primary] ?: SportsServerHealth.UNCHECKED) },
            preferredBase, activeBase, checking)
    }

    override suspend fun checkServers() {
        configure(true)
        val region = sportsCountry(country())
        val pending = configuration.withLock { publishServers(checking = true); candidates() }
        try {
            for (candidate in pending) {
                configuration.withLock {
                    if (configuredCountry == region) { health[candidate.primary] = SportsServerHealth.CHECKING; publishServers() }
                }
                val result = try {
                    val body = transport.get(candidate.primary + "cats.txt", mapOf("User-Agent" to candidate.userAgent, "Cache-Control" to "no-cache")).body
                    withContext(Dispatchers.Default) { SportsCatalogue.categories(body) }
                    SportsServerHealth.AVAILABLE
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) { SportsServerHealth.UNAVAILABLE }
                configuration.withLock {
                    if (configuredCountry == region) { health[candidate.primary] = result; publishServers() }
                }
            }
        } finally {
            withContext(NonCancellable) { configuration.withLock {
                if (configuredCountry == region) {
                    health.replaceAll { _, value -> if (value == SportsServerHealth.CHECKING) SportsServerHealth.UNCHECKED else value }
                    publishServers(checking = false)
                }
            } }
        }
    }

    override suspend fun selectServer(base: String?) {
        configure(false)
        val region = sportsCountry(country())
        configuration.withLock {
            require(base == null || candidates().any { it.primary == base }) { "This sports server is no longer listed." }
            withContext(Dispatchers.IO) { endpointStore?.prefer(region, base) }
            preferredBase = base
            cachedEvents = null
            bodies.clear()
            publishServers()
        }
    }
}
