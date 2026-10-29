package com.nikhil.niktv.sports

import com.nikhil.niktv.data.RemoteCredentials
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class CricketHighlightsConnection(val url: String, val token: String = "") {
    val configured get() = cricketHttpsUrl(url)
    override fun toString() = "CricketHighlightsConnection(configured=$configured)"
}

internal fun interface CricketHighlightsTransport {
    suspend fun get(url: String, headers: Map<String, String>): ByteArray
}

internal class CricketAccessDenied(val serverTimeMillis: Long?) : IOException(
    "Cricbuzz access was rejected. Refresh and retry.")

internal class CricketHighlightsHttpTransport : CricketHighlightsTransport {
    // A provider credential must never be redirected to another origin.
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    override suspend fun get(url: String, headers: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        if (!cricketHttpsUrl(url)) throw SportsException("The highlights provider returned an unsupported address.")
        val request = Request.Builder().url(url).header("User-Agent", "NikTV Cricket Highlights").apply {
            headers.forEach { (key, value) ->
                if (value.any { it == '\r' || it == '\n' }) throw SportsException("The highlights provider supplied invalid credentials.")
                header(key, value)
            }
        }.build()
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        SportsException("Could not connect to the highlights provider.").apply { initCause(e) })
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        response.use {
            if (it.code in setOf(401, 403)) {
                val serverTime = it.header("Date")?.let { date -> runCatching {
                    java.time.ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                }.getOrNull() }
                throw CricketAccessDenied(serverTime)
            }
            if (!it.isSuccessful) throw SportsException("The highlights provider returned HTTP ${it.code}.")
            val body = it.body ?: throw SportsException("The highlights provider returned an empty response.")
            if (body.contentLength() > CricketHighlightsCatalogue.MAX_BYTES) throw SportsException("The highlights response is too large.")
            val source = body.source()
            source.request(CricketHighlightsCatalogue.MAX_BYTES.toLong() + 1)
            if (source.buffer.size > CricketHighlightsCatalogue.MAX_BYTES) throw SportsException("The highlights response is too large.")
            source.buffer.readByteArray()
        }
    }
}

internal class CricketHighlightsRepository(
    private val transport: CricketHighlightsTransport = CricketHighlightsHttpTransport(),
    private val connection: () -> CricketHighlightsConnection = {
        CricketHighlightsConnection(RemoteCredentials.get("NIKTV_CRICKET_HIGHLIGHTS_URL"), RemoteCredentials.get("NIKTV_CRICKET_HIGHLIGHTS_TOKEN"))
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val direct: CricbuzzDirectClient = CricbuzzDirectClient(transport),
) {
    var appVersion: String
        get() = direct.appVersion
        set(value) { direct.appVersion = value; cached = null; nextCursor = null }
    var region: String
        get() = direct.region
        set(value) { direct.region = value; cached = null; nextCursor = null }
    val configured get() = direct.configured || connection().configured
    val collections get() = if (direct.configured) direct.collections else emptyList()
    val sections get() = if (direct.configured) direct.sections else emptyList()
    val hasMore get() = nextCursor != null
    private val mutex = Mutex()
    private var cached: List<CricketHighlight>? = null
    private var cachedAt = 0L
    private var cachedConnection = "" to ""
    private var nextCursor: String? = null
    private var collectionId: Int? = null

    suspend fun featured(refresh: Boolean = false, collectionId: Int? = null): List<CricketHighlight> = mutex.withLock {
        val source = connection()
        if (!configured) throw SportsException("Import your Cricbuzz configuration to connect the Canadian catalogue.")
        val identity = if (direct.configured) "cricbuzz-direct" to direct.cacheIdentity else source.url to source.token
        cached?.takeIf { !refresh && this.collectionId == collectionId && cachedConnection == identity && now() - cachedAt in 0 until 120_000 }?.let { return@withLock it }
        val result = if (direct.configured) {
            val page = direct.browse(collectionId, refreshCollections = refresh)
            nextCursor = page.nextCursor
            page.videos
        } else {
            nextCursor = null
            val url = source.url.toHttpUrlOrNull()!!.newBuilder().setQueryParameter("country", "CA").build().toString()
            val headers = mapOf("Accept" to "application/json, application/x-protobuf", "CB-Loc" to "CA") +
                if (source.token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${source.token}")
            CricketHighlightsCatalogue.parse(transport.get(url, headers))
        }
        cached = result; cachedAt = now(); cachedConnection = identity; this.collectionId = collectionId
        result
    }

    suspend fun loadMore(): List<CricketHighlight> = mutex.withLock {
        val existing = cached.orEmpty()
        val cursor = nextCursor ?: return@withLock existing
        if (!direct.configured || existing.size >= 500) { nextCursor = null; return@withLock existing }
        val page = direct.browse(collectionId, cursor)
        val result = mergeCricketVideos(existing, page.videos)
        // Stop on an empty/repeated page, repeated cursor, or the bounded catalogue limit.
        nextCursor = page.nextCursor.takeIf { it != cursor && result.size > existing.size && result.size < 500 }
        cached = result; cachedAt = now()
        result
    }

    /** Re-fetch by stable identity on every play/retry; never query a signed-out detail URL. */
    suspend fun fullStream(id: String): SportsStream {
        val item = if (direct.configured) direct.resolve(id) else featured(refresh = true).firstOrNull { it.id == id }
            ?: throw SportsException("This highlight is no longer featured. Refresh the catalogue.")
        val stream = item.fullStream()
        val master = transport.get(stream.link, stream.headers).toString(Charsets.UTF_8)
        if (!master.trimStart().startsWith("#EXTM3U")) throw SportsException("The provider did not return a playable highlight.")
        val firstVariant = master.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList().let { lines ->
            lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }.takeIf { it >= 0 }?.let { lines.getOrNull(it + 1) }
        }
        val media = if (firstVariant == null) master else {
            val variant = stream.link.toHttpUrlOrNull()?.resolve(firstVariant)?.toString().orEmpty()
            transport.get(variant, stream.headers).toString(Charsets.UTF_8)
        }
        verifyCricketDuration(item.duration, media)
        return stream
    }
}

internal fun verifyCricketDuration(expected: String, playlist: String) {
    if (!playlist.trimStart().startsWith("#EXTM3U") || "#EXT-X-ENDLIST" !in playlist)
        throw SportsException("The provider did not return an on-demand highlight.")
    val parts = expected.split(':').map { it.toLongOrNull() }
    val seconds = if (parts.size in 2..3 && parts.all { it != null && it in 0..3599 }) parts.fold(0L) { value, part -> value * 60 + part!! } else 0L
    val actual = playlist.lineSequence().filter { it.startsWith("#EXTINF:") }
        .sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 }
    if (actual <= 0 || (seconds >= 180 && actual < seconds * .75 && seconds - actual > 60))
        throw SportsException("The provider returned a preview instead of the full highlight. Refresh your provider access and retry.")
}
