package com.nikhil.niktv.sports

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class SportsEdgeToken(val value: String, val ttl: Long = 120)

/** Shared by playlist loaders in one player session; never persisted or logged. */
internal class SportsEdgeTokenState(
    private val fetch: () -> SportsEdgeToken,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var token = ""
    private var refreshAt = 0L
    private var validUntil = 0L

    @Synchronized fun token(): String {
        val at = now()
        if (token.isNotEmpty() && at < refreshAt) return token
        try {
            val fresh = fetch()
            if (fresh.value.isBlank() || fresh.value.length > 16_384) throw IOException("Missing playlist token")
            val ttl = fresh.ttl.coerceIn(20, Int.MAX_VALUE.toLong())
            val received = now()
            token = fresh.value
            refreshAt = received + ttl * 500
            validUntil = received + ttl * 1000 - 2000
        } catch (failure: IOException) {
            if (token.isEmpty() || now() >= validUntil) {
                token = ""; refreshAt = 0; validUntil = 0
                throw IOException("Unable to renew playlist access", failure)
            }
        }
        return token
    }

    @Synchronized fun invalidate(rejected: String) {
        if (token == rejected) { token = ""; refreshAt = 0; validUntil = 0 }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SportsEdgeTokenDataSource(
    private val upstream: DataSource,
    private val manifest: String,
    private val state: SportsEdgeTokenState,
    private val now: () -> Long = System::currentTimeMillis,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString().toHttpUrlOrNull()
        if (url == null || !url.encodedPath.endsWith(".m3u8", true) || !sportsSameOrigin(manifest, url.toString()))
            return upstream.open(dataSpec)
        fun authorized(token: String) = dataSpec.buildUpon().setUri(url.newBuilder()
            .removeAllQueryParameters("t").removeAllQueryParameters("_")
            .addQueryParameter("t", token).addQueryParameter("_", now().toString()).build().toString()).build()
        val token = state.token()
        return try { upstream.open(authorized(token)) }
        catch (failure: HttpDataSource.InvalidResponseCodeException) {
            if (failure.responseCode != 403) throw failure
            upstream.close()
            state.invalidate(token)
            // A rejected playlist gets exactly one fresh-token retry.
            upstream.open(authorized(state.token()))
        }
    }
}

internal fun sportsEdgeTokenState(api: String, headers: Map<String, String>, now: () -> Long = System::currentTimeMillis): SportsEdgeTokenState {
    val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    return SportsEdgeTokenState(fetch = {
        val request = Request.Builder().url(api).apply {
            headers.forEach { (key, value) -> if (value.none { it == '\r' || it == '\n' || it == '\u0000' }) header(key, value) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Playlist authorization returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty playlist authorization")
            val source = body.source()
            source.request(65_537)
            if (source.buffer.size > 65_536) throw IOException("Playlist authorization is too large")
            val obj = try { Json.parseToJsonElement(source.buffer.readUtf8()) as? JsonObject }
                catch (_: Exception) { null } ?: throw IOException("Unreadable playlist authorization")
            SportsEdgeToken((obj["token"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (obj["ttl"] as? JsonPrimitive)?.longOrNull ?: 120)
        }
    }, now = now)
}
