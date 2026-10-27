package com.nikhil.niktv.sports

import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SportsIntegrationTest {
    @Test fun streamQueuePreservesDuplicatesWithoutExposingUrlsAndWraps() {
        val source = SportsStream("https://media.example.test/live?token=private-fixture", "English")
        val queue = sportsStreamQueue(listOf(source, source, source.copy(title = "")))
        assertEquals(3, queue.map { it.id }.distinct().size)
        assertTrue(queue.none { it.id.contains("private-fixture") })
        assertEquals("Source 3", queue[2].title)
        assertEquals(1, sportsAdjacentStream(0, 1, 3))
        assertEquals(2, sportsAdjacentStream(0, -1, 3))
        assertEquals(0, sportsAdjacentStream(2, 1, 3))
        assertNull(sportsAdjacentStream(0, 1, 1))
        assertNull(sportsAdjacentStream(-1, 1, 3))
    }
    @Test fun sharedPlayerRetainsHeadersAndLiveIdentityWithoutHistoryOrResume() {
        val stream = SportsStream("https://media.example.test/live.m3u8?token=fixture", "English",
            headers = mapOf("Referer" to "https://sports.example.test/", "Cookie" to "fixture=1"))
        val media = stream.asPlayingMedia("India vs New Zealand")
        assertEquals(com.nikhil.niktv.model.CatalogType.LIVE_TV, media.catalogType)
        assertEquals(stream.link, media.url)
        assertEquals("fixture=1", media.streamRequestHeaders["Cookie"])
        assertEquals(SPORTS_USER_AGENT, media.streamRequestHeaders["User-Agent"])
        assertEquals("HLS", media.playbackFormat)
        assertTrue(media.allowLiveWindowSeeking)
        assertEquals(0L, media.resumePositionMillis)
        assertFalse(media.progressKey.contains("token="))
        assertEquals(media.progressKey, stream.asPlayingMedia("Renamed event").progressKey)
        assertNull(media.streamDrm)
    }

    @Test fun sharedPlayerPreservesRemoteAndLocalDrmWithoutLoggingLicenses() {
        val widevine = SportsStream("https://media.example.test/live.mpd", "English", drmType = "widevine",
            drmLicense = "https://license.example.test/issue?token=fixture")
        val media = widevine.asPlayingMedia("Match")
        assertEquals("DASH", media.playbackFormat)
        assertEquals(androidx.media3.common.C.WIDEVINE_UUID, media.streamDrm!!.scheme)
        assertEquals(widevine.drmLicense, media.streamDrm!!.licenseUri)
        assertFalse(media.streamDrm.toString().contains("fixture"))
        val local = widevine.copy(drmType = "clearkey", drmLicense = "00".repeat(16) + ":" + "11".repeat(16))
            .asPlayingMedia("Match").streamDrm!!
        assertNull(local.licenseUri)
        assertNotNull(local.localResponse)
    }
    private val events = """[{"id":"1","title":"Team A vs Team B","slug":"team-a-v-team-b","publish":"1","eventInfo":{"teamA":"Team A","teamB":"Team B","eventCat":"Cricket","eventType":"Test","startTime":"2026/10/27 08:30:00 +0000","endTime":"2026/10/27 17:30:00 +0000"},"formats":[{"title":"Source one"}]}]"""
    private val config = """{"parameters":{"cric_api2":{"defaultValue":{"value":"https://sports.example.test/"}}}}"""

    @Test fun decodesCapturedConfigurationResponse() {
        val captured = "x5zP0EQZkobSJcRCQg8LFP8+u22YAugW6iQukO6qtWdst8o08kd0StKccDKbeQQXsd8j2oARxErGzFkrvZz184VensDkoSw/e8xn3onXeGzAl1UfGuBZLfRrH1NUL/OtPVHLPPXlz8rQpqW0g6EeXow4I6rnH4OInnLQMhDLf6BTT8b2WU71YvqnI3/PDH6qdScQn1uKke40X/lZ0wgpWkV8qlS0AiA6iVEbO66r5OQ="
        assertEquals("https://cftkhffmgf147.top/", SportsCatalogue.baseUrl(captured))
    }

    @Test fun catalogueDecryptsBothLegacyEnvelopesAndUsesUtcSchedule() {
        for ((key, iv) in listOf("3hHzxYAgSGI8/ham" to "UqCvkvjecEIz842V", "MqeYGCT4AYKoHKrT" to "oHKrTQuPx8zl8oJ+")) {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
            val event = SportsCatalogue.events(Base64.getEncoder().encodeToString(cipher.doFinal(events.toByteArray()))).single()
            assertEquals("team-a-v-team-b", event.slug)
            assertEquals(SportsStatus.UPCOMING, event.status(Instant.parse("2026-10-27T08:29:59Z")))
            assertEquals(SportsStatus.LIVE, event.status(Instant.parse("2026-10-27T08:30:00Z")))
            assertEquals(SportsStatus.LIVE, event.status(Instant.parse("2026-10-27T17:30:00Z")))
            assertEquals(SportsStatus.FINISHED, event.status(Instant.parse("2026-10-27T17:30:01Z")))
        }
    }

    @Test fun unknownScheduleIsNotLabelledLiveAndInvalidSlugIsDropped() {
        assertEquals(SportsStatus.UNKNOWN, SportsCatalogue.events(events.replace("2026/10/27 08:30:00 +0000", "bad")).single().status(Instant.now()))
        assertTrue(SportsCatalogue.events(events.replace("team-a-v-team-b", "../private")).isEmpty())
    }

    @Test fun configurationRotationAndChannelChoiceAreEndToEnd() = runBlocking {
        val requests = mutableListOf<String>()
        var base = "https://sports.example.test/"
        val repository = SportsRepository(SportsTransport { url, _ ->
            requests += url
            val body = when {
                url.endsWith("/config.txt") -> config.replace("https://sports.example.test/", base)
                url.endsWith("/categories/live-events.txt") -> events
                url.endsWith("/channels/team-a-v-team-b.txt") -> """{"streamUrls":[{"title":"One","type":"0","link":"https://media.example.test/live.m3u8|referer=https://sports.example.test/"}]}"""
                else -> error("Unexpected request")
            }
            SportsResponse(url, body)
        })
        val event = repository.events().single()
        assertEquals(2, requests.size)
        assertEquals(event, repository.events().single())
        assertEquals(2, requests.size)
        val stream = repository.streams(event.slug).single()
        val resolved = SportsStreamResolver(repository.transport) { error("Unexpected WebView") }.resolve(stream)
        assertEquals("https://media.example.test/live.m3u8", resolved.link)
        assertEquals("https://sports.example.test/", resolved.headers["Referer"])
        base = "https://rotated.example.test/"
        repository.events(refresh = true)
        assertTrue(requests.last().startsWith(base))
    }

    @Test fun resolversHandleApiUrlsWebMediaAndServerLookup() = runBlocking {
        val transport = object : SportsTransport {
            override suspend fun post(url: String, headers: Map<String, String>, form: Map<String, String>): SportsResponse {
                assertEquals("rapidstreamz", form["referer"])
                assertEquals("rapidstreamz", headers["referer"])
                return get(url, headers)
            }
            override suspend fun get(url: String, headers: Map<String, String>) = SportsResponse(url, when {
            "server_lookup" in url -> """{"server_key":"edge1"}"""
            "playback" in url -> """{"playback_url":"https://media.example.test/manifest.m3u8"}"""
            "embed" in url -> """{"hlsManifestUrl":"https:\/\/media.example.test\/manifest.m3u8?x=1\u0026y=2"}"""
            else -> "?token=fixture"
        }) }
        val resolver = SportsStreamResolver(transport) { it.copy(link = "https://media.example.test/web.m3u8", headers = mapOf("Cookie" to "fixture")) }
        assertEquals("https://media.example.test/live.m3u8?token=fixture", resolver.resolve(SportsStream("https://media.example.test/live.m3u8", "Two", 2, "https://api.example.test/token")).link)
        assertTrue(resolver.resolve(SportsStream("https://page.example.test/", "Web", 4)).headers.containsKey("Cookie"))
        assertEquals("https://media.example.test/manifest.m3u8", resolver.resolve(SportsStream("channel/42", "API", 6, "https://api.example.test/playback/%s")).link)
        assertEquals("https://media.example.test/manifest.m3u8?x=1&y=2", resolver.resolve(SportsStream("https://page.example.test/embed", "Embed", 8)).link)
        val nine = resolver.resolve(SportsStream("42", "Lookup", 9))
        assertTrue(nine.link.contains("edge1new.newkso.ru/edge1/premium42/mono.m3u8"))
        assertEquals("https://newembedplay.xyz", nine.headers["Origin"])
    }

    @Test fun headerDecodingPreservesSignedValues() {
        val stream = splitSportsHeaders(SportsStream("https://cdn.example.test/live.m3u8?token=a+b|User-Agent=Player%2F1&cookie=key=a+b&Referer=https%3A%2F%2Fexample.test%2F", "One"))
        assertTrue(stream.link.endsWith("token=a+b"))
        assertEquals("key=a+b", stream.headers["Cookie"])
        assertEquals("Player/1", stream.headers["User-Agent"])
        assertEquals("https://example.test/", stream.headers["Referer"])
    }

    @Test fun protectedTokenSourcesDoNotTransmitEmbeddedCredentials() = runBlocking {
        val resolver = SportsStreamResolver(SportsTransport { _, _ -> error("No request should be made") }) { error("No WebView should be opened") }
        for (type in listOf(1, 3)) {
            try { resolver.resolve(SportsStream("https://media.example.test/live.m3u8", "Protected", type, "https://token.example.test/")); fail("Authorization should be required") }
            catch (expected: SportsException) { assertTrue(expected.message.orEmpty().contains("authorization")) }
        }
    }

    @Test fun clearKeyUsesFeedValuesAndDoesNotExposeHexKeysAsUrls() {
        val response = Json.parseToJsonElement(sportsClearKeyResponse("00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100").toString(Charsets.UTF_8)).jsonObject
        val key = response["keys"]!!.jsonArray.single().jsonObject
        assertEquals("oct", key.text("kty"))
        assertEquals("ABEiM0RVZneImaq7zN3u_w", key.text("kid"))
    }
}
