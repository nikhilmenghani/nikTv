package com.nikhil.niktv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class AlternateSourceResolverTest {
    // These fixtures exercise the original Unity search/iframe protocol. Auto's
    // multi-source fan-out is covered independently in AlternateAutoSourceTest.
    private val movie = AlternateSourceTarget("A Sample Film", false, tmdbId = null, year = 2020,
        provider = AlternateSourceProvider.STREAMING_UNITY)
    private class Fixture {
        val requests = mutableListOf<AlternateSourceHttpRequest>()
        var clock = 1000L
        var search = """{"data":[{"id":12,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"}]}"""
        var frame = """<iframe src="https://vixcloud.co/embed/sample?language=en&amp;safe=1"></iframe>"""
        var season = """<div data-page="{&quot;props&quot;:{&quot;loadedSeason&quot;:{&quot;episodes&quot;:[{&quot;id&quot;:321,&quot;number&quot;:2}]}}}"></div>"""
        var embed = """const masterPlaylist={ url:'https://cdn.example.test/video/master.m3u8?existing=keep', params:{'token':'test-only','expires':'9999999999'} }; window.canPlayFHD=false;"""
        var manifest = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
720/index.m3u8?quality=720
#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
../1080/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
https://other.example.test/360.m3u8
"""
        var code = 200
        val resolver = AlternateSourceResolver(fetch = { request ->
            requests += request
            val body = when {
                "/it/search" in request.url -> search
                "/season-" in request.url -> season
                "/it/iframe" in request.url -> frame
                "vixcloud.co" in request.url -> embed
                else -> manifest
            }
            AlternateSourceHttpResponse(request.url, code, body)
        }, now = { clock })
    }

    @Test fun movieReturnsAutoAndSortedVariantsPreservingSigningAndHeaders() = runBlocking {
        val fixture = Fixture()
        val streams = fixture.resolver.resolve(movie)
        assertEquals(listOf(null, 1080, 720, 360), streams.map { it.height })
        assertTrue(streams.first().label.startsWith("Auto"))
        assertTrue(streams[1].url.startsWith("https://cdn.example.test/1080/index.m3u8?"))
        assertTrue(streams[1].url.contains("token=test-only"))
        assertTrue(streams[2].url.contains("quality=720"))
        assertTrue(streams[2].url.contains("existing=keep"))
        assertFalse(streams.last().url.contains("token="))
        assertEquals(setOf("Referer", "User-Agent"), streams.first().headers.keys)
        assertEquals("https://vixcloud.co/embed/sample?language=en&safe=1", streams.first().headers["Referer"])
        assertEquals(4, fixture.requests.size)
        assertTrue(fixture.requests.first().headers["Accept"] == "application/json")
    }

    @Test fun seriesRequiresExplicitEpisodeThenUsesActualEpisodeIdentity() = runBlocking {
        val fixture = Fixture()
        try { fixture.resolver.resolve(movie.copy(series = true)); fail("Missing episode allowed") }
        catch (_: AlternateSourceException) { }
        assertTrue(fixture.requests.isEmpty())
        fixture.search = """{"data":[{"id":9,"type":"tv","name":"A Sample Film","slug":"a-sample-film"}]}"""
        assertFalse(fixture.resolver.resolve(movie.copy(series = true, season = 1, episode = 2)).isEmpty())
        assertTrue(fixture.requests.any { it.url.contains("/it/titles/9-a-sample-film/season-1") })
        assertTrue(fixture.requests.any { it.url.contains("episode_id=321") })
        assertEquals(5, fixture.requests.size)
    }

    @Test fun unrelatedTitleTypeYearAndMissingEpisodeDoNotPickAnotherResult() = runBlocking {
        for (search in listOf(
            """{"data":[{"id":1,"type":"movie","name":"A Sample Film 2","release_date":"2020-01-01"}]}""",
            """{"data":[{"id":1,"type":"tv","name":"A Sample Film","release_date":"2020-01-01"}]}""",
            """{"data":[{"id":1,"type":"movie","name":"A Sample Film","release_date":"1980-01-01"}]}""",
        )) {
            val fixture = Fixture().apply { this.search = search }
            assertTrue(fixture.resolver.resolve(movie).isEmpty())
            assertEquals(1, fixture.requests.size)
        }
        val fixture = Fixture().apply { search = """{"data":[{"id":9,"type":"tv","name":"A Sample Film","slug":"sample"}]}""" }
        assertTrue(fixture.resolver.resolve(movie.copy(series = true, season = 1, episode = 3)).isEmpty())
        assertEquals(2, fixture.requests.size)
    }


    @Test fun ambiguousExactNamesNeverChooseAnArbitraryRemake() = runBlocking {
        val fixture = Fixture().apply {
            search = """{"data":[{"id":11,"type":"movie","name":"A Sample Film","release_date":"2000-01-01"},{"id":22,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"}]}"""
        }
        assertTrue(fixture.resolver.resolve(movie.copy(year = null)).isEmpty())
        assertEquals(1, fixture.requests.size)
    }

    @Test fun exactYearAndVerifiedPublicTmdbHintDisambiguateNames() = runBlocking {
        val fixture = Fixture().apply {
            search = """{"data":[{"id":11,"type":"movie","name":"A Sample Film","release_date":"2000-01-01"},{"id":22,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"}]}"""
        }
        assertFalse(fixture.resolver.resolve(movie).isEmpty())
        assertTrue(fixture.requests.any { it.url.endsWith("/it/iframe/22") })
        val hints = Fixture().apply {
            search = """{"data":[{"id":11,"tmdb_id":99,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"},{"id":22,"tmdb_id":7,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"}]}"""
        }
        assertFalse(hints.resolver.resolve(movie.copy(tmdbId = 7)).isEmpty())
        assertTrue(hints.requests.any { it.url.endsWith("/it/iframe/22") })
        assertFalse(hints.requests.any { it.url.endsWith("/it/iframe/11") })
    }

    @Test fun equalYearRemakesRemainAmbiguousButDuplicateResultIdsDoNot() = runBlocking {
        val fixture = Fixture().apply {
            search = """{"data":[{"id":11,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"},{"id":22,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"}]}"""
        }
        assertTrue(fixture.resolver.resolve(movie).isEmpty())
        val duplicate = Fixture().apply {
            search = """{"data":[{"id":11,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"},{"id":11,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"}]}"""
        }
        assertFalse(duplicate.resolver.resolve(movie).isEmpty())
    }

    @Test fun temporaryCacheIsBoundedExpiresAndKeepsEpisodeKeysSeparate() = runBlocking {
        val fixture = Fixture()
        fixture.resolver.resolve(movie)
        fixture.resolver.resolve(movie)
        assertEquals(4, fixture.requests.size)
        fixture.clock += 120_000
        fixture.resolver.resolve(movie)
        assertEquals(8, fixture.requests.size)
        fixture.search = """{"data":[{"id":9,"type":"tv","name":"A Sample Film","slug":"sample"}]}"""
        fixture.resolver.resolve(movie.copy(series = true, season = 1, episode = 2))
        fixture.resolver.resolve(movie.copy(series = true, season = 1, episode = 3))
        assertEquals(15, fixture.requests.size)
    }


    @Test fun explicitRetryInvalidatesCacheAndNearExpiryLinksAreRefetched() = runBlocking {
        val fixture = Fixture()
        fixture.resolver.resolve(movie)
        fixture.resolver.invalidate(movie)
        fixture.resolver.resolve(movie)
        assertEquals(8, fixture.requests.size)
        fixture.resolver.invalidate(movie)
        fixture.clock = 1_900_000_000_000L
        fixture.embed = """const masterPlaylist={url:'https://cdn.example.test/master.m3u8',params:{'expires':'1900000010'}};"""
        fixture.resolver.resolve(movie)
        fixture.resolver.resolve(movie)
        assertEquals(16, fixture.requests.size)
    }


    @Test fun browserEquivalentRequestDoesNotInventFhdEntitlementOrReadOtherParams() = runBlocking {
        val fixture = Fixture().apply {
            embed = """const unrelated={params:{'h':'1','foreign':'wrong'}};
window.masterPlaylist={params:{'token':'test-only','expires':'9999999999'},url:'https://cdn.example.test/master.m3u8'};
window.canPlayFHD=false; const after={params:{'after':'wrong'}};"""
        }
        val result = fixture.resolver.resolve(movie)
        val playlistRequest = fixture.requests.last().url
        assertTrue(playlistRequest.contains("token=test-only"))
        assertFalse(playlistRequest.contains("h=1"))
        assertFalse(playlistRequest.contains("foreign="))
        assertFalse(playlistRequest.contains("after="))
        assertFalse(result.first().url.contains("h=1"))
    }

    @Test fun emptyResultsAndFailuresAreNotCached() = runBlocking {
        val fixture = Fixture().apply { search = """{"data":[]}""" }
        assertTrue(fixture.resolver.resolve(movie).isEmpty())
        fixture.search = """{"data":[{"id":12,"type":"movie","name":"A Sample Film","release_date":"2020-01-01"}]}"""
        assertFalse(fixture.resolver.resolve(movie).isEmpty())
        assertEquals(5, fixture.requests.size)
    }

    @Test fun authorizationChallengeOversizeAndProtectedMediaFailWithoutBypass() = runBlocking {
        for (code in listOf(401, 403, 429)) {
            val fixture = Fixture().apply { this.code = code }
            try { fixture.resolver.resolve(movie); fail("Rejected status allowed") }
            catch (_: AlternateSourceException) { }
            assertEquals(1, fixture.requests.size)
        }
        for (body in listOf("<title>Just a moment...</title><script src='/cdn-cgi/challenge-platform'>", "x".repeat(512 * 1024 + 1))) {
            val fixture = Fixture().apply { search = body }
            try { fixture.resolver.resolve(movie); fail("Rejected response allowed") }
            catch (_: AlternateSourceException) { }
            assertEquals(1, fixture.requests.size)
        }
        val fixture = Fixture().apply { manifest = "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"license\",KEYFORMAT=\"com.widevine\"\n" }
        try { fixture.resolver.resolve(movie); fail("DRM allowed") }
        catch (_: AlternateSourceException) { }
    }


    @Test fun standardHlsEncryptionIsLeftForThePlayerWithoutCustomKeyHandling() = runBlocking {
        val fixture = Fixture().apply {
            manifest = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\",KEYFORMAT=\"identity\"\n#EXTINF:6,\nsegment.ts\n"
        }
        assertEquals(1, fixture.resolver.resolve(movie).size)
        assertEquals(4, fixture.requests.size)
        assertTrue(fixture.requests.none { it.url.endsWith("/key") || it.url.endsWith("segment.ts") })
    }

    @Test fun unsafeLinksAndMalformedSourceDataAreNotPlayed() = runBlocking {
        for (url in listOf("http://cdn.example.test/master.m3u8", "https://localhost/master.m3u8", "https://192.168.1.2/master.m3u8", "https://user:password@cdn.example.test/master.m3u8")) {
            val fixture = Fixture().apply { embed = "const masterPlaylist={url:'$url'};" }
            try { fixture.resolver.resolve(movie); fail("Unsafe link allowed") }
            catch (_: AlternateSourceException) { }
            assertEquals(3, fixture.requests.size)
        }
        val fixture = Fixture().apply { embed = "const masterPlaylist={url:computedUrl()};" }
        assertTrue(fixture.resolver.resolve(movie).isEmpty())
        assertEquals(3, fixture.requests.size)
    }

    @Test fun childQueryWinsAndAbsoluteChildDoesNotInheritSigning() = runBlocking {
        val fixture = Fixture().apply {
            manifest = """#EXTM3U
#EXT-X-STREAM-INF:RESOLUTION=1920x1080
high.m3u8?token=child
#EXT-X-STREAM-INF:RESOLUTION=1280x720
https://cdn.example.test/absolute.m3u8
"""
        }
        val result = fixture.resolver.resolve(movie)
        assertTrue(result[1].url.contains("token=child"))
        assertFalse(result[1].url.contains("token=test-only"))
        assertEquals("https://cdn.example.test/absolute.m3u8", result[2].url)
    }


    @Test fun lookupBudgetExpiryIsAnActionableFailureRatherThanUserCancellation() = runBlocking {
        val resolver = AlternateSourceResolver(fetch = {
            delay(60_000)
            error("Timed out request resumed")
        }, timeoutMillis = 20L)
        try { resolver.resolve(movie); fail("Hanging source did not time out") }
        catch (safe: AlternateSourceException) { assertTrue(safe.message.orEmpty().contains("took too long")) }
        assertFalse(Fixture().resolver.resolve(movie).isEmpty())
    }

    @Test fun cancellingLookupStopsWorkAndAllowsTheNextLookup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resolver = AlternateSourceResolver(fetch = {
            started.complete(Unit)
            delay(60_000)
            error("Cancelled request resumed")
        })
        val pending = async { resolver.resolve(movie) }
        started.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertFalse(Fixture().resolver.resolve(movie).isEmpty())
    }


    @Test fun updatedPublicEmbedHostnameAndLeadingArticleAreSupported() = runBlocking {
        val fixture = Fixture().apply {
            search = """{"data":[{"id":1994,"type":"movie","name":"Matrix","last_air_date":"1999-03-30"}]}"""
            frame = """<iframe src="https://vixcloud.cc/embed/test-only?lang=en"></iframe>"""
        }
        // The transport fixture returns the static embed for either supported public host.
        val resolver = AlternateSourceResolver(fetch = { request ->
            val response = when {
                "/it/search" in request.url -> fixture.search
                "/it/iframe" in request.url -> fixture.frame
                "vixcloud.cc" in request.url -> fixture.embed
                else -> fixture.manifest
            }
            AlternateSourceHttpResponse(request.url, 200, response)
        })
        val streams = resolver.resolve(movie.copy(title = "The Matrix", year = 1999))
        assertFalse(streams.isEmpty())
        assertTrue(streams.first().headers["Referer"].orEmpty().startsWith("https://vixcloud.cc/embed/"))
        assertTrue(streams.all { it.headers.keys == setOf("Referer", "User-Agent") })
    }

    @Test fun networkErrorsNeverExposeSignedLinks() = runBlocking {
        val resolver = AlternateSourceResolver(fetch = { throw java.io.IOException("Failed https://cdn.example.test/private?token=do-not-show") })
        try { resolver.resolve(movie); fail() }
        catch (safe: AlternateSourceException) {
            assertFalse(safe.message.orEmpty().contains("token"))
            assertFalse(safe.message.orEmpty().contains("https"))
        }
    }

    @Test fun adapterDoesNotParseOrExecutePageJavascript() = runBlocking {
        val fixture = Fixture().apply {
            embed = "const masterPlaylist={url:decrypt('private-payload')}; const params=account.unlock();"
        }
        assertTrue(fixture.resolver.resolve(movie).isEmpty())
        assertTrue(fixture.requests.none { "/v1/" in it.url })
    }
}
