package com.nikhil.niktv.discovery

import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTileSourceTest {
    private val provider = MediaItem("393989", "Kyunki Saas Bhi Kabhi Bahu Thi", null, null)
    private val tmdb = provider.copy(id = "tmdb-series:6060", externalTmdbId = 6060)

    @Test fun providerArtworkAndTmdbMetadataDoNotChangeItsActualSource() {
        assertEquals(DiscoveryTileSource.IPTV, provider.copy(externalTmdbId = 6060,
            logo = "https://image.tmdb.org/t/p/w342/poster.jpg").discoveryCatalogSource())
    }
    @Test fun explicitTmdbCatalogueEntriesAreLabelledAsTmdb() {
        assertEquals(DiscoveryTileSource.TMDB, tmdb.discoveryCatalogSource())
        assertEquals(DiscoveryTileSource.TMDB, tmdb.copy(id = "tmdb-movie:6060").discoveryCatalogSource())
    }
    @Test fun actualAlternatePlaybackWinsOverItsTmdbOrProviderMetadata() {
        for (root in listOf(provider, tmdb)) {
            val recent = RecentItem(FavoriteKind.SERIES, root,
                alternateTarget = AlternateSourceTarget("Show", true, 6060))
            assertEquals(DiscoveryTileSource.ALTERNATE, recent.discoveryPlaybackSource())
        }
    }
    @Test fun enrichingAnIptvEpisodeDoesNotLabelItsHistoryTmdb() {
        val recent = RecentItem(FavoriteKind.SERIES, provider,
            lastPlayed = provider.copy(id = "393989:2:21", externalTmdbId = 6060, seasonNumber = 2, episodeNumber = 21))
        assertEquals(DiscoveryTileSource.IPTV, recent.discoveryPlaybackSource())
    }
    @Test fun aTmdbCatalogueEntryWithoutPlaybackIsNotClaimedToBeAvailableOnIptv() {
        assertEquals(DiscoveryTileSource.TMDB, RecentItem(FavoriteKind.SERIES, tmdb).discoveryPlaybackSource())
    }
}
