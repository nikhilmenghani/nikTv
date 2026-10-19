package com.nikhil.niktv.data

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class HistoryRecommendationsTest {
    @Test fun earlierEpisodeCanSeedRecommendationsWithoutCrossingProfilesOrSeries() {
        val earlier = PlaybackProgress("progress-v2|a|SERIES|series:42|s:1|e:1|episode", 1_200_000, 2_400_000)
        val latest = PlaybackProgress("progress-v2|a|SERIES|series:42|s:1|e:2|episode", 0, 2_400_000)
        assertEquals(earlier, seriesRecommendationProgress("a", "42", listOf(earlier, latest)))
        assertNull(seriesRecommendationProgress("b", "42", listOf(earlier)))
        assertNull(seriesRecommendationProgress("a", "4", listOf(earlier)))
    }
    private data class Candidate(val id: Int, val title: String, val year: Int)
    private fun match(title: String, candidates: List<Candidate>) = unambiguousTmdbMatch(title,
        candidates, { listOf(it.title) }, { it.year }, { it.id })

    @Test fun remakeRequiresYearOrUniqueIdentity() {
        val versions = listOf(Candidate(1, "Dune", 1984), Candidate(2, "Dune", 2021))
        assertNull(match("Dune", versions))
        assertEquals(2, match("Dune (2021) 1080p", versions)?.id)
        assertNull(match("Dune (2000)", versions))
        assertEquals(1, match("Dune", listOf(versions.first(), versions.first()))?.id)
        assertEquals(2, match("EN | Dune (2021) [4K]", versions)?.id)
        assertEquals(3, match("1917 (2019)", listOf(Candidate(3, "1917", 2019)))?.id)
        assertEquals(4, match("2001: A Space Odyssey", listOf(Candidate(4, "2001: A Space Odyssey", 1968)))?.id)
        assertEquals("Title: A Real Subtitle", providerTmdbTitle("Title: A Real Subtitle"))
        assertEquals("It: Chapter Two", providerTmdbTitle("It: Chapter Two"))
    }

    @Test fun accidentalStartsDoNotBecomeSeedsButCompletedShortTitlesDo() {
        assertFalse(meaningfulViewing(30_000, 7_200_000))
        assertFalse(meaningfulViewing(119_999, 120_000))
        assertTrue(meaningfulViewing(600_000, 7_200_000))
        assertTrue(meaningfulViewing(240_000, 1_200_000))
        assertTrue(meaningfulViewing(600_000, 0))
    }

    @Test fun seedsAreProfileScopedBoundedAndExcludeAdultAndLiveContent() {
        fun recent(id: String, profile: String = "a", adult: Boolean = false,
            kind: FavoriteKind = FavoriteKind.MOVIE, at: Long = 1) = RecentItem(kind,
            MediaItem(id, id, null, null, isAdult = adult), profileKey = profile, playedAtMillis = at)
        val entries = listOf(recent("other", "b"), recent("adult", adult = true),
            recent("channel", kind = FavoriteKind.CHANNEL), recent("first", at = 9),
            recent("second", at = 8), recent("third", at = 7), recent("fourth", at = 6))
        assertEquals(listOf("first", "second", "third"), recommendationSeeds("a", entries) {
            PlaybackProgress("key", 900_000, 3_600_000)
        }.map { it.media.id })
        assertTrue(recommendationSeeds("a", entries) { null }.isEmpty())
    }
}
