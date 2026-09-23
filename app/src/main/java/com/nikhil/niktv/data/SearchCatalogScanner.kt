package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.BrowseCatalogCache
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PortalSession
import com.nikhil.niktv.model.SearchCatalogCache
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.PortalCatalogPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
class SearchCatalogScanner internal constructor(
    context: Context,
    private val fetchCategories: suspend (PortalSession, CatalogType) -> List<Category>,
    private val fetchPage: suspend (PortalSession, Category, Int) -> PortalCatalogPage,
    private val pace: suspend (Long) -> Unit
) {
    constructor(context: Context) : this(context, StalkerPortalClient(context))
    private constructor(context: Context, portal: StalkerPortalClient) : this(context,
        { session, type -> portal.categories(session, type) },
        { session, category, page -> portal.catalogPage(session, category, page, includeEpg = false) },
        { delay(it) })
    private val appContext = context.applicationContext
    private val repository = CatalogRepository(appContext)

    suspend fun scan(
        session: PortalSession,
        type: CatalogType,
        requestDelayMillis: Long,
        refreshCompleted: Boolean = true,
        timeBudgetMillis: Long = Long.MAX_VALUE,
        onProgress: (SearchCatalogScanProgress) -> Unit = {}
    ): SearchCatalogScanResult = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            scanInternal(session, type, requestDelayMillis, refreshCompleted, timeBudgetMillis, onProgress)
        }
    }

    private suspend fun scanInternal(
        session: PortalSession, type: CatalogType, requestDelayMillis: Long,
        refreshCompleted: Boolean, timeBudgetMillis: Long, onProgress: (SearchCatalogScanProgress) -> Unit
    ): SearchCatalogScanResult {
        val started = android.os.SystemClock.elapsedRealtime()
        val profileKey = session.profile.cacheKey()
        val operation = CatalogOperations.scan(CatalogScanPreferences.id(session.profile))
        fun stage(message: String) {
            if (!CatalogOperations.held(appContext, operation)) CatalogOperations.message(appContext, operation, message)
        }
        fun structured(
            phase: String,
            category: Category? = null,
            categoryPosition: Int = 0,
            categoryCount: Int = 0,
            page: Int = 0,
            totalPages: Int? = null,
            recordsInPage: Int = 0,
            recordsInCategory: Int = 0,
            totalRecords: Int = 0
        ) = CatalogOperations.progress(appContext, operation, CatalogOperationProgress(
            phase = phase, mediaType = type.title, category = category?.title.orEmpty(),
            categoryPosition = categoryPosition, categoryCount = categoryCount,
            page = page, totalPages = totalPages ?: 0, recordsInPage = recordsInPage,
            recordsInCategory = recordsInCategory, totalRecords = totalRecords,
            estimatedRemainingMillis = category?.let {
                CatalogOperations.estimatedRemainingMillis(
                    appContext, operation, type.name, it.id, page, totalPages
                )
            } ?: 0
        ))
        CatalogOperations.check(appContext, operation)
        stage("${type.title} · Reading saved Room checkpoint")
        structured("Reading saved database")
        val persisted = repository.browse(profileKey, type)
        pace(requestDelayMillis.coerceAtLeast(2_000L))
        CatalogOperations.check(appContext, operation)
        stage("${type.title} · Fetching category list")
        structured("Loading categories")
        val availableCategories = fetchCategories(session, type)
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
            var knownTotalPages = CatalogOperations.pageTotal(appContext, operation, category.id)

            if (knownHasMore != false || refreshCompleted) {
                val refreshFromStart = knownHasMore == false
                if (refreshFromStart || page <= 0) page = 1 else page += 1
                val startedAtFirstPage = page == 1
                var keepLoading = true
                var pagesRead = 0
                val seenThisScan = mutableSetOf<String>()
                while (keepLoading && pagesRead < MAX_PAGES_PER_CATEGORY) {
                    CatalogOperations.check(appContext, operation)
                    if (timeBudgetMillis == Long.MAX_VALUE) {
                        while (CatalogPlaybackActivity.playing) { CatalogOperations.check(appContext, operation); delay(1_000L) }
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
                    structured("Requesting provider", category, categoryIndex + 1, categories.size, page,
                        knownTotalPages, recordsInCategory = items.size,
                        totalRecords = cache.itemsByCategory.values.sumOf { it.size })
                    pace(requestDelayMillis.coerceAtLeast(2_000L))
                    CatalogOperations.check(appContext, operation)
                    val location = "${type.title} · ${category.title} · page $page"
                    val reportKey = "${type.name}:${category.id}:$page"
                    stage("$location${knownTotalPages?.let { " of $it" }.orEmpty()} · Requesting provider · category ${categoryIndex + 1}/${categories.size}")
                    val pageResult = runCatching {
                        fetchPage(session, category, page)
                    }
                    if (pageResult.isFailure) {
                        (pageResult.exceptionOrNull() as? CancellationException)?.let { throw it }
                        failures += 1
                        CatalogOperations.page(appContext, operation, CatalogPageEvent(reportKey, location = location,
                            outcome = "Failed", detail = "Provider request failed (${pageResult.exceptionOrNull()?.javaClass?.simpleName}). Page will be retried; existing records are unchanged."))
                        return SearchCatalogScanResult(cache, cache.itemsByCategory.values.sumOf { it.size }, failures)
                    }
                    val result = pageResult.getOrThrow()
                    knownTotalPages = result.totalPages ?: knownTotalPages
                    CatalogOperations.pageTotal(appContext, operation, category.id, result.totalPages)
                    val newlySeen = result.items.count { seenThisScan.add(it.id) }
                    if (result.hasMore && newlySeen == 0) {
                        CatalogOperations.page(appContext, operation, CatalogPageEvent(reportKey, location = location,
                            outcome = "Failed", detail = "Provider repeated a page. Cursor was not advanced; retry will start here."))
                        return SearchCatalogScanResult(cache, cache.itemsByCategory.values.sumOf { it.size }, failures + 1)
                    }
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
                    if (!result.hasMore && startedAtFirstPage) completed[category.id] = seenThisScan.toSet()
                    pagesRead += 1
                    // Persist each completed page so process death can resume instead of restarting a category.
                    stage("$location${knownTotalPages?.let { " of $it" }.orEmpty()} · Writing ${result.items.size} records to Room")
                    structured("Saving to local database", category, categoryIndex + 1, categories.size, page,
                        knownTotalPages, result.items.size, items.size,
                        cache.itemsByCategory.values.sumOf { it.size })
                    try {
                        repository.saveBrowsePage(
                            profile = profileKey,
                            type = type,
                            categories = availableCategories,
                            category = category,
                            page = page,
                            items = result.items,
                            hasMore = result.hasMore,
                            observedAt = cache.cachedAtMillis
                        )
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        CatalogOperations.page(appContext, operation, CatalogPageEvent(reportKey, location = location,
                            outcome = "Failed", detail = "Room write failed (${error.javaClass.simpleName}). Last committed page is retained."))
                        throw error
                    }
                    CatalogOperations.page(appContext, operation, CatalogPageEvent(reportKey, location = location,
                        outcome = "Stored", detail = "${result.items.size} records committed. ${items.size} unique records in this category; ${if (result.hasMore) "more pages pending" else "last page"}."))
                    val pageProgress = knownTotalPages?.let { total ->
                        " · ${(page * 100 / total.coerceAtLeast(page)).coerceIn(0, 100)}%"
                    }.orEmpty()
                    stage("$location${knownTotalPages?.let { " of $it" }.orEmpty()}$pageProgress · Saved to Room · ${items.size} records in category")
                    structured("Saved", category, categoryIndex + 1, categories.size, page,
                        knownTotalPages, result.items.size, items.size,
                        cache.itemsByCategory.values.sumOf { it.size })
                    CatalogOperations.check(appContext, operation)
                    keepLoading = result.hasMore && newlySeen > 0
                    page += 1
                }
                if (keepLoading) failures += 1
            }
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
            if (categoryIndex < categories.lastIndex) pace(requestDelayMillis.coerceAtLeast(2_000L))
        }

        CatalogOperations.check(appContext, operation)
        stage("${type.title} · Finalizing local search index")
        structured("Finalizing search index", totalRecords = cache.itemsByCategory.values.sumOf { it.size })
        val allItems = cache.itemsByCategory.values.flatten().distinctBy { it.id }
        repository.saveSearch(
            SearchCatalogCache(
                profileKey = profileKey,
                type = type,
                cachedAtMillis = System.currentTimeMillis(),
                items = allItems,
                completedCategoryIds = cache.hasMoreByCategory.filterValues { !it }.keys
            )
        )
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
