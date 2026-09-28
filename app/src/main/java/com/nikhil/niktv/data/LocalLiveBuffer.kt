package com.nikhil.niktv.data

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

internal data class BufferedLiveSegment(val sequence: Long, val seconds: Double, val file: File)

/** One provider reader; both players consume the same bounded local HLS window. */
internal class LocalLiveBuffer private constructor(cacheRoot: File, private val source: String) : Closeable {
    private val token = UUID.randomUUID().toString()
    private val directory = File(cacheRoot, "live-rewind/$token").apply { mkdirs() }
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/$token/index.m3u8"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val segments = ArrayDeque<BufferedLiveSegment>()
    private var bytes = 0L
    private var sequence = 0L
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var ended = false
    @Volatile private var closed = false
    @Volatile private var call: okhttp3.Call? = null
    @Volatile private var failure: Throwable? = null

    init {
        scope.launch {
            try {
                while (isActive) {
                    val socket = server.accept()
                    launch { try { serve(socket) } catch (_: java.io.IOException) { socket.close() } }
                }
            } catch (_: Exception) { }
        }
        scope.launch {
            try { ingest() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error; ready.completeExceptionally(error) }
            finally { ended = true; if (!ready.isCompleted) ready.complete(false) }
        }
    }

    private fun request(url: String): okhttp3.Response {
        val next = http.newCall(Request.Builder().url(url).header("User-Agent", "NikTV/0.1 Android").build())
        call = next
        val response = next.execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw java.io.IOException("Live stream returned HTTP $code")
        }
        return response
    }

    private suspend fun ingest() {
        request(source).use { response ->
            val body = response.body ?: throw java.io.IOException("Live stream returned no data")
            val input = body.byteStream().buffered()
            input.mark(16)
            val prefix = ByteArray(7)
            val count = input.read(prefix)
            input.reset()
            if (count > 0 && String(prefix, 0, count).startsWith("#EXTM3U")) {
                val text = input.reader().readText()
                ingestHls(response.request.url.toString(), text)
            } else {
                // MPEG transport streams can be repackaged without decoding or re-encoding.
                // Other formats retain their original player path.
                if (count <= 0 || prefix[0].toInt() and 255 != 0x47) { ready.complete(false); return }
                ingestTransportStream(input)
            }
        }
    }

    private suspend fun ingestHls(initialUrl: String, initialText: String) {
        var playlistUrl = initialUrl
        var text = initialText
        val seen = linkedSetOf<String>()
        var masterDepth = 0
        while (scope.isActive) {
            val lines = text.lines().map(String::trim)
            // Separate audio, encryption, byte ranges and fMP4 require preserving extra
            // playlist dependencies. Fall back intact rather than dropping functionality.
            if (lines.any { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-MAP") ||
                    it.startsWith("#EXT-X-BYTERANGE") || it.startsWith("#EXT-X-MEDIA:") }) {
                ready.complete(false); return
            }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                if (++masterDepth > 3) throw java.io.IOException("Invalid live playlist hierarchy")
                val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
                val child = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?: throw java.io.IOException("Live playlist contains no variant")
                playlistUrl = URI(playlistUrl).resolve(child).toString()
                text = request(playlistUrl).use { it.body?.string().orEmpty() }
                continue
            }
            var seconds: Double? = null
            var added = false
            for (line in lines) {
                if (line.startsWith("#EXTINF:")) seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                else if (line.isNotBlank() && !line.startsWith("#") && seconds != null) {
                    val segmentUrl = URI(playlistUrl).resolve(line).toString()
                    if (seen.add(segmentUrl)) {
                        request(segmentUrl).use { part ->
                            val file = File(directory, "${sequence}.ts")
                            part.body?.byteStream()?.use { input -> file.outputStream().use { output ->
                                val chunk = ByteArray(32 * 1024)
                                var size = 0L
                                while (true) {
                                    val count = input.read(chunk)
                                    if (count < 0) break
                                    size += count
                                    if (size > 16L * 1024 * 1024) throw java.io.IOException("Live segment exceeds local rewind limits")
                                    output.write(chunk, 0, count)
                                }
                            } }
                            if (file.length() > 0) { append(file, seconds!!); added = true }
                            else file.delete()
                        }
                        if (seen.size > 4096) seen.remove(seen.first())
                    }
                    seconds = null
                }
            }
            if (added) ready.complete(true)
            if (lines.any { it == "#EXT-X-ENDLIST" }) return
            delay(1500)
            text = request(playlistUrl).use { it.body?.string().orEmpty() }
        }
    }

    private suspend fun ingestTransportStream(input: java.io.InputStream) {
        val packet = ByteArray(188)
        var file = File(directory, "${sequence}.ts")
        var output = file.outputStream()
        var started = System.nanoTime()
        var count = 0L
        var firstPts: Long? = null
        var lastPts: Long? = null
        var pat: ByteArray? = null
        var pmt: ByteArray? = null
        var pmtPid = -1
        try {
            while (currentCoroutineContext().isActive) {
                var read = 0
                while (read < packet.size) {
                    val next = input.read(packet, read, packet.size - read)
                    if (next < 0) return
                    read += next
                }
                if (packet[0].toInt() and 255 != 0x47) throw java.io.IOException("Live stream is not MPEG transport stream")
                val pid = ((packet[1].toInt() and 31) shl 8) or (packet[2].toInt() and 255)
                if (pid == 0) {
                    pat = packet.copyOf()
                    val payload = tsPayloadOffset(packet)
                    if (payload != null && packet[1].toInt() and 64 != 0) {
                        val table = payload + 1 + (packet[payload].toInt() and 255)
                        if (table + 12 < 188) pmtPid = ((packet[table + 10].toInt() and 31) shl 8) or
                            (packet[table + 11].toInt() and 255)
                    }
                } else if (pid == pmtPid) pmt = packet.copyOf()
                val pts = tsPresentationTimestamp(packet)
                if (pts != null) {
                    if (firstPts == null) firstPts = pts
                    val previous = lastPts
                    // Audio and video packets may arrive slightly out of order.
                    // A backward timestamp is not an eight-hour wraparound window.
                    if (previous == null || ((pts - previous + (1L shl 33)) % (1L shl 33)) < 5_400_000L)
                        lastPts = pts
                }
                output.write(packet)
                count += packet.size
                val seconds = if (firstPts != null && lastPts != null)
                    ((lastPts!! - firstPts!! + (1L shl 33)) % (1L shl 33)) / 90_000.0
                    else (System.nanoTime() - started) / 1_000_000_000.0
                if (seconds >= 2.0 || count >= 8 * 1024 * 1024) {
                    output.close()
                    append(file, seconds.coerceAtLeast(.1))
                    ready.complete(true)
                    file = File(directory, "${sequence}.ts")
                    output = file.outputStream()
                    pat?.let { output.write(it) }
                    pmt?.let { output.write(it) }
                    count = 0
                    firstPts = lastPts
                    started = System.nanoTime()
                }
            }
        } finally {
            output.close()
            if (count > 0 && !closed) append(file, ((System.nanoTime() - started) / 1_000_000_000.0).coerceAtLeast(.1))
            else file.delete()
        }
    }

    private fun append(file: File, seconds: Double) = synchronized(lock) {
        if (closed) { file.delete(); return@synchronized }
        segments.addLast(BufferedLiveSegment(sequence++, seconds, file))
        bytes += file.length()
        var duration = segments.sumOf { it.seconds }
        while (segments.size > 2 && (duration > 600.0 || bytes > 256L * 1024 * 1024)) {
            val oldest = segments.removeFirst()
            duration -= oldest.seconds
            bytes -= oldest.file.length()
            oldest.file.delete()
        }
    }

    private fun serve(socket: Socket) = socket.use {
        socket.soTimeout = 5000
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine().orEmpty()
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        while (!reader.readLine().isNullOrEmpty()) { }
        val content = synchronized(lock) {
            when {
                failure != null && path == "/$token/index.m3u8" -> null
                path == "/$token/index.m3u8" && segments.isNotEmpty() -> {
                    val text = bufferedLivePlaylist(segments.toList(), ended && failure == null)
                    "application/vnd.apple.mpegurl" to text.toByteArray()
                }
                path.startsWith("/$token/") -> {
                    val name = path.substringAfterLast('/')
                    segments.firstOrNull { it.file.name == name }?.file?.takeIf(File::exists)
                        ?.let { "video/mp2t" to it.readBytes() }
                }
                else -> null
            }
        }
        val output = socket.getOutputStream()
        val status = if (content == null) "404 Not Found" else "200 OK"
        val data = content?.second ?: ByteArray(0)
        output.write(("HTTP/1.1 $status\r\nContent-Type: ${content?.first ?: "text/plain"}\r\n" +
            "Content-Length: ${data.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        if (!requestLine.startsWith("HEAD ")) output.write(data)
        output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        call?.cancel()
        scope.cancel()
        server.close()
        synchronized(lock) { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    companion object {
        suspend fun open(context: Context, source: String): LocalLiveBuffer? = open(context.cacheDir, source)

        internal suspend fun open(cacheRoot: File, source: String): LocalLiveBuffer? = withContext(Dispatchers.IO) {
            val buffer = LocalLiveBuffer(cacheRoot, source)
            try {
                if (withTimeoutOrNull(3_000) { buffer.ready.await() } == true) buffer
                else { buffer.close(); null }
            } catch (cancelled: CancellationException) {
                buffer.close(); throw cancelled
            } catch (_: Exception) {
                // Rewind is optional: a denied/unsupported ingest must not block playback.
                buffer.close(); null
            }
        }
    }
}

internal fun bufferedLivePlaylist(segments: List<BufferedLiveSegment>, ended: Boolean): String = buildString {
    appendLine("#EXTM3U")
    appendLine("#EXT-X-VERSION:3")
    appendLine("#EXT-X-TARGETDURATION:${ceil(segments.maxOfOrNull { it.seconds } ?: 2.0).toInt().coerceAtLeast(1)}")
    appendLine("#EXT-X-MEDIA-SEQUENCE:${segments.firstOrNull()?.sequence ?: 0}")
    for (segment in segments) {
        appendLine("#EXTINF:${String.format(java.util.Locale.US, "%.3f", segment.seconds)},")
        appendLine(segment.file.name)
    }
    if (ended) appendLine("#EXT-X-ENDLIST")
}

private fun tsPayloadOffset(packet: ByteArray): Int? {
    val control = (packet[3].toInt() ushr 4) and 3
    if (control == 0 || control == 2) return null
    val offset = if (control == 3) 5 + (packet[4].toInt() and 255) else 4
    return offset.takeIf { it < packet.size }
}

internal fun tsPresentationTimestamp(packet: ByteArray): Long? {
    if (packet.size != 188 || packet[0].toInt() and 255 != 0x47 || packet[1].toInt() and 64 == 0) return null
    val offset = tsPayloadOffset(packet) ?: return null
    if (offset + 14 > packet.size || packet[offset] != 0.toByte() || packet[offset + 1] != 0.toByte() ||
        packet[offset + 2] != 1.toByte() || packet[offset + 7].toInt() and 128 == 0) return null
    val p = offset + 9
    fun byte(index: Int) = packet[index].toLong() and 255
    return ((byte(p) and 14) shl 29) or (byte(p + 1) shl 22) or
        ((byte(p + 2) and 254) shl 14) or (byte(p + 3) shl 7) or ((byte(p + 4) and 254) shr 1)
}
