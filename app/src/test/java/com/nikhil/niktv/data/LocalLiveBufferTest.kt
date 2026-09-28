package com.nikhil.niktv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class LocalLiveBufferTest {
    @Test fun `playlist preserves sequence and duration for rewind`() {
        val playlist = bufferedLivePlaylist(listOf(
            BufferedLiveSegment(12, 2.125, File("12.ts")),
            BufferedLiveSegment(13, 3.5, File("13.ts"))), false)
        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:12"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:4"))
        assertTrue(playlist.contains("#EXTINF:2.125,"))
        assertFalse(playlist.contains("ENDLIST"))
        assertTrue(bufferedLivePlaylist(emptyList(), true).contains("ENDLIST"))
    }

    @Test fun `replaying local segments does not fetch provider segment again`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = ConcurrentHashMap<String, Int>()
        val provider = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1]
                    while (!input.readLine().isNullOrEmpty()) { }
                    requests.merge(path, 1, Int::plus)
                    val data = if (path == "/live.m3u8")
                        "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()
                        else ByteArray(188) { if (it == 0) 0x47.toByte() else 0 }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(data); flush()
                    }
                }
            } catch (_: Exception) { }
        }
        val root = Files.createTempDirectory("local-live-test").toFile()
        try {
            val buffer = requireNotNull(LocalLiveBuffer.open(root, "http://127.0.0.1:${server.localPort}/live.m3u8"))
            val playlist = URL(buffer.url).readText()
            val segment = playlist.lines().first { it.endsWith(".ts") }
            val url = URL(URL(buffer.url), segment)
            assertArrayEquals(url.readBytes(), url.readBytes())
            assertEquals(1, requests["/segment.ts"])
            buffer.close()
            assertTrue(File(root, "live-rewind").listFiles().orEmpty().isEmpty())
        } finally { server.close(); provider.join(1000); root.deleteRecursively() }
    }

    @Test fun `provider denial falls back and clears the buffer`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val provider = thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
            }
        }
        val root = Files.createTempDirectory("local-live-denied-test").toFile()
        try {
            assertNull(LocalLiveBuffer.open(root, "http://127.0.0.1:${server.localPort}/live"))
            assertTrue(File(root, "live-rewind").listFiles().orEmpty().isEmpty())
        } finally { server.close(); provider.join(1000); root.deleteRecursively() }
    }

    @Test fun `timestamp parser rejects non transport data`() {
        assertNull(tsPresentationTimestamp(ByteArray(188)))
        assertNull(tsPresentationTimestamp(ByteArray(2)))
    }
}
