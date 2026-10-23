package com.nikhil.niktv.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class VidSparkSourceResolverTest {
    private val movie = AlternateSourceTarget("The Matrix", false, 603, 1999)
    private class Fixture {
        val requests = mutableListOf<AlternateSourceHttpRequest>()
        var apiCode = 200
        var cdnApiCode = 404
        var cdnApi = """{"result":false}"""
        var cdnPage = """<script src="/assets/main-current.js"></script>"""
        var cdnBundle = """const cdnBinding='fixture-cdn-client-header';fetch('/api/vidora',{headers:{'x-player-key':cdnBinding}})"""
        var api = """{"result":true,"type":"movie","tmdb_id":"603","view_id":777,"year":1999,"sources":[{"url":"https://cdn.example.test/master.m3u8?expires=9999999999","source":"vidora","tracks":[]}]}"""
        var page = """<script src="/assets/index-fresh.js"></script>"""
        var bundle = """const differentBinding='fixture-public-client-header';function read(e){return fetch(`/api/vidora${'$'}{e}`,{headers:{"x-player-key":differentBinding}})}"""
        var master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.640028,mp4a.40.2"
720/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
1080/index.m3u8
"""
        val resolver = AlternateSourceResolver(fetch = { request ->
            requests += request
            when {
                request.url.startsWith("https://cdn.vidspark.to/") -> when {
                    "/assets/" in request.url -> AlternateSourceHttpResponse(request.url, 200, cdnBundle)
                    "/api/vidora/" in request.url -> AlternateSourceHttpResponse(request.url, cdnApiCode, cdnApi)
                    else -> AlternateSourceHttpResponse(request.url, 200, cdnPage)
                }
                "/assets/" in request.url -> AlternateSourceHttpResponse(request.url, 200, bundle)
                "/api/vidora/" in request.url -> AlternateSourceHttpResponse(request.url, apiCode, api)
                "cdn.example.test" in request.url -> AlternateSourceHttpResponse(request.url, 200, master)
                "/it/search" in request.url -> AlternateSourceHttpResponse(request.url, 200, """{"data":[]}""")
                else -> AlternateSourceHttpResponse(request.url, 200, page)
            }
        })
    }

    @Test fun publicClientDiscoveryReturnsExactTitleAutoAndMuxedQualities() = runBlocking {
        val fixture = Fixture()
        val streams = fixture.resolver.resolve(movie)
        assertEquals(listOf(null, 1080, 720), streams.map { it.height })
        assertTrue(streams.first().label.contains("VidSpark"))
        assertEquals(4, fixture.requests.size)
        assertTrue(fixture.requests.any { it.url == "https://moviesapi.to/api/vidora/v1/movie/603" })
        assertTrue(fixture.requests.any { "/assets/index-fresh.js" in it.url && it.maxBytes >= 2 * 1024 * 1024 })
        val apiRequest = fixture.requests.single { "/api/vidora/" in it.url }
        assertEquals("fixture-public-client-header", apiRequest.headers["x-player-key"])
        assertTrue(fixture.requests.filter { "cdn.example.test" in it.url }.all { "x-player-key" !in it.headers && "Origin" !in it.headers })
        assertTrue(streams.all { it.headers.keys == setOf("User-Agent", "Referer") })
        assertFalse(fixture.requests.any { "streamingunity" in it.url })
    }


    @Test fun olderMovieUsesOnlyNormalAlphaFallbackAndItsOwnPublicClientOrigin() = runBlocking {
        val fixture = Fixture().apply {
            apiCode = 404
            cdnApiCode = 200
            cdnApi = api
            master = "#EXTM3U\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n"
        }
        val streams = fixture.resolver.resolve(movie)
        assertEquals(1, streams.size)
        assertTrue(streams.single().label.contains("VidSpark Alpha"))
        assertEquals("https://cdn.vidspark.to/", streams.single().headers["Referer"])
        assertEquals(7, fixture.requests.size)
        val alphaApi = fixture.requests.single { it.url.startsWith("https://cdn.vidspark.to/api/vidora/") }
        assertTrue(alphaApi.url.endsWith("/v1/movie/603?source=scrapify"))
        assertEquals("fixture-cdn-client-header", alphaApi.headers["x-player-key"])
        assertEquals("https://cdn.vidspark.to", alphaApi.headers["Origin"])
        assertTrue(fixture.requests.filter { "cdn.example.test" in it.url }.all { it.headers.keys == setOf("User-Agent", "Referer") && it.maxBytes >= 1024 * 1024 })
        assertFalse(fixture.requests.any { "streamingunity" in it.url })
    }

    @Test fun alphaTvFallbackPreservesExactEpisodeAndDoesNotFanOutServers() = runBlocking {
        val fixture = Fixture().apply {
            apiCode = 404
            cdnApiCode = 200
            cdnApi = """{"result":true,"type":"tv","tmdb_id":"1668","season":3,"episode":25,"sources":[{"url":"https://cdn.example.test/master.m3u8"}]}"""
        }
        assertFalse(fixture.resolver.resolve(movie.copy(title = "Friends", series = true, tmdbId = 1668, season = 3, episode = 25)).isEmpty())
        val api = fixture.requests.filter { "/api/vidora/" in it.url }
        assertEquals(2, api.size)
        assertTrue(api.last().url.endsWith("/v1/tv/1668/3/25?source=scrapify"))
        assertFalse(fixture.requests.any { "oreon" in it.url || "vaplayer" in it.url || "quasar" in it.url })
    }

    @Test fun alphaAuthorizationFailureIsNotRetriedOrHiddenByLegacyLookup() = runBlocking {
        val fixture = Fixture().apply { apiCode = 404; cdnApiCode = 403 }
        try { fixture.resolver.resolve(movie); fail() }
        catch (safe: AlternateSourceException) {
            assertEquals(AlternateSourceFailure.AUTHORIZATION, safe.kind)
            assertTrue(safe.message.orEmpty().contains("VidSpark Alpha source lookup"))
        }
        assertEquals(6, fixture.requests.size)
        assertFalse(fixture.requests.any { "streamingunity" in it.url })
    }

    @Test fun tvUsesExactTmdbSeasonAndEpisodeInsteadOfInternalViewId() = runBlocking {
        val fixture = Fixture().apply {
            api = """{"result":true,"type":"tv","tmdb_id":"1668","view_id":999,"season":2,"episode":3,"sources":[{"url":"https://cdn.example.test/master.m3u8"}]}"""
        }
        assertFalse(fixture.resolver.resolve(movie.copy(title = "Friends", series = true, tmdbId = 1668, season = 2, episode = 3)).isEmpty())
        assertTrue(fixture.requests.any { it.url == "https://moviesapi.to/tv/1668/2/3" })
        assertTrue(fixture.requests.any { it.url == "https://moviesapi.to/api/vidora/v1/tv/1668/2/3" })
    }

    @Test fun responseIdentityMismatchNeverFetchesMediaOrFallsBackByTitle() = runBlocking {
        for (api in listOf(
            """{"result":true,"type":"movie","tmdb_id":"604","sources":[]}""",
            """{"result":true,"type":"movie","sources":[]}""",
            """{"result":true,"tmdb_id":"603","sources":[]}""",
            """{"result":true,"type":"movie","tmdb_id":"0","sources":[]}""",
            """{"result":true,"type":"tv","tmdb_id":"603","sources":[]}""",
            """{"result":true,"type":"movie","tmdb_id":"603","year":2024,"sources":[]}""",
        )) {
            val fixture = Fixture().apply { this.api = api }
            try { fixture.resolver.resolve(movie); fail("Different identity accepted") }
            catch (safe: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, safe.kind) }
            assertEquals(3, fixture.requests.size)
        }
    }

    @Test fun deniedApiIsNotRetriedOrHiddenByAnotherSource() = runBlocking {
        val fixture = Fixture().apply { apiCode = 403 }
        try { fixture.resolver.resolve(movie); fail() }
        catch (safe: AlternateSourceException) {
            assertEquals(AlternateSourceFailure.AUTHORIZATION, safe.kind)
            assertTrue(safe.message.orEmpty().contains("VidSpark source lookup"))
            assertFalse(safe.message.orEmpty().contains("fixture-public"))
        }
        assertEquals(3, fixture.requests.size)
    }

    @Test fun genuineNoResultsCanUseLegacyLookupWithoutSendingClientHeader() = runBlocking {
        val fixture = Fixture().apply { api = """{"result":false,"sources":[]}""" }
        assertTrue(fixture.resolver.resolve(movie).isEmpty())
        assertEquals(7, fixture.requests.size)
        assertTrue(fixture.requests.last().url.startsWith("https://streamingunity.fun/it/search"))
        assertFalse(fixture.requests.last().headers.containsKey("x-player-key"))
    }


    @Test fun unavailablePublicIdentityIsANormalEmptyResultAndFallbackRemainsBounded() = runBlocking {
        val fixture = Fixture().apply { apiCode = 404 }
        assertTrue(fixture.resolver.resolve(movie).isEmpty())
        assertEquals(7, fixture.requests.size)
        assertEquals(1, fixture.requests.count { it.url.startsWith("https://moviesapi.to/api/vidora/") })
        assertEquals(1, fixture.requests.count { it.url.startsWith("https://cdn.vidspark.to/api/vidora/") })
    }

    @Test fun failedFallbackRetainsPreferredNoResultAndDebugLogsContainOnlySafeMetadata() = runBlocking {
        val fixture = Fixture()
        val resolver = AlternateSourceResolver(fetch = { request ->
            when {
                "/assets/" in request.url -> AlternateSourceHttpResponse(request.url, 200, fixture.bundle)
                "/api/vidora/" in request.url -> AlternateSourceHttpResponse(request.url, 404, "{}")
                "/it/search" in request.url -> AlternateSourceHttpResponse(request.url, 403, "denied")
                else -> AlternateSourceHttpResponse(request.url, 200, if (request.url.startsWith("https://cdn.vidspark.to/")) fixture.cdnPage else fixture.page)
            }
        })
        try { resolver.resolve(movie); fail() }
        catch (safe: AlternateSourceException) {
            assertEquals(AlternateSourceFailure.AUTHORIZATION, safe.kind)
            assertTrue(safe.message.orEmpty().contains("VidSpark has no source"))
            assertTrue(safe.message.orEmpty().contains("fallback search request returned HTTP 403"))
        }
        if (com.nikhil.niktv.BuildConfig.DEBUG) {
            val log = org.robolectric.shadows.ShadowLog.getLogsForTag("NikTvAlternateSource").joinToString("\n") { it.msg }
            assertTrue(log.contains("VidSpark source lookup HTTP 404 bytes"))
            assertFalse(log.contains("https://"))
            assertFalse(log.contains("fixture-public-client-header"))
            assertFalse(log.contains("x-player-key"))
        }
    }

    @Test fun externalAudioAndSubtitleGroupsRemainOnTheCompleteMaster() = runBlocking {
        for (type in listOf("AUDIO", "SUBTITLES")) {
            val fixture = Fixture().apply {
                master = """#EXTM3U
#EXT-X-MEDIA:TYPE=$type,GROUP-ID="separate",NAME="English",URI="english.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.640028,mp4a.40.2",$type="separate"
720/index.m3u8
"""
            }
            val streams = fixture.resolver.resolve(movie)
            assertEquals(1, streams.size)
            assertTrue(streams.single().label.startsWith("Auto"))
            assertTrue(streams.single().url.contains("master.m3u8"))
        }
        val fixture = Fixture().apply {
            master = """#EXTM3U
#EXT-X-STREAM-INF:RESOLUTION=1920x1080,CODECS="avc1.640028"
video-only.m3u8
"""
        }
        assertEquals(1, fixture.resolver.resolve(movie).size)
    }

    @Test fun scriptMustBelongToPublicSiteAndPlainClientValueIsNeverEvaluated() = runBlocking {
        val fixture = Fixture().apply { page = """<script src="https://other.example.test/assets/index-external.js"></script>""" }
        assertTrue(fixture.resolver.resolve(movie).isEmpty()) // unsupported source -> empty legacy lookup
        assertEquals(5, fixture.requests.size)
        assertFalse(fixture.requests.any { "other.example.test" in it.url })
        val dynamic = Fixture().apply { bundle = "const token=account.unlock();fetch('/api/vidora',{headers:{'x-player-key':token}})" }
        assertTrue(dynamic.resolver.resolve(movie).isEmpty())
        assertTrue(dynamic.requests.none { it.url.startsWith("https://moviesapi.to/api/vidora/") })
    }


    @Test fun publicScriptDifferentSchemeOrPortIsNotTheSameOrigin() = runBlocking {
        for (script in listOf("https://moviesapi.to:9443/assets/index-other.js", "http://moviesapi.to/assets/index-other.js")) {
            val fixture = Fixture().apply { page = """<script src="$script"></script>""" }
            assertTrue(fixture.resolver.resolve(movie).isEmpty())
            assertEquals(5, fixture.requests.size) // page, unavailable Alpha, then safe legacy search
            assertFalse(fixture.requests.any { it.url == script })
        }
    }

    @Test fun clientHeaderResponseMustRemainOnTheExactApiOrigin() = runBlocking {
        val fixture = Fixture()
        val requests = mutableListOf<AlternateSourceHttpRequest>()
        val resolver = AlternateSourceResolver(fetch = { request ->
            requests += request
            when {
                "/assets/" in request.url -> AlternateSourceHttpResponse(request.url, 200, fixture.bundle)
                "/api/vidora/" in request.url -> AlternateSourceHttpResponse("https://moviesapi.to:9443/api/vidora/v1/movie/603", 200, fixture.api)
                else -> AlternateSourceHttpResponse(request.url, 200, fixture.page)
            }
        })
        try { resolver.resolve(movie); fail("Different API origin accepted") }
        catch (safe: AlternateSourceException) { assertEquals(AlternateSourceFailure.AUTHORIZATION, safe.kind) }
        assertEquals(3, requests.size)
        assertFalse(requests.any { "cdn.example.test" in it.url })
    }

    @Test fun unknownIdentityLookupRunsInsideBudgetAndPreservesEpisodeContext() = runBlocking {
        var calls = 0
        var httpCalls = 0
        val resolver = AlternateSourceResolver(fetch = { httpCalls++; error("No HTTP expected") }, identityLookup = {
            calls++
            delay(60_000)
            it
        }, timeoutMillis = 20L)
        try { resolver.resolve(movie.copy(tmdbId = null)); fail() }
        catch (safe: AlternateSourceException) { assertTrue(safe.message.orEmpty().contains("took too long")) }
        assertEquals(1, calls)
        assertEquals(0, httpCalls)
        val wrongContext = AlternateSourceResolver(fetch = { error("No HTTP expected") }, identityLookup = { it.copy(tmdbId = 603, season = 2) })
        try { wrongContext.resolve(movie.copy(tmdbId = null)); fail() }
        catch (safe: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, safe.kind) }
    }

    @Test fun resolvedIdentityUsesPublicSourceAndCachedRetryCanBeInvalidated() = runBlocking {
        val fixture = Fixture()
        var identityCalls = 0
        val resolver = AlternateSourceResolver(fetch = { request ->
            when {
                "/assets/" in request.url -> AlternateSourceHttpResponse(request.url, 200, fixture.bundle)
                "/api/vidora/" in request.url -> AlternateSourceHttpResponse(request.url, 200, fixture.api)
                "cdn.example.test" in request.url -> AlternateSourceHttpResponse(request.url, 200, fixture.master)
                else -> AlternateSourceHttpResponse(request.url, 200, fixture.page)
            }
        }, identityLookup = { identityCalls++; it.copy(tmdbId = 603) })
        val unresolved = movie.copy(tmdbId = null)
        assertFalse(resolver.resolve(unresolved).isEmpty())
        resolver.resolve(unresolved)
        assertEquals(1, identityCalls)
        resolver.invalidate(unresolved)
        resolver.resolve(unresolved)
        assertEquals(2, identityCalls)
    }
}
