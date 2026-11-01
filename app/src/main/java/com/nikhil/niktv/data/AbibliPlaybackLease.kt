package com.nikhil.niktv.data

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Owned by a committed player session, not by catalogue lookup, history or a provider profile. */
interface AlternatePlaybackLease : Closeable {
    val url: String
    val sourceKey: String
}

internal const val ABIBLI_API = "https://s5v.lazyrocket.space"
internal const val ABIBLI_PLAYER = "https://rov.funnyotter.xyz"
internal fun newAbibliClientId(): String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
internal fun abibliHeaders(client: String) = mapOf("User-Agent" to AlternateSourceResolver.USER_AGENT,
    "Origin" to ABIBLI_PLAYER, "Referer" to "$ABIBLI_PLAYER/", "X-S5V-Client" to client)
internal fun requireAbibliApi(value: String) = requirePublicHttps(value).also {
    if (!sameAlternateOrigin(it, ABIBLI_API.toHttpUrl())) throw AlternateSourceException("Abibli returned an unsupported session host.")
}
internal class AbibliGuest(val master: HttpUrl, val expiresAt: Long)
internal fun parseAbibliGuest(body: String): AbibliGuest {
    try {
        val data = JSONObject(body)
        val raw = data.getString("playlist")
        val master = requireAbibliApi(ABIBLI_API.toHttpUrl().resolve(raw)?.toString() ?: "")
        val expiry = data.getLong("exp")
        if (expiry !in 1_000_000_000L..9_000_000_000L) throw IOException()
        return AbibliGuest(master, expiry * 1000)
    } catch (_: Exception) { throw AlternateSourceException("Abibli could not establish a playback session.") }
}

internal class AbibliResponse(val code: Int, val url: HttpUrl, val length: Long,
    val headers: Map<String, String>, val body: InputStream, val finish: () -> Unit) : Closeable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) try { body.close() } finally { finish() } }
}
internal interface AbibliHttp : Closeable {
    fun guest(video: String, client: String): AbibliGuest
    fun get(url: HttpUrl, client: String, range: String?, head: Boolean): AbibliResponse
}

/** Cookies/private credentials are never forwarded. The source's anonymous client stays on its API. */
private class NetworkAbibliHttp(private val http: OkHttpClient) : AbibliHttp {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val closed = AtomicBoolean()
    suspend fun initial(video: String, client: String): AbibliGuest {
        val call = http.newCall(guestRequest(video, client)); track(call)
        try { return call.readCancellable { response ->
            if (!response.isSuccessful) throw IOException("Guest session unavailable")
            parseAbibliGuest(boundedText(response.body.byteStream(), 8 * 1024))
        } } finally { calls.remove(call) }
    }
    private fun guestRequest(video: String, client: String) = Request.Builder().url("$ABIBLI_API/mQ7x2P")
        .apply { abibliHeaders(client).forEach { (name, value) -> header(name, value) } }
        .post(JSONObject().put("video", video).put("client", client).toString().toRequestBody("application/json".toMediaType())).build()
    override fun guest(video: String, client: String): AbibliGuest {
        val call = http.newCall(guestRequest(video, client)); track(call)
        try { return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Guest session unavailable")
            parseAbibliGuest(boundedText(response.body.byteStream(), 8 * 1024))
        } } finally { calls.remove(call) }
    }
    private fun track(call: Call) { calls.add(call); if (closed.get()) { call.cancel(); throw IOException("Closed") } }
    override fun get(url: HttpUrl, client: String, range: String?, head: Boolean): AbibliResponse {
        var destination = url
        repeat(4) {
            val builder = Request.Builder().url(requirePublicHttps(destination.toString()))
                .header("User-Agent", AlternateSourceResolver.USER_AGENT).header("Origin", ABIBLI_PLAYER).header("Referer", "$ABIBLI_PLAYER/")
            if (sameAlternateOrigin(destination, ABIBLI_API.toHttpUrl())) builder.header("X-S5V-Client", client)
            range?.let { builder.header("Range", it) }
            if (head) builder.head()
            val call = http.newCall(builder.build()); track(call)
            val response = try { call.execute() } catch (error: Exception) { calls.remove(call); throw error }
            if (response.code in 300..399) {
                val next = response.header("Location")?.let(destination::resolve)
                response.close(); calls.remove(call)
                destination = requirePublicHttps(next?.toString() ?: throw IOException("Invalid redirect"))
            } else {
                return AbibliResponse(response.code, response.request.url, response.body.contentLength(),
                    listOf("Content-Range", "Accept-Ranges").mapNotNull { name -> response.header(name)?.let { name to it } }.toMap(),
                    response.body.byteStream()) { response.close(); calls.remove(call) }
            }
        }
        throw IOException("Too many redirects")
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // TLS close-notify can write to the network. Retire sockets on this
        // client's worker, never on the UI thread that is leaving the player.
        val worker = http.dispatcher.executorService
        worker.execute {
            try {
                calls.forEach { runCatching { it.cancel() } }
                calls.clear()
                http.connectionPool.evictAll()
            } finally { worker.shutdown() }
        }
    }
}

/** Authenticated opaque routes: no unbounded URL map, readable grants in player URLs, or URL query proxy. */
internal class AbibliRouteCodec {
    private val random = SecureRandom()
    private val key = SecretKeySpec(ByteArray(16).also(random::nextBytes), "AES")
    fun seal(kind: String, url: HttpUrl): String {
        require(kind in setOf("m3u8", "m4s", "ts", "mp4", "key"))
        val text = "$kind\n$url"
        require(text.toByteArray().size <= 4096)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return Base64.encodeToString(nonce + cipher.doFinal(text.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".$kind"
    }
    fun open(route: String): Pair<String, HttpUrl>? = try {
        if (route.length > 5600) null else {
            val bytes = Base64.decode(route.substringBeforeLast('.'), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            require(bytes.size >= 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val text = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            val kind = text.substringBefore('\n')
            require(route.endsWith(".$kind") && kind in setOf("m3u8", "m4s", "ts", "mp4", "key"))
            kind to requirePublicHttps(text.substringAfter('\n'))
        }
    } catch (_: Exception) { null }
}

/** Only one selected Abibli playback creates this loopback bridge. No stream is opened by lookup. */
internal class AbibliPlaybackLease internal constructor(
    private val spec: AbibliPlaybackSpec,
    private val client: String,
    initial: AbibliGuest,
    private val upstream: AbibliHttp,
    private val now: () -> Long = System::currentTimeMillis,
) : NanoHTTPD("127.0.0.1", 0), AlternatePlaybackLease {
    private val closed = AtomicBoolean()
    private val codec = AbibliRouteCodec()
    private val gate = Any()
    private var guest = initial
    private val root = "/${newAbibliClientId()}/"
    private var socket: ServerSocket? = null
    private val handlers = ConcurrentHashMap.newKeySet<ClientHandler>()
    private val executor = ThreadPoolExecutor(4, 4, 20, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "Abibli media").apply { isDaemon = true } })
    override val sourceKey get() = spec.videoId
    override val url get() = "http://127.0.0.1:$listeningPort${root}master.m3u8"
    init {
        executor.allowCoreThreadTimeOut(true)
        setServerSocketFactory { ServerSocket().also { socket = it } }
        setAsyncRunner(object : AsyncRunner {
            override fun closeAll() { handlers.toList().forEach(ClientHandler::close); handlers.clear(); executor.shutdownNow() }
            override fun closed(handler: ClientHandler) { handlers.remove(handler) }
            override fun exec(handler: ClientHandler) {
                if (closed.get()) { handler.close(); return }
                handlers.add(handler)
                try { executor.execute(handler) } catch (_: java.util.concurrent.RejectedExecutionException) { handlers.remove(handler); handler.close() }
            }
        })
        try { start(10_000, true) } catch (error: Exception) { close(); throw error }
    }
    private fun currentGuest(): AbibliGuest = synchronized(gate) {
        if (closed.get()) throw IOException("Closed")
        if (now() >= guest.expiresAt - 30_000) guest = upstream.guest(spec.videoId, client).also {
            if (it.expiresAt <= now() + 1000) throw IOException("Expired guest session")
        }
        guest
    }
    override fun serve(request: IHTTPSession): Response {
        if (closed.get() || !request.uri.startsWith(root) || request.queryParameterString?.isNotBlank() == true)
            return error(Response.Status.NOT_FOUND)
        if (request.method !in setOf(Method.GET, Method.HEAD)) return error(Response.Status.METHOD_NOT_ALLOWED)
        val route = request.uri.removePrefix(root)
        val decoded = if (route == "master.m3u8") null else (codec.open(route) ?: return error(Response.Status.NOT_FOUND))
        val kind = decoded?.first ?: "m3u8"
        val range = request.headers["range"]
        if (range != null && !range.matches(Regex("bytes=(?:[0-9]{1,16}-[0-9]{0,16}|-[0-9]{1,16})"))) return error(Response.Status.RANGE_NOT_SATISFIABLE)
        return try {
            var remote = decoded?.second ?: currentGuest().master
            if (sameAlternateOrigin(remote, ABIBLI_API.toHttpUrl())) {
                val live = currentGuest().master
                remote = remote.newBuilder().apply {
                    live.queryParameterNames.forEach { name -> setQueryParameter(name, live.queryParameter(name)) }
                }.build()
            }
            val reply = upstream.get(remote, client, if (kind == "m3u8") null else range, request.method == Method.HEAD && kind != "m3u8")
            if (reply.code !in setOf(200, 206)) { reply.close(); return error(Response.Status.SERVICE_UNAVAILABLE) }
            val response = if (kind == "m3u8") reply.use {
                val text = boundedText(it.body, 1024 * 1024)
                val body = rewrite(text, it.url).toByteArray()
                newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", ByteArrayInputStream(body), body.size.toLong())
            } else {
                if (reply.length > MAX_MEDIA_BYTES) { reply.close(); return error(Response.Status.SERVICE_UNAVAILABLE) }
                val input = object : FilterInputStream(reply.body) {
                    private var total = 0L
                    override fun read(): Int = super.read().also { if (it != -1 && ++total > MAX_MEDIA_BYTES) throw IOException("Media response too large") }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
                        if (it > 0) { total += it; if (total > MAX_MEDIA_BYTES) throw IOException("Media response too large") }
                    }
                    override fun close() = reply.close()
                }
                val status = if (reply.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                val result = if (reply.length >= 0) newFixedLengthResponse(status, "application/octet-stream", input, reply.length)
                    else newChunkedResponse(status, "application/octet-stream", input)
                reply.headers.forEach { (name, value) -> result.addHeader(name, value) }
                result
            }
            response.addHeader("Cache-Control", "no-store")
            response.addHeader("Connection", "close")
            response.setKeepAlive(false)
            response
        } catch (_: Exception) { error(Response.Status.SERVICE_UNAVAILABLE) }
    }
    internal fun rewrite(text: String, base: HttpUrl): String {
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("Not a playlist")
        rejectDrm(text)
        val master = text.contains("#EXT-X-STREAM-INF:")
        val fragment = if (text.contains("#EXT-X-MAP:")) "m4s" else "ts"
        var routes = 0
        fun route(raw: String, kind: String): String {
            if (++routes > 10_000) throw IOException("Playlist has too many resources")
            val resolved = requirePublicHttps(base.resolve(raw)?.toString() ?: throw IOException("Invalid URI"))
            return "http://127.0.0.1:$listeningPort$root" + codec.seal(kind, resolved)
        }
        val output = StringBuilder()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val rewritten = if (line.startsWith('#')) {
                val kind = when {
                    line.startsWith("#EXT-X-MAP:") -> "mp4"
                    line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> "key"
                    line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") || line.startsWith("#EXT-X-RENDITION-REPORT:") -> "m3u8"
                    else -> fragment
                }
                Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${route(it.groupValues[1], kind)}\"" }
            } else if (line.isBlank()) line else route(line, if (master) "m3u8" else fragment)
            if (output.length + rewritten.length > 4 * 1024 * 1024) throw IOException("Playlist response too large")
            output.append(rewritten).append('\n')
        }
        return output.toString()
    }
    private fun error(status: Response.Status) = newFixedLengthResponse(status, "text/plain", "Source temporarily unavailable").apply {
        addHeader("Cache-Control", "no-store")
        // NanoHTTPD applies the request's keep-alive after serve(); the explicit
        // header also closes rejected connections and prevents stale parser state.
        addHeader("Connection", "close")
        setKeepAlive(false)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Do not join a listener/native/network thread on the player or UI thread.
        runCatching { socket?.close() }
        asyncRunner.closeAll()
        upstream.close()
    }
    override fun toString() = "AbibliPlaybackLease(closed=${closed.get()})"
    companion object {
        private const val MAX_MEDIA_BYTES = 128L * 1024 * 1024
        suspend fun open(spec: AbibliPlaybackSpec): AbibliPlaybackLease {
            require(spec.videoId.matches(Regex("[A-Za-z0-9]{6}")))
            val network = NetworkAbibliHttp(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build())
            var created: AbibliPlaybackLease? = null
            try {
                val client = newAbibliClientId()
                val guest = network.initial(spec.videoId, client)
                return withContext(Dispatchers.IO) {
                    ensureActive()
                    AbibliPlaybackLease(spec, client, guest, network).also { created = it }
                }
            } catch (error: Exception) { created?.close(); network.close(); throw error }
        }
    }
}

internal fun boundedText(input: InputStream, max: Int): String {
    val output = ByteArrayOutputStream()
    val bytes = ByteArray(8192)
    while (true) {
        val count = input.read(bytes, 0, minOf(bytes.size, max - output.size() + 1))
        if (count == -1) break
        output.write(bytes, 0, count)
        if (output.size() > max) throw IOException("Source response too large")
    }
    return output.toString(Charsets.UTF_8.name())
}
