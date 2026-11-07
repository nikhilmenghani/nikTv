package com.nikhil.niktv.discovery

import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class DiscoverySeriesDetailTest {
    private val card = DiscoveryCard(123, true, "Series")
    private fun recent(profile: String, id: Int, time: Long = 1) = RecentItem(
        FavoriteKind.SERIES, card.media, profileKey = profile, playedAtMillis = time,
        alternateTarget = AlternateSourceTarget("Series", true, tmdbId = id, season = 2, episode = 9))

    @Test fun resumesAlternateEpisodeForCurrentProfile() {
        assertEquals(2 to 9, discoveryLastEpisode(card, "a", listOf(recent("a", 123))))
    }
    @Test fun excludesOtherTitles() {
        assertNull(discoveryLastEpisode(card, "a", listOf(recent("a", 456))))
    }
    @Test fun excludesOtherProfiles() {
        assertNull(discoveryLastEpisode(card, "b", listOf(recent("a", 123))))
    }
    @Test fun missingEpisodeIdentityDoesNotGuess() {
        assertNull(discoveryLastEpisode(card, "a", listOf(RecentItem(FavoriteKind.SERIES, card.media, profileKey = "a"))))
    }
    @Test fun emptyAnnouncedSeasonDoesNotBlockEarlierEpisodes() {
        val details = DiscoveryDetails(card, seasons = listOf(0, 1, 17, 18),
            seasonEpisodeCounts = mapOf(0 to 0, 1 to 41, 17 to 13, 18 to 0))
        assertEquals(17, discoveryInitialSeriesSeason(details, SeriesStartSeason.LAST))
        assertEquals(1, discoveryInitialSeriesSeason(details, SeriesStartSeason.FIRST))
        assertEquals(18, discoveryInitialSeriesSeason(details, SeriesStartSeason.LAST, resume = 18))
    }
    @Test fun unknownSeasonCountsAndCompletelyEmptySeriesRemainBrowsable() {
        assertEquals(18, discoveryInitialSeriesSeason(DiscoveryDetails(card, seasons = listOf(1, 18)), SeriesStartSeason.LAST))
        assertEquals(1, discoveryInitialSeriesSeason(DiscoveryDetails(card, seasons = listOf(1), seasonEpisodeCounts = mapOf(1 to 0)), SeriesStartSeason.LAST))
        assertNull(discoveryInitialSeriesSeason(null, SeriesStartSeason.LAST))
    }
    @Test fun legacyCachedMetadataCanAdvanceAfterAnActualEmptySeasonWithoutLooping() {
        val details = DiscoveryDetails(card, seasons = listOf(1, 17, 18))
        assertEquals(18, discoveryInitialSeriesSeason(details, SeriesStartSeason.LAST))
        assertEquals(17, discoverySeriesSeasonAfterEmpty(details, SeriesStartSeason.LAST, setOf(18)))
        assertEquals(1, discoverySeriesSeasonAfterEmpty(details, SeriesStartSeason.LAST, setOf(18, 17)))
        assertNull(discoverySeriesSeasonAfterEmpty(details, SeriesStartSeason.LAST, setOf(1, 17, 18)))
    }
}
