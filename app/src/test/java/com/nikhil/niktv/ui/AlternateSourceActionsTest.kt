package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.SearchContentType
import org.junit.Assert.*
import org.junit.Test

class AlternateSourceActionsTest {
    private fun media(id: String = "provider:12") = MediaItem(id, "Example", null, null)

    @Test fun tmdbTilesKeepTheirKnownMovieAndSeriesIdentity() {
        assertEquals(CatalogType.MOVIES, alternateSourceRequest(media("tmdb-movie:12"), null)?.type)
        assertEquals(CatalogType.SERIES, alternateSourceRequest(media("tmdb-series:12"), null)?.type)
        assertNull(alternateSourceRequest(media(), null))
    }

    @Test fun liveAndRadioContextNeverBecomesAMovieAction() {
        assertNull(alternateSourceRequest(media("tmdb-movie:12"), CatalogType.LIVE_TV))
        assertNull(alternateSourceRequest(media(), CatalogType.RADIO))
        assertNull(alternateSourceRequest(media().copy(searchResultType = SearchContentType.LIVE_TV), CatalogType.MOVIES))
        assertNull(alternateSourceRequest(media().copy(channelNumber = 12), CatalogType.MOVIES))
    }

    @Test fun searchMetadataWinsOverThePreviouslyOpenedCatalogType() {
        val item = media().copy(searchResultType = SearchContentType.SERIES)
        assertEquals(CatalogType.SERIES, alternateSourceRequest(item, CatalogType.MOVIES)?.type)
    }

    @Test fun providerEpisodesRetainTheirParentAndExactEpisodeIdentity() {
        val series = media("show:1").copy(externalTmdbId = 12)
        val episode = media("episode:3").copy(seasonNumber = 2, episodeNumber = 3)
        val request = alternateSourceRequest(episode, CatalogType.SERIES, series)!!
        assertSame(series, request.series)
        assertSame(episode, request.item)
        assertEquals(2, request.season)
        assertEquals(3, request.episode)
    }

    @Test fun explicitTmdbEpisodeNumbersSupportSpecialsWithoutDefaultingMissingIdentity() {
        val series = media("tmdb-series:12")
        val request = alternateSourceRequest(series, CatalogType.SERIES, series, season = 0, episode = 4)!!
        assertEquals(0, request.season)
        assertEquals(4, request.episode)
        val unknown = alternateSourceRequest(series, CatalogType.SERIES)!!
        assertNull(unknown.season)
        assertNull(unknown.episode)
    }
    @Test fun onlyTheSelectedIncompatibleStreamUsesTheVlcFallback() {
        val target = com.nikhil.niktv.data.AlternateSourceTarget("Friends", true, 1668, season = 3, episode = 25)
        val regular = com.nikhil.niktv.data.AlternateStream("regular", "Regular", "https://media.example.test/a.m3u8")
        val atlanta = regular.copy(id = "atlanta", vlcCompatible = false)
        val source = com.nikhil.niktv.model.AlternatePlaybackSource(target, listOf(regular, atlanta), "regular", "test")
        assertTrue(alternateSourceSupportsVlc(null))
        assertTrue(alternateSourceSupportsVlc(source))
        assertFalse(alternateSourceSupportsVlc(source.copy(selectedId = "atlanta")))
        assertTrue(alternateSourceSupportsVlc(source.copy(selectedId = "missing")))
    }

}
