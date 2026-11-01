package com.nikhil.niktv.ui

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.EpisodeSeasonCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeMetadataTest {
    @Test fun oldGenericCacheRepairsProviderTitlesWithoutInvalidatingAnyPagesOrTimestamps() {
        val raw = MediaItem("4851", "Episode 4851. 2026-10-26 - A meaningful provider title", null, "command",
            seasonNumber = 49, episodeNumber = 4851)
        val old = EpisodeSeasonCache("profile", "series", 49, listOf(48, 49), listOf(raw.copy(title = "Episode 4851")),
            page = 8, hasMore = true, metadataVersion = 4, cachedAtMillis = 1234,
            iptvEpisodes = listOf(raw), tmdbEpisodeKeys = setOf("already-checked"), tmdbSeriesId = 8630, tmdbCachedAtMillis = 5678)
        val fixed = repairEpisodePresentationCache(old, "Series")
        assertEquals(old.copy(episodes = listOf(raw)), fixed)
        assertEquals(fixed, repairEpisodePresentationCache(fixed, "Series"))
    }

    @Test fun cacheRepairPreservesMeaningfulTmdbTitlesAndCannotInventMissingProviderData() {
        val raw = MediaItem("1", "Series S1E1", null, null, seasonNumber = 1, episodeNumber = 1)
        val cache = EpisodeSeasonCache("p", "s", 1, listOf(1), listOf(raw.copy(title = "Pilot")), iptvEpisodes = listOf(raw))
        assertEquals(cache, repairEpisodePresentationCache(cache, "Series"))
        val generic = cache.copy(episodes = listOf(raw.copy(title = "Episode 1")))
        assertEquals(generic, repairEpisodePresentationCache(generic, "Series"))
    }

    @Test fun refreshMessageDistinguishesRestoringProviderTitlesFromTmdbResults() {
        val raw = MediaItem("1", "Episode 1. 1994-09-22 - A provider title", null, null)
        val generic = raw.copy(title = "Episode 1")
        val sparse = TmdbEpisode(1, 1, "Episode 1", null, "1994-09-22", null)
        org.junit.Assert.assertTrue(episodeTmdbRefreshMessage(raw, generic, raw, sparse, "Series").startsWith("Restored the episode title from your IPTV provider."))
        org.junit.Assert.assertTrue(episodeTmdbRefreshMessage(raw, raw, raw, sparse, "Series").startsWith("Kept the episode title from your IPTV provider."))
        assertEquals("Updated TMDB title, description for this episode.", episodeTmdbRefreshMessage(raw, raw, raw,
            sparse.copy(name = "Pilot", overview = "Synopsis"), "Series"))
    }
    @Test fun missingProviderSeasonUsesOnlyAnUnambiguousSingleCanonicalSeason() {
        assertEquals(1, tmdbSeasonForProviderSeason(49, listOf(0, 1)))
        assertEquals(2, tmdbSeasonForProviderSeason(2, listOf(1, 2)))
        assertNull(tmdbSeasonForProviderSeason(49, listOf(1, 2)))
        assertNull(tmdbSeasonForProviderSeason(49, emptyList()))
    }

    @Test fun indexRejectsDuplicateOrdinalsAndUsesAirDateBeforeNumber() {
        val index = TmdbEpisodeMetadataIndex(listOf(
            TmdbEpisode(1, 4850, "Original", "Description", "2026-10-24", null),
            TmdbEpisode(1, 4850, "Duplicate", null, "2026-10-25", null)))
        val provider = MediaItem("iptv", "Series S49E4850", null, null, seasonNumber = 49, episodeNumber = 4850)
        assertNull(index.select(provider))
        assertEquals("Original", index.select(provider.copy(episodeAirDate = "2026-10-24"))?.name)
    }
    private val episodes = listOf(
        TmdbEpisode(10, 15, "The One Where Estelle Dies", null, "2004-04-22", null),
        TmdbEpisode(10, 16, "The One with Rachel's Going Away Party", null, "2004-04-29", null)
    )

    @Test
    fun airDateWinsWhenProviderEpisodeNumberIsWrong() {
        val providerEpisode = MediaItem(
            id = "provider-16",
            title = "Friends S10 E16",
            logo = null,
            command = null,
            seasonNumber = 10,
            episodeNumber = 15,
            episodeAirDate = "2004-04-29"
        )

        assertEquals(
            "The One with Rachel's Going Away Party",
            selectTmdbEpisodeMetadata(providerEpisode, episodes)?.name
        )
    }

    @Test
    fun episodeNumberRemainsTheFallbackForGenericProviderTitles() {
        val providerEpisode = MediaItem(
            id = "provider-16",
            title = "Season 10 Episode 16",
            logo = null,
            command = null,
            seasonNumber = 10,
            episodeNumber = 16
        )

        assertEquals(
            "The One with Rachel's Going Away Party",
            selectTmdbEpisodeMetadata(providerEpisode, episodes)?.name
        )
    }
}
