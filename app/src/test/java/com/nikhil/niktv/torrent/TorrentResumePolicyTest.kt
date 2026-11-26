package com.nikhil.niktv.torrent

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.discovery.DiscoveryCard
import org.junit.Assert.*
import org.junit.Test

class TorrentResumePolicyTest {
    private val card = DiscoveryCard(100, true, "Series", null, null, null, "2000-01-01")
    private val ep = TmdbEpisode(2, 3, "Episode", null, null, null)
    private val file = TorrentVideoFile(4, "Pack/Series.S02E03.mkv", 1_000_000)
    private val hash = "a".repeat(40)
    private val saved = torrentLibraryEntry(card, ep, file, hash, positionMillis = 50_000)
    @Test fun sameTorrentAndEpisodeFileResumes() { assertTrue(torrentCanResume(saved, card, ep, file, hash)) }
    @Test fun differentTorrentStartsFreshEvenForSameEpisode() { assertFalse(torrentCanResume(saved, card, ep, file, "b".repeat(40))) }
    @Test fun anotherEpisodeInTheSamePackCannotInheritTime() {
        assertFalse(torrentCanResume(saved, card, ep.copy(episodeNumber = 4), file, hash))
        assertFalse(torrentCanResume(saved, card, ep, file.copy(name = "Series.S02E04.mkv"), hash))
    }
    @Test fun unknownSourceOrChangedFileSizeCannotResumeAutomatically() {
        assertFalse(torrentCanResume(saved.copy(infoHash = null), card, ep, file, hash))
        assertFalse(torrentCanResume(saved, card, ep, file.copy(sizeBytes = file.sizeBytes + 1), hash))
    }
}
