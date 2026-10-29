package com.nikhil.niktv.sports

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CricketHighlightsTest {
    private val full = "https://cdn.example.test/full.m3u8?session=fresh"
    private val promo = "https://cdn.example.test/promo.m3u8"
    private fun catalogue(url: String = full, country: String = "CA") = """
        {"country":"$country","videos":[{"id":"42","title":"Team A vs Team B",
        "durationStr":"24:27","videoType":"Highlights","videoUrl":"$promo","premiumVideoUrl":"$url","isLoginRequired":true}]}
    """.trimIndent().toByteArray()
    private fun media(duration: Double = 1467.78) = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:$duration,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()
    private fun rejected(block: () -> Unit): String = try { block(); fail("Expected unavailable response"); "" }
        catch (failure: SportsException) { failure.message.orEmpty() }

    @Test fun homeFullUrlIsSelectedWithoutLocalAccountState() {
        val item = CricketHighlightsCatalogue.parse(catalogue()).single()
        assertEquals(full, item.fullStream().link)
        assertFalse(item.toString().contains("session="))
    }

    @Test fun fullSelectionPreservesSessionAndUsesOnDemandPlayerIdentity() {
        val item = CricketHighlightsCatalogue.parse(catalogue()).single()
        val stream = item.fullStream()
        assertEquals(full, stream.link)
        assertFalse(stream.isLive)
        assertEquals("cricket:42", stream.contentId)
        val playing = stream.asPlayingMedia(item.title)
        assertEquals(com.nikhil.niktv.model.CatalogType.MOVIES, playing.catalogType)
        assertEquals("HLS", playing.playbackFormat)
        assertFalse(playing.allowLiveWindowSeeking)
        assertNull(playing.streamDrm)
        assertEquals(playing.progressKey, stream.copy(link = "$full-rotated").asPlayingMedia(item.title).progressKey)
    }

    @Test fun sameKalturaPreviewAssetWithDifferentParametersIsRejected() {
        val base = "https://cdnapisec.kaltura.com/p/1/playManifest/entryId/preview/format/applehttp"
        val item = CricketHighlight("1", "Match", fullUrl = "$base/protocol/https/a.m3u8?ks=full",
            previewUrl = "$base/a.m3u8?ks=preview")
        assertTrue(rejected { item.fullStream() }.contains("preview"))
    }

    @Test fun canadianRegionAndHttpsAreRequiredAndUnknownFieldsAreIgnored() {
        assertTrue(rejected { CricketHighlightsCatalogue.parse(catalogue(country = "US")) }.contains("Canadian"))
        val item = CricketHighlightsCatalogue.parse(catalogue(url = "http://cdn.example.test/full.m3u8")).single()
        assertTrue(rejected { item.fullStream() }.contains("full highlight"))
        assertFalse(cricketHttpsUrl("https://user:pass@cdn.example.test/full.m3u8"))
        val extended = catalogue().toString(Charsets.UTF_8).replace("\"country\"", "\"future\":{\"value\":1},\"country\"")
        assertEquals("42", CricketHighlightsCatalogue.parse(extended.toByteArray()).single().id)
    }

    @Test fun shortPromoCannotMasqueradeAsTwentyFourMinuteHighlight() {
        assertTrue(rejected { verifyCricketDuration("24:27", media(76.44).toString(Charsets.UTF_8)) }.contains("preview"))
        verifyCricketDuration("24:27", media().toString(Charsets.UTF_8))
        verifyCricketDuration("1:16", media(76.44).toString(Charsets.UTF_8))
        assertTrue(rejected { verifyCricketDuration("24:27", "<html>not a playlist</html>") }.contains("on-demand"))
    }

    @Test fun protobufReadsFullHomeUrlsAndSkipsAds() {
        val item = blob(1, "42".toByteArray()) + blob(2, "Team A vs Team B".toByteArray()) +
            blob(10, "24:27".toByteArray()) + blob(14, promo.toByteArray()) + blob(17, full.toByteArray()) +
            blob(18, "Highlights".toByteArray()) + number(27, 1) + blob(88, "future".toByteArray())
        val list = blob(1, blob(2, "advert".toByteArray())) + blob(1, blob(1, item))
        val root = blob(3, list) + blob(10, list)
        val raw = CricketHighlightsCatalogue.parse(root)
        assertEquals(1, raw.size)
        assertEquals(full, raw.single().fullStream().link)
        val envelope = """{"country":"CA","catalogueBase64":"${java.util.Base64.getEncoder().encodeToString(root)}"}"""
        assertEquals(full, CricketHighlightsCatalogue.parse(envelope.toByteArray()).single().fullStream().link)
    }

    @Test fun malformedProtobufAndEmptyOrHtmlResponsesFailWithoutCaching() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0x1a, 0x7f), ByteArray(20) { 0xff.toByte() }, "<html>error</html>".toByteArray()))
            assertTrue(rejected { CricketHighlightsCatalogue.parse(bytes) }.isNotEmpty())
    }

    @Test fun playbackFetchesFreshCatalogueAndDoesNotForwardProviderTokenToCdn() = runBlocking {
        var count = 0
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val repo = CricketHighlightsRepository(CricketHighlightsTransport { url, headers ->
            calls += url to headers
            if (url.startsWith("https://provider.example.test/")) catalogue(full + "-${++count}") else media()
        }, { CricketHighlightsConnection("https://provider.example.test/featured", "private-fixture") })
        repo.featured(); repo.featured()
        assertEquals(1, count)
        val first = repo.fullStream("42")
        val second = repo.fullStream("42")
        assertNotEquals(first.link, second.link)
        assertEquals(3, count)
        assertTrue(calls.filter { it.first.startsWith("https://provider.example.test/") }.all {
            it.first.endsWith("country=CA") && it.second["CB-Loc"] == "CA" && it.second["Authorization"] == "Bearer private-fixture"
        })
        assertTrue(calls.filter { it.first.startsWith("https://cdn.example.test/") }.all { "Authorization" !in it.second })
        assertTrue(calls.none { "/detail/" in it.first })
    }

    @Test fun missingFullUrlOnRefreshCannotReusePreviouslyPlayableUrl() = runBlocking {
        var hasFullUrl = true
        val repo = CricketHighlightsRepository(CricketHighlightsTransport { target, _ ->
            if (target.startsWith("https://provider.example.test/")) catalogue(url = if (hasFullUrl) full else "") else media()
        }, { CricketHighlightsConnection("https://provider.example.test/featured") })
        assertEquals(full, repo.fullStream("42").link)
        hasFullUrl = false
        try { repo.fullStream("42"); fail("A missing full URL must not reuse stale playback") }
        catch (failure: SportsException) { assertTrue(failure.message.orEmpty().contains("full highlight")) }
    }

    @Test fun cancellationPropagatesAndFailedRefreshIsNotCached() = runBlocking {
        var calls = 0
        val repo = CricketHighlightsRepository(CricketHighlightsTransport { _, _ ->
            if (++calls == 1) throw CancellationException("fixture") else catalogue()
        }, { CricketHighlightsConnection("https://provider.example.test/featured") })
        try { repo.featured(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals("42", repo.featured().single().id)
        assertEquals(2, calls)
    }

    private fun variable(value: Long): ByteArray {
        var remaining = value
        return ByteArrayOutputStream().apply {
            while (remaining >= 128) { write(((remaining and 127) or 128).toInt()); remaining = remaining ushr 7 }
            write(remaining.toInt())
        }.toByteArray()
    }
    private fun blob(tag: Int, bytes: ByteArray) = variable((tag.toLong() shl 3) or 2) + variable(bytes.size.toLong()) + bytes
    private fun number(tag: Int, value: Long) = variable(tag.toLong() shl 3) + variable(value)
}
