package com.nikhil.niktv.torrent

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class TorrentOfflineIdentityTest {
    private val movie = DiscoveryCard(100, false, "Movie", null, null, null, "2000-01-01")
    private val series = movie.copy(series = true)
    private val episode = TmdbEpisode(2, 3, "Episode", null, "2000-01-01", null)
    private fun download(card: DiscoveryCard, ep: TmdbEpisode? = null) = OfflineMediaDownload(
        requestId = "torrent-download:fixture", profileKey = TORRENT_OFFLINE_PROFILE,
        catalogType = if (card.series) CatalogType.SERIES else CatalogType.MOVIES,
        media = MediaItem(torrentOfflineMediaId(card, ep), card.title, null, null))

    @Test fun movieAndSeriesWithSameTmdbNumberNeverShareAFile() {
        assertFalse(torrentOfflineMatches(download(movie), series, episode))
        assertFalse(torrentOfflineMatches(download(series, episode), movie, null))
    }
    @Test fun episodesAndSeasonsMustMatchExactly() {
        val local = download(series, episode)
        assertTrue(torrentOfflineMatches(local, series, episode))
        assertFalse(torrentOfflineMatches(local, series, episode.copy(episodeNumber = 4)))
        assertFalse(torrentOfflineMatches(local, series, episode.copy(seasonNumber = 1)))
        assertFalse(torrentOfflineMatches(local, series.copy(id = 101), episode))
    }
    @Test fun iptvDownloadsCannotSatisfyTorrentPlayback() {
        assertFalse(torrentOfflineMatches(download(movie).copy(profileKey = "iptv-profile"), movie, null))
        assertFalse(torrentOfflineMatches(download(movie).copy(catalogType = CatalogType.SERIES), movie, null))
    }
    @Test(expected = IllegalArgumentException::class) fun seasonWithoutEpisodeCannotBecomeAMovieIdentity() {
        torrentOfflineMediaId(series, null)
    }
}
