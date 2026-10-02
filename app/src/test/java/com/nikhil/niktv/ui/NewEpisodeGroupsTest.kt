package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class NewEpisodeGroupsTest {
    private val series = MediaItem("show", "Daily show", null, null)
    private fun episode(id: String, season: Int, number: Int) =
        MediaItem(id, "Episode $number", null, "play-$id", seasonNumber = season, episodeNumber = number)

    @Test fun backlogBecomesOneSeriesWithEarliestEpisodeFirstAcrossSeasons() {
        val episodes = listOf(episode("3", 2, 1), episode("2", 1, 11), episode("1", 1, 2))
        val group = newEpisodeGroups(listOf(WatchedSeries("profile", series, newEpisodes = episodes)), "profile").single()
        assertEquals(listOf("1", "2", "3"), group.episodes.map { it.id })
        assertEquals("play-1", group.episodes.first().command)
        assertEquals("3", group.latest.id)
        assertEquals("play-3", group.latest.command)
    }

    @Test fun otherProfilesAndCaughtUpSeriesAreExcludedWithoutDroppingBacklog() {
        val backlog = (1..100).map { episode("$it", 1, it) }
        val groups = newEpisodeGroups(listOf(
            WatchedSeries("profile", series, newEpisodes = backlog.reversed()),
            WatchedSeries("other", series, newEpisodes = listOf(episode("other", 1, 1))),
            WatchedSeries("profile", series.copy(id = "caught-up"))
        ), "profile")
        assertEquals(1, groups.size)
        assertEquals(100, groups.single().episodes.size)
    }

    @Test fun duplicateRowsMergeButDifferentSeriesKeepTheirEpisodeIdentity() {
        val ep = episode("shared-id", 1, 1)
        val groups = newEpisodeGroups(listOf(
            WatchedSeries("profile", series, newEpisodes = listOf(ep, ep)),
            WatchedSeries("profile", series, newEpisodes = listOf(ep)),
            WatchedSeries("profile", series.copy(id = "other-show"), newEpisodes = listOf(ep))
        ), "profile")
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.episodes.size == 1 })
        assertNotEquals(groups[0].key, groups[1].key)
    }

    @Test fun markingNextWatchedUpdatesCountAndNextEpisodeWithoutChangingOtherSeries() {
        val first = episode("1", 1, 1)
        val next = episode("2", 1, 2)
        val watched = WatchedSeries("profile", series, newEpisodes = listOf(next, first))
        val updated = newEpisodeGroups(listOf(watched.copy(newEpisodes = listOf(next))), "profile").single()
        assertEquals(1, updated.episodes.size)
        assertEquals(next, updated.episodes.first())
        assertTrue(newEpisodeGroups(listOf(watched.copy(newEpisodes = emptyList())), "profile").isEmpty())
    }
}
