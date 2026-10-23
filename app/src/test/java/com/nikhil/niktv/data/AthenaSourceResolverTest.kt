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
class AthenaSourceResolverTest {
    private val target = AlternateSourceTarget("Friends", true, tmdbId = 1668, season = 2, episode = 4, provider = AlternateSourceProvider.ATLANTIC_ATHENA)
    @Test fun exactEpisodeGetsNativeMasterAndAdvertisedQualities() = runBlocking {
        val requests = mutableListOf<AlternateSourceHttpRequest>()
        val resolver = AthenaSourceResolver { r, _ ->
            requests += r
            AlternateSourceHttpResponse(r.url, 200, if (requests.size == 1)
                """{"ready":true,"url":"https://media.example.test/master.m3u8"}"""
            else "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nvideo.m3u8\n")
        }
        val streams = resolver.resolve(target)
        assertEquals(listOf(null, 1080), streams.map { it.height })
        assertTrue(streams.all { it.route == AlternatePlaybackRoute.NATIVE })
        val query = requests[0].url.toHttpUrl()
        assertEquals("1668", query.queryParameter("tmdb"))
        assertEquals("tv", query.queryParameter("type"))
        assertEquals("2", query.queryParameter("season"))
        assertEquals("4", query.queryParameter("episode"))
        assertEquals(setOf("User-Agent", "Referer"), streams[0].headers.keys)
    }
    @Test fun unavailableTitleHasNoBrowserFallback() = runBlocking {
        val resolver = AthenaSourceResolver { r, _ -> AlternateSourceHttpResponse(r.url, 200, """{"ready":false}""") }
        assertTrue(resolver.resolve(target).isEmpty())
        assertFalse(AlternateSourceProvider.RIVE_VANGUARD in nativeSourceProviders)
    }
    @Test fun unsafeUrlAndMismatchedTitleAreRejected() = runBlocking {
        for (body in listOf("""{"ready":true,"tmdb":12,"url":"https://media.example.test/master.m3u8"}""",
            """{"ready":true,"url":"file:///private"}""")) {
            try { AthenaSourceResolver { r, _ -> AlternateSourceHttpResponse(r.url, 200, body) }.resolve(target); fail() }
            catch (_: AlternateSourceException) { }
        }
    }
}
