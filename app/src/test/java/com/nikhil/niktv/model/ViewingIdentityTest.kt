package com.nikhil.niktv.model

import org.junit.Assert.*
import org.junit.Test

class ViewingIdentityTest {
    @Test fun providerIdentitySurvivesRenameAndCredentialRotation() {
        val item = MediaItem("provider-42", "Original", null, "https://host/secret-a")
        assertEquals(providerProgressKey("portal-uuid", item, CatalogType.MOVIES, null),
            providerProgressKey("portal-uuid", item.copy(title = "Renamed", command = "https://other/secret-b"), CatalogType.MOVIES, null))
        assertFalse(providerProgressKey("portal-uuid", item, CatalogType.MOVIES, null).contains("secret"))
    }

    @Test fun equalTitlesNeverMergeDifferentProviderItemsOrPortals() {
        val first = MediaItem("one", "Same title", null, null)
        val second = first.copy(id = "two")
        assertNotEquals(providerProgressKey("portal-a", first, CatalogType.MOVIES, null),
            providerProgressKey("portal-a", second, CatalogType.MOVIES, null))
        assertNotEquals(providerProgressKey("portal-a", first, CatalogType.MOVIES, null),
            providerProgressKey("portal-b", first, CatalogType.MOVIES, null))
    }

    @Test fun episodeIdentityIncludesSeriesSeasonEpisodeAndProviderEpisode() {
        val series = MediaItem("series-1", "Series", null, null)
        val episode = MediaItem("episode-1", "Episode", null, null, seasonNumber = 1, episodeNumber = 2)
        val key = providerProgressKey("portal", episode, CatalogType.SERIES, series)
        assertNotEquals(key, providerProgressKey("portal", episode.copy(seasonNumber = 2), CatalogType.SERIES, series))
        assertNotEquals(key, providerProgressKey("portal", episode.copy(id = "episode-2"), CatalogType.SERIES, series))
        assertNotEquals(key, providerProgressKey("portal", episode, CatalogType.SERIES, series.copy(id = "series-2")))
    }

    @Test fun providerIdentityIsNeverTruncatedIntoAnotherItem() {
        val prefix = "x".repeat(400)
        assertNotEquals(providerProgressKey("portal", MediaItem(prefix + "a", "A", null, null), CatalogType.MOVIES, null),
            providerProgressKey("portal", MediaItem(prefix + "b", "B", null, null), CatalogType.MOVIES, null))
    }

    @Test fun completionRewindAndRestartAreExplicitActions() {
        val previous = PlaybackProgress("key", 50_000, 100_000)
        assertEquals(ViewingAction.COMPLETED, playbackSample(previous, "key", 98_000, 100_000).action)
        assertEquals(ViewingAction.REWIND, playbackSample(previous, "key", 25_000, 100_000).action)
        assertEquals(ViewingAction.RESTART, playbackSample(previous, "key", 0, 100_000).action)
        assertEquals(ViewingAction.PROGRESS, playbackSample(previous, "key", 55_000, 100_000).action)
    }

    @Test fun introIdentityUsesLogicalPortalAcrossCredentialChanges() {
        val item = MediaItem("movie-42", "Movie", null, null)
        val first = PlayingMedia(item, "https://fixture.invalid/one", CatalogType.MOVIES,
            profileKey = "local-credentials-one", logicalPortalId = "stable-portal")
        val second = first.copy(profileKey = "local-credentials-two", url = "https://fixture.invalid/two")
        assertEquals(com.nikhil.niktv.ui.introTarget(first)?.episodeKey,
            com.nikhil.niktv.ui.introTarget(second)?.episodeKey)
        assertNotEquals(com.nikhil.niktv.ui.introTarget(first)?.episodeKey,
            com.nikhil.niktv.ui.introTarget(first.copy(logicalPortalId = "other-portal"))?.episodeKey)
    }
}
