package com.nikhil.niktv.data

import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.WatchedSeries
import org.junit.Assert.*
import org.junit.Test

class NewEpisodeDiscoveryTest {
    private val series = MediaItem("show", "Show", null, null)
    private fun episode(id: String, number: Int) = MediaItem(id, "Episode $number", null, null, seasonNumber = 1, episodeNumber = number)

    @Test fun repeatedResponsesNeverNotifyAgainIncludingPreviouslyDismissedEpisodes() {
        val watched = WatchedSeries("profile", series, knownEpisodeIds = setOf("1"), checkedAtMillis = 10)
        val first = discoverNewEpisodes(watched, listOf(episode("1", 1), episode("2", 2), episode("2", 2)), 20)
        assertEquals(listOf("2"), first.added.map { it.id })
        val dismissed = first.updated.copy(newEpisodes = emptyList())
        assertTrue(discoverNewEpisodes(dismissed, listOf(episode("2", 2)), 30).added.isEmpty())
    }

    @Test fun missingBaselineDoesNotAnnounceTheEntireExistingSeason() {
        val result = discoverNewEpisodes(WatchedSeries("profile", series), listOf(episode("1", 1), episode("2", 2)), 20)
        assertTrue(result.added.isEmpty())
        assertEquals(setOf("1", "2"), result.updated.knownEpisodeIds)
        assertEquals(20L, result.updated.checkedAtMillis)
    }

    @Test fun preservesBacklogAndKeepsLatestEpisodeFirstWithoutForgettingOlderIds() {
        val watched = WatchedSeries("profile", series, knownEpisodeIds = setOf("old", "1"), newEpisodes = listOf(episode("1", 1)), checkedAtMillis = 10)
        val result = discoverNewEpisodes(watched, listOf(episode("3", 3), episode("2", 2)), 20)
        assertEquals(listOf("3", "2", "1"), result.updated.newEpisodes.map { it.id })
        assertTrue("old" in result.updated.knownEpisodeIds)
        assertEquals(watched.key, result.updated.key)
    }

    @Test fun emptyProviderResponseNeverClearsKnownIdsOrBacklog() {
        val watched = WatchedSeries("profile", series, knownEpisodeIds = setOf("1"), newEpisodes = listOf(episode("1", 1)), checkedAtMillis = 10)
        val result = discoverNewEpisodes(watched, emptyList(), 20)
        assertTrue(result.added.isEmpty())
        assertEquals(watched.newEpisodes, result.updated.newEpisodes)
        assertEquals(watched.knownEpisodeIds, result.updated.knownEpisodeIds)
    }

    @Test fun matchingEpisodeIdsInAnotherProfileRemainIndependent() {
        val first = WatchedSeries("first", series, knownEpisodeIds = setOf("1"), checkedAtMillis = 10)
        val second = WatchedSeries("second", series, knownEpisodeIds = setOf("2"), checkedAtMillis = 10)
        assertEquals(listOf("2"), discoverNewEpisodes(first, listOf(episode("2", 2)), 20).added.map { it.id })
        assertTrue(discoverNewEpisodes(second, listOf(episode("2", 2)), 20).added.isEmpty())
    }
}
