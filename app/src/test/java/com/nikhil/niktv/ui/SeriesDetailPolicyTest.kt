package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackProgress
import com.nikhil.niktv.model.EpisodeSeasonCache
import org.junit.Assert.*
import org.junit.Test

class SeriesDetailPolicyTest {
    @Test fun seasonCountsPreferActualProviderTotalsAndLabelPublicFallbacks() {
        val counts = seasonEpisodeCountLabels(mapOf(9 to 23), mapOf(8 to 24, 9 to 24, 10 to 17), 10, 17, false, false)
        assertEquals("23 episodes", counts[9])
        assertEquals("24 eps · TMDB", counts[8])
        assertEquals("17 episodes", counts[10])
        assertNull(counts[7])
    }
    @Test fun partialOrLoadingSeasonsDoNotBecomeFalseCompleteCounts() {
        assertEquals("40+ loaded", seasonEpisodeCountLabels(emptyMap(), mapOf(1 to 200), 1, 40, true, false)[1])
        assertEquals("24 episodes", seasonEpisodeCountLabels(mapOf(1 to 24), emptyMap(), 1, 0, false, true)[1])
        assertEquals("24 episodes", seasonEpisodeCountLabels(mapOf(1 to 24), emptyMap(), 1, 0, false, false)[1])
        assertNull(seasonEpisodeCountLabels(emptyMap(), emptyMap(), 1, 0, false, true)[1])
        assertEquals("0 episodes", seasonEpisodeCountLabels(emptyMap(), emptyMap(), 1, 0, false, false)[1])
    }
    @Test fun episodeSearchUsesLoadedMetadataAndNumberWithoutRequiringProviderLookups() {
        assertTrue(episodeMatchesSearch("Pilot", "Rachel arrives in New York", 1, ""))
        assertTrue(episodeMatchesSearch("Pilot", "Rachel arrives in New York", 1, "rachel new york"))
        assertTrue(episodeMatchesSearch("Pilot", null, 1, "pilot"))
        assertTrue(episodeMatchesSearch("A long title", null, 17, "17"))
        assertFalse(episodeMatchesSearch("Pilot", "Rachel arrives in New York", 1, "17"))
        assertFalse(episodeMatchesSearch("Pilot", null, null, "99"))
        assertFalse(episodeMatchesSearch("Pilot", null, 1, "missing title"))
    }
    @Test fun repeatedUpReachesTheTopIncludingDescriptionAndSingleSeasonSeries() {
        for (hasSeasons in listOf(true, false)) {
            var target = SeriesDetailFocus.EPISODES
            val visited = mutableListOf(target)
            repeat(8) { adjacentSeriesDetailFocus(target, -1, hasSeasons)?.let { target = it; visited += it } }
            assertEquals(SeriesDetailFocus.TOP, target)
            assertTrue(visited.contains(SeriesDetailFocus.INFO))
            assertEquals(hasSeasons, visited.contains(SeriesDetailFocus.SEASONS))
            assertNull(adjacentSeriesDetailFocus(target, -1, hasSeasons))
        }
    }
    @Test fun offscreenHeroControlsRevealTheTopSlotBeforeRequestingFocus() {
        assertEquals(0, seriesDetailFocusSlot(SeriesDetailFocus.PLAY))
        assertEquals(0, seriesDetailFocusSlot(SeriesDetailFocus.INFO))
        assertEquals(0, seriesDetailFocusSlot(SeriesDetailFocus.TOP))
        assertEquals(1, seriesDetailFocusSlot(SeriesDetailFocus.SEASONS))
        assertEquals(SERIES_EPISODE_START_INDEX, seriesDetailFocusSlot(SeriesDetailFocus.EPISODES))
    }
    @Test fun horizontalEpisodeReturnUsesOneVerticalSlotWithoutLosingTheEpisodeIndex() {
        assertEquals(SERIES_EPISODE_START_INDEX, seriesEpisodeVerticalIndex(80, horizontal = true))
        assertEquals(SERIES_EPISODE_START_INDEX + 80, seriesEpisodeVerticalIndex(80, horizontal = false))
    }
    @Test fun horizontalPaginationUsesTheEpisodeRailAndDoesNotFetchDuringFilteringOrLoading() {
        assertTrue(shouldLoadMoreLandscapeEpisodes(20, 17, true, false, "", true))
        assertFalse(shouldLoadMoreLandscapeEpisodes(20, 4, true, false, "", true))
        assertFalse(shouldLoadMoreLandscapeEpisodes(20, 19, true, false, "", false))
        assertFalse(shouldLoadMoreLandscapeEpisodes(20, 19, true, true, "", true))
        assertFalse(shouldLoadMoreLandscapeEpisodes(20, 19, true, false, "filter", true))
        assertFalse(shouldLoadMoreLandscapeEpisodes(20, 19, false, false, "", true))
    }
    private val series = MediaItem("42", "A series", null, null)
    private val episode = MediaItem("provider-4843", "A new episode", null, null, seasonNumber = 49, episodeNumber = 4843)
    private val own = PlaybackProgress("progress-v2|p|SERIES|series:42|s:49|e:4843|a-new-episode|stream", 60_000, 180_000)

    @Test fun appendingSeasonPagesNeverReplacesTheKnownSeriesIndex() {
        assertEquals(listOf(0, 1, 2, 49), mergeSeriesSeasons(listOf(49, 2, 1, 0), listOf(49), 49))
        assertEquals(listOf(1, 2), mergeSeriesSeasons(listOf(1), emptyList(), 2))
    }
    @Test fun legacyCollapsedSeasonIndexIsRepairedOnceWithoutInvalidatingEpisodeContent() {
        val old = EpisodeSeasonCache("p", "42", 49, listOf(49), listOf(episode), page = 4)
        assertTrue(needsSeriesSeasonIndexRepair(old))
        assertFalse(needsSeriesSeasonIndexRepair(old.copy(seasonIndexValidated = true)))
        assertFalse(needsSeriesSeasonIndexRepair(old.copy(availableSeasons = listOf(48, 49))))
        assertFalse(needsSeriesSeasonIndexRepair(old.copy(page = 1)))
    }

    @Test fun progressUsesPlaybackIdentityWithoutProviderEpisodeId() {
        assertEquals(own, seriesEpisodeProgress(listOf(own), "p", series, episode))
        assertEquals("2 min left", episodeRemainingLabel(own))
    }
    @Test fun progressDoesNotLeakBetweenProfilesSeasonsOrPartialIds() {
        assertNull(seriesEpisodeProgress(listOf(own), "other", series, episode))
        assertNull(seriesEpisodeProgress(listOf(own), "p", series.copy(id = "4"), episode))
        assertNull(seriesEpisodeProgress(listOf(own), "p", series, episode.copy(seasonNumber = 4)))
        assertNull(seriesEpisodeProgress(listOf(own), "p", series, episode.copy(episodeNumber = 484)))
    }
    @Test fun legacyProgressNeedsAnExactEpisodeId() {
        val legacy = own.copy(key = "SERIES:${episode.id}")
        assertEquals(legacy, seriesEpisodeProgress(listOf(legacy), "p", series, episode))
        assertNull(seriesEpisodeProgress(listOf(legacy), "p", series, episode.copy(id = "provider-484")))
    }
    @Test fun longTitleProgressUsesTheSameBoundAsPlaybackStorage() {
        val longEpisode = episode.copy(title = "a".repeat(260))
        val saved = own.copy(key = "progress-v2|p|SERIES|series:42|s:49|e:4843|${longEpisode.title}|stream".take(240))
        assertEquals(saved, seriesEpisodeProgress(listOf(saved), "p", series, longEpisode))
    }
    @Test fun progressStatusDoesNotInventDurationOrResumeForCompletedEpisodes() {
        assertNull(episodeRemainingLabel(own.copy(durationMillis = 0)))
        assertNull(episodeRemainingLabel(own.copy(positionMillis = 0)))
        assertEquals("Watched", episodeRemainingLabel(own.copy(positionMillis = 170_000)))
    }
    @Test fun pagingStartsNearLoadedBoundaryAndNotOnEntryOrSupplementarySections() {
        fun check(last: Int, more: Boolean = true, loading: Boolean = false, query: String = "", moved: Boolean = true) =
            shouldLoadMoreSeriesEpisodes(45, last, more, loading, query, moved)
        assertFalse(check(5))
        assertTrue(check(43))
        assertFalse(check(43, loading = true))
        assertFalse(check(43, more = false))
        assertFalse(check(43, query = "4843"))
        assertFalse(check(43, moved = false))
        assertFalse(check(49)) // Jumping to Related must not load every intervening provider page.
    }
    @Test fun countsDistinguishLoadedPagesAndLocalSearch() {
        assertEquals("45 episodes loaded", seriesEpisodeCountLabel(45, true, false))
        assertEquals("45 episodes", seriesEpisodeCountLabel(45, false, false))
        assertEquals("1 match in loaded episodes", seriesEpisodeCountLabel(1, true, true))
    }
}
