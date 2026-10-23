package com.nikhil.niktv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class OnlyFlixSourceResolverTest {
    private val episode = AlternateSourceTarget("Friends", true, 1668, season = 2, episode = 4, provider = AlternateSourceProvider.ONLYFLIX)
    private val source = """{"provider":"vaplayer","streams":[{"url":"/m3u8-proxy?url=test","route":"/m3u8-proxy","quality":"4K","verified":false}]}"""
    private val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhttps://media.example.test/video.m3u8\n"

    @Test fun episodeIdentityAndManifestQualityArePreserved() = runBlocking {
        val requests = mutableListOf<AlternateSourceHttpRequest>()
        val streams = OnlyFlixSourceResolver { r, _ ->
            requests += r
            AlternateSourceHttpResponse(r.url, 200, if (requests.size == 1) source else master)
        }.resolve(episode)
        assertTrue(requests[0].url.endsWith("/vaplayer/tv/1668/2/4"))
        assertEquals("https://2.medmedia03.wiki/embed/tv/1668/2/4", requests[1].headers["Referer"])
        assertEquals(listOf(null, 1080), streams.map { it.height })
        assertTrue(streams.all { it.route == AlternatePlaybackRoute.NATIVE })
    }

    @Test fun noStreamsDoesNotOpenBrowser() = runBlocking {
        val result = OnlyFlixSourceResolver { r, _ -> AlternateSourceHttpResponse(r.url, 200,
            """{"provider":"vaplayer","streams":[]}""") }.resolve(episode)
        assertTrue(result.isEmpty())
    }

    @Test fun unsafeAndEmbeddedLinksAreNeverFetched() = runBlocking {
        var calls = 0
        val result = OnlyFlixSourceResolver { r, _ ->
            calls++
            AlternateSourceHttpResponse(r.url, 200, """{"provider":"vaplayer","streams":[
                {"url":"https://other.example.test/media","route":"/m3u8-proxy"},
                {"url":"/embed/movie/12","route":"embed"},
                {"url":"","route":"/m3u8-proxy"}]}""")
        }.resolve(episode)
        assertEquals(1, calls)
        assertTrue(result.isEmpty())
    }

    @Test fun wrongProviderAndProtectedMediaAreRejected() = runBlocking {
        for (wrong in listOf(true, false)) {
            try {
                OnlyFlixSourceResolver { r, _ -> AlternateSourceHttpResponse(r.url, 200,
                    if (r.url.contains("embed-api")) {
                        if (wrong) source.replace("vaplayer", "other") else source
                    } else "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES\n") }.resolve(episode)
                fail("Must reject identity mismatch or protected media")
            } catch (failure: AlternateSourceException) {
                assertEquals(if (wrong) AlternateSourceFailure.IDENTITY else AlternateSourceFailure.PROTECTED_MEDIA, failure.kind)
            }
        }
    }

    @Test fun brokenMovieBackendDoesNotDiscardWorkingFallback() = runBlocking {
        val streams = OnlyFlixSourceResolver { r, _ ->
            if (r.url.contains("/onetouchtv/")) throw AlternateSourceException("Unavailable")
            AlternateSourceHttpResponse(r.url, 200, if (r.url.contains("embed-api")) source else master)
        }.resolve(episode.copy(series = false, season = null, episode = null))
        assertTrue(streams.isNotEmpty())
        assertTrue(streams.first().label.contains("Vaplayer"))
    }

    @Test fun verifiedMovieBackendIsPreferredWithoutWaitingForExtraMirrors() = runBlocking {
        val requests = mutableListOf<String>()
        val streams = OnlyFlixSourceResolver { r, _ ->
            requests += r.url
            AlternateSourceHttpResponse(r.url, 200, if (r.url.contains("embed-api"))
                source.replace("vaplayer", "onetouchtv").replace("false", "true") else master)
        }.resolve(episode.copy(series = false, season = null, episode = null))
        assertTrue(requests.first().contains("/onetouchtv/movie/1668"))
        assertEquals(2, requests.size)
        assertTrue(streams.first().label.contains("Onetouchtv"))
    }

    @Test fun cancellationPropagatesAndMissingIdentityMakesNoRequests() = runBlocking {
        val resolver = OnlyFlixSourceResolver { _, _ -> throw CancellationException("Canceled") }
        assertTrue(resolver.resolve(episode.copy(tmdbId = null)).isEmpty())
        try { resolver.resolve(episode); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        try { resolver.resolve(episode.copy(episode = 0)); fail("Invalid episode accepted") }
        catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, failure.kind) }
    }

    @Test fun autoIncludesOnlyFlixAndCachesItsNativeResults() = runBlocking {
        var lookups = 0
        val resolver = AlternateSourceResolver(fetch = { r ->
            when {
                r.url.contains("/embed-api/source/vaplayer/") -> {
                    lookups++
                    AlternateSourceHttpResponse(r.url, 200, source)
                }
                r.url.contains("/embed-api/source/onetouchtv/") -> AlternateSourceHttpResponse(r.url, 200,
                    """{"provider":"onetouchtv","streams":[]}""")
                r.url.contains("/m3u8-proxy") -> AlternateSourceHttpResponse(r.url, 200, master)
                else -> AlternateSourceHttpResponse(r.url, 403, "Unavailable")
            }
        })
        val movie = episode.copy(series = false, season = null, episode = null, provider = AlternateSourceProvider.AUTO)
        val first = resolver.resolve(movie)
        assertTrue(first.isNotEmpty())
        assertTrue(first.all { it.id.startsWith("onlyflix:") })
        assertEquals(first, resolver.resolve(movie))
        assertEquals(1, lookups)
    }
}
