package com.nikhil.niktv.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SportsWebExtractionTest {
    @Test fun decodesWebViewResultAndRejectsNonWebOrCredentialedAddresses() {
        val doc = """{"media":["https://cdn.example.test/master.m3u8?token=a+b","file:///private","javascript:alert(1)","https://user:pass@cdn.example.test/private.m3u8"],"frames":["https://embed.example.test/player"]}"""
        val result = sportsWebCandidates(Json.encodeToString(doc))
        assertEquals(listOf("https://cdn.example.test/master.m3u8?token=a+b"), result.media)
        assertEquals(listOf("https://embed.example.test/player"), result.frames)
        assertTrue(sportsWebCandidates("null").media.isEmpty())
        assertTrue(sportsWebCandidates("\"not JSON\"").media.isEmpty())
    }

    @Test fun onlyPublicPlaybackHeadersArePassedToExtractedMedia() {
        val input = SportsStream("https://page.example.test/", "Fixture", headers = mapOf(
            "User-Agent" to "Custom/2", "Referer" to "https://provider.example.test/", "Origin" to "https://provider.example.test",
            "Authorization" to "fixture-private", "Cookie" to "session=fixture", "Host" to "wrong.example.test", "Range" to "bytes=0-", "x-header" to "private"))
        val headers = sportsWebHeaders(input, "https://page.example.test/child")
        assertEquals(setOf("User-Agent", "Referer", "Origin"), headers.keys)
        assertEquals("Custom/2", headers["User-Agent"])
        assertEquals("https://provider.example.test/", headers["Referer"])
        assertEquals("https://page.example.test/child", sportsWebHeaders(input.copy(headers = emptyMap()), "https://page.example.test/child")["Referer"])
    }

    @Test fun resourceCaptureRecognizesManifestsWithoutMistakingSegmentsOrPagesForStreams() {
        assertTrue(sportsMediaRequest("https://cdn.example.test/video.M3U8?token=abc"))
        assertTrue(sportsMediaRequest("https://cdn.example.test/video.mpd"))
        assertFalse(sportsMediaRequest("https://cdn.example.test/segment01.ts"))
        assertFalse(sportsMediaRequest("https://page.example.test/player?next=video.m3u8"))
    }
}
