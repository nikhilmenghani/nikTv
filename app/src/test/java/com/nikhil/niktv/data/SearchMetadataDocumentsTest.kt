package com.nikhil.niktv.data

import com.nikhil.niktv.model.BrowseCatalogCache
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SearchMetadataDocumentsTest {
    private val profile = PortalProfile(
        name = "Private profile",
        portalUrl = "https://provider.example",
        macAddress = "00:11:22:33:44:55"
    )

    @Test
    fun build_stripsPlaybackAndArtworkData() {
        val media = MediaItem(
            id = "series-42",
            title = "Friends",
            logo = "https://provider.example/private/logo?token=secret",
            command = "ffmpeg https://provider.example/play?token=secret",
            description = "provider-only description",
            portalCategoryId = "netflix",
            externalTmdbId = 1668
        )
        val browse = BrowseCatalogCache(
            profileKey = profile.cacheKey(),
            type = CatalogType.SERIES,
            cachedAtMillis = 1,
            categories = listOf(Category("netflix", "Netflix", CatalogType.SERIES)),
            itemsByCategory = mapOf("netflix" to listOf(media))
        )

        val index = SearchMetadataDocuments.build(
            profile, CatalogType.SERIES, null, browse, nowMillis = 2
        )
        val restored = SearchMetadataDocuments.asLocalCache(index, profile.cacheKey()).items.single()

        assertEquals("Friends", restored.title)
        assertEquals("netflix", restored.portalCategoryId)
        assertEquals(1668, restored.externalTmdbId)
        assertNull(restored.logo)
        assertNull(restored.command)
        assertNull(restored.description)
    }

    @Test
    fun build_rejectsIdsThatCouldBePlaybackUrls() {
        val unsafe = MediaItem(
            id = "https://provider.example/play?token=secret",
            title = "Unsafe",
            logo = null,
            command = null
        )
        val safe = unsafe.copy(id = "channel-7", title = "Safe")
        val browse = BrowseCatalogCache(
            profileKey = profile.cacheKey(),
            type = CatalogType.LIVE_TV,
            cachedAtMillis = 1,
            categories = emptyList(),
            itemsByCategory = mapOf("1" to listOf(unsafe, safe))
        )

        val index = SearchMetadataDocuments.build(
            profile, CatalogType.LIVE_TV, null, browse, nowMillis = 2
        )

        assertEquals(listOf("channel-7"), index.items.map { it.id })
        assertFalse(index.anonymousProfileId.contains("provider"))
        assertEquals(24, index.anonymousProfileId.length)
    }

    @Test
    fun merge_keepsLocalMetadataAndAddsRemoteOnlyItems() {
        val local = SearchMetadataIndex(
            anonymousProfileId = "0123456789abcdef01234567",
            type = CatalogType.SERIES,
            updatedAtMillis = 20,
            items = listOf(SearchMetadataItem("1", "Local title"))
        )
        val remote = local.copy(
            updatedAtMillis = 10,
            items = listOf(
                SearchMetadataItem("1", "Stale title"),
                SearchMetadataItem("2", "Remote title")
            )
        )

        val merged = SearchMetadataDocuments.merge(local, remote)

        assertEquals(listOf("Local title", "Remote title"), merged.items.map { it.title })
        assertEquals(20, merged.updatedAtMillis)
        assertSame(local, SearchMetadataDocuments.merge(local, null))
    }
}
