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
import java.util.Collections
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class ProgrammeGuideLookupTest {
    private val now = System.currentTimeMillis()
    private val item = MediaItem("4385", "Channel", null, "unchanged-stream")
    private fun row(title: String, start: Long = now - 60_000, end: Long = now + 60_000) =
        """{"ch_id":"4385","name":"$title","start_timestamp":${start / 1000},"stop_timestamp":${end / 1000}}"""

    private fun lookup(xtream: Boolean = false, sharedGuideAcrossProfiles: Boolean = false,
                       response: (String) -> String): Pair<MediaItem, List<String>> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    requests += request
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val bytes = response(request).toByteArray(Charsets.UTF_8)
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            val root = "http://127.0.0.1:${server.localPort}"
            val profile = PortalProfile("Local fixture", root, "00:00:00:00:00:00",
                portalType = if (xtream) PortalType.XTREAM else PortalType.STALKER)
            val session = PortalSession(profile, "test", "$root/server/load.php", "", "{}", "")
            val result = runBlocking {
                val client = StalkerPortalClient(RuntimeEnvironment.getApplication())
                if (sharedGuideAcrossProfiles) {
                    client.categoryChannelSchedules(session.copy(profile = profile.copy(macAddress = "00:00:00:00:00:01")), listOf(item))
                    client.categoryChannelSchedules(session, listOf(item)).single()
                } else client.playingChannelSchedule(session, item, userSelected = true, forceRefresh = true)
            }
            return result to requests.toList()
        } finally { server.close(); worker.join(1000) }
    }

    @Test fun futureOnlyShortGuideUsesSharedGuideForCurrentProgramme() {
        val next = row("Next movie", now + 60_000, now + 120_000)
        val (result, requests) = lookup { request ->
            if (request.contains("action=get_short_epg")) """{"js":[$next]}"""
            else """{"js":{"data":[${row("Current movie")}]}}"""
        }
        assertEquals("Current movie", result.currentLiveProgramme(now)?.title)
        assertEquals(listOf("Current movie", "Next movie"), result.liveSchedule.map { it.title })
        assertEquals(2, requests.size)
        assertEquals(1, requests.count { it.contains("action=get_epg_info") })
        assertEquals("unchanged-stream", result.command)
    }

    @Test fun singleProgrammeObjectIsNotMistakenForChannelMap() {
        val (result, requests) = lookup { """{"js":{"data":${row("Single programme")}}}""" }
        assertEquals("Single programme", result.currentLiveProgramme(now)?.title)
        assertEquals(1, requests.size)
    }

    @Test fun nestedChannelIdTakesPrecedenceOverWrapperKey() {
        val (result, requests) = lookup {
            """{"js":{"data":{"wrapper":{"ch_id":"4385","epg":[${row("Nested programme")}]}}}}"""
        }
        assertEquals("Nested programme", result.currentLiveProgramme(now)?.title)
        assertEquals(1, requests.size)
    }

    @Test fun xtreamEndTimestampAliasPreservesProgressAndRejectsExpiredRow() {
        val (result, _) = lookup(xtream = true) {
            """{"epg_listings":[{"title":"T2xkIG1vdmll","start_timestamp":${now / 1000 - 120},"end_timestamp":${now / 1000 - 60}},{"title":"Q3VycmVudCBtb3ZpZQ==","start_timestamp":${now / 1000 - 60},"end_timestamp":${now / 1000 + 60}}]}"""
        }
        assertEquals("Current movie", result.currentLiveProgramme(now)?.title)
        assertNotNull(result.currentLiveProgramme(now)?.endTimeMillis)
        assertEquals(1, result.liveSchedule.size)
    }

    @Test fun sharedGuideCacheCannotCrossProfilesEvenWithIdenticalToken() {
        var calls = 0
        val (result, requests) = lookup(sharedGuideAcrossProfiles = true) {
            calls++
            """{"js":{"4385":[${row("Profile $calls")}]}}"""
        }
        assertEquals("Profile 2", result.currentLiveProgramme(now)?.title)
        assertEquals(2, requests.size)
    }

    @Test fun futureRowsSurviveWhenNeitherEndpointHasACurrentProgramme() {
        val (result, requests) = lookup { request ->
            if (request.contains("action=get_short_epg"))
                """{"js":[${row("Later", now + 60_000, now + 120_000)}]}""" else """{"js":[]}"""
        }
        assertNull(result.currentLiveProgramme(now))
        assertEquals("Later", result.currentLiveProgramme(now + 60_000)?.title)
        assertEquals(3, requests.size)
    }

    @Test fun unrelatedChannelGuideIsNeverShownForRequestedChannel() {
        val (result, _) = lookup { """{"js":[${row("Other channel").replace("4385", "9999")}]}""" }
        assertNull(result.currentLiveProgramme(now))
        assertTrue(result.liveSchedule.isEmpty())
    }
}
