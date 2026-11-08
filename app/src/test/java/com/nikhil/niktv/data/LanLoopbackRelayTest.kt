package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LanLoopbackRelayTest {
    @Test fun hlsManifestSegmentsAndKeysUseOnlyAuthenticatedOpaqueRoutes() {
        val requests = AtomicInteger()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { exchange ->
            requests.incrementAndGet()
            val data = when (exchange.requestURI.path) {
                "/master.m3u8" -> "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"/key\"\n#EXTINF:2,\nsegment.ts?fixture=selected\n#EXT-X-ENDLIST\n".toByteArray()
                "/key" -> ByteArray(16) { 7 }
                else -> byteArrayOf(1, 2, 3, 4)
            }
            if (exchange.requestHeaders.getFirst("X-Fixture") != "selected") { exchange.sendResponseHeaders(403, -1); exchange.close() }
            else {
                exchange.responseHeaders.add("Content-Type", if (exchange.requestURI.path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream")
                exchange.sendResponseHeaders(200, data.size.toLong()); exchange.responseBody.use { it.write(data) }
            }
        }
        upstream.start()
        val media = PlayingMedia(MediaItem("fixture", "Selected loopback video", null, null), "http://127.0.0.1:${upstream.address.port}/master.m3u8", CatalogType.MOVIES,
            playbackFormat = "HLS", streamRequestHeaders = mapOf("X-Fixture" to "selected"))
        try {
            LanSelectedMediaServer(RuntimeEnvironment.getApplication(), "127.0.0.1", media, "127.0.0.1").use { relay ->
                val manifest = URL(relay.url).readText()
                assertTrue(manifest.startsWith("#EXTM3U"))
                assertFalse(manifest.contains("fixture=selected"))
                assertFalse(manifest.contains("/master.m3u8"))
                val key = Regex("URI=\"([^\"]+)\"").find(manifest)!!.groupValues[1]
                assertArrayEquals(ByteArray(16) { 7 }, URL(URL(relay.url), key).readBytes())
                val segment = manifest.lineSequence().first { it.isNotBlank() && !it.startsWith('#') }
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), URL(URL(relay.url), segment).readBytes())
                val before = requests.get()
                val tampered = URL(relay.url.substringBeforeLast('/') + "/forged.bin").openConnection() as HttpURLConnection
                try { assertEquals(503, tampered.responseCode) } finally { tampered.disconnect() }
                assertEquals(before, requests.get())
            }
        } finally { upstream.stop(0) }
    }
}
