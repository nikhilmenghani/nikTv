package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class SharedGuideIntegrationTest {
    private data class Reply(val body: String, val declaredLength: Long? = null)
    private class Provider(private val respond: (String) -> Reply) : java.io.Closeable {
        private val server = ServerSocket(0)
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val headers = Collections.synchronizedList(mutableListOf<List<String>>())
        val endpoint = "http://127.0.0.1:${server.localPort}/server/load.php"
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                    thread(isDaemon = true) {
                        runCatching { socket.use {
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty()
                            val requestHeaders = mutableListOf<String>()
                            while (true) {
                                val line = reader.readLine()
                                if (line.isNullOrEmpty()) break
                                requestHeaders += line
                            }
                            headers += requestHeaders
                            requests += request
                            val response = respond(request)
                            val bytes = response.body.toByteArray()
                            it.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.declaredLength ?: bytes.size.toLong()}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                        } }
                    }
                }
            }
        }
        fun session(token: String = "test-token-alpha") = PortalSession(
            PortalProfile("Fixture", endpoint, "00:00:00:00:00:00"), token, endpoint, "", "{}", "")
        fun count(action: String) = requests.count { it.contains("action=$action") }
        override fun close() { server.close() }
    }
    private fun row(now: Long, name: String = "Current film", future: Boolean = false) =
        """{"name":"$name","start_timestamp":${now / 1000 + if (future) 60 else -60},"stop_timestamp":${now / 1000 + if (future) 120 else 60},"descr":"Not retained in the guide cache"}"""
    private val context get() = RuntimeEnvironment.getApplication()
    private fun channel(id: String) = MediaItem(id, "Channel $id", null, "unchanged-stream-command")

    @Test fun emptyShortGuideUsesSharedDataOtherTilesAndRestartNeedNoProviderCalls() = runBlocking {
        val now = System.currentTimeMillis()
        Provider { request -> Reply(if (request.contains("action=get_epg_info"))
            """{"js":{"data":{"1":[${row(now)},${row(now, "Next film", true)}],"2":[${row(now, "Other channel")}]}}}""" else """{"js":[]}""") }.use { provider ->
            val client = StalkerPortalClient(context)
            val result = client.playingChannelSchedule(provider.session(), channel("1"))
            assertEquals("Current film", result.currentLiveProgramme(now)?.title)
            assertEquals("Next film", result.currentLiveProgramme((now / 1000 + 60) * 1000)?.title)
            assertEquals("Other channel", client.playingChannelSchedule(provider.session(), channel("2")).currentLiveProgramme(now)?.title)
            client.close()
            val restarted = StalkerPortalClient(context)
            try {
                assertEquals("Other channel", restarted.playingChannelSchedule(provider.session("renewed-token"), channel("2")).currentLiveProgramme(now)?.title)
                assertEquals(1, provider.count("get_epg_info"))
                assertEquals(1, provider.count("get_short_epg"))
                assertEquals(0, provider.count("get_simple_data_table"))
                assertTrue(provider.headers.all { header -> header.any { it == "Authorization: Bearer test-token-alpha" } })
                assertTrue(provider.headers.all { header -> header.count { it.startsWith("User-Agent:") } == 2 })
                assertTrue(provider.requests.none { it.contains("action=create_link") })
                assertEquals("unchanged-stream-command", result.command)
                val disk = context.cacheDir.resolve("live-guide-v1").listFiles().orEmpty().joinToString { it.readText() }
                assertFalse(disk.contains("test-token-alpha"))
                assertFalse(disk.contains("Not retained"))
            } finally { restarted.close() }
        }
    }

    @Test fun channelCalendarStillWorksWhenSharedGuideHasOnlyAnotherChannel() = runBlocking {
        val now = System.currentTimeMillis()
        Provider { request -> Reply(when {
            request.contains("action=get_epg_info") -> """{"js":{"data":{"2":[${row(now, "Other channel")}]}}}"""
            request.contains("action=get_simple_data_table") -> """{"js":{"data":[${row(now, "Scoped programme")}]}}"""
            else -> """{"js":[]}"""
        }) }.use { provider ->
            val client = StalkerPortalClient(context)
            try {
                assertEquals("Scoped programme", client.playingChannelSchedule(provider.session(), channel("1")).currentLiveProgramme(now)?.title)
                assertEquals(1, provider.count("get_epg_info"))
                assertEquals(1, provider.count("get_simple_data_table"))
            } finally { client.close() }
        }
    }

    @Test fun oversizedBulkGuideIsNotRepeatedPerChannelAndDoesNotPreventScopedFallback() = runBlocking {
        val now = System.currentTimeMillis()
        Provider { request -> when {
            request.contains("action=get_epg_info") -> Reply("{}", 8L * 1024 * 1024 + 1)
            request.contains("action=get_simple_data_table") -> Reply("""{"js":{"data":[${row(now, "Scoped programme")}]}}""")
            else -> Reply("""{"js":[]}""")
        } }.use { provider ->
            val client = StalkerPortalClient(context)
            try {
                for (id in listOf("1", "2")) assertEquals("Scoped programme",
                    client.playingChannelSchedule(provider.session(), channel(id)).currentLiveProgramme(now)?.title)
                assertEquals(1, provider.count("get_epg_info"))
                assertEquals(2, provider.count("get_simple_data_table"))
            } finally { client.close() }
        }
    }

    @Test fun http200AuthorizationFailureIsNotCachedAsMissingProgramme() = runBlocking {
        val now = System.currentTimeMillis()
        val authorized = AtomicBoolean(false)
        val full = AtomicInteger()
        Provider { request -> Reply(if (request.contains("action=get_epg_info")) {
            full.incrementAndGet()
            if (!authorized.get()) "Authorization failed." else """{"js":{"data":{"1":[${row(now)}]}}}"""
        } else """{"js":[]}""") }.use { provider ->
            val client = StalkerPortalClient(context)
            try {
                try { client.playingChannelSchedule(provider.session(), channel("1")); fail("Authentication failure was hidden") }
                catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("Authorization failed")) }
                authorized.set(true)
                assertEquals("Current film", client.playingChannelSchedule(provider.session("renewed-token"), channel("1")).currentLiveProgramme(now)?.title)
                assertEquals(2, full.get())
            } finally { client.close() }
        }
    }

    @Test fun foregroundNavigationCancelsAHangingGuideAndReleasesProviderGate() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        Provider { request ->
            if (request.contains("action=get_epg_info")) { started.countDown(); release.await(10, TimeUnit.SECONDS) }
            Reply(if (request.contains("action=get_genres")) """{"js":[{"id":"9","title":"Category"}]}""" else """{"js":[]}""")
        }.use { provider ->
            val client = StalkerPortalClient(context)
            val loading = async(Dispatchers.IO) { client.playingChannelSchedule(provider.session(), channel("1")) }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                client.prioritizePlayback()
                withTimeout(5_000) { assertTrue(client.categories(provider.session(), CatalogType.LIVE_TV).isNotEmpty()) }
            } finally { release.countDown(); loading.cancelAndJoin(); client.close() }
        }
    }
}
