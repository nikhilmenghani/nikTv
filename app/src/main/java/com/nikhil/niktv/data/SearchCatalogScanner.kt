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
    val deferred: Boolean = false,
    val providerCategories: List<Category> = emptyList(),
    val providerConfirmedEmpty: Boolean = false,
    val existingCatalogRetainedAfterEmptyResponse: Boolean = false,
    val appendBoundaryChanged: Boolean = false
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
        mediaPosition: Int = 1,
        mediaCount: Int = 1,
        appendOnly: Boolean = false,
        onProgress: (SearchCatalogScanProgress) -> Unit = {}
    ): SearchCatalogScanResult = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            scanInternal(session, type, requestDelayMillis, refreshCompleted, timeBudgetMillis,
                mediaPosition, mediaCount, appendOnly, onProgress)
        }
    }

    private suspend fun scanInternal(
        session: PortalSession, type: CatalogType, requestDelayMillis: Long,
        refreshCompleted: Boolean, timeBudgetMillis: Long, mediaPosition: Int, mediaCount: Int,
        appendOnly: Boolean,
        onProgress: (SearchCatalogScanProgress) -> Unit
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
            mediaPosition = mediaPosition, mediaCount = mediaCount,
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
        val persisted = repository.scanState(session.profile, type)
        pace(requestDelayMillis.coerceAtLeast(2_000L))
        CatalogOperations.check(appContext, operation)
        stage("${type.title} · Fetching category list")
        structured("Loading categories")
        var availableCategories = fetchCategories(session, type)
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        if (availableCategories.isEmpty()) {
            stage("${type.title} · Verifying empty provider catalog")
            structured("Verifying empty catalog")
            pace(requestDelayMillis.coerceAtLeast(2_000L))
            CatalogOperations.check(appContext, operation)
            availableCategories = fetchCategories(session, type)
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
            if (availableCategories.isEmpty()) {
                if (persisted.totalItems > 0) {
                    val detail = "Provider returned no categories twice; ${persisted.totalItems} existing " +
                        "records were retained. Retry or investigate provider category mapping."
                    CatalogOperations.page(
                        appContext,
                        operation,
                        CatalogPageEvent(
                            key = "${type.name}:categories:empty",
                            location = "${type.title} · category list",
                            outcome = "Failed",
                            detail = detail
                        )
                    )
                    stage("${type.title} · Existing records retained because the provider returned no categories")
                    return SearchCatalogScanResult(
                        cache = BrowseCatalogCache(
                            profileKey = profileKey,
                            type = type,
                            cachedAtMillis = persisted.cachedAtMillis,
                            categories = persisted.categories,
                            itemsByCategory = emptyMap(),
                            pagesByCategory = persisted.pagesByCategory,
                            hasMoreByCategory = persisted.hasMoreByCategory
                        ),
                        itemCount = persisted.totalItems,
                        failures = 1,
                        providerCategories = emptyList(),
                        providerConfirmedEmpty = false,
                        existingCatalogRetainedAfterEmptyResponse = true
                    )
                }
                val emptyCache = BrowseCatalogCache(
                    profileKey = profileKey, type = type,
                    cachedAtMillis = System.currentTimeMillis(),
                    categories = emptyList(),
                    itemsByCategory = emptyMap(),
                    pagesByCategory = persisted.pagesByCategory,
                    hasMoreByCategory = persisted.hasMoreByCategory
                )
                return SearchCatalogScanResult(
                    cache = emptyCache,
                    itemCount = 0,
                    failures = 0,
                    providerCategories = emptyList(),
                    providerConfirmedEmpty = true
                )
            }
        }
        val categories = effectiveCatalogCategories(availableCategories).let { effective ->
            if (appendOnly) effective.filter { (persisted.pagesByCategory[it.id] ?: 0) > 0 }
            else effective
        }
        if (appendOnly && categories.isEmpty()) {
            return SearchCatalogScanResult(
                cache = BrowseCatalogCache(profileKey, type, persisted.cachedAtMillis,
                    persisted.categories, emptyMap(), persisted.pagesByCategory, persisted.hasMoreByCategory),
                itemCount = persisted.totalItems,
                failures = 0,
                providerCategories = availableCategories,
                appendBoundaryChanged = true
            )
        }
        var cache = BrowseCatalogCache(
            profileKey = profileKey,
            type = type,
            cachedAtMillis = persisted.cachedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
            categories = availableCategories,
            itemsByCategory = emptyMap(),
            pagesByCategory = persisted.pagesByCategory,
            hasMoreByCategory = persisted.hasMoreByCategory
        )
        var failures = 0
        var totalRecords = persisted.totalItems
        categories.forEachIndexed { categoryIndex, category ->
            val knownHasMore = cache.hasMoreByCategory[category.id]
            var page = cache.pagesByCategory[category.id] ?: 0
            var categoryItemCount = persisted.itemCountsByCategory[category.id] ?: 0
            val pageTotalKey = "${type.name}:${category.id}"
            var knownTotalPages = CatalogOperations.pageTotal(appContext, operation, pageTotalKey)

            if (knownHasMore != false || refreshCompleted || appendOnly) {
                val appendBoundary = appendOnly && knownHasMore == false && page > 0
                val refreshFromStart = knownHasMore == false && !appendOnly
                if (refreshFromStart || page <= 0) page = 1
                else if (!appendBoundary) page += 1
                var keepLoading = true
                var pagesRead = 0
                val seenThisScan = mutableSetOf<String>()
                while (keepLoading && pagesRead < MAX_PAGES_PER_CATEGORY) {
                    CatalogOperations.check(appContext, operation)
                    if (timeBudgetMillis == Long.MAX_VALUE) {
                        while (CatalogPlaybackActivity.playing) { CatalogOperations.check(appContext, operation); delay(1_000L) }
                    }
                    if (CatalogPlaybackActivity.playing || android.os.SystemClock.elapsedRealtime() - started >= timeBudgetMillis) {
                        return SearchCatalogScanResult(cache, totalRecords,
                            failures, deferred = true, providerCategories = availableCategories)
                    }
                    onProgress(
                        SearchCatalogScanProgress(
                            category.title,
                            page,
                            categoryIndex.toFloat() / categories.size.coerceAtLeast(1),
                            categoryIndex + 1,
                            categories.size,
                            totalRecords
                        )
                    )
                    structured("Requesting provider", category, categoryIndex + 1, categories.size, page,
                        knownTotalPages, recordsInCategory = categoryItemCount,
                        totalRecords = totalRecords)
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
                        return SearchCatalogScanResult(cache, totalRecords, failures,
                            providerCategories = availableCategories)
                    }
                    val result = pageResult.getOrThrow()
                    if (appendBoundary && pagesRead == 0) {
                        val savedIds = repository.storedPageIds(
                            session.profile, type, category.id, page)
                        val fetchedIds = result.items.map { it.id }
                        if (savedIds.isEmpty() || fetchedIds.take(savedIds.size) != savedIds) {
                            CatalogOperations.page(appContext, operation, CatalogPageEvent(
                                reportKey, location = location, outcome = "Changed",
                                detail = "The saved last page changed. Append stopped before writing it; use Sync to refresh earlier pages."))
                            return SearchCatalogScanResult(cache, totalRecords, failures,
                                providerCategories = availableCategories, appendBoundaryChanged = true)
                        }
                    }
                    knownTotalPages = result.totalPages ?: knownTotalPages
                    CatalogOperations.pageTotal(appContext, operation, pageTotalKey, result.totalPages)
                    val newlySeen = result.items.count { seenThisScan.add(it.id) }
                    val resultIds = result.items.map { it.id }
                    val previousPageIds = if (page > 1) {
                        repository.storedPageIds(session.profile, type, category.id, page - 1)
                    } else emptyList()
                    val previousIds = previousPageIds.toHashSet()
                    val repeatedPersistedPage = resultIds.isNotEmpty() && previousIds.isNotEmpty() &&
                        resultIds.all { it in previousIds }
                    val madeNoProgress = newlySeen == 0 &&
                        (result.hasMore || result.items.isNotEmpty())
                    if (madeNoProgress || repeatedPersistedPage) {
                        CatalogOperations.page(appContext, operation, CatalogPageEvent(reportKey, location = location,
                            outcome = "Failed", detail = "Provider repeated a page. Cursor was not advanced; retry will start here."))
                        return SearchCatalogScanResult(cache, totalRecords, failures + 1,
                            providerCategories = availableCategories)
                    }
                    cache = cache.copy(
                        cachedAtMillis = System.currentTimeMillis(),
                        categories = availableCategories,
                        pagesByCategory = cache.pagesByCategory + (category.id to page),
                        hasMoreByCategory = cache.hasMoreByCategory +
                            (category.id to result.hasMore)
                    )
                    pagesRead += 1
                    // Persist each completed page so process death can resume instead of restarting a category.
                    stage("$location${knownTotalPages?.let { " of $it" }.orEmpty()} · Writing ${result.items.size} records to Room")
                    structured("Saving to local database", category, categoryIndex + 1, categories.size, page,
                        knownTotalPages, result.items.size, categoryItemCount, totalRecords)
                    try {
                        repository.saveBrowsePage(
                            profile = profileKey,
                            type = type,
                            categories = if (appendOnly) categories else availableCategories,
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
                    val updatedCategoryCount = repository.storedBucketItemCount(
                        profileKey, type, category.id
                    )
                    totalRecords = (totalRecords + updatedCategoryCount - categoryItemCount).coerceAtLeast(0)
                    categoryItemCount = updatedCategoryCount
                    CatalogOperations.page(appContext, operation, CatalogPageEvent(reportKey, location = location,
                        outcome = "Stored", detail = "${result.items.size} records committed. $categoryItemCount records stored in this category; ${if (result.hasMore) "more pages pending" else "last page"}."))
                    val pageProgress = knownTotalPages?.let { total ->
                        " · ${(page * 100 / total.coerceAtLeast(page)).coerceIn(0, 100)}%"
                    }.orEmpty()
                    stage("$location${knownTotalPages?.let { " of $it" }.orEmpty()}$pageProgress · Saved to Room · $categoryItemCount records in category")
                    structured("Saved", category, categoryIndex + 1, categories.size, page,
                        knownTotalPages, result.items.size, categoryItemCount, totalRecords)
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
                    totalRecords
                )
            )
            if (categoryIndex < categories.lastIndex) pace(requestDelayMillis.coerceAtLeast(2_000L))
        }

        CatalogOperations.check(appContext, operation)
        stage("${type.title} · Finalizing catalog")
        structured("Finalizing catalog", categoryPosition = categories.size,
            categoryCount = categories.size, totalRecords = totalRecords)
        return SearchCatalogScanResult(cache, totalRecords, failures,
            providerCategories = availableCategories)
    }

    companion object {
        private val scanMutex = Mutex()
        private const val MAX_PAGES_PER_CATEGORY = 500
    }
}

internal object CatalogPlaybackActivity { @Volatile var playing = false }
