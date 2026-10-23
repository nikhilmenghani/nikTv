package com.nikhil.niktv.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class RiveSourceResolverTest {
    private val movie = AlternateSourceTarget("Movie", false, tmdbId = 603, provider = AlternateSourceProvider.RIVE_CITADEL)
    @Test fun movieAndEpisodeUseExactAdvertisedIdentityAndOnlyPublicMediaHeaders() = runBlocking {
        val requests = mutableListOf<AlternateSourceHttpRequest>()
        val resolver = RiveSourceResolver { request, _ ->
            requests += request
            AlternateSourceHttpResponse(request.url, 200, if (request.url.contains("/api/provider"))
                """{"data":{"sources":[{"url":"https://media.example.test/master.m3u8","format":"hls","quality":"1080p | Hindi"}]}}"""
            else "#EXTM3U\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n")
        }
        val stream = resolver.resolve(movie).single()
        assertEquals(1080, stream.height)
        assertTrue(stream.label.contains("Hindi"))
        assertEquals(AlternatePlaybackRoute.NATIVE, stream.route)
        assertEquals(setOf("Referer", "User-Agent"), stream.headers.keys)
        assertEquals("citadel", requests[0].url.toHttpUrl().queryParameter("provider"))
        assertNull(requests[0].url.toHttpUrl().queryParameter("season"))
        resolver.resolve(movie.copy(series = true, tmdbId = 1668, season = 3, episode = 12))
        val episode = requests[2].url.toHttpUrl()
        assertEquals("1668", episode.queryParameter("id"))
        assertEquals("3", episode.queryParameter("season"))
        assertEquals("12", episode.queryParameter("episode"))
    }
    @Test fun missingSourcesAndEmbedsAreNotNativeChoices() = runBlocking {
        for (body in listOf("""{"data":null}""", """{"data":{"sources":[{"format":"embed","url":"https://example.test/player"}]}}""")) {
            assertTrue(RiveSourceResolver { r, _ -> AlternateSourceHttpResponse(r.url, 200, body) }.resolve(movie).isEmpty())
        }
    }
    @Test fun mismatchedTitleAndNonMediaPagesAreRejected() = runBlocking {
        for (body in listOf("""{"data":{"tmdbId":604}}""", """{"data":{"sources":[{"format":"hls","url":"http://127.0.0.1/private"}]}}""")) {
            try { RiveSourceResolver { r, _ -> AlternateSourceHttpResponse(r.url, 200, body) }.resolve(movie); fail() }
            catch (_: AlternateSourceException) { }
        }
    }
    @Test fun removedBrowserProvidersAreRejectedBeforeAnyNetwork() = runBlocking {
        val resolver = AlternateSourceResolver(fetch = { error("Browser lookup must never run") })
        for (provider in listOf(AlternateSourceProvider.CINEJOY, AlternateSourceProvider.CNO)) {
            try { resolver.resolve(movie.copy(provider = provider)); fail() } catch (_: AlternateSourceException) { }
        }
    }
}
