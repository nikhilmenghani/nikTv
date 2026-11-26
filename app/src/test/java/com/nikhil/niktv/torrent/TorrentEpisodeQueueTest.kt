package com.nikhil.niktv.torrent

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.discovery.DiscoveryCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TorrentEpisodeQueueTest {
    private val show = DiscoveryCard(1, true, "Example Show", date = "2020-01-01")
    private val today = "2026-11-25"
    private fun episode(season: Int, number: Int, date: String? = "2020-01-01") =
        TmdbEpisode(season, number, null, null, date, null)

    @Test fun nextEpisodeUsesSortedMetadataWithoutAssumingContiguousNumbers() = runTest {
        val current = episode(2, 3)
        val result = findNextTorrentEpisode(show, current, listOf(1, 2, 3),
            listOf(episode(2, 8), current, episode(2, 5), episode(2, 5)),
            { error("Already have seasons") }, { error("Already have current season") }, today)
        assertEquals(2, result!!.episode.seasonNumber)
        assertEquals(5, result.episode.episodeNumber)
        assertEquals(show, result.card)
    }

    @Test fun lastEpisodeMovesToTheFirstAvailableEpisodeOfTheNextSeason() = runTest {
        val calls = mutableListOf<Int>()
        val current = episode(1, 10)
        val result = findNextTorrentEpisode(show, current, listOf(3, 0, 2, 1, 2), listOf(current),
            { error("Already have seasons") }, { season ->
                calls += season
                when (season) { 2 -> emptyList(); 3 -> listOf(episode(3, 4), episode(3, 2)); else -> error("Unexpected season") }
            }, today)
        assertEquals(listOf(2, 3), calls)
        assertEquals(episode(3, 2), result!!.episode)
    }

    @Test fun absentLoadedMetadataIsFetchedForTheActualCurrentSeason() = runTest {
        val calls = mutableListOf<Int>()
        val result = findNextTorrentEpisode(show, episode(5, 1), emptyList(), listOf(episode(4, 2)),
            { error("No need for seasons when current has a successor") }, { season ->
                calls += season
                listOf(episode(5, 2), episode(5, 1))
            }, today)
        assertEquals(listOf(5), calls)
        assertEquals(episode(5, 2), result!!.episode)
    }

    @Test fun missingSeasonListIsFetchedOnlyAfterTheCurrentSeasonFinishes() = runTest {
        var seasonLoads = 0
        val current = episode(2, 12)
        val result = findNextTorrentEpisode(show, current, emptyList(), listOf(current),
            { seasonLoads++; listOf(0, 1, 2, 4) }, { season -> listOf(episode(season, 1)) }, today)
        assertEquals(1, seasonLoads)
        assertEquals(episode(4, 1), result!!.episode)
    }

    @Test fun specialsInvalidNumbersAndFutureEpisodesDoNotEnterTheQueue() = runTest {
        val current = episode(1, 2)
        val result = findNextTorrentEpisode(show, current, listOf(0, 1), listOf(
            current, episode(0, 3), episode(1, -1), episode(1, 0), episode(1, 3, "2026-11-26")),
            { error("Already have seasons") }, { error("No later seasons") }, today)
        assertNull(result)
    }

    @Test fun unknownOptionalEpisodeMetadataDoesNotPreventPlayback() = runTest {
        val current = episode(1, 1)
        val next = episode(1, 2, null)
        assertEquals(next, findNextTorrentEpisode(show, current, listOf(1), listOf(current, next),
            { emptyList() }, { emptyList() }, today)!!.episode)
    }

    @Test fun extremeEpisodeNumbersCannotOverflowIntoAnotherEpisode() = runTest {
        val current = episode(1, Int.MAX_VALUE)
        val result = findNextTorrentEpisode(show, current, listOf(1), listOf(episode(1, 1), current),
            { emptyList() }, { emptyList() }, today)
        assertNull(result)
    }

    @Test fun nonSeriesAndSpecialsDoNotStartMetadataRequests() = runTest {
        suspend fun resolve(card: DiscoveryCard, current: TmdbEpisode) = findNextTorrentEpisode(card, current,
            emptyList(), emptyList(), { error("Must not fetch") }, { error("Must not fetch") }, today)
        assertNull(resolve(show.copy(series = false), episode(1, 1)))
        assertNull(resolve(show, episode(0, 1)))
        assertNull(resolve(show, episode(1, 0)))
    }

    @Test fun cancellationPropagatesWithoutTurningIntoAnEmptyQueue() = runTest {
        try {
            findNextTorrentEpisode(show, episode(1, 1), emptyList(), emptyList(),
                { emptyList() }, { throw CancellationException("Prefetch cancelled") }, today)
            fail("Cancellation should propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun prefetchWaitsForAllSourcesAndUsesTheBestSeededPlayableResult() {
        val a = TorrentSource("a".repeat(40), "A", "magnet:?xt=urn:btih:${"a".repeat(40)}", null, 1000, 2, "1080p", "A")
        val b = a.copy(infoHash = "b".repeat(40), title = "B", seeders = 12, quality = "720p")
        val dead = a.copy(infoHash = "c".repeat(40), seeders = 0)
        assertNull(selectTorrentPrefetchSource(TorrentSearchSnapshot(listOf(a, b), pending = setOf("Pending"))))
        assertEquals(b, selectTorrentPrefetchSource(TorrentSearchSnapshot(listOf(a, b, dead))))
        assertNull(selectTorrentPrefetchSource(TorrentSearchSnapshot(listOf(dead))))
    }

    @Test fun automaticFileSelectionCannotChooseAnUnrelatedOrAmbiguousEpisode() {
        val target = episode(2, 3)
        val right = TorrentVideoFile(3, "Show/Show.S02E03.mkv", 1000)
        val wrong = TorrentVideoFile(4, "Show/Show.S02E04.mkv", 9000)
        val otherSeason = TorrentVideoFile(5, "Show/Show.S01E03.mkv", 10000)
        val sample = TorrentVideoFile(6, "Show/Show.S02E03.sample.mkv", 200)
        assertEquals(right, selectTorrentEpisodeFile(listOf(wrong, otherSeason, sample, right), target))
        assertNull(selectTorrentEpisodeFile(listOf(wrong, otherSeason), target))
        assertNull(selectTorrentEpisodeFile(listOf(right, right.copy(index = 9, name = "Show.S02E03.720p.mp4")), target))
        assertNull(selectTorrentEpisodeFile(listOf(TorrentVideoFile(1, "video.mp4", 1000)), target))
    }
}
