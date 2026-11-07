package com.nikhil.niktv.data

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class Hdhub4uSourceResolverTest {
    private val movie = AlternateSourceTarget("Fixture Movie", false, 42, 2026, provider = AlternateSourceProvider.HDHUB4U)
    private val series = AlternateSourceTarget("Fixture Show", true, 43, 2022, 2, 2, AlternateSourceProvider.HDHUB4U)
    private val playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\nvideo.m3u8\n"

    private inner class Fixture {
        val requests = java.util.Collections.synchronizedList(mutableListOf<AlternateSourceHttpRequest>())
        var domain = "https://new.hdhub4u.test"
        var titles = listOf("Fixture Movie (2026) WEB-DL [Hindi & English] 1080p | Full Movie")
        var detail = """<article class="entry-content"><h1><span class="material-text">Fixture Movie (2026) | Full Movie</span></h1>
            <a href="https://hdstream4u.example/file/abc123">Watch 1080p</a></article>"""
        val pages = mutableMapOf(
            "hdstream4u.example/v/abc123" to """<script>const player={file:"https://media.example.test/master.m3u8"};</script>""",
            "media.example.test/master.m3u8" to playlist)
        val contentTypes = mutableMapOf<String, String>()
        val resolver = AlternateSourceResolver(fetch = { request ->
            requests += request
            val url = request.url.toHttpUrl()
            val key = url.host + url.encodedPath
            val body = when {
                request.url == Hdhub4uSourceResolver.DOMAINS -> JSONObject().put("HDHUB4u", domain).toString()
                url.host == "search.pingora.fyi" -> JSONObject().put("found", titles.size).put("hits", JSONArray(titles.mapIndexed { i, title ->
                    JSONObject().put("document", JSONObject().put("post_title", title)
                        .put("permalink", "https://retired.hdhub4u.test/title-$i/"))
                })).toString()
                url.host == domain.toHttpUrl().host -> detail
                else -> pages[key] ?: error("Unexpected fixture request: $key")
            }
            AlternateSourceHttpResponse(request.url, if (request.headers.containsKey("Range")) 206 else 200, body,
                contentTypes[key]?.let { mapOf("content-type" to it) }.orEmpty())
        })
    }

    @Test fun movieUsesCurrentDomainAndPublicPlayerWithoutSendingLandingPageToPlayback() = runBlocking {
        val f = Fixture()
        val streams = f.resolver.resolve(movie)
        assertEquals(listOf(null, 1080), streams.map { it.height })
        assertTrue(streams.all { it.route == AlternatePlaybackRoute.NATIVE && it.url.startsWith("https://media.example.test/") })
        assertTrue("Every HDStream HLS quality uses the compatible player path", streams.all { !it.vlcCompatible })
        assertEquals("https://hdstream4u.example/", streams.first().headers["Referer"])
        assertTrue(f.requests.any { it.url == "${f.domain}/title-0/" })
        assertFalse(f.requests.any { it.url.contains("retired.hdhub4u") || it.url.contains("/file/abc123") })
        assertTrue(f.requests.all { it.method == "GET" && it.headers.keys.none { name -> name.equals("Cookie", true) || name.equals("Authorization", true) } })
    }

    @Test fun directHlsFromOtherMirrorsKeepsVlcCompatibility() = runBlocking {
        val f = Fixture().apply {
            detail = detail.replace("https://hdstream4u.example/file/abc123", "https://media.example.test/master.m3u8")
        }
        val streams = f.resolver.resolve(movie)
        assertFalse(streams.isEmpty())
        assertTrue(streams.all { it.vlcCompatible })
    }

    @Test fun fuzzyTitlesRemakesAndWrongMediaTypesAreNeverOpened() = runBlocking {
        val f = Fixture().apply { titles = listOf("Almost Fixture Movie (2026)", "Fixture Movie (1998)", "Fixture Movie (Season 1)") }
        assertTrue(f.resolver.resolve(movie).isEmpty())
        assertEquals(2, f.requests.size)
    }

    @Test fun detailTitleMismatchCannotReachAnyMediaHost() = runBlocking {
        val f = Fixture().apply { detail = detail.replace("Fixture Movie (2026)", "Different Film (2026)") }
        try { f.resolver.resolve(movie); fail("Expected identity rejection") }
        catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, failure.kind) }
        assertEquals(3, f.requests.size)
    }

    @Test fun unknownReleaseYearCannotSelectAnArbitraryRemake() = runBlocking {
        val f = Fixture().apply { titles = listOf("Fixture Movie (1998) Full Movie", "Fixture Movie (2026) Full Movie") }
        try { f.resolver.resolve(movie.copy(year = null)); fail("Expected ambiguous identity") }
        catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, failure.kind) }
        assertEquals(2, f.requests.size)
    }

    private fun Fixture.episodePage() {
        titles = listOf("Fixture Show (Season 2) (2024) | Full Series")
        detail = """<article class="entry-content"><h1>Fixture Show (Season 2) (2024) | Full Series</h1>
            <h3>Episode 1</h3><a href="https://hdstream4u.example/file/wrong1">Watch 1080p</a>
            <h3>Episode 2</h3><a href="https://hdstream4u.example/file/abc123">Watch 1080p</a>
            <a href="https://archive.example.test/season.zip">Complete season</a>
            <h3>Episode 20</h3><a href="https://hdstream4u.example/file/wrong20">Watch 1080p</a></article>"""
    }

    @Test fun seriesSelectsExactEpisodeAndKeepsTheSeasonReleaseYearSeparateFromFirstAirYear() = runBlocking {
        val f = Fixture().apply { episodePage() }
        assertFalse(f.resolver.resolve(series).isEmpty())
        assertFalse(f.requests.any { it.url.contains("wrong") || it.url.endsWith(".zip") })
        assertTrue(f.resolver.resolve(series.copy(season = 3)).isEmpty())
    }

    @Test fun missingEpisodeAndSeasonPacksNeverFallBackToEpisodeOne() = runBlocking {
        val f = Fixture().apply { episodePage() }
        assertTrue(f.resolver.resolve(series.copy(episode = 4)).isEmpty())
        assertEquals(3, f.requests.size)
    }

    @Test fun multiSeasonPageRequiresTheRequestedSeasonHeading() = runBlocking {
        val f = Fixture().apply {
            titles = listOf("Fixture Show (Season 1-2) | Full Series")
            detail = """<article><h1>Fixture Show (Season 1-2) | Full Series</h1>
                <h2>Season 1</h2><h3>Episode 2</h3><a href="https://hdstream4u.example/file/wrong1">Watch</a>
                <h2>Season 2</h2><h3>Episode 2</h3><a href="https://hdstream4u.example/file/abc123">Watch</a></article>"""
        }
        assertFalse(f.resolver.resolve(series).isEmpty())
        assertFalse(f.requests.any { it.url.contains("wrong1") })
    }

    @Test fun publicPackedPlayerIsDecodedByStringSubstitutionWithoutExecutingScript() {
        val html = """<script>eval(function(p,a,c,k,e,d){return p}('0:{1:"https://media.example.test/master.m3u8"}',2,2,'player|file'.split('|'),0,{}))</script>"""
        assertTrue(unpackHdhubPlayer(html).contains("file:\"https://media.example.test/master.m3u8\""))
        assertEquals("plain", unpackHdhubPlayer("plain"))
        assertEquals("}('x',99,2,'x|y'.split('|'))", unpackHdhubPlayer("}('x',99,2,'x|y'.split('|'))"))
    }

    @Test fun literalPlayerMirrorsFollowDeclaredPreferenceAndAcceptTextManifests() = runBlocking {
        val f = Fixture().apply {
            pages["hdstream4u.example/v/abc123"] = """var links={"hls2":"https://media.example.test/master.m3u8","hls3":"https://media.example.test/master.txt","hls4":"/stream/master.m3u8"};"""
            pages["hdstream4u.example/stream/master.m3u8"] = "not a playlist"
            pages["media.example.test/master.txt"] = playlist
        }
        assertFalse(f.resolver.resolve(movie).isEmpty())
        assertEquals(listOf("/stream/master.m3u8", "/master.txt"), f.requests.takeLast(2).map { it.url.toHttpUrl().encodedPath })
        assertEquals("https://hdstream4u.example/", f.requests.last().headers["Referer"])
    }

    @Test fun publicRedirectEnvelopeIsDecodedAndMalformedPayloadsAreRejected() {
        fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
        val json = JSONObject().put("o", b64("https://hubcloud.example/title")).toString()
        val rotated = b64(json).map { c -> when (c) {
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        } }.joinToString("")
        val payload = b64(b64(rotated))
        assertEquals("https://hubcloud.example/title", decodeHdhubRedirect("s('o','$payload')"))
        assertNull(decodeHdhubRedirect("s('o','broken')"))
    }

    @Test fun metadataEnvelopeDoesNotMeanProtectedMediaIsAccepted() = runBlocking {
        val f = Fixture().apply { pages["media.example.test/master.m3u8"] = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"license\"" }
        try { f.resolver.resolve(movie); fail("Expected protected-media rejection") }
        catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.PROTECTED_MEDIA, failure.kind) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES"), IvParameterSpec("0123456789abcdef".toByteArray()))
        val encoded = cipher.doFinal("""{"source":"https://media.example.test/master.m3u8"}""".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("https://media.example.test/master.m3u8", hdhubPlayerMetadata(encoded)?.getString("source"))
        assertNull(hdhubPlayerMetadata("not-hex"))
    }

    @Test fun browserChallengesAndUnsafeDomainRotationAreNotBypassed() = runBlocking {
        val f = Fixture().apply { detail = "<title>Just a moment...</title> cf-chl-" }
        try { f.resolver.resolve(movie); fail("Expected browser challenge") }
        catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.CHALLENGE, failure.kind) }
        val unsafe = Fixture().apply { domain = "https://127.0.0.1/" }
        try { unsafe.resolver.resolve(movie); fail("Expected unsafe domain rejection") }
        catch (_: AlternateSourceException) { }
        assertEquals(1, unsafe.requests.size)
    }

    @Test fun hubCloudDownloadIsRangeProbedAndHtmlIsNeverReturnedAsVideo() = runBlocking {
        val f = Fixture().apply {
            detail = detail.replace("https://hdstream4u.example/file/abc123", "https://hubcloud.example/title")
            pages["hubcloud.example/title"] = """<a id="download" href="/hubcloud.php">Continue</a>"""
            pages["hubcloud.example/hubcloud.php"] = """<div class="card-header">Fixture Movie 1080p</div><a class="btn" href="https://files.example.test/video.mp4">FSL Download</a>"""
            pages["files.example.test/video.mp4"] = "0000ftypisom"
            contentTypes["files.example.test/video.mp4"] = "video/mp4"
        }
        val streams = f.resolver.resolve(movie)
        assertEquals(1, streams.size)
        assertEquals("MP4", streams.single().format)
        assertTrue("Direct video downloads keep their existing player support", streams.single().vlcCompatible)
        assertEquals("bytes=0-1023", f.requests.last().headers["Range"])
        f.resolver.invalidate(movie)
        f.pages["files.example.test/video.mp4"] = "<html>download landing page</html>"
        assertTrue(f.resolver.resolve(movie).isEmpty())
    }

    @Test fun repeatedPagesAreBoundedAndCancelingStopsBeforeAnyFurtherRequest() = runBlocking {
        val f = Fixture().apply {
            detail = detail.replace("https://hdstream4u.example/file/abc123", "https://hubcloud.example/loop")
            pages["hubcloud.example/loop"] = """<a id="download" href="/loop">Continue</a>"""
        }
        assertTrue(f.resolver.resolve(movie).isEmpty())
        assertEquals(4, f.requests.size)
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val resolver = AlternateSourceResolver(fetch = { calls++; started.complete(Unit); awaitCancellation() })
        val job = async { resolver.resolve(movie) }
        started.await(); job.cancelAndJoin()
        assertEquals(1, calls)
    }

    @Test fun successfulResultsCacheAndExplicitRetryObtainsFreshLinks() = runBlocking {
        val f = Fixture()
        val first = f.resolver.resolve(movie)
        assertEquals(first, f.resolver.resolve(movie))
        assertEquals(5, f.requests.size)
        f.resolver.invalidate(movie)
        assertEquals(first, f.resolver.resolve(movie))
        assertEquals(10, f.requests.size)
    }
}
