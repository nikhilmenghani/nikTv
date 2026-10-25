package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
class PortalVodHierarchyTest {
    private val container = MediaItem("movie", "Fixture event", null, "placeholder", portalCategoryId = "sports")
    private fun fixture(body: (String) -> String, test: suspend (StalkerPortalClient, PortalSession, List<String>) -> Unit) {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty(); requests += request
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val bytes = body(request).toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
        val root = "http://127.0.0.1:${server.localPort}"
        val session = PortalSession(PortalProfile("Fixture", root, "00:00:00:00:00:00"), "token", "$root/server/load.php", "", "{}", "")
        val client = StalkerPortalClient(RuntimeEnvironment.getApplication())
        try { runBlocking { test(client, session, requests) } }
        finally { client.close(); server.close(); worker.join(1000) }
    }

    @Test fun movieContainerOpensSeasonsAndUsesActualEpisodeFileNotSeasonFile() {
        fixture({ request -> when {
            request.contains("action=create_link") -> """{"js":{"cmd":"ffmpeg https://media.invalid/episode.m3u8"}}"""
            request.contains("episode_id=file1") -> """{"js":{"data":[{"id":"file1"}]}}"""
            request.contains("season_id=season1") -> """{"js":{"data":[
                {"id":"file2","is_episode":true,"series_number":"2","name":"Episode 2. Highlights","series":["2","1"]},
                {"id":"file1","is_episode":true,"series_number":"1","name":"Episode 1. Highlights","series":["2","1"]}],"total_items":"2"}}"""
            else -> """{"js":{"data":[{"id":"season1","is_season":true,"season_number":"1","name":"Season 1"}]}}"""
        } }) { client, session, requests ->
            assertTrue(client.movieHasEpisodes(session, container))
            try { client.playableUrl(session, container, CatalogType.MOVIES); fail("Season cannot be played") }
            catch (_: PortalVodSeriesContainerException) { }
            assertEquals(1, requests.size) // Probe and Play reuse the same metadata, no create_link.
            val result = client.episodeSeason(session, container.copy(command = null, vodSeriesContainer = true), SeriesStartSeason.FIRST)
            assertEquals(listOf(1), result.availableSeasons)
            assertEquals(listOf(1, 2), result.episodes.map { it.episodeNumber })
            assertEquals(listOf("file1", "file2"), result.episodes.map { it.portalEpisodeId })
            assertFalse(result.hasMore)
            assertEquals("https://media.invalid/episode.m3u8", client.playableUrl(session, result.episodes.first(), CatalogType.SERIES))
            assertTrue(requests.last(), java.net.URLDecoder.decode(requests.last(), "UTF-8").contains("cmd=/media/file_file1.mpg"))
            assertTrue(requests.last().contains("series=1"))
        }
    }

    @Test fun ordinaryMovieWithAmbiguousFlagsStillPlaysAndMetadataIsSessionScoped() {
        fixture({ request -> if (request.contains("action=create_link"))
            """{"js":{"cmd":"ffmpeg https://media.invalid/movie.mp4"}}"""
            else """{"js":{"data":[{"id":"file","is_movie":true,"is_series":"1","series":[]}]}}"""
        }) { client, session, requests ->
            assertFalse(client.movieHasEpisodes(session, container))
            assertEquals("https://media.invalid/movie.mp4", client.playableUrl(session, container, CatalogType.MOVIES))
            assertEquals(2, requests.size)
            assertFalse(client.movieHasEpisodes(session.copy(token = "renewed"), container))
            assertEquals(3, requests.size)
            client.invalidateCatalog(session.profile, Category("sports", "Sports", CatalogType.MOVIES))
            assertFalse(client.movieHasEpisodes(session, container))
            assertEquals(4, requests.size)
        }
    }

    @Test fun onlyExplicitHierarchyEvidenceClassifiesAContainer() {
        for (json in listOf("[]", "{}", "null", """[{"is_series":1,"is_movie":true,"series":[]}]"""))
            assertFalse(portalVodHasEpisodes(Json.parseToJsonElement(json)))
        for (json in listOf("""[{"is_season":"1"}]""", """{"data":[{"is_episode":true}]}""", """[{"series":["1","2"]}]"""))
            assertTrue(portalVodHasEpisodes(Json.parseToJsonElement(json)))
    }
}
