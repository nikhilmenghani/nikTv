package com.nikhil.niktv.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class AbibliSourceResolverTest {
    private val movie = AlternateSourceTarget("Iron Man", false, 1726, 2008, provider = AlternateSourceProvider.ABIBLI)
    private val series = AlternateSourceTarget("Breaking Bad", true, 1396, 2008, 1, 2, AlternateSourceProvider.ABIBLI)
    private class Fixture(val target: AlternateSourceTarget) {
        val requests = mutableListOf<AlternateSourceHttpRequest>()
        var page = detail(target)
        var search = "<a title='${target.title}' href='https://abibli.com/${if (target.series) "series" else "movie"}/fixture'>Title</a>"
        var grant = """{"playlist":"/hls/master.txt?session=fixture&client=fixture&exp=1900003600&sig=fixture","exp":1900003600}"""
        var manifest = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English\",URI=\"audio.txt\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO=\"a\"\nvideo.txt\n"
        val resolver = AlternateSourceResolver(fetch = { request ->
            requests += request
            val body = when {
                "/search?" in request.url -> search
                request.url.startsWith("https://abibli.com/") -> page
                request.method == "POST" -> grant
                else -> manifest
            }
            AlternateSourceHttpResponse(request.url, 200, body)
        })
    }
    @Test fun movieReturnsTheCompleteMasterAndAPublicPlaybackDescriptorWithoutOpeningABridge() = runBlocking {
        val f = Fixture(movie)
        val streams = f.resolver.resolve(movie)
        assertEquals(1, streams.size)
        assertEquals("ABC123", streams.single().abibli!!.videoId)
        assertEquals("ABC123", streams.single().sourceVariant)
        assertEquals(AlternatePlaybackRoute.NATIVE, streams.single().route)
        assertTrue(f.requests.all { it.url.startsWith("https://") })
        assertEquals(4, f.requests.size)
        assertEquals("ABC123", JSONObject(f.requests[2].jsonBody!!).getString("video"))
        assertTrue(streams.single().headers.isEmpty())
    }
    @Test fun seriesUsesRequestedSeasonAndEpisodeRatherThanFirstEpisode() = runBlocking {
        val f = Fixture(series)
        assertEquals("ABC123", f.resolver.resolve(series).single().abibli!!.videoId)
        assertTrue(f.requests[1].url.endsWith("?season=1"))
        f.resolver.invalidate(series)
        assertTrue(f.resolver.resolve(series.copy(episode = 1)).isEmpty())
    }
    @Test fun wrongNameYearEpisodeAndPlayerHostNeverRequestAGuestSession() = runBlocking {
        for (page in listOf(detail(movie.copy(title = "Iron Man 2")), detail(movie.copy(year = 2010)),
            detail(movie).replace("rov.funnyotter.xyz", "foreign.example.test"))) {
            val f = Fixture(movie).apply { this.page = page }
            assertTrue(f.resolver.resolve(movie).isEmpty())
            assertEquals(2, f.requests.size)
        }
        val f = Fixture(series).apply { page = detail(series.copy(season = 2)) }
        assertTrue(f.resolver.resolve(series).isEmpty())
        assertEquals(2, f.requests.size)
    }
    @Test fun equalNameRemakesWithoutYearAreNotArbitrarilySelected() = runBlocking {
        val f = Fixture(movie.copy(year = null))
        f.search += "<a title='Iron Man' href='https://abibli.com/movie/other'>Other</a>"
        // Distinct usable entries with the same name remain ambiguous.
        f.page = detail(movie) + detail(movie, id = "DEF456")
        assertTrue(f.resolver.resolve(movie.copy(year = null)).isEmpty())
    }
    @Test fun protectedOrMalformedPlaylistsAndForeignGuestHostsAreRejected() = runBlocking {
        for (grant in listOf("{}", """{"playlist":"https://foreign.example.test/master.txt","exp":1900003600}""")) {
            val f = Fixture(movie).apply { this.grant = grant }
            try { f.resolver.resolve(movie); fail("Invalid guest accepted") } catch (_: AlternateSourceException) { }
            assertEquals(3, f.requests.size)
        }
        val f = Fixture(movie).apply { manifest = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"license\"" }
        try { f.resolver.resolve(movie); fail("DRM accepted") }
        catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.PROTECTED_MEDIA, failure.kind) }
    }
    @Test fun titleMatchingDecodesPublishedUnicodeWithoutExecutingScripts() {
        val target = movie.copy(title = "A & B's Film")
        assertEquals("ABC123", parseAbibliVideo(detail(target), target))
        assertNull(parseAbibliVideo(detail(movie).replace("ABC123", "ABC123/script"), movie))
    }

    companion object {
        internal fun detail(target: AlternateSourceTarget, id: String = "ABC123"): String {
            val meta = JSONObject().put("@type", if (target.series) "TVSeries" else "Movie")
                .put("name", target.title).put("dateCreated", target.year ?: 2008)
            val info = JSONObject().put("type", if (target.series) "episode" else "movie").put("title", target.title)
                .put("subtitle", if (target.series) "S${target.season.toString().padStart(2,'0')}E${target.episode.toString().padStart(2,'0')}" else "2008")
            val encoded = info.toString().replace("\\", "\\\\").replace("\"", "\\u0022").replace("'", "\\u0027").replace("&", "\\u0026")
            return """<script type="application/ld+json">$meta</script><button @click.stop="${'$'}store.streamPlayer.play('https:\/\/rov.funnyotter.xyz\/#$id', JSON.parse('$encoded'))">Play</button>"""
        }
    }
}
