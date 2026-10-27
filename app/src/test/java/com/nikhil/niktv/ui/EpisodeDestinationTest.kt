package com.nikhil.niktv.ui

import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EpisodeDestinationTest {
    @Test fun partialRefreshRetainsCompleteSeasonAndNewEpisodes() {
        val old = (1..500).map { episode(it) }
        val cache = EpisodeSeasonCache("profile", "series", 1, listOf(1), old, page = 25,
            hasMore = false, iptvEpisodes = old)
        val result = page(481..501, 1, true).retainLoadedEpisodes(cache, "profile", "series", old)
        assertEquals(501, result.episodes.size)
        assertEquals(25, result.page)
        assertFalse(result.hasMore)
        assertTrue(result.episodes.any { it.episodeNumber == 250 })
        assertEquals(21, page(481..501, 1, true).retainLoadedEpisodes(cache, "other", "series", old).episodes.size)
        assertEquals(21, page(481..501, 1, true).copy(selectedSeason = 2)
            .retainLoadedEpisodes(cache, "profile", "series", old).episodes.size)
        // A complete provider response remains authoritative, including removals.
        assertEquals(20, page(1..20, 1, false).retainLoadedEpisodes(cache, "profile", "series", old).episodes.size)
    }
    private fun episode(n: Int, season: Int = 1) = MediaItem("$season:$n", "Episode $n", null, null,
        seasonNumber = season, episodeNumber = n)
    private fun page(numbers: IntRange, page: Int, more: Boolean) =
        EpisodeSeasonResult(numbers.map(::episode), listOf(1), 1, page, more)

    @Test fun earliestLoadsBeyondFiveHundredWithoutAPageLimit() = runTest {
        val requests = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val (loaded, target) = loadEpisodeDestination(page(581..600, 1, true), null, { previous ->
            val next = previous.page + 1
            requests += next
            page((601 - next * 20)..(620 - next * 20), next, next < 30)
        }, { progress += it.episodes.size })
        assertEquals(1, target!!.episodeNumber)
        assertEquals(600, loaded.episodes.size)
        assertEquals((2..30).toList(), requests)
        assertEquals(600, progress.last())
    }

    @Test fun exactNumberStopsAsSoonAsItArrives() = runTest {
        var requests = 0
        val (loaded, target) = loadEpisodeDestination(page(481..500, 1, true), 465, {
            requests++; page(461..480, 2, true)
        }, {})
        assertEquals(1, requests)
        assertEquals(465, target!!.episodeNumber)
        assertTrue(loaded.hasMore)
    }

    @Test fun cachedTargetDoesNotMakeAnyRequest() = runTest {
        val (_, target) = loadEpisodeDestination(page(1..600, 30, false), 2,
            { error("Must use cache") }, { error("No new page") })
        assertEquals(2, target!!.episodeNumber)
    }

    @Test fun completeCacheCanSortWithoutNetwork() = runTest {
        val (_, target) = loadEpisodeDestination(page(1..600, 30, false), null,
            { error("Must use cache") }, {})
        assertEquals(1, target!!.episodeNumber)
    }

    @Test fun missingEpisodeDoesNotFallBackToAnAdjacentNumber() = runTest {
        val (_, target) = loadEpisodeDestination(page(3..5, 1, true), 2, { page(6..9, 2, false) }, {})
        assertNull(target)
    }

    @Test fun repeatedPageReportsFailureInsteadOfClaimingEarliest() = runTest {
        try {
            loadEpisodeDestination(page(481..500, 1, true), null, { page(481..500, 2, true) }, {})
            fail("Repeated pages must terminate the request")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("repeated")) }
    }

    @Test fun aFinalEmptyPagePreservesLoadedEpisodes() = runTest {
        val (loaded, target) = loadEpisodeDestination(page(1..5, 1, true), null, { page(1..0, 2, false) }, {})
        assertEquals(5, loaded.episodes.size)
        assertEquals(1, target!!.episodeNumber)
    }

    @Test fun neverCrossesSeasonBoundaries() = runTest {
        try {
            loadEpisodeDestination(page(481..500, 1, true), 1, {
                EpisodeSeasonResult(listOf(episode(1, 2)), listOf(1, 2), 2, 2, false)
            }, { error("Wrong season must never be published") })
            fail("Wrong season must fail")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("different season")) }
    }

    @Test fun cancellationRejectsLateResultsEvenIfTransportIgnoresCancellation() = runTest {
        val reply = CompletableDeferred<EpisodeSeasonResult>()
        var published = false
        val job = launch {
            loadEpisodeDestination(page(481..500, 1, true), 1,
                { withContext(NonCancellable) { reply.await() } }, { published = true })
        }
        testScheduler.runCurrent()
        job.cancel()
        reply.complete(page(1..20, 2, false))
        job.join()
        assertFalse(published)
    }

    @Test fun overlappingPageIsDeduplicatedAndUsesEpisodeIdentity() = runTest {
        val (loaded, target) = loadEpisodeDestination(page(3..5, 1, true), 1, { page(1..4, 2, false) }, {})
        assertEquals(5, loaded.episodes.size)
        assertEquals("1:1", target!!.id)
    }

    @Test fun nonAdvancingPageCannotLoopForever() = runTest {
        try {
            loadEpisodeDestination(page(3..5, 1, true), 1, { page(1..2, 1, true) }, {})
            fail("Page must advance")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("advance")) }
    }
}
