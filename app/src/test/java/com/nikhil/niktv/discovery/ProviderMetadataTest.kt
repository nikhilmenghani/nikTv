package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun movie(title: String = "Movie (2020)", id: Int? = null) =
        MediaItem("private-provider-id", title, null, "private-provider-stream-secret", externalTmdbId = id)
    @Test fun broadcasterMissDoesNotBlockCleanSeriesLookup() = runBlocking {
        val cache = DiscoveryCache(temporary.newFolder())
        val decorated = movie("Star Plus Hindi : Anupama")
        cache.load("provider-presentation-v1:SERIES:null:Star Plus Hindi : Anupama",
            DiscoveryCache.FEED_TTL, kotlinx.serialization.builtins.ListSerializer(DiscoveryCard.serializer())) {
            emptyList<DiscoveryCard>()
        }
        var calls = 0
        val expected = DiscoveryCard(42, true, "Anupama")
        assertEquals(expected, cachedProviderMetadata(cache, decorated, CatalogType.SERIES) { calls++; expected })
        assertEquals(expected, cachedProviderMetadata(cache, movie("Anupama"), CatalogType.SERIES) { calls++; null })
        assertEquals(1, calls)
    }
    @Test fun cachedPresentationIsReusedWithoutSavingProviderCommands() = runBlocking {
        val directory = temporary.newFolder()
        val cache = DiscoveryCache(directory)
        var calls = 0
        suspend fun lookup() = DiscoveryCard(++calls, false, "Movie", date = "2020-01-01")
        val first = cachedProviderMetadata(cache, movie(), CatalogType.MOVIES, ::lookup)
        val second = cachedProviderMetadata(cache, movie().copy(id = "other-profile-id", command = "another-private-stream"), CatalogType.MOVIES, ::lookup)
        assertEquals(first, second); assertEquals(1, calls)
        val disk = directory.listFiles().orEmpty().joinToString { it.readText() }
        assertFalse(disk.contains("private-provider")); assertFalse(disk.contains("another-private"))
    }
    @Test fun typesYearsAndKnownTmdbIdentitiesCannotShareTheWrongMetadata() = runBlocking {
        val cache = DiscoveryCache(temporary.newFolder())
        var calls = 0
        suspend fun lookup() = DiscoveryCard(++calls, false, "Result")
        val results = listOf(
            cachedProviderMetadata(cache, movie(), CatalogType.MOVIES, ::lookup),
            cachedProviderMetadata(cache, movie(), CatalogType.SERIES, ::lookup),
            cachedProviderMetadata(cache, movie("Movie (2024)"), CatalogType.MOVIES, ::lookup),
            cachedProviderMetadata(cache, movie(id = 55), CatalogType.MOVIES, ::lookup))
        assertEquals(4, results.map { it?.id }.distinct().size)
    }
    @Test fun negativeMatchesExpireAndDoNotHammerTmdbOnEveryOpen() = runBlocking {
        var now = 0L; var calls = 0
        val cache = DiscoveryCache(temporary.newFolder(), now = { now })
        repeat(2) { assertNull(cachedProviderMetadata(cache, movie(), CatalogType.MOVIES) { calls++; null }) }
        assertEquals(1, calls)
        now = DiscoveryCache.FEED_TTL + 1
        cachedProviderMetadata(cache, movie(), CatalogType.MOVIES) { calls++; null }
        assertEquals(2, calls)
    }
    @Test fun expiredMetadataRemainsAvailableWhenRefreshFails() = runBlocking {
        var now = 0L
        val cache = DiscoveryCache(temporary.newFolder(), now = { now })
        val expected = DiscoveryCard(42, false, "Cached title")
        cachedProviderMetadata(cache, movie(), CatalogType.MOVIES) { expected }
        now = DiscoveryCache.FEED_TTL + 1
        assertEquals(expected, cachedProviderMetadata(cache, movie(), CatalogType.MOVIES) { error("offline") })
    }
    @Test fun trustedTmdbHintCannotLeakPrivateProviderArtworkIntoPublicCache() = runBlocking {
        val directory = temporary.newFolder()
        val item = movie(id = 42).copy(logo = "https://private-provider/secret-artwork", description = "private-provider-description")
        val result = cachedProviderMetadata(DiscoveryCache(directory), item, CatalogType.MOVIES) { providerTmdbSeed(item, CatalogType.MOVIES) }
        assertEquals(42, result?.id)
        val disk = directory.listFiles().orEmpty().joinToString { it.readText() }
        assertFalse(disk.contains("private-provider"))
    }
    @Test fun queueContextDoesNotMistakeMediaIdsForSourceCategoryIds() {
        val items = listOf(movie().copy(portalCategoryId = "provider-category"))
        val category = Category("collection", "Movies", CatalogType.MOVIES)
        val filter = providerCategoryQueueFilter(category, items, 0, "")
        assertEquals("collection", filter.contextId)
        assertNull(filter.sourceIds)
        assertNull(filter.fixedIds)
    }
    @Test fun queueSearchAndDuplicateOccurrenceAreRetained() {
        val items = listOf(movie(), movie().copy(title = "Pinned occurrence"), movie())
        val filter = providerCategoryQueueFilter(Category("c", "Movies", CatalogType.MOVIES), items, 2, " movie ")
        assertEquals(2, filter.selectedOrdinal)
        assertEquals("movie", filter.query)
        assertEquals(emptyList<String>(), filter.pinnedIds)
    }
}
