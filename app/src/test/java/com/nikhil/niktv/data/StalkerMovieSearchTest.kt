package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class StalkerMovieSearchTest {
    private fun search(rows: String, query: String = "Heart of the Beast", lookup: Boolean = true): PortalSearchPage = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portal") { exchange ->
            val body = """{"js":{"data":[$rows],"max_page":1}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/portal"
            val profile = PortalProfile("Fixture", url, "00:00:00:00:00:01")
            StalkerPortalClient(RuntimeEnvironment.getApplication()).search(
                PortalSession(profile, "fixture", url, "", "", ""), SearchContentType.MOVIES,
                query, 1, movieLookup = lookup)
        } finally { server.stop(0) }
    }

    @Test fun wordSeriesInMovieTitleDoesNotClassifyItAsTv() {
        val page = search("""{"id":"movie","name":"Series 7: The Contenders","is_movie":true,"is_series":"0"}""", "Series 7")
        assertEquals(listOf("movie"), page.items.map { it.id })
    }

    @Test fun contradictoryMovieAndSeriesFlagsAreRetainedOnlyForReview() {
        val page = search("""{"id":"movie","name":"Heart of the Beast","is_movie":true,"is_series":"1","episode_id":"0"}""")
        assertTrue(page.items.isEmpty())
        assertEquals(listOf("movie"), page.movieReviewCandidates.map { it.id })
    }

    @Test fun realSeriesAndEpisodesCannotBecomeMovieReviewCandidates() {
        val page = search("""
            {"id":"series","name":"Heart of the Beast","is_movie":false,"is_series":"1"},
            {"id":"episode","name":"Heart of the Beast","is_movie":true,"is_series":"1","episode_id":"123"},
            {"id":"episode-flag","name":"Heart of the Beast","is_movie":true,"is_episode":true,"episode_id":"0"}
        """.trimIndent())
        assertTrue(page.items.isEmpty())
        assertTrue(page.movieReviewCandidates.isEmpty())
        assertEquals(3, page.providerItemIds.size)
    }

    @Test fun lookupKeepsProviderTmdbIdentityEvenWhenTitleIsLocalized() {
        val row = """{"id":"localized","name":"Provider localized title","is_series":"0","tmdb_id":"42"}"""
        val lookup = search(row)
        assertEquals(42, lookup.items.single().externalTmdbId)
        assertTrue(search(row, lookup = false).items.isEmpty()) // Ordinary Search keeps its keyword filter.
    }

    @Test fun zeroProviderTmdbIdDoesNotClaimAnIdentity() {
        val page = search("""{"id":"movie","name":"Heart of the Beast","tmdb_id":"0"}""")
        assertNull(page.items.single().externalTmdbId)
    }
}
