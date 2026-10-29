package com.nikhil.niktv.sports

import androidx.compose.ui.input.key.Key
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SportsHubCatalogueTest {
    private val category = SportsCategory("provider", "Provider", link = "https://playlist.example.test/list")

    @Test fun categoryRowsKeepAllEventsAndSeparateSportsWithoutEmptyOrDuplicateCategories() {
        val cricket = SportsEvent("1", "Cricket match", "cricket", "Cricket", "League", "", "A", "B", null, null, emptyList())
        val football = cricket.copy(id = "2", title = "Football match", slug = "football", sport = "Football")
        val sections = sportsEventSections(listOf(cricket, football), listOf(
            SportsEventFilter("all", "All"), SportsEventFilter("cricket", "Cricket"),
            SportsEventFilter("duplicate", "CRICKET"), SportsEventFilter("empty", "Tennis")))
        assertEquals(listOf("All sports", "Cricket", "Football"), sections.map { it.title })
        assertEquals(listOf("cricket", "football"), sections[0].events.map { it.slug })
        assertEquals(listOf(cricket), sections[1].events)
        assertEquals(listOf(football), sections[2].events)
        assertTrue(sportsEventSections(emptyList(), emptyList()).isEmpty())
    }

    @Test fun categorySentinelsUseBuiltInPathsAndNetworkLinksStayExternal() {
        val rows = SportsCatalogue.categories("""[{"id":1,"title":"Kids","catLink":"N"},{"id":2,"title":"ISLAMIC TV","catLink":"null"},{"id":3,"title":"Movies","catLink":"ok"},{"id":4,"title":"Provider","catLink":"https://playlist.example.test/list?token=fixture"}]""")
        assertEquals(listOf(false, false, false, true), rows.map { it.isPlaylist })
        assertEquals("categories/islamic-tv.txt", rows[1].path)
        assertEquals("categories/movies.txt", rows[2].path)
        assertTrue(rows[3].link.endsWith("token=fixture"))
    }

    @Test fun channelAndDetailParsingPreserveDifferentNavigationAndSourceOccurrences() {
        val channel = """{"id":"1","title":"Willow","slug":"willow-","cat":"Sports","formats":[{"title":"Preview","webLink":"https://page.example.test/","geo":"IN"}]}"""
        val preview = SportsCatalogue.channels("[$channel]").single()
        assertEquals("willow-", preview.slug)
        assertEquals("IN", preview.formats.single().geo)
        val detail = SportsCatalogue.detail("""{"streamUrls":[{"id":1,"link":"https://cdn.example.test/one.m3u8","title":"Same"},{"id":1,"link":"https://cdn.example.test/one.m3u8","title":"Same"}],"related":[$channel],"prevChannel":"previous-","nextChannel":"next-","geo":"CA"}""")
        assertEquals(2, detail.streams.size)
        assertEquals(listOf("willow-"), detail.related.map { it.slug })
        assertEquals("previous-", detail.previous)
        assertEquals("next-", detail.next)
        assertEquals("CA", detail.geo)
        assertTrue(SportsCatalogue.channels("[$channel,${channel.replace("willow-", "../private")}]").size == 1)
    }

    @Test fun eventArtworkCountdownsAndRecentUseTheScheduleWithoutFetching() {
        val event = SportsCatalogue.events("""[{"id":1,"title":"A vs B","slug":"a-b","eventInfo":{"teamA":"A","teamB":"B","teamAFlag":"https://img.example.test/a","teamBFlag":"https://img.example.test/b","isHot":1,"eventName":"Cricket","startTime":"2026/10/28 10:00:00 +0000","endTime":"2026/10/28 12:00:00 +0000"},"formats":[{"title":"HD","geo":"CA","webLink":"https://page.example.test/"}]}]""").single()
        assertTrue(event.isHot); assertTrue(event.teamAFlag.endsWith("/a")); assertTrue(event.teamBFlag.endsWith("/b"))
        assertEquals("CA", event.formats.single().geo)
        val before = Instant.parse("2026-10-28T09:59:00Z")
        assertEquals("Starts in 1:00", sportsEventClock(event, before))
        assertEquals("Live · 1:00 elapsed", sportsEventClock(event, before.plusSeconds(120)))
        assertFalse(sportsEventMatches(event, "All sports", "Recent", "", before))
        assertTrue(sportsEventMatches(event, "All sports", "Recent", "", before.plusSeconds(8000)))
    }

    @Test fun m3uHeadersDrmGroupsAndDuplicatesAreScopedToTheirOwnEntry() {
        val body = """#EXTM3U
            #EXTINF:-1 tvg-logo="https://img.example.test/logo" group-title="Sport, Live",Same
            #EXTVLCOPT:http-user-agent=Fixture/1
            #EXTVLCOPT:http-referrer=https://origin.example.test/
            #EXTVLCOPT:http-origin=https://origin.example.test
            #EXTHTTP:{"cookie":"signed=a+b"}
            #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
            #KODIPROP:inputstream.adaptive.license_key=00:11
            https://cdn.example.test/one.mpd?token=a+b
            #EXTINF:-1 group-title="Sport, Live",Same
            https://cdn.example.test/two.m3u8|User-Agent=Other%2F2
        """.trimIndent()
        val rows = SportsPlaylist.parse(body, category)
        assertEquals(2, rows.size); assertNotEquals(rows[0].key, rows[1].key)
        assertEquals("Sport, Live", rows[0].group)
        assertEquals("signed=a+b", rows[0].direct!!.headers["Cookie"])
        assertEquals("org.w3.clearkey", rows[0].direct!!.drmType)
        assertEquals("00:11", rows[0].direct!!.drmLicense)
        assertEquals(mapOf("User-Agent" to "Other/2"), rows[1].direct!!.headers)
        assertEquals("", rows[1].direct!!.drmLicense)
        val rotated = SportsPlaylist.parse(body.replace("token=a+b", "token=c+d"), category)
        assertEquals(rows.map { it.key }, rotated.map { it.key })
    }

    @Test fun jsonCollectionsCarryHeadersAndDoNotConfuseErrorObjectsWithEmptyLists() {
        val rows = SportsPlaylist.parse("""{"channels":[{"name":"TV","groupTitle":"News","link":"https://cdn.example.test/live.mpd","userAgent":"Fixture/2","referrer":"https://origin.example.test/","drmScheme":"widevine","drmLicense":"https://license.example.test/"}]}""", category)
        assertEquals("News", rows.single().group)
        assertEquals("Fixture/2", rows.single().direct!!.headers["User-Agent"])
        assertEquals("widevine", rows.single().direct!!.drmType)
        assertTrue(SportsPlaylist.parse("#EXTM3U\n# empty provider", category).isEmpty())
        for (body in listOf("<html>upstream error</html>", "{\"error\":\"private token=secret\"}", "CFP1.fixture")) {
            try { SportsPlaylist.parse(body, category); fail("Must reject an unavailable provider response") }
            catch (error: SportsException) { assertFalse(error.message.orEmpty().contains("secret")) }
        }
    }

    @Test fun supportsTheLegacyPlaylistEnvelopeWithoutUsingTheNativeAppLibrary() {
        val plain = "#EXTM3U\n#EXTINF:-1,Fixture\nhttps://cdn.example.test/live.m3u8"
        val key = ByteArray(32) { it.toByte() }; val iv = ByteArray(16) { (it + 3).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val b64 = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray()))
        val envelope = b64.take(10) + Base64.getEncoder().encodeToString(iv) + b64.substring(10, b64.length - 10) + Base64.getEncoder().encodeToString(key) + b64.takeLast(10)
        assertEquals("Fixture", SportsPlaylist.parse(envelope, category).single().title)
    }

    @Test fun categoryLoadingUsesTheExternalRouteAndDoesNotCacheAnErrorAsAPlaylist() = runBlocking {
        val calls = mutableListOf<String>(); var external = "<html>error</html>"
        val repo = SportsRepository(SportsTransport { url, _ -> calls += url; SportsResponse(url, when {
            url.endsWith("config.txt") -> """{"cric_api2":"https://catalogue.example.test/"}"""
            url == category.link -> external
            else -> error("Unexpected catalogue route")
        }) }, country = { "CA" })
        try { repo.channels(category); fail("HTML is unavailable") } catch (_: SportsException) { }
        external = "#EXTM3U\n#EXTINF:-1,Available\nhttps://cdn.example.test/live.m3u8"
        assertEquals("Available", repo.channels(category).single().title)
        assertEquals(2, calls.count { it == category.link })
        repo.channels(category)
        assertEquals(2, calls.count { it == category.link })
    }

    @Test fun detailRegionCannotBeOverriddenByAWorldwideStream() = runBlocking {
        val repo = SportsRepository(SportsTransport { url, _ -> SportsResponse(url, if (url.endsWith("config.txt"))
            """{"cric_api2":"https://catalogue.example.test/"}""" else """{"geo":"IN","streamUrls":[{"link":"https://cdn.example.test/live.m3u8","geo":"ALL"}]}""") }, country = { "CA" })
        try { repo.detail("channel"); fail("Detail restriction applies before stream choice") } catch (_: SportsException) { }
    }

    @Test fun dpadMovementPreservesRowsAndHandlesIncompleteFinalRows() {
        assertEquals(0, sportsGridTarget(0, 3, 8, Key.DirectionLeft))
        assertEquals(2, sportsGridTarget(2, 3, 8, Key.DirectionRight))
        assertEquals(-1, sportsGridTarget(1, 3, 8, Key.DirectionUp))
        assertEquals(7, sportsGridTarget(4, 3, 8, Key.DirectionDown))
        assertEquals(7, sportsGridTarget(5, 3, 8, Key.DirectionDown))
        assertEquals(8, sportsGridTarget(7, 3, 8, Key.DirectionDown))
        assertEquals(7, sportsGridTarget(7, 3, 8, Key.DirectionRight))
    }
}
