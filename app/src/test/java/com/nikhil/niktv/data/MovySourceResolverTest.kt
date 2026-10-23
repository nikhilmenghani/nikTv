package com.nikhil.niktv.data

import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
class MovySourceResolverTest {
    private val movie = AlternateSourceTarget("The Matrix", false, 603, 1999)

    private class Fixture {
        val requests = mutableListOf<AlternateSourceHttpRequest>()
        var seed = "fixture-guest-seed"
        var ttl = 30_000L
        var clock: () -> Long = { 1_000L }
        var primaryCode = 200
        var secondaryCode = 200
        var redirectedApi = false
        var payload = JSONObject().put("sources", JSONArray().put(JSONObject().put("quality", "1080p").put("url", "https://media.example.test/video.m3u8")))
        var body = "#EXTM3U\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n"
        val resolver get() = MovySourceResolver(read = { request, phase ->
            requests += request
            val url = request.url.toHttpUrl()
            val code = if (url.encodedPath == "/miami/sources") primaryCode else if (url.encodedPath == "/boise/sources") secondaryCode else 200
            if (code !in 200..299) throw AlternateSourceException("Unavailable.", if (code == 403) AlternateSourceFailure.AUTHORIZATION else AlternateSourceFailure.SOURCE, phase, code)
            val response = when {
                url.encodedPath == "/seed" -> JSONObject().put("seed", seed).put("ttlMs", ttl).toString()
                url.host == "api.wecollege.net" -> encode(payload.toString(), seed, url.queryParameter("tmdbId")!!.toInt())
                else -> body
            }
            AlternateSourceHttpResponse(if (redirectedApi && url.encodedPath.endsWith("/sources")) "https://unrelated.example.test/result" else request.url, code, response)
        }, now = clock)
    }

    @Test fun publicCodecKnownVectorAndWrongSeedAreDeterministic() {
        // Fixed independently generated vector for the website's public version-2 codec.
        val vector = "0ms73FpA3KEY4YGksTEZMPP0R1tMVBJy7GQXSBIqrw"
        assertEquals("""{"sources":[],"tmdbId":603}""", decodeMovyPayload(vector, "fixture-guest-seed", 603))
        for ((seed, id) in listOf("wrong-seed" to 603, "fixture-guest-seed" to 604)) {
            try { decodeMovyPayload(vector, seed, id); fail("Incorrect seed or media identity accepted") }
            catch (failure: AlternateSourceException) { assertFalse(failure.message.orEmpty().contains(seed)) }
        }
        for (payload in listOf("", "<html>challenge</html>", "{}", "a".repeat(1024 * 1024 + 1))) {
            try { decodeMovyPayload(payload, "fixture-guest-seed", 603); fail("Unsupported payload accepted") }
            catch (_: AlternateSourceException) { }
        }
    }

    @Test fun guestFlowReturnsNativeHlsWithoutLeakingSeedOrApiHeadersToMedia() = runBlocking {
        val fixture = Fixture()
        val stream = fixture.resolver.resolve(movie).single()
        assertEquals("1080p · Movy Miami", stream.label)
        assertEquals(1080, stream.height)
        assertEquals("hls", stream.format)
        assertEquals(3, fixture.requests.size)
        assertEquals("603", fixture.requests.first().url.toHttpUrl().queryParameter("mediaId"))
        val api = fixture.requests[1].url.toHttpUrl()
        assertEquals("The%20Matrix", api.queryParameter("title"))
        assertEquals("movie", api.queryParameter("mediaType"))
        assertEquals("1999", api.queryParameter("year"))
        assertEquals("2", api.queryParameter("enc"))
        assertEquals(setOf("User-Agent", "Referer"), fixture.requests.last().headers.keys)
        assertEquals(fixture.requests.last().headers, stream.headers)
        assertFalse(stream.url.contains(fixture.seed))
        assertFalse(stream.headers.values.any { it.contains(fixture.seed) })
    }

    @Test fun tvRequestUsesExactEpisodeAndRejectsReturnedMismatchBeforeMedia() = runBlocking {
        val target = movie.copy(title = "Friends", series = true, tmdbId = 1668, season = 3, episode = 25)
        val fixture = Fixture()
        assertEquals(1, fixture.resolver.resolve(target).size)
        val api = fixture.requests[1].url.toHttpUrl()
        assertEquals("1668", api.queryParameter("tmdbId"))
        assertEquals("3", api.queryParameter("seasonId"))
        assertEquals("25", api.queryParameter("episodeId"))
        for ((field, value) in listOf("tmdbId" to 1669, "seasonId" to 4, "episode" to 24)) {
            val mismatch = Fixture().apply { payload.put(field, value) }
            try { mismatch.resolver.resolve(target); fail("Mismatched episode accepted") }
            catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, failure.kind) }
            assertEquals(2, mismatch.requests.size)
        }
    }

    @Test fun missingIdentityAndInvalidEpisodeNeverStartSourceLookup() = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.resolver.resolve(movie.copy(tmdbId = null)).isEmpty())
        for (target in listOf(movie.copy(series = true), movie.copy(series = true, season = -1, episode = 1), movie.copy(series = true, season = 1, episode = 0))) {
            try { fixture.resolver.resolve(target); fail("Invalid episode accepted") }
            catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.IDENTITY, failure.kind) }
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun unavailableServerUsesOnlyOneAdvertisedFallback() = runBlocking {
        val fixture = Fixture().apply { primaryCode = 500 }
        assertEquals("1080p · Movy Boise", fixture.resolver.resolve(movie).single().label)
        assertEquals(listOf("/seed", "/miami/sources", "/boise/sources", "/video.m3u8"), fixture.requests.map { it.url.toHttpUrl().encodedPath })
    }

    @Test fun authorizationIsTerminalAndApiRedirectIsRejected() = runBlocking {
        for (fixture in listOf(Fixture().apply { primaryCode = 403 }, Fixture().apply { redirectedApi = true })) {
            try { fixture.resolver.resolve(movie); fail("Unauthorized session accepted") }
            catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.AUTHORIZATION, failure.kind) }
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun shortLivedGuestSessionIsNotUsedAfterExpiry() = runBlocking {
        var reads = 0
        val fixture = Fixture().apply { clock = { if (reads++ == 0) 1_000 else 31_001 } }
        try { fixture.resolver.resolve(movie); fail("Expired session used") }
        catch (failure: AlternateSourceException) { assertTrue(failure.message.orEmpty().contains("expired")) }
        assertEquals(1, fixture.requests.size)
    }

    @Test fun sourceFanOutIsBoundedAndUnsupportedResponsesSuggestAnotherSource() = runBlocking {
        val fixture = Fixture().apply {
            body = "<html>ordinary unsupported player page</html>"
            payload.put("sources", JSONArray().apply { repeat(30) { put(JSONObject().put("url", "https://media.example.test/$it.m3u8")) } })
        }
        try { fixture.resolver.resolve(movie); fail("HTML accepted as native media") }
        catch (failure: AlternateSourceException) { assertTrue(failure.message.orEmpty().contains("choose another source")) }
        assertEquals(6, fixture.requests.size) // one seed, one server, four bounded media probes
        assertFalse(fixture.requests.any { it.url.toHttpUrl().encodedPath == "/boise/sources" })
    }

    @Test fun masterPreservesExternalAudioAndSubtitlesAndDashRejectsProtectedMedia() = runBlocking {
        val fixture = Fixture().apply {
            body = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="en",NAME="English",URI="audio.m3u8"
#EXT-X-STREAM-INF:RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="en"
video.m3u8
"""
        }
        assertTrue(fixture.resolver.resolve(movie).single().label.startsWith("Auto"))
        fixture.body = """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period/></MPD>"""
        assertEquals("dash", fixture.resolver.resolve(movie).single().format)
        fixture.body = """<MPD><Period><ContentProtection schemeIdUri="urn:uuid:example"/></Period></MPD>"""
        try { fixture.resolver.resolve(movie); fail("Protected DASH accepted") }
        catch (failure: AlternateSourceException) { assertEquals(AlternateSourceFailure.PROTECTED_MEDIA, failure.kind) }
    }

    @Test fun cancellationIsNotConvertedIntoNoResultsOrFallback() = runBlocking {
        var requests = 0
        val resolver = MovySourceResolver(read = { _, _ -> requests++; throw CancellationException("cancelled") })
        try { resolver.resolve(movie); fail() } catch (_: CancellationException) { }
        assertEquals(1, requests)
    }

    companion object {
        /** Fixture encoder uses the public protocol; no real seed or signed media link is saved. */
        private fun encode(payload: String, seed: String, mediaId: Int): String {
            fun mix(n: Int): Int {
                var value = (n xor (n ushr 16)) * 0x85ebca6b.toInt()
                value = (value xor (value ushr 13)) * 0xc2b2ae35.toInt()
                return value xor (value ushr 16)
            }
            fun slot(n: Int) = ((n.toLong() and 0xffffffffL) % 61).toInt()
            val golden = 0x9e3779b9.toInt()
            var hash = 0x811c9dc5.toInt()
            seed.forEach { hash = (hash xor it.code) * 0x1000193 }
            var state = mix(mix(hash) xor mix(mediaId xor golden))
            val slots = mutableMapOf<Int, Int>()
            repeat(8) { index -> val at = slot(state); state = Integer.rotateLeft(state + golden, 7 + index); slots[at] = state xor mix(state); state = mix(state + at) }
            var acc = mix(0xa5a5a5a5.toInt() xor state)
            val bytes = ("mvm1" + payload).toByteArray(Charsets.UTF_8)
            for (offset in bytes.indices step 4) {
                val at = slot(acc)
                val value = slots.getOrDefault(at, 0) xor (golden * (offset / 4 + 1))
                val combined = (acc xor value) or if (slots.containsKey(at)) acc and value else 0
                acc = mix((Integer.rotateLeft(combined + acc, at) xor Integer.rotateLeft(acc, at * 7)) + golden)
                slots[at] = acc
                repeat(minOf(4, bytes.size - offset)) { j -> bytes[offset + j] = (bytes[offset + j].toInt() xor (acc ushr (8 * j))).toByte() }
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
