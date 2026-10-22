package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.NikTvState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryLibraryFeedTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun media(id: String, title: String = id) = MediaItem(id, title, null, "private-command")
    @Test fun savedFiltersSortAndSearchAreLocalAndProfileScoped() {
        val state = NikTvState(favorites = listOf(
            FavoriteItem(FavoriteKind.MOVIE, media("m1", "Zulu"), addedAtMillis = 2, profileKey = "a"),
            FavoriteItem(FavoriteKind.MOVIE, media("m2", "Alpha"), addedAtMillis = 1, profileKey = "a"),
            FavoriteItem(FavoriteKind.SERIES, media("s1", "Other"), profileKey = "a"),
            FavoriteItem(FavoriteKind.MOVIE, media("m1", "Private other profile"), profileKey = "b")
        ))
        val rows = librarySavedRows(state, "a", FavoriteKind.MOVIE, "", LibrarySort.TITLE)
        assertEquals(listOf("Alpha", "Zulu"), rows.single().entries.map { it.media.title })
        assertEquals("Zulu", librarySavedRows(state, "a", FavoriteKind.MOVIE, "zUL", LibrarySort.RECENT).single().entries.single().media.title)
        assertEquals("Zulu", librarySavedRows(state, "a", FavoriteKind.MOVIE, "", LibrarySort.RECENT).single().entries.first().media.title)
        assertTrue(librarySavedRows(state, "missing", null, "", LibrarySort.RECENT).isEmpty())
    }
    @Test fun livePreviewUsesOnlyVisibleCachedCategoriesAndCurrentProfileHistory() {
        val visible = Category("one", "English", CatalogType.LIVE_TV)
        val cache = BrowseCatalogCache("a", CatalogType.LIVE_TV, 1, listOf(visible), mapOf("one" to listOf(media("1")), "hidden" to listOf(media("secret"))))
        val state = NikTvState(categories = listOf(visible), browseCachesByType = mapOf(CatalogType.LIVE_TV to cache), recentlyPlayed = listOf(
            RecentItem(FavoriteKind.CHANNEL, media("recent"), profileKey = "a"), RecentItem(FavoriteKind.CHANNEL, media("wrong"), profileKey = "b")))
        val rows = libraryLiveRows(state, "a", mapOf("one" to listOf("1", "1")))
        assertEquals(listOf("pinned", "recent", "live:one"), rows.map { it.key })
        assertEquals(1, rows.first().entries.size)
        assertFalse(rows.flatMap { it.entries }.any { it.media.id in listOf("secret", "wrong") })
        assertTrue(libraryLiveRows(state, "b", emptyMap()).none { it.key.startsWith("live:") })
    }
    @Test fun customCategoryKeepsItsSourcesAndIntentionalOccurrences() {
        val category = Category("club", "Together", CatalogType.LIVE_TV, sourceCategoryIds = listOf("one", "two"))
        val cache = BrowseCatalogCache("a", CatalogType.LIVE_TV, 1, emptyList(), mapOf("one" to listOf(media("same")), "two" to listOf(media("same"))))
        val rows = libraryLiveRows(NikTvState(categories = listOf(category), browseCachesByType = mapOf(CatalogType.LIVE_TV to cache)), "a", emptyMap())
        assertEquals(2, rows.single().entries.size)
        assertEquals(category, rows.single().entries.first().category)
    }
    @Test fun heroReadsExistingPublicMetadataWithoutWritingProviderDetails() = runBlocking {
        val folder = temporary.newFolder()
        val cache = DiscoveryCache(folder)
        val entry = LibraryEntry(media("p1", "Example").copy(externalTmdbId = 77), FavoriteKind.MOVIE)
        val details = DiscoveryDetails(DiscoveryCard(77, false, "Cached title", backdrop = "https://image.tmdb.org/t/p/w1280/example.jpg"))
        cache.write("detail-v4:movie:77", Json.encodeToString(DiscoveryDetails.serializer(), details))
        assertEquals("Cached title", libraryCachedPresentation(cache, entry)?.title)
        assertEquals(1, folder.listFiles()!!.size)
        assertFalse(folder.listFiles()!!.any { it.readText().contains("private-command") })
    }
    @Test fun missingPresentationDoesNotCreateMetadataOrFetchAProviderStream() = runBlocking {
        val folder = temporary.newFolder()
        val cache = DiscoveryCache(folder)
        val entry = LibraryEntry(media("p1", "Local title"), FavoriteKind.SERIES)
        assertEquals("Local title", libraryCachedPresentation(cache, entry)?.title)
        assertNull(libraryCachedPresentation(cache, entry)?.backdrop)
        assertTrue(folder.listFiles()!!.isEmpty())
        assertNull(libraryCachedPresentation(cache, entry.copy(kind = FavoriteKind.CHANNEL)))
    }
    @Test fun compactPreviewDoesNotTruncateThePlayerQueueOrLoseLaterPins() {
        val category = Category("one", "English", CatalogType.LIVE_TV)
        val items = (1..50).map { media(it.toString()) }
        val state = NikTvState(categories = listOf(category), browseCachesByType = mapOf(CatalogType.LIVE_TV to
            BrowseCatalogCache("a", CatalogType.LIVE_TV, 1, listOf(category), mapOf("one" to items))))
        val rows = libraryLiveRows(state, "a", mapOf("one" to listOf("49")))
        assertEquals("49", rows.first().entries.single().media.id)
        val preview = rows.first { it.category != null }
        assertEquals(12, preview.entries.size)
        val (queue, filter) = libraryLiveQueue(state, "a", preview.entries.first(), emptyList())
        assertEquals(50, queue.size)
        assertEquals("one", filter.contextId)
        assertFalse(queue.any { it.id == "not-in-context" })
    }
    @Test fun queuePreservesDuplicateOccurrenceAndRejectsAnotherProfilesCache() {
        val category = Category("one", "English", CatalogType.LIVE_TV)
        val first = media("same"); val second = media("same").copy(title = "Other occurrence")
        val state = NikTvState(browseCachesByType = mapOf(CatalogType.LIVE_TV to BrowseCatalogCache("a", CatalogType.LIVE_TV,
            1, listOf(category), mapOf("one" to listOf(first, second)))))
        val entry = LibraryEntry(second, FavoriteKind.CHANNEL, category)
        assertEquals(1, libraryLiveQueue(state, "a", entry, emptyList()).second.selectedOrdinal)
        assertEquals(listOf(second), libraryLiveQueue(state, "b", entry, emptyList()).first)
    }
}
