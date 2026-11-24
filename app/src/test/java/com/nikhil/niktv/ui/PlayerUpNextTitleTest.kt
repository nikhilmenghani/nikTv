package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.EpisodeSeasonCache
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import org.junit.Assert.*
import org.junit.Test

class PlayerUpNextTitleTest {
    private val next = MediaItem("provider-next", "Episode 4868. 2026-11-14 - Will Motibhai change his mind?", null, "cmd",
        seasonNumber = 1, episodeNumber = 4868, portalEpisodeId = "source-next")
    private val series = MediaItem("provider-series", "Fixture Series", null, null)
    private val playing = PlayingMedia(next.copy(id = "current"), "fixture", CatalogType.SERIES,
        nextEpisode = next, series = series, profileKey = "portal")
    private val tmdb = next.copy(title = "Motibhai Makes a Decision", externalTmdbId = 123)
    private val cache = EpisodeSeasonCache("portal", series.id, 1, listOf(1), listOf(tmdb), iptvEpisodes = listOf(next))

    @Test fun trimsProviderEpisodeNumberDateAndRepeatingSeriesName() {
        assertEquals("Will Motibhai change his mind?", upNextEpisodeTitle(next, series.title))
        assertEquals("The Missing Letter", upNextEpisodeTitle(
            next.copy(title = "Fixture Series | S01 - E23 · The Missing Letter"), series.title))
    }

    @Test fun prefersTheCachedTitleForTheSameNextEpisodeOnly() {
        assertEquals(tmdb.title, cachedUpNextEpisodeTitle(playing, listOf(cache)))
        assertEquals(tmdb.title, cachedUpNextEpisodeTitle(playing.copy(nextEpisode = next.copy(id = "refreshed")), listOf(cache)))
        assertNull(cachedUpNextEpisodeTitle(playing, listOf(cache.copy(profileKey = "other"), cache.copy(season = 2))))
        assertNull(cachedUpNextEpisodeTitle(playing.copy(nextEpisode = next.copy(id = "different", portalEpisodeId = null)), listOf(cache)))
    }

    @Test fun aGenericCachedTitleFallsBackToTheDescriptiveProviderTitle() {
        assertNull(cachedUpNextEpisodeTitle(playing, listOf(cache.copy(episodes = listOf(tmdb.copy(title = "Episode 4868"))))))
        assertEquals("Episode 4868", upNextEpisodeTitle(next.copy(title = "Episode 4868. 2026-11-14"), null))
    }
}
