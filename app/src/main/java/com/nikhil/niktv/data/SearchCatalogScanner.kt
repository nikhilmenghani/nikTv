package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.BrowseCatalogCache
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PortalSession
import com.nikhil.niktv.model.SearchCatalogCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

data class SearchCatalogScanProgress(
    val categoryTitle: String,
    val page: Int,
    val fraction: Float,
    val categoryPosition: Int,
    val categoryCount: Int,
    val discoveredItems: Int
)

data class SearchCatalogScanResult(
    val cache: BrowseCatalogCache,
    val itemCount: Int,
    val failures: Int
)

/** Checkpointed, sequential catalogue crawler shared by UI and periodic work. */
class SearchCatalogScanner(context: Context) {
    private val appContext = context.applicationContext
    private val store = ProfileStore(appContext)
    private val portal = StalkerPortalClient(appContext)

    suspend fun scan(
        session: PortalSession,
        type: CatalogType,
        requestDelayMillis: Long,
        refreshCompleted: Boolean = true,
        onProgress: (SearchCatalogScanProgress) -> Unit = {}
    ): SearchCatalogScanResult {
        val profileKey = session.profile.cacheKey()
        val persisted = store.browseCatalog(type, profileKey).first()
        val availableCategories = persisted?.categories.orEmpty()
            .ifEmpty { portal.categories(session, type) }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        val categories = availableCategories.firstOrNull { it.id == "*" }
            ?.let(::listOf)
            ?: availableCategories
        var cache = persisted ?: BrowseCatalogCache(
            profileKey = profileKey,
            type = type,
            cachedAtMillis = System.currentTimeMillis(),
            categories = availableCategories,
            itemsByCategory = emptyMap()
        )
        var failures = 0

        categories.forEachIndexed { categoryIndex, category ->
            val knownItems = cache.itemsByCategory[category.id].orEmpty()
            val knownHasMore = cache.hasMoreByCategory[category.id]
            var page = cache.pagesByCategory[category.id] ?: 0
            var items = knownItems

            if (knownHasMore != false || knownItems.isEmpty() || refreshCompleted) {
                val refreshFromStart = refreshCompleted && knownHasMore == false && knownItems.isNotEmpty()
                if (refreshFromStart || page <= 0) page = 1 else page += 1
                var keepLoading = true
                var pagesRead = 0
                while (keepLoading && pagesRead < MAX_PAGES_PER_CATEGORY) {
                    onProgress(
                        SearchCatalogScanProgress(
                            category.title,
                            page,
                            categoryIndex.toFloat() / categories.size.coerceAtLeast(1),
                            categoryIndex + 1,
                            categories.size,
                            cache.itemsByCategory.values.sumOf { it.size }
                        )
                    )
                    val pageResult = runCatching {
                        portal.catalogPage(session, category, page)
                    }
                    if (pageResult.isFailure) {
                        failures += 1
                        break
                    }
                    val result = pageResult.getOrThrow()
                    val merged = (items + result.items).distinctBy { it.id }
                    val added = merged.size - items.size
                    items = merged
                    cache = cache.copy(
                        cachedAtMillis = System.currentTimeMillis(),
                        categories = availableCategories,
                        itemsByCategory = cache.itemsByCategory + (category.id to items),
                        pagesByCategory = cache.pagesByCategory + (category.id to page),
                        hasMoreByCategory = cache.hasMoreByCategory +
                            (category.id to (result.hasMore && added > 0))
                    )
                    pagesRead += 1
                    keepLoading = result.hasMore && added > 0
                    page += 1
                    if (keepLoading) delay(requestDelayMillis)
                }
            }

            store.saveBrowseCatalog(cache, scheduleMetadataSync = false)
            onProgress(
                SearchCatalogScanProgress(
                    category.title,
                    page,
                    (categoryIndex + 1f) / categories.size.coerceAtLeast(1),
                    categoryIndex + 1,
                    categories.size,
                    cache.itemsByCategory.values.sumOf { it.size }
                )
            )
            if (categoryIndex < categories.lastIndex) delay(requestDelayMillis)
        }

        val allItems = cache.itemsByCategory.values.flatten().distinctBy { it.id }
        store.saveSearchCatalog(
            SearchCatalogCache(
                profileKey = profileKey,
                type = type,
                cachedAtMillis = System.currentTimeMillis(),
                items = allItems,
                completedCategoryIds = cache.hasMoreByCategory.filterValues { !it }.keys
            ),
            scheduleMetadataSync = false
        )
        return SearchCatalogScanResult(cache, allItems.size, failures)
    }

    companion object {
        private const val MAX_PAGES_PER_CATEGORY = 500
    }
}
