package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.BrowseCatalogCache
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PortalSession
import com.nikhil.niktv.model.SearchCatalogCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val failures: Int,
    val deferred: Boolean = false
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
        timeBudgetMillis: Long = Long.MAX_VALUE,
        onProgress: (SearchCatalogScanProgress) -> Unit = {}
    ): SearchCatalogScanResult = scanMutex.withLock {
        scanInternal(session, type, requestDelayMillis, refreshCompleted, timeBudgetMillis, onProgress)
    }

    private suspend fun scanInternal(
        session: PortalSession, type: CatalogType, requestDelayMillis: Long,
        refreshCompleted: Boolean, timeBudgetMillis: Long, onProgress: (SearchCatalogScanProgress) -> Unit
    ): SearchCatalogScanResult {
        val started = android.os.SystemClock.elapsedRealtime()
        val profileKey = session.profile.cacheKey()
        val persisted = store.browseCatalog(type, profileKey).first()
        val availableCategories = portal.categories(session, type)
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
        val completed = mutableMapOf<String, Set<String>>()

        categories.forEachIndexed { categoryIndex, category ->
            val knownItems = cache.itemsByCategory[category.id].orEmpty()
            val knownHasMore = cache.hasMoreByCategory[category.id]
            var page = cache.pagesByCategory[category.id] ?: 0
            var items = knownItems

            if (knownHasMore != false || refreshCompleted) {
                val refreshFromStart = knownHasMore == false
                if (refreshFromStart || page <= 0) page = 1 else page += 1
                val startedAtFirstPage = page == 1
                var keepLoading = true
                var pagesRead = 0
                val seenThisScan = mutableSetOf<String>()
                while (keepLoading && pagesRead < MAX_PAGES_PER_CATEGORY) {
                    if (timeBudgetMillis == Long.MAX_VALUE) {
                        while (CatalogPlaybackActivity.playing) delay(1_000L)
                    }
                    if (CatalogPlaybackActivity.playing || android.os.SystemClock.elapsedRealtime() - started >= timeBudgetMillis) {
                        return SearchCatalogScanResult(cache, cache.itemsByCategory.values.flatten().distinctBy { it.id }.size,
                            failures, deferred = true)
                    }
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
                        (pageResult.exceptionOrNull() as? CancellationException)?.let { throw it }
                        failures += 1
                        break
                    }
                    val result = pageResult.getOrThrow()
                    val newlySeen = result.items.count { seenThisScan.add(it.id) }
                    val merged = (result.items + items).distinctBy { it.id }
                    items = merged
                    cache = cache.copy(
                        cachedAtMillis = System.currentTimeMillis(),
                        categories = availableCategories,
                        itemsByCategory = cache.itemsByCategory + (category.id to items),
                        pagesByCategory = cache.pagesByCategory + (category.id to page),
                        hasMoreByCategory = cache.hasMoreByCategory +
                            (category.id to result.hasMore)
                    )
                    // A repeated page is an incomplete scan, not evidence that the category ended.
                    if (result.hasMore && newlySeen == 0) failures += 1
                    if (!result.hasMore && startedAtFirstPage) completed[category.id] = seenThisScan.toSet()
                    pagesRead += 1
                    // Persist each completed page so process death can resume instead of restarting a category.
                    store.saveBrowseCatalog(cache, scheduleMetadataSync = false)
                    keepLoading = result.hasMore && newlySeen > 0
                    page += 1
                    if (keepLoading) delay(requestDelayMillis)
                }
                if (keepLoading) failures += 1
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
        val repository = CatalogRepository(appContext)
        completed.forEach { (category, seen) ->
            repository.reconcileCategory(profileKey, type, category, seen, System.currentTimeMillis())
        }
        return SearchCatalogScanResult(cache, allItems.size, failures)
    }

    companion object {
        private val scanMutex = Mutex()
        private const val MAX_PAGES_PER_CATEGORY = 500
    }
}

internal object CatalogPlaybackActivity { @Volatile var playing = false }
