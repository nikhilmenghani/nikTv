package com.nikhil.niktv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class SevenMoviesSourceResolverTest {
    private val movie = AlternateSourceTarget("The Matrix", false, tmdbId = 603, provider = AlternateSourceProvider.SEVEN_MOVIES)
    private class Fixture {
        val requests = mutableListOf<AlternateSourceHttpRequest>()
        var meta = JSONObject().put("tmdbId", "603").put("type", "movie")
            .put("bigbangUrl", "https://media.example.test/master.m3u8?exp=9999999999")
        var playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"\n1080.m3u8\n"
        var grant = """{"token":"fixture-guest-grant-not-a-secret"}"""
        var clock = 1000L
        val resolver = AlternateSourceResolver(fetch = { request ->
            requests += request
            val body = when {
                request.url.contains("/api/playback-token") -> grant
                request.url.contains("/api/boot/") -> JSONObject().put("meta", meta).toString()
                else -> playlist
            }
            AlternateSourceHttpResponse(request.url, 200, body)
        }, now = { clock })
    }

    @Test fun movieUsesAnonymousGrantAndNativePlaylistWithoutLeakingGrantToMedia() = runBlocking {
        val f = Fixture()
        val streams = f.resolver.resolve(movie)
        assertEquals(3, f.requests.size)
        assertEquals("POST", f.requests.first().method)
        val body = JSONObject(f.requests.first().jsonBody!!)
        assertEquals(603, body.getInt("tmdbId"))
        assertEquals("movie", body.getString("type"))
        val boot = f.requests[1].url.toHttpUrl()
        assertEquals("/api/boot/movie/603", boot.encodedPath)
        assertEquals("fixture-guest-grant-not-a-secret", boot.queryParameter("token"))
        assertEquals("https://7movies.ac", f.requests[1].headers["x-embed-parent"])
        assertEquals(listOf(null, 1080), streams.map { it.height })
        assertTrue(streams.all { it.label.contains("7Movies") && it.route == AlternatePlaybackRoute.NATIVE })
        assertTrue(streams.all { "fixture-guest-grant" !in it.url && "fixture-guest-grant" !in it.headers.values.joinToString() })
        assertFalse(f.requests.last().headers.containsKey("x-embed-parent"))
    }

    @Test fun episodeUsesExactIdentityAndMissingEpisodeDoesNotStartAGuestSession() = runBlocking {
        val f = Fixture()
        val target = movie.copy(tmdbId = 1668, series = true, season = 2, episode = 3)
        try { f.resolver.resolve(target.copy(episode = null)); fail("Expected identity check") }
        catch (_: AlternateSourceException) { }
        assertTrue(f.requests.isEmpty())
        f.meta.put("tmdbId", 1668).put("type", "tv").put("season", 2).put("episode", 3)
        assertFalse(f.resolver.resolve(target).isEmpty())
        assertEquals("/api/boot/tv/1668/2/3", f.requests[1].url.toHttpUrl().encodedPath)
        assertEquals(3, JSONObject(f.requests.first().jsonBody!!).getInt("episode"))
    }

    @Test fun mismatchedMovieOrEpisodeCannotReachTheMediaHost() = runBlocking {
        for (field in listOf("tmdbId", "type", "season", "episode")) {
            val f = Fixture()
            f.meta.put("tmdbId", 1668).put("type", "tv").put("season", 1).put("episode", 2)
            f.meta.put(field, if (field == "type") "movie" else 99)
            try { f.resolver.resolve(movie.copy(tmdbId = 1668, series = true, season = 1, episode = 2)); fail(field) }
            catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, failure.kind) }
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun externalAudioAndSubtitlesKeepTheCompleteMaster() = runBlocking {
        val f = Fixture()
        f.playlist = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,AUDIO="audio"
video.m3u8
"""
        assertEquals(1, f.resolver.resolve(movie).size)
        assertEquals(3, f.requests.size)
    }

    @Test fun browserChallengeDrmAndUnsafeMediaAreNotPlayable() = runBlocking {
        for (body in listOf("<title>Just a moment...</title>", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"license\"")) {
            val f = Fixture().apply { playlist = body }
            try { f.resolver.resolve(movie); fail("Expected rejection") }
            catch (failure: AlternateSourceException) { assertTrue(failure.kind in listOf(AlternateSourceFailure.CHALLENGE, AlternateSourceFailure.PROTECTED_MEDIA)) }
        }
        val f = Fixture().apply { meta.put("bigbangUrl", "https://127.0.0.1/internal") }
        try { f.resolver.resolve(movie); fail("Expected unsafe URL rejection") }
        catch (_: AlternateSourceException) { }
        assertEquals(2, f.requests.size)
    }

    @Test fun malformedOrMissingGrantNeverOpensThePlayerEndpoint() = runBlocking {
        for (body in listOf("not-json", "{}", """{"token":"short"}""")) {
            val f = Fixture().apply { grant = body }
            try { f.resolver.resolve(movie); fail("Expected unsupported grant") }
            catch (failure: AlternateSourceException) { assertFalse(failure.message.orEmpty().contains(body)) }
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun cacheInvalidationAndExpiryObtainFreshGuestGrants() = runBlocking {
        val f = Fixture()
        f.resolver.resolve(movie); f.resolver.resolve(movie)
        assertEquals(3, f.requests.size)
        f.resolver.invalidate(movie); f.resolver.resolve(movie)
        assertEquals(6, f.requests.size)
        f.clock += 120_000; f.resolver.resolve(movie)
        assertEquals(9, f.requests.size)
    }

    @Test fun cancellingGuestSessionDoesNotContinueToBootOrMedia() = runBlocking {
        val started = CompletableDeferred<Unit>(); var calls = 0
        val resolver = AlternateSourceResolver(fetch = { calls++; started.complete(Unit); awaitCancellation() })
        val job = async { resolver.resolve(movie) }
        started.await(); job.cancelAndJoin()
        assertEquals(1, calls)
    }

    @Test fun guestAndBootResponsesCannotRedirectIdentityToAnotherOrigin() = runBlocking {
        for (redirectGrant in listOf(true, false)) {
            val resolver = AlternateSourceResolver(fetch = { request ->
                val grant = request.method == "POST"
                val body = if (grant) """{"token":"fixture-guest-grant-not-a-secret"}""" else "{}"
                AlternateSourceHttpResponse(if (grant == redirectGrant) "https://foreign.example.test/" else request.url, 200, body)
            })
            try { resolver.resolve(movie); fail("Expected origin check") }
            catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, failure.kind) }
        }
    }
}
