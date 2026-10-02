package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class StalkerGuideResponseTest {
    @Test fun emptyProviderResponseClearsCacheManualRefreshBypassesAndLaterResponseRecovers() = runBlocking {
        val server = ServerSocket(0)
        val recovered = AtomicBoolean(false)
        val fullRequests = AtomicInteger(0)
        val now = System.currentTimeMillis()
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                    val full = request.contains("action=get_epg_info")
                    if (full) fullRequests.incrementAndGet()
                    val body = if (recovered.get() && !full) {
                        """{"js":[{"ch_id":"4385","name":"Current movie","start_timestamp":${now / 1000 - 10},"stop_timestamp":${now / 1000 + 300}}]}"""
                    } else if (full) """{"js":{"4385":[],"71743":[]}}""" else """{"js":[]}"""
                    val bytes = body.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            val endpoint = "http://127.0.0.1:${server.localPort}/server/load.php"
            val profile = PortalProfile("Test", endpoint, "00:00:00:00:00:00")
            val session = PortalSession(profile, "test", endpoint, "", "{}", "")
            val client = StalkerPortalClient(RuntimeEnvironment.getApplication())
            val old = LiveProgramme("Cached movie", now - 1000, now + 300_000)
            val item = MediaItem("4385", "Channel", null, "stream", liveProgramme = old, liveSchedule = listOf(old))
            assertEquals(old, client.categoryChannelSchedules(session, listOf(item)).single().currentLiveProgramme(now))
            val blank = client.playingChannelSchedule(session, item)
            assertNull(blank.liveProgramme)
            assertTrue(blank.liveSchedule.isEmpty())
            client.playingChannelSchedule(session, item)
            assertEquals(1, fullRequests.get())
            client.playingChannelSchedule(session, item, userSelected = true, forceRefresh = true)
            assertEquals(2, fullRequests.get())
            recovered.set(true)
            val fresh = client.playingChannelSchedule(session, blank)
            assertEquals("Current movie", fresh.currentLiveProgramme(System.currentTimeMillis())?.title)
            assertEquals("stream", fresh.command)
        } finally {
            server.close()
            worker.join(1000)
        }
    }
}
