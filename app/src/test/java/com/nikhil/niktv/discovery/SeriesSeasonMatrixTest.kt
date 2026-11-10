package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.SeriesStartSeason
import org.junit.Assert.*
import org.junit.Test

class SeriesSeasonMatrixTest {
    private val card = DiscoveryCard(900100, true, "Season matrix")

    @Test fun firstLastAndResumeAreConsistentAcrossSeasonCountsAndProviderOrdering() {
        for (count in listOf(1, 2, 3, 5, 11, 16, 24)) {
            val ordered = (1..count).toList()
            val details = DiscoveryDetails(card, seasons = ordered.reversed() + ordered.take(1),
                seasonEpisodeCounts = ordered.associateWith { 30 })
            assertEquals("FIRST for $count seasons", 1, discoveryInitialSeriesSeason(details, SeriesStartSeason.FIRST))
            assertEquals("LAST for $count seasons", count, discoveryInitialSeriesSeason(details, SeriesStartSeason.LAST))
            val resume = (count / 2 + 1).coerceAtMost(count)
            for (preference in SeriesStartSeason.entries) {
                assertEquals("Resume wins over $preference for $count seasons", resume,
                    discoveryInitialSeriesSeason(details, preference, resume))
            }
        }
    }

    @Test fun announcedEmptySeasonsFallBackInNumericOrderWithoutRepeatingThem() {
        val details = DiscoveryDetails(card, seasons = listOf(16, 2, 1, 11, 16, 3),
            seasonEpisodeCounts = mapOf(16 to 0, 2 to 24, 1 to 0, 11 to 30, 3 to 10))
        assertEquals(2, discoveryInitialSeriesSeason(details, SeriesStartSeason.FIRST))
        assertEquals(11, discoveryInitialSeriesSeason(details, SeriesStartSeason.LAST))
        assertEquals(3, discoverySeriesSeasonAfterEmpty(details, SeriesStartSeason.FIRST, setOf(2)))
        assertEquals(3, discoverySeriesSeasonAfterEmpty(details, SeriesStartSeason.LAST, setOf(11)))
        assertNull(discoverySeriesSeasonAfterEmpty(details, SeriesStartSeason.LAST, setOf(2, 3, 11)))
    }

    @Test fun specialsResumeWithoutChangingTheUnwatchedDefaultSeason() {
        val details = DiscoveryDetails(card, seasons = listOf(2, 0, 1),
            seasonEpisodeCounts = mapOf(0 to 5, 1 to 10, 2 to 10))
        assertEquals(1, discoveryInitialSeriesSeason(details, SeriesStartSeason.FIRST))
        assertEquals(2, discoveryInitialSeriesSeason(details, SeriesStartSeason.LAST))
        assertEquals(0, discoveryInitialSeriesSeason(details, SeriesStartSeason.LAST, resume = 0))
        assertEquals(2, discoveryInitialSeriesSeason(details, SeriesStartSeason.LAST, resume = 99))
    }
}
