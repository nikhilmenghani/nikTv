package com.nikhil.niktv.sports

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SportsWebDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun resolvesPlayerApisMetadataEncodedUrlsAndNestedFrames() = runBlocking {
        val sources = mapOf(
            "/jw" to "<script>window.jwplayer=function(){return {getPlaylist:function(){return [{file:'https://media.example.test/' + ['jw','m3u8'].join('.')}];}}};</script>",
            "/videojs" to "<script>window.videojs={getPlayers:function(){return {one:{currentSrc:function(){return 'https://media.example.test/' + ['videojs','m3u8'].join('.');}}};}};</script>",
            "/shaka" to "<script>window.shakaPlayer={getAssetUri:function(){return 'https://media.example.test/' + ['shaka','mpd'].join('.');}};</script>",
            "/meta" to "<meta property='og:video:url' content='https://media.example.test/meta.m3u8?x=1&amp;y=2'>",
            "/json" to "<script type='application/ld+json'>{\"video\":{\"contentUrl\":\"https://media.example.test/json.m3u8\"}}</script>",
            "/data" to "<div data-hls='https://media.example.test/data.m3u8'></div>",
            "/encoded" to "<script>var encoded='aHR0cHM6Ly9tZWRpYS5leGFtcGxlLnRlc3QvZW5jb2RlZC5tM3U4';</script>",
        )
        LocalSportsPages(sources).use { server ->
            val resolver = SportsWebResolver(context)
            for (path in sources.keys) {
                val result = resolver.resolve(SportsStream(server.base + path, "Fixture", 4))
                val expected = when (path) { "/shaka" -> "shaka.mpd"; "/meta" -> "meta.m3u8?x=1&y=2"; else -> path.removePrefix("/") + ".m3u8" }
                assertEquals("Fixture $path", "https://media.example.test/$expected", result.link)
                assertEquals(server.base + path, result.headers["Referer"])
                assertFalse(result.headers.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) })
            }
            LocalSportsPages(mapOf("/nested" to "<iframe src='${server.base}/jw'></iframe>")).use { parent ->
                val nested = resolver.resolve(SportsStream(parent.base + "/nested", "Nested", 4))
                assertEquals("https://media.example.test/jw.m3u8", nested.link)
                assertEquals(server.base + "/jw", nested.headers["Referer"])
            }
        }
    }

    @Test fun cancellationDisposesTheWebViewAndNextResolutionStillWorks() = runBlocking {
        LocalSportsPages(mapOf("/empty" to "<p>No media</p>", "/player" to "<div data-hls='https://media.example.test/retry.m3u8'></div>")).use { server ->
            val resolver = SportsWebResolver(context)
            try { withTimeout(750) { resolver.resolve(SportsStream(server.base + "/empty", "Empty", 4)) }; fail("Expected timeout") }
            catch (_: TimeoutCancellationException) { }
            assertEquals("https://media.example.test/retry.m3u8", resolver.resolve(SportsStream(server.base + "/player", "Retry", 4)).link)
        }
    }
}
