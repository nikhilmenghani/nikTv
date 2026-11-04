package com.nikhil.niktv.sports

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SportsEdgeTokenDeviceTest {
    @Test fun edgeRecognitionEnablesRenewalOnlyInTokenAwareMode() = runBlocking {
        TokenServer().use { server ->
            val resolver = SportsWebResolver(InstrumentationRegistry.getInstrumentation().targetContext)
            val stream = SportsStream(server.base + "/edge", "Live event", 4)
            val modern = resolver.resolve(stream, SportsResolverMode.TOKEN_AWARE)
            assertEquals(server.base + "/live/index.m3u8", modern.link)
            assertEquals(server.base + "/api/playlist-token?slug=live", modern.edgeTokenApi)
            assertEquals(server.base, modern.headers["Origin"])
            val legacy = resolver.resolve(stream, SportsResolverMode.LEGACY)
            assertEquals(server.base + "/live/index.m3u8", legacy.link)
            assertEquals("", legacy.edgeTokenApi)
        }
    }

    @Test fun playlistLoadsRenewAtHalfLifeAndRetry403OnceWithoutChangingThePlayerUrl() {
        TokenServer().use { server ->
            var clock = 1_000L
            val manifest = server.base + "/live/index.m3u8?keep=a%2Bb&t=old&_=old"
            val state = sportsEdgeTokenState(server.base + "/api/playlist-token", mapOf("Origin" to "https://player.example.test"), { clock })
            fun load(path: String = manifest) {
                val source = SportsEdgeTokenDataSource(DefaultHttpDataSource.Factory().createDataSource(), manifest, state, { clock })
                try {
                    source.open(DataSpec(Uri.parse(path)))
                    val buffer = ByteArray(1024)
                    while (source.read(buffer, 0, buffer.size) >= 0) { }
                } finally { source.close() }
            }
            load(); assertEquals(1, server.tokens.get())
            load(); assertEquals(1, server.tokens.get())
            clock = 11_000; load(); assertEquals(2, server.tokens.get())
            server.reject.set(1); load(); assertEquals(3, server.tokens.get())
            val last = Uri.parse(server.requests.last())
            assertEquals("token3", last.getQueryParameter("t"))
            assertEquals("a+b", last.getQueryParameter("keep"))
            assertEquals(listOf("token3"), last.getQueryParameters("t"))
            assertEquals("11000", last.getQueryParameter("_"))
            server.reject.set(3)
            val before = server.requests.size
            assertThrows(java.io.IOException::class.java) { load() }
            assertEquals(2, server.requests.size - before) // bounded: no infinite 403 loop
            server.reject.set(0)
            val tokenCalls = server.tokens.get()
            load(server.base + "/segment.ts?keep=segment")
            assertEquals(tokenCalls, server.tokens.get())
            assertNull(Uri.parse(server.requests.last()).getQueryParameter("t"))
        }
    }

    private class TokenServer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        val base = "http://127.0.0.1:${server.localPort}"
        val tokens = AtomicInteger()
        val reject = AtomicInteger()
        val requests = CopyOnWriteArrayList<String>()
        init {
            workers.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: SocketException) { break }
                    workers.execute {
                        socket.use {
                            it.soTimeout = 5000
                            val input = it.getInputStream().bufferedReader()
                            val path = input.readLine().split(' ')[1]
                            while (!input.readLine().isNullOrEmpty()) { }
                            val api = path.startsWith("/api/playlist-token")
                            if (!api) requests.add(base + path)
                            val status = if (!api && reject.getAndUpdate { n -> (n - 1).coerceAtLeast(0) } > 0) 403 else 200
                            val body = if (api) """{"token":"token${tokens.incrementAndGet()}","ttl":20}"""
                                else if (path == "/edge") """<script>const EDGE='$base'; const CHANNEL='live'; const api='/api/playlist-token';</script><div data-hls='/live/index.m3u8'></div>"""
                                else "#EXTM3U\n"
                            val bytes = body.toByteArray()
                            it.getOutputStream().apply {
                                val mime = if (path == "/edge") "text/html" else "application/vnd.apple.mpegurl"
                                write("HTTP/1.1 $status Result\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                        }
                    }
                }
            }
        }
        override fun close() { server.close(); workers.shutdownNow() }
    }
}
