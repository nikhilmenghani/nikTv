package com.nikhil.niktv.sports

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlayfyCatalogueTest {
    private fun document(value: String) = Json.parseToJsonElement(value)

    @Test fun categoryIdentityAndExternalPlaylistRemainSeparateFromCricfy() {
        val categories = PlayfyCatalogue.categories(document("""[
            {"id":"1","title":"Sports","catLink":"null"},
            {"id":2,"title":"News","catLink":"https://playlist.example.test/news.m3u","geo":"CA"},
            {"id":"3","title":"Hidden","publish":"0"}
        ]"""))
        assertEquals(listOf("playfy-category-1", "playfy-category-2"), categories.map { it.id })
        assertFalse(categories.first().isPlaylist)
        assertEquals("https://playlist.example.test/news.m3u", categories.last().link)
        assertEquals("CA", categories.last().geo)
        assertEquals("cats/united-states.json", PlayfyCatalogue.categoryPath(" United States "))
        assertEquals("cats/fancode-(ind).json", PlayfyCatalogue.categoryPath("FANCODE (IND)"))
    }

    @Test fun numericChannelIdsAreNamespacedAndFormatsKeepTheirRegionalRestrictions() {
        val channels = PlayfyCatalogue.channels(document("""[
            {"id":"380","title":"Test Cricket","image":"","formats":["FHD","HD"],"formatGeos":["CA","ALL"]},
            {"id":381,"title":"Second","formats":[]},
            {"id":"../../other","title":"Invalid"},
            {"id":382,"title":"Hidden","publish":false}
        ]"""))
        assertEquals(2, channels.size)
        assertEquals("playfy-380", channels.first().slug)
        assertEquals(channels.first().slug, channels.first().key)
        assertEquals("380", PlayfyCatalogue.channelId(channels.first().slug))
        assertNull(PlayfyCatalogue.channelId("380"))
        assertNull(PlayfyCatalogue.channelId("playfy-../380"))
        assertEquals(listOf("FHD", "HD"), channels.first().formats.map { it.title })
        assertEquals(listOf("CA", "ALL"), channels.first().formats.map { it.geo })
    }

    @Test fun eventsUseTheObjectEnvelopeAndKeepScheduleTeamsAndCategory() {
        val body = document("""{"channels":[{
            "id":"17","title":"A vs B","image":"event.png","category":"Cricket","formats":["Main"],
            "eventInfo":{"eventName":"Test League","teamA":"A","teamB":"B","teamAFlag":"a.png","teamBFlag":"b.png",
                "startTime":"2026/11/22 10:00:00 +0000","endTime":"2026/11/22 12:00:00 +0000","isPinned":1}
        }],"cats":[{"id":"1","title":"All"},{"id":"2","title":"Cricket","image":"cricket.png"}]}""")
        val event = PlayfyCatalogue.events(body).single()
        assertEquals("playfy-17", event.slug)
        assertEquals("Cricket", event.sport)
        assertEquals("Test League", event.competition)
        assertEquals(Instant.parse("2026-11-22T10:00:00Z"), event.start)
        assertEquals(SportsStatus.FINISHED, event.status(Instant.parse("2026-11-23T00:00:00Z")))
        assertEquals("A", event.teamA)
        assertEquals("b.png", event.teamBFlag)
        assertTrue(event.isHot)
        assertEquals(listOf("All", "Cricket"), PlayfyCatalogue.eventFilters(body).map { it.title })
        assertEquals("cricket.png", PlayfyCatalogue.eventFilters(body).last().image)
    }

    @Test fun streamTypeMappingDoesNotReuseCricfyNumericMeanings() {
        val streams = PlayfyCatalogue.streams(document("""[
            {"title":"Direct","link":"https://media.example.test/live.m3u8","type":0},
            {"title":"Web","link":"https://player.example.test/embed","type":2},
            {"title":"YouTube","link":"abcdefghijk","type":3},
            {"title":"Lookup","link":"123","type":4,"api":"https://api.example.test/%s"},
            {"title":"Direct seven","link":"https://media.example.test/seven","type":7}
        ]"""))
        assertEquals(listOf(0, 4, 103, 6, 0), streams.map { it.type })
        assertEquals("https://api.example.test/%s", streams[3].api)
    }

    @Test fun drmFieldsUseExplicitSchemeOrThePublishedLicenseSuffix() {
        val streams = PlayfyCatalogue.streams(document("""[
            {"link":"https://media.example.test/clear.mpd","type":1,"dl":"https://license.example.test/clear&clearkey"},
            {"link":"https://media.example.test/wide.mpd","type":1,"dl":"https://license.example.test/wide&widevine"},
            {"link":"https://media.example.test/play.mpd","type":1,"api":"https://license.example.test/play","drmType":"playready"}
        ]"""))
        assertEquals(listOf("clearkey", "widevine", "playready"), streams.map { it.drmType })
        assertEquals("https://license.example.test/clear", streams.first().drmLicense)
        assertEquals("https://license.example.test/wide", streams[1].drmLicense)
        assertTrue(streams.all { it.type == 0 })
    }

    @Test fun rawTypeOneUsesItsInlineApiLicenseWithoutRequiringPlayerExtraNames() {
        val syntheticLicense = "00".repeat(16) + ":" + "11".repeat(16)
        val stream = PlayfyCatalogue.detail(document("""[
            {"link":"https://media.example.test/live.mpd","type":"1","api":"$syntheticLicense"}
        ]""")).streams.single()
        assertEquals(0, stream.type)
        assertEquals("clearkey", stream.drmType)
        assertEquals(syntheticLicense, stream.drmLicense)
        assertNotNull(stream.playbackDrm())
    }

    @Test fun explicitFormatHintsAndHeaderValuesSurviveWithoutInventingCredentials() {
        val streams = PlayfyCatalogue.streams(document("""[
            {"link":"https://media.example.test/opaque","type":5,"dl":"mpd","headers":{"Referer":"https://player.example.test/","X-Bad":"line\nbreak"}},
            {"link":"https://media.example.test/smooth","type":5,"api":"ismv"}
        ]"""))
        assertEquals("DASH", streams.first().playbackFormat)
        assertEquals("SS", streams.last().playbackFormat)
        assertEquals(mapOf("Referer" to "https://player.example.test/"), streams.first().headers)
        assertTrue(streams.first().drmLicense.isBlank())
    }

    @Test fun unsupportedSourceCannotHideTheOtherPlayableChoices() = runBlocking {
        val streams = PlayfyCatalogue.detail(document("""[
            {"link":"https://media.example.test/unknown","type":99},
            {"link":"https://media.example.test/live.m3u8","type":0}
        ]""")).streams
        assertEquals(listOf(-1, 0), streams.map { it.type })
        val resolver = SportsStreamResolver(SportsTransport { _, _ -> error("No network lookup expected") },
            { error("No WebView lookup expected") })
        try { resolver.resolve(streams.first()); fail("Unsupported occurrence must be rejected") }
        catch (failure: SportsException) { assertTrue(failure.message.orEmpty().contains("unsupported")) }
        assertEquals(streams.last().link, resolver.resolve(streams.last()).link)
    }

    @Test fun zeroGeoFlagIsUnrestrictedAcrossPlayfyModels() {
        val category = PlayfyCatalogue.categories(document("""[{"id":"1","title":"Sports","geo":"0"}]""")).single()
        val channel = PlayfyCatalogue.channels(document("""[
            {"id":"17","title":"Test Cricket","geo":"0","formats":["Main"],"formatGeos":["0"]}
        ]""")).single()
        val events = PlayfyCatalogue.events(document("""{"channels":[{
            "id":"18","title":"A vs B","geo":"0","formats":["Main"],"formatGeos":["0"],
            "eventInfo":{"teamA":"A","teamB":"B","startTime":"2026/11/22 10:00:00 +0000","endTime":"2026/11/22 12:00:00 +0000"}
        }],"cats":[]}"""))
        val stream = PlayfyCatalogue.detail(document("""[
            {"link":"https://media.example.test/live.m3u8","geo":"0"}
        ]""")).streams.single()
        assertTrue(listOf(category.geo, channel.geo, channel.formats.single().geo,
            events.single().geo, events.single().formats.single().geo, stream.geo).all { it == "ALL" })
        assertTrue(events.all { sportsGeoAvailable(it.geo, "CA") })
    }

    @Test(expected = SportsException::class)
    fun aConfigurationObjectCannotMasqueradeAsAChannelCatalogue() {
        PlayfyCatalogue.channels(document("""{"data":"not-decoded"}"""))
    }

    @Test(expected = SportsException::class)
    fun categoryTitleCannotIntroduceAnotherPath() {
        PlayfyCatalogue.categoryPath("../other")
    }
}
