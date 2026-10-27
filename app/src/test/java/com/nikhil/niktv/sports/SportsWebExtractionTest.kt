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

    @Test fun configuredAuthenticationStaysOnItsOriginAndTransportHeadersAreNotReused() {
        val input = SportsStream("https://page.example.test/", "Fixture", headers = mapOf(
            "User-Agent" to "Custom/2", "Referer" to "https://provider.example.test/", "Origin" to "https://provider.example.test",
            "Authorization" to "fixture-private", "Cookie" to "session=fixture", "Host" to "wrong.example.test", "Range" to "bytes=0-", "x-header" to "private"))
        val headers = sportsWebHeaders(input, "https://page.example.test/child")
        assertEquals(setOf("User-Agent", "Referer", "Origin", "Authorization", "Cookie", "x-header"), headers.keys)
        assertEquals("Custom/2", headers["User-Agent"])
        assertEquals("https://provider.example.test/", headers["Referer"])
        assertEquals("fixture-private", headers["Authorization"])
        val crossOrigin = sportsWebHeaders(input, "https://page.example.test/child", "https://cdn.example.test/master.m3u8")
        assertEquals(setOf("User-Agent", "Referer", "Origin"), crossOrigin.keys)
        assertFalse(sportsWebHeaders(input, "https://page.example.test/", "http://page.example.test/movie.mp4").containsKey("Authorization"))
        assertFalse(sportsWebHeaders(input, "https://page.example.test/", "https://page.example.test:8443/movie.mp4").containsKey("Cookie"))
        assertEquals("https://page.example.test/child", sportsWebHeaders(input.copy(headers = emptyMap()), "https://page.example.test/child")["Referer"])
    }

    @Test fun interceptedAuthenticationBelongsToTheActualMediaTarget() {
        val input = SportsStream("https://page.example.test/", "Fixture", headers = mapOf(
            "Authorization" to "page-only", "Cookie" to "page-session=fixture", "X-Token" to "page-token"))
        val headers = sportsWebHeaders(input, "https://page.example.test/child", "https://cdn.example.test/master.m3u8", mapOf(
            "authorization" to "media-only", "cookie" to "media-session=fixture", "x-token" to "media-token",
            "Referer" to "https://page.example.test/child", "Origin" to "https://page.example.test",
            "Host" to "wrong.test", "Range" to "bytes=0-", "Connection" to "keep-alive", "Accept-Encoding" to "gzip"))
        assertEquals("media-only", headers["Authorization"])
        assertEquals("media-session=fixture", headers["Cookie"])
        assertEquals("media-token", headers["x-token"])
        assertEquals(setOf("User-Agent", "Referer", "Origin", "Authorization", "Cookie", "x-token"), headers.keys)
    }

    @Test fun extractedHeadersRejectInjectionAndDuplicateNamesIgnoringCase() {
        val input = SportsStream("https://page.example.test/", "Fixture", headers = mapOf(
            "X-Token" to "old", "Cookie" to "bad\r\nvalue", "Origin" to "bad\u0000value"))
        val headers = sportsWebHeaders(input, input.link, input.link, mapOf(
            "x-token" to "new", "User-Agent" to "bad\nagent", "x-bad\rname" to "value"))
        assertEquals("new", headers["x-token"])
        assertEquals(1, headers.keys.count { it.equals("X-Token", true) })
        assertEquals(SPORTS_USER_AGENT, headers["User-Agent"])
        assertFalse(headers.containsKey("Cookie"))
        assertFalse(headers.containsKey("Origin"))
        assertFalse(headers.keys.any { it.contains('\r') })
    }

    @Test fun resourceCaptureRecognizesManifestsWithoutMistakingSegmentsOrPagesForStreams() {
        assertTrue(sportsMediaRequest("https://cdn.example.test/video.M3U8?token=abc"))
        assertTrue(sportsMediaRequest("https://cdn.example.test/video.mpd"))
        assertFalse(sportsMediaRequest("https://cdn.example.test/segment01.ts"))
        assertFalse(sportsMediaRequest("https://page.example.test/player?next=video.m3u8"))
    }
}
