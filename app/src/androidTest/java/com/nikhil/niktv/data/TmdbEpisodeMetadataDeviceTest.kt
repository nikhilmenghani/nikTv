package com.nikhil.niktv.data

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import com.nikhil.niktv.ui.tmdbSeasonForProviderSeason
import com.nikhil.niktv.ui.TmdbEpisodeMetadataIndex

/** Public TMDB read-only check. Does not query IPTV, open UI, play video, or modify user data. */
class TmdbEpisodeMetadataDeviceTest {
    @Test fun compareSparseDailySerialEpisodeRecords() = runBlocking {
        RemoteCredentials.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val tmdb = TmdbClient()
        val batch = tmdb.seasonEpisodes(8630, 1).associateBy { it.episodeNumber }
        for (number in 4851..4856) {
            val episode = tmdb.episodeMetadata(8630, 1, number)
            Log.i("NikTvTmdbEpisodeCheck", "TMKOC E$number: seasonTitle=${batch[number]?.name}; directTitle=${episode?.name}; " +
                "seasonSynopsis=${!batch[number]?.overview.isNullOrBlank()}; directSynopsis=${!episode?.overview.isNullOrBlank()}; " +
                "seasonArtwork=${!batch[number]?.stillUrl.isNullOrBlank()}; directArtwork=${!episode?.stillUrl.isNullOrBlank()}")
        }
        val store = ProfileStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val seriesIds = store.tmdbMappings.first().filter { it.tmdbId == 8630 }.map { it.media.id }.toSet()
        store.episodeSeasonCaches.first().filter { it.tmdbSeriesId == 8630 || it.seriesId in seriesIds }.forEach { cache ->
            cache.iptvEpisodes.filter { it.episodeNumber in 4851..4856 }.forEach { raw ->
                val shown = cache.episodes.firstOrNull { it.id == raw.id }
                Log.i("NikTvTmdbEpisodeCheck", "${if (cache.profileKey.contains("XTREAM")) "Xtream" else "Stalker"} E${raw.episodeNumber}: " +
                    "rawTitle=${raw.title}; cachedTitle=${shown?.title}; keys=${cache.tmdbEpisodeKeys.size}; " +
                    "metadataAgeMinutes=${(System.currentTimeMillis() - cache.tmdbCachedAtMillis) / 60_000}")
            }
        }
    }
    @Test fun providerSeason49FindsRealCanonicalEpisode4850() = runBlocking {
        RemoteCredentials.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val tmdb = TmdbClient()
        val seasons = tmdb.episodeSeasons(8630)
        val canonical = tmdbSeasonForProviderSeason(49, seasons)
        assertEquals(1, canonical)
        val episodes = tmdb.seasonEpisodes(8630, requireNotNull(canonical))
        val provider = MediaItem("fixture", "Taarak Mehta Ka Ooltah Chashmah - S49E4850", null, null,
            seasonNumber = 49, episodeNumber = 4850)
        val matched = TmdbEpisodeMetadataIndex(episodes).select(provider)
        assertNotNull("Provider E4850 must match the real TMDB canonical episode", matched)
        assertEquals(4850, matched!!.episodeNumber)
        assertFalse(matched.name.isNullOrBlank())
        Log.i("NikTvTmdbEpisodeCheck", "TMKOC S49E4850 -> S${matched.seasonNumber}E${matched.episodeNumber}: " +
            "name=${matched.name}, date=${matched.airDate}, description=${!matched.overview.isNullOrBlank()}, artwork=${!matched.stillUrl.isNullOrBlank()}")
        Unit
    }
    @Test fun publicSeriesLookupReturnsEpisodePresentation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RemoteCredentials.initialize(context)
        val tmdb = TmdbClient()
        assertTrue("TMDB configuration must be present on this test device", tmdb.configured)
        val show = tmdb.confidentlyMatchSeries(MediaItem("fixture", "Friends", null, null))
        assertEquals(1668, show?.id)
        val episodes = tmdb.seasonEpisodes(requireNotNull(show).id, 1)
        assertEquals(24, episodes.size)
        val first = episodes.single { it.seasonNumber == 1 && it.episodeNumber == 1 }
        assertFalse(first.name.isNullOrBlank())
        assertFalse(first.overview.isNullOrBlank())
        assertFalse(first.stillUrl.isNullOrBlank())
        Log.i("NikTvTmdbEpisodeCheck", "TMDB Friends S1: episodes=${episodes.size}, E1=${first.name}, description=true, artwork=true")
        Unit
    }
}
