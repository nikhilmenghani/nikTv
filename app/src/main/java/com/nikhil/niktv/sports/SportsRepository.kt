package com.nikhil.niktv.sports

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(SportsException("Could not connect to the sports service.")) }
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
    val transport: SportsTransport = SportsHttpTransport(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val configuration = Mutex()
    private var base = "https://cftkhffmgf147.top/"
    private var configuredAt = 0L
    private var cachedEvents: Pair<Long, List<SportsEvent>>? = null

    suspend fun events(refresh: Boolean = false): List<SportsEvent> {
        cachedEvents?.takeIf { !refresh && now() - it.first < 120_000 }?.let { return it.second }
        val result = SportsCatalogue.events(catalogue("categories/live-events.txt", refresh))
        cachedEvents = now() to result
        return result
    }

    suspend fun streams(slug: String): List<SportsStream> {
        require(slug.matches(Regex("[A-Za-z0-9_-]{1,200}")))
        return SportsCatalogue.streams(catalogue("channels/${slug.lowercase(java.util.Locale.ROOT)}.txt"))
    }

    private suspend fun catalogue(path: String, refresh: Boolean = false): String {
        configure(refresh)
        try { return transport.get(base + path, emptyMap()).body }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) {
            configure(true)
            return transport.get(base + path, emptyMap()).body
        }
    }

    private suspend fun configure(force: Boolean) = configuration.withLock {
        if (!force && configuredAt != 0L && now() - configuredAt < 15 * 60_000L) return@withLock
        try {
            val response = transport.get("https://pub-9610baca6bf84734bf9d40546dc754a6.r2.dev/config.txt", emptyMap())
            val url = SportsCatalogue.baseUrl(response.body).toHttpUrlOrNull()
                ?: throw SportsException("Sports configuration contains an invalid server address.")
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
                throw SportsException("Sports configuration contains an invalid server address.")
            base = url.toString().trimEnd('/') + "/"
            configuredAt = now()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { /* The last known HTTPS server remains usable during a configuration outage. */ }
    }
}
