package com.nikhil.niktv.torrent

import org.junit.Assert.*
import org.junit.Test

class TorrentEpisodePrefetcherTest {
    private val mib = 1024L * 1024
    private fun current(buffer: Long, done: Long = 20 * mib, total: Long = 100 * mib) =
        TorrentStreamStatus(stage = "Streaming", downloadedBytes = done, totalBytes = total, bufferedBytes = buffer)

    @Test fun startsOnlyAfterSixMiBAndDoesNotFlapInTheMiddleBand() {
        assertEquals(TorrentPrefetchGate.PAUSE, torrentPrefetchGate(current(6 * mib - 1), true))
        assertEquals(TorrentPrefetchGate.RUN, torrentPrefetchGate(current(6 * mib), true))
        assertEquals(TorrentPrefetchGate.RUN, torrentPrefetchGate(current(4 * mib), false))
        assertEquals(TorrentPrefetchGate.PAUSE, torrentPrefetchGate(current(4 * mib), true))
        assertEquals(TorrentPrefetchGate.PAUSE, torrentPrefetchGate(current(3 * mib - 1), false))
        assertEquals(TorrentPrefetchGate.RUN, torrentPrefetchGate(current(3 * mib), false))
    }

    @Test fun completedCurrentTorrentNeverNeedsCompetingNetworkBandwidth() {
        assertEquals(TorrentPrefetchGate.RUN, torrentPrefetchGate(current(0, 100 * mib), true))
    }

    @Test fun foregroundPromotionReusesPreparationEvenWhenCurrentPlaybackHasEnded() {
        assertEquals(TorrentPrefetchGate.RUN, torrentPrefetchGate(current(0), true, foreground = true))
        assertEquals(TorrentPrefetchGate.RUN, torrentPrefetchGate(current(0).copy(stage = "Closed"), true, foreground = true))
        assertEquals(TorrentPrefetchGate.RUN, torrentPrefetchGate(null, true, foreground = true))
    }

    @Test fun missingFailedClosedOrColdCurrentPlaybackCancelsPreparation() {
        assertEquals(TorrentPrefetchGate.CANCEL, torrentPrefetchGate(null, true))
        assertEquals(TorrentPrefetchGate.CANCEL, torrentPrefetchGate(TorrentStreamStatus(), true))
        assertEquals(TorrentPrefetchGate.CANCEL, torrentPrefetchGate(current(20 * mib).copy(stage = "Closed"), false))
        assertEquals(TorrentPrefetchGate.CANCEL, torrentPrefetchGate(current(20 * mib).copy(error = "Stream dropped"), false))
        assertEquals(TorrentPrefetchGate.CANCEL, torrentPrefetchGate(current(20 * mib, total = 0), false))
    }

    @Test fun sameQualityIsPreferredOnlyForProtectedBackgroundPreparation() {
        fun source(hash: Char, seeds: Int, quality: String) = TorrentSource(hash.toString().repeat(40), "Episode", "magnet:?xt=urn:btih:${hash.toString().repeat(40)}", null, 1000, seeds, quality, "Fixture")
        val same = source('a', 3, "1080p")
        val mostSeeds = source('b', 30, "720p")
        val dead = source('c', 0, "1080p")
        val snapshot = TorrentSearchSnapshot(listOf(mostSeeds, same, dead))
        assertEquals(same, torrentPrefetchCandidates(snapshot, "1080p", true).first())
        assertEquals(mostSeeds, torrentPrefetchCandidates(snapshot, "1080p", false).first())
        assertEquals(2, torrentPrefetchCandidates(snapshot, "1080p", true).size)
        assertTrue(torrentPrefetchCandidates(snapshot.copy(pending = setOf("Source")), "1080p", true).isEmpty())
    }
}
