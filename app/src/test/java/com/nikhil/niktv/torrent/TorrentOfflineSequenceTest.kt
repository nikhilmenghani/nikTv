package com.nikhil.niktv.torrent

import android.content.Context
import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.discovery.DiscoveryCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, manifest = Config.NONE)
class TorrentOfflineSequenceTest {
    @Test fun sparseDownloadsDoNotDefineTheNextEpisodeAndSeasonsMergeWithoutCrossProfileLeak() = runBlocking {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        val owner = UserScope("7912", "sequence-owner")
        val other = UserScope("7912", "sequence-other")
        val episodes = (1..3).map { TmdbEpisode(1, it, "Episode $it", null, "2000-01-01", null) }
        TorrentOfflineLibrary.rememberSequence(context, owner, 7621, listOf(1, 2), mapOf(1 to episodes))
        TorrentOfflineLibrary.rememberSequence(context, owner, 7621, listOf(1, 2), mapOf(2 to listOf(episodes.first().copy(seasonNumber = 2))))
        val saved = TorrentOfflineLibrary.sequence(context, owner, 7621)
        assertEquals(setOf(1, 2), saved.completeSeasons)
        val next = findNextTorrentEpisode(DiscoveryCard(7621, true, "Series", null, null, null, "2000-01-01"),
            episodes.first(), saved.seasons, saved.episodes.map(TorrentOfflineEpisode::episode),
            { error("Cached seasons need no network") }, { error("Cached complete season needs no network") })
        assertEquals("Downloading only E1 and E3 must still identify E2 as next", 2, next?.episode?.episodeNumber)
        assertTrue(TorrentOfflineLibrary.sequence(context, other, 7621).episodes.isEmpty())
    }
}
