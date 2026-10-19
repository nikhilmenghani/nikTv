package com.nikhil.niktv.data

import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveProgrammeRecommendationsTest {
    private val now = 1_000_000L
    private fun channel(title: String = "The Matrix (1999)") = MediaItem("channel-1", "Film Channel", "channel-logo", "channel-url",
        channelNumber = 1, externalTmdbId = 999,
        liveProgramme = LiveProgramme(title, now - 1_000, now + 1_000))

    @Test fun usesProgrammeTitleAndNeverChannelMetadata() {
        val item = liveProgrammeRecommendationItem(channel(), now)!!
        assertEquals("The Matrix (1999)", item.title)
        assertNull(item.command)
        assertNull(item.logo)
        assertNull(item.externalTmdbId)
        assertNull(item.liveProgramme)
        assertNull(item.channelNumber)
        assertEquals(item.id, liveProgrammeRecommendationItem(channel().copy(id = "other-channel"), now)!!.id)
    }
    @Test fun excludesMissingExpiredFutureAndAdultProgrammes() {
        assertNull(liveProgrammeRecommendationItem(channel("No programme information"), now))
        assertNull(liveProgrammeRecommendationItem(channel(), now + 2_000))
        assertNull(liveProgrammeRecommendationItem(channel(), now - 2_000))
        assertNull(liveProgrammeRecommendationItem(channel().copy(isAdult = true), now))
        assertNull(liveProgrammeRecommendationItem(channel().copy(liveProgramme = null), now))
        assertNull(liveProgrammeRecommendationItem(channel().copy(liveProgramme = LiveProgramme("Old text", observedAtMillis = now - 600_000)), now))
    }
    @Test fun episodeSuffixSelectsSeriesAndPreservesColonsAndYear() {
        val item = liveProgrammeRecommendationItem(channel("Law & Order: SVU S02E03"), now)!!
        assertEquals("Law & Order: SVU", item.title)
        assertEquals(SearchContentType.SERIES, item.searchResultType)
        assertEquals("It: Chapter Two (2019)", liveProgrammeRecommendationItem(channel("It: Chapter Two (2019)"), now)!!.title)
    }
    private val movie = TmdbMovie(42, "Title", "Title", null, null, null, "1999-01-01", null)
    private val series = TmdbSeries(43, "Title", "Title", null, null, null, "2000-01-01", null)
    @Test fun requiresExactlyOneCatalogMatch() = runBlocking {
        val item = liveProgrammeRecommendationItem(channel(), now)!!
        val film = MediaIdentity("p", CatalogType.MOVIES, item.id, item.title, movie = movie)
        val show = MediaIdentity("p", CatalogType.SERIES, item.id, item.title, series = series)
        assertEquals(film, resolveLiveProgrammeRecommendation(item) { type, _ -> if (type == CatalogType.MOVIES) film else null })
        assertEquals(show, resolveLiveProgrammeRecommendation(item) { type, _ -> if (type == CatalogType.SERIES) show else null })
        assertNull(resolveLiveProgrammeRecommendation(item) { type, _ -> if (type == CatalogType.MOVIES) film else show })
        assertNull(resolveLiveProgrammeRecommendation(item) { _, _ -> null })
        val calls = mutableListOf<CatalogType>()
        assertEquals(show, resolveLiveProgrammeRecommendation(item.copy(searchResultType = SearchContentType.SERIES)) { type, _ -> calls += type; show })
        assertEquals(listOf(CatalogType.SERIES), calls)
    }
}
