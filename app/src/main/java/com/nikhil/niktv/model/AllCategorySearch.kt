package com.nikhil.niktv.model

import kotlinx.coroutines.*

internal data class AllCategorySearchResult(val page: PortalSearchPage, val failedCategories: Int)

internal fun requiresCategorySearch(portal: PortalType, type: SearchContentType, category: String,
    complete: Boolean) = portal == PortalType.STALKER && type != SearchContentType.ALL && category == "*" && !complete

/** A wildcard is only a hint on Stalker: every enabled category must still be searched. */
internal suspend fun searchAllCategoryPage(page: Int, categoryIds: List<String>,
    search: suspend (String) -> PortalSearchPage,
    progress: (Int, Int) -> Unit = { _, _ -> },
    publish: (List<MediaItem>) -> Unit = {}): AllCategorySearchResult {
    suspend fun request(category: String) = try { Result.success(search(category)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    val wildcard = request("*")
    var items = wildcard.getOrNull()?.items.orEmpty()
    var more = wildcard.getOrNull()?.hasMore ?: false
    var failures = 0
    var successes = if (wildcard.isSuccess) 1 else 0
    val categories = categoryIds.filter { it != "*" }.distinct()
    if (items.isNotEmpty()) publish(items)
    for ((index, batch) in categories.chunked(3).withIndex()) {
        currentCoroutineContext().ensureActive()
        progress(index * 3, categories.size)
        val pages = coroutineScope { batch.map { category -> async { request(category) } }.awaitAll() }
        for (result in pages) result.fold(onSuccess = { found ->
            successes++
            items = (items + found.items).distinctBy { it.id }
            more = more || found.hasMore
            publish(items)
        }, onFailure = { failures++ })
    }
    if (successes == 0) throw wildcard.exceptionOrNull() ?: IllegalStateException("Provider unavailable")
    return AllCategorySearchResult(PortalSearchPage(items, page, more), failures)
}
