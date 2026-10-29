package com.nikhil.niktv.sports

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CricbuzzVideoCatalogueTest {
    private val config = CricbuzzCredentials("0123456789abcdef".repeat(4))
    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream().apply {
            while (n >= 128) { write(((n and 127) or 128).toInt()); n = n ushr 7 }; write(n.toInt())
        }.toByteArray()
    }
    private fun number(tag: Int, value: Long) = varint(tag.toLong() shl 3) + varint(value)
    private fun blob(tag: Int, bytes: ByteArray) = varint((tag.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes
    private fun text(tag: Int, value: String) = blob(tag, value.toByteArray())
    private fun item(id: String, time: Long, type: String = "Highlights", premium: String = mediaUrl(id), free: Boolean = false, live: Boolean = false) =
        text(1, id) + text(2, "Fixture video $id") + number(4, time) + text(10, "04:00") +
            text(14, if (free) mediaUrl(id) else "https://cdn.example.test/promo.m3u8") + text(17, premium) + text(18, type) +
            number(20, if (free) 1 else 0) + number(8, if (live) 1 else 0)
    private fun mediaUrl(id: String) = "https://cdn.example.test/$id.m3u8?ks=fixture"
    private fun list(vararg items: ByteArray) = items.fold(byteArrayOf()) { bytes, item -> bytes + blob(1, blob(1, item)) }
    private fun home() = blob(3, list(item("1", 300)))
    private fun collection(id: Int, title: String, vararg items: ByteArray): ByteArray =
        blob(1, blob(1, number(1, id.toLong()) + text(2, title) + items.fold(byteArrayOf()) { bytes, item -> bytes + blob(6, blob(1, item)) }))
    private fun groups() = collection(1, "Latest Videos") + collection(656, "India tour of New Zealand, 2026") + collection(650, "ODI Tri-Series")
    private fun vod() = "#EXTM3U\n#EXTINF:240,\nsegment.ts\n#EXT-X-ENDLIST\n".toByteArray()

    @Test fun latestReadsAllClipTypesAndOnlyUsesStandardUrlsForExplicitlyFreeClips() {
        val data = list(item("2", 200, "Top Knocks", premium = "", free = true),
            item("3", 100, "Top Spells", premium = ""), item("4", 50, live = true)) + blob(1, blob(2, text(1, "advert")))
        val page = CricketHomeProtobuf.latest(data)
        assertEquals(listOf("2", "3"), page.videos.map { it.id })
        assertEquals("Top Knocks", page.videos.first().type)
        assertEquals(mediaUrl("2"), page.videos.first().fullStream().link)
        assertEquals("", page.videos.first().previewUrl)
        assertEquals("50", page.nextCursor)
        try { page.videos[1].fullStream(); fail("A missing premium URL must not substitute the preview") }
        catch (error: SportsException) { assertTrue(error.message.orEmpty().contains("full highlight")) }
    }

    @Test fun seriesReaderFollowsCollectionWrappersAndIgnoresCategoryAndAdFields() {
        val payload = collection(656, "  India tour of New Zealand, 2026  ", item("7", 70), item("8", 60)) +
            blob(3, number(1, 99) + text(2, "Unrelated category")) + blob(1, blob(2, text(1, "advert")))
        assertEquals(listOf(CricketVideoCollection(656, "India tour of New Zealand, 2026")), CricketHomeProtobuf.collections(payload))
        val page = CricketHomeProtobuf.collectionPage(payload)
        assertEquals(listOf("7", "8"), page.videos.map { it.id })
        assertEquals("60", page.nextCursor)
    }

    @Test fun imageIdsUseSizedThumbnailsAndExplicitImageUrlsArePreserved() {
        val page = CricketHomeProtobuf.latest(list(
            item("2", 200) + text(6, "123456"),
            item("3", 100) + text(6, "654321") + text(22, "https://images.example.test/explicit.jpg")))
        assertEquals("https://static.cricbuzz.com/a/img/v1/1280x720/i1/c123456/i.jpg", page.videos[0].image)
        assertEquals("https://images.example.test/explicit.jpg", page.videos[1].image)
    }

    @Test fun combinedBrowseKeepsHomeFullUrlsAndIncludesLatestClipsWithoutDuplicates() = runBlocking {
        val calls = mutableListOf<String>()
        val transport = CricketHighlightsTransport { url, _ ->
            calls += url
            when {
                url.endsWith("geo-location") -> """{"country":"CA"}""".toByteArray()
                url.endsWith("collection/videoIndex") -> groups()
                url.endsWith("/home/v1/index") -> home()
                else -> list(item("1", 300, premium = ""), item("2", 200, "Top Knocks", premium = "", free = true))
            }
        }
        val direct = CricbuzzDirectClient(transport, { config })
        val page = direct.browse()
        assertEquals(listOf("1", "2"), page.videos.map { it.id })
        assertEquals(mediaUrl("1"), page.videos.first().fullStream().link)
        assertEquals(listOf(1, 656, 650), direct.collections.map { it.id })
        assertTrue(calls.none { "/detail/" in it || "/iam/" in it })
    }

    @Test fun paginationDeduplicatesStopsRepeatedPagesAndRefreshesAnOlderVideoAtItsOriginalCursor() = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        var oldFetches = 0
        val transport = CricketHighlightsTransport { url, headers ->
            calls += url to headers
            when {
                url.endsWith("geo-location") -> """{"country":"CA"}""".toByteArray()
                url.endsWith("collection/videoIndex") -> groups()
                url.endsWith("/home/v1/index") -> home()
                url.endsWith("/videos/v1/index") -> list(item("2", 200))
                url.endsWith("?pt=200") -> list(item("3", 100, premium = mediaUrl("3") + "-${++oldFetches}"))
                url.endsWith("?pt=100") -> list(item("3", 100))
                url.startsWith("https://cdn.example.test/") -> vod()
                else -> error("Unexpected catalogue request")
            }
        }
        val repo = CricketHighlightsRepository(transport, direct = CricbuzzDirectClient(transport, { config }))
        assertEquals(listOf("1", "2"), repo.featured().map { it.id })
        assertTrue(repo.hasMore)
        assertEquals(listOf("1", "2", "3"), repo.loadMore().map { it.id })
        assertEquals(3, repo.loadMore().size)
        assertFalse(repo.hasMore)
        val stream = repo.fullStream("3")
        assertTrue(stream.link.endsWith("fixture-2"))
        assertEquals(2, calls.count { it.first.endsWith("?pt=200") })
        assertTrue(calls.filter { it.first.startsWith("https://cdn.example.test/") }.all { "Authentication" !in it.second })
    }

    @Test fun switchingSeriesChangesTheRouteAndResolvesOlderSeriesVideosWithoutDetailRequests() = runBlocking {
        val calls = mutableListOf<String>()
        val transport = CricketHighlightsTransport { url, _ ->
            calls += url
            when {
                url.endsWith("geo-location") -> """{"country":"CA"}""".toByteArray()
                url.endsWith("collection/videoIndex") -> groups()
                url.endsWith("/home/v1/index") -> home()
                url.endsWith("/collection/detail/656") -> collection(656, "New Zealand", item("7", 700))
                url.endsWith("/collection/detail/656?pt=700") -> collection(656, "New Zealand", item("8", 600))
                url.endsWith("/collection/detail/650") -> collection(650, "Tri-Series", item("9", 900))
                url.startsWith("https://cdn.example.test/") -> vod()
                else -> error("Unexpected series request")
            }
        }
        val repo = CricketHighlightsRepository(transport, direct = CricbuzzDirectClient(transport, { config }))
        assertEquals("7", repo.featured(collectionId = 656).single().id)
        assertEquals(2, repo.loadMore().size)
        assertEquals(mediaUrl("8"), repo.fullStream("8").link)
        assertEquals("9", repo.featured(collectionId = 650).single().id)
        assertEquals(2, calls.count { it.endsWith("/collection/detail/656?pt=700") })
        assertTrue(calls.none { "/videos/v1/detail/" in it })
    }

    @Test fun emptyAndUnknownCollectionFieldsDoNotBecomeVideosOrPaginationCursors() {
        val payload = blob(99, collection(656, "Hidden unknown field", item("5", 100)))
        assertTrue(CricketHomeProtobuf.collectionPage(payload).videos.isEmpty())
        assertNull(CricketHomeProtobuf.collectionPage(payload).nextCursor)
        assertTrue(CricketHomeProtobuf.collections(payload).isEmpty())
    }
}
