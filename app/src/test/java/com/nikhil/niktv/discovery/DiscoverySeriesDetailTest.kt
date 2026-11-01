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
}
