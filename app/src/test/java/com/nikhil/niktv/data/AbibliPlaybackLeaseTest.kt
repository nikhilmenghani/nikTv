package com.nikhil.niktv.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class AbibliPlaybackLeaseTest {
    private class Upstream : AbibliHttp {
        val requests = Collections.synchronizedList(mutableListOf<Triple<HttpUrl, String?, Boolean>>())
        val renewals = AtomicInteger()
        val closes = AtomicInteger()
        val responseCloses = AtomicInteger()
        var text = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English\",URI=\"audio.txt\"\n#EXT-X-STREAM-INF:BANDWIDTH=200000,AUDIO=\"a\"\nvideo.txt\n"
        override fun guest(video: String, client: String): AbibliGuest {
            assertEquals("ABC123", video); assertEquals("client-fixture", client)
            renewals.incrementAndGet()
            return AbibliGuest("$ABIBLI_API/master.txt?session=fresh&sig=fresh&exp=1900010800&client=client-fixture".toHttpUrl(), 1_900_010_800_000)
        }
        override fun get(url: HttpUrl, client: String, range: String?, head: Boolean): AbibliResponse {
            assertEquals("client-fixture", client)
            requests += Triple(url, range, head)
            val bytes = when {
                url.encodedPath.endsWith("master.txt") -> text.toByteArray()
                url.encodedPath.endsWith(".txt") -> "#EXTM3U\n#EXT-X-MAP:URI=\"https://media.example.test/init.woff2\"\n#EXTINF:5,\nhttps://media.example.test/segment.woff2\n".toByteArray()
                else -> "fragment-fixture".toByteArray()
            }
            return AbibliResponse(if (range == null) 200 else 206, url, bytes.size.toLong(),
                if (range == null) emptyMap() else mapOf("Content-Range" to "bytes 0-15/16"), ByteArrayInputStream(bytes)) { responseCloses.incrementAndGet() }
        }
        override fun close() { closes.incrementAndGet() }
    }
    private fun lease(upstream: Upstream, expiresAt: Long = 1_900_003_600_000, now: () -> Long = { 1_900_000_000_000 }) =
        AbibliPlaybackLease(AbibliPlaybackSpec("ABC123"), "client-fixture",
            AbibliGuest("$ABIBLI_API/master.txt?session=old&sig=old&exp=1900003600&client=client-fixture".toHttpUrl(), expiresAt), upstream, now)
    private data class Reply(val status: Int, val text: String, val headers: Map<String, List<String>>)
    private fun request(url: String, range: String? = null, head: Boolean = false): Reply {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2000; connection.readTimeout = 2000
        if (range != null) connection.setRequestProperty("Range", range)
        if (head) connection.requestMethod = "HEAD"
        try {
            val status = connection.responseCode
            val body = (if (status < 400) connection.inputStream else connection.errorStream)?.use { String(it.readBytes()) }.orEmpty()
            return Reply(status, body, connection.headerFields.filterKeys { it != null })
        } finally { connection.disconnect() }
    }
    private fun mediaUri(text: String) = text.lineSequence().first { it.isNotBlank() && !it.startsWith('#') }

    @Test fun masterRetainsAudioAndRewritesAllResourcesToOpaqueAuthenticatedRoutes() {
        val upstream = Upstream()
        lease(upstream).use { bridge ->
            val master = request(bridge.url)
            assertEquals(200, master.status)
            assertTrue(master.text.contains("GROUP-ID=\"a\""))
            assertFalse(master.text.contains("lazyrocket")); assertFalse(master.text.contains("sig="))
            val audio = Regex("URI=\"([^\"]+)\"").find(master.text)!!.groupValues[1]
            assertTrue(audio.startsWith(bridge.url.substringBeforeLast('/')))
            assertEquals(200, request(audio).status)
            val child = request(mediaUri(master.text))
            val init = Regex("URI=\"([^\"]+)\"").find(child.text)!!.groupValues[1]
            assertTrue(init.endsWith(".mp4")); assertTrue(mediaUri(child.text).endsWith(".m4s"))
            assertEquals("fragment-fixture", request(init).text)
            assertEquals("fragment-fixture", request(mediaUri(child.text)).text)
            assertEquals(5, upstream.responseCloses.get())
            assertTrue(upstream.requests.take(3).all { it.first.queryParameter("session") == "old" })
            assertNull(upstream.requests.last().first.queryParameter("session"))
        }
        assertEquals(1, upstream.closes.get())
    }
    @Test fun renewalIsSerializedAndUpdatesPreviouslyIssuedChildPlaylistUrls() {
        val upstream = Upstream()
        var clock = 1_900_000_000_000L
        lease(upstream, now = { clock }).use { bridge ->
            val child = mediaUri(request(bridge.url).text)
            clock = 1_900_003_580_000
            val pool = Executors.newFixedThreadPool(3)
            try { pool.invokeAll(List(3) { java.util.concurrent.Callable { request(child).status } }).forEach { assertEquals(200, it.get()) } }
            finally { pool.shutdownNow() }
            assertEquals(1, upstream.renewals.get())
            assertTrue(upstream.requests.drop(1).all { it.first.queryParameter("session") == "fresh" })
        }
    }
    @Test fun rangesAndHeadAreForwardedAndNoArbitraryRouteReachesUpstream() {
        val upstream = Upstream()
        lease(upstream).use { bridge ->
            val child = mediaUri(request(bridge.url).text)
            val segment = mediaUri(request(child).text)
            assertEquals(206, request(segment, "bytes=0-15").status)
            assertEquals("bytes=0-15", upstream.requests.last().second)
            assertEquals("", request(segment, head = true).text)
            assertTrue(upstream.requests.last().third)
            val count = upstream.requests.size
            assertEquals(416, request(segment, "bytes=0-1,3-4").status)
            assertEquals(404, request(bridge.url.substringBeforeLast('/') + "/https://private.test/").status)
            assertEquals(404, request(segment.replace(".m4s", ".key")).status)
            assertEquals(404, request(bridge.url + "?url=https://private.test").status)
            assertEquals(count, upstream.requests.size)
            assertEquals(200, request(segment).status)
        }
    }
    @Test fun protectedAndOversizedResponsesFailClosedWithoutLeakingRemoteUrls() {
        val upstream = Upstream()
        lease(upstream).use { bridge ->
            upstream.text = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"https://secret.example.test/license\""
            assertEquals(503, request(bridge.url).status)
            upstream.text = "#EXTM3U\n" + "a".repeat(1024 * 1024)
            val reply = request(bridge.url)
            assertEquals(503, reply.status); assertFalse(reply.text.contains("secret"))
            assertEquals(2, upstream.responseCloses.get())
        }
    }
    @Test fun authenticatedRoutesCannotBeReusedAcrossSessionsAndCleanupIsIdempotent() {
        val upstream = Upstream()
        val first = lease(upstream)
        val second = lease(Upstream())
        try {
            val route = mediaUri(request(first.url).text).substringAfterLast('/')
            assertEquals(404, request(second.url.substringBeforeLast('/') + "/$route").status)
        } finally { first.close(); first.close(); second.close() }
        assertEquals(1, upstream.closes.get())
        try { request(first.url); fail("Listener still reachable") } catch (_: IOException) { }
    }
}
