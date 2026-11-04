package com.nikhil.niktv.discovery

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

internal typealias ProviderDiscoveryPageLoader = suspend (PortalSession, Category, Int, Boolean) -> PortalCatalogPage

/** A user-selected title must not wait behind an obsolete catalogue HTTP call. */
internal class DiscoveryMetadataRequests {
    private val jobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    suspend fun <T> run(block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
        if (job != null) jobs.add(job)
        return try { block() } finally { if (job != null) jobs.remove(job) }
    }
    fun cancel() { jobs.toList().forEach { it.cancel(CancellationException("Title selected")) } }
}

/** Never copy provider diagnostics, credentials or response bodies into a row. */
internal fun providerDiscoveryFailure(error: Exception): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(6).toList()
    return when {
        causes.any { it is java.net.UnknownHostException } -> "Could not reach the provider. Check the connection, then retry."
        causes.any { it is java.net.SocketTimeoutException } -> "The provider timed out. Retry when connected."
        error.javaClass.simpleName.contains("Cooldown") -> "Provider requests are temporarily paused. Retry later."
        else -> "Could not update IPTV titles. Cached titles are still available."
    }
}

/** Private provider metadata stays in the existing clearable catalogue cache, never TMDB's cache. */
@Serializable
internal data class ProviderDiscoveryPage(val items: List<MediaItem> = emptyList(), val page: Int = 0,
    val hasMore: Boolean = true, val cachedAt: Long = 0, val truncated: Boolean = false)
@Serializable
internal data class ProviderDiscoveryCache(val profile: String, val type: CatalogType,
    val pages: Map<String, ProviderDiscoveryPage> = emptyMap(), val preview: Boolean = false)

internal fun providerDiscoveryCategoryRows(categories: List<Category>, implicitSelection: Boolean): List<Category> =
    categories.filterNot { it.isAdult || adultContentLabel(it.title) }.let { safe ->
        if (implicitSelection && safe.any { it.id != "*" }) safe.filterNot { it.id == "*" } else safe
    }

internal fun providerDiscoverySources(categories: List<Category>): List<Category> = categories
    .filterNot { it.isAdult || adultContentLabel(it.title) }
    .flatMap { category -> category.sourceCategoryIds.ifEmpty { listOf(category.id) }.map { category.copy(id = it, sourceCategoryIds = emptyList()) } }
    .distinctBy { it.id }

internal fun providerDiscoveryItems(categories: List<Category>, pages: Map<String, ProviderDiscoveryPage>): List<MediaItem> =
    categories.flatMap { category -> pages[category.id]?.items.orEmpty() }
        .filterNot { it.isAdult || adultContentLabel(it.title) }.distinctBy { it.id }

internal fun providerDiscoveryFresh(page: ProviderDiscoveryPage?, now: Long, ttl: Long) =
    page != null && page.page > 0 && now >= page.cachedAt && now - page.cachedAt < ttl

internal fun searchProviderDiscovery(items: List<MediaItem>, query: String): List<MediaItem> {
    if (query.isBlank()) return items
    val matches = titleKeywordMatcher(query.trim())
    return items.filter { matches(it.title) }
}

internal fun boundProviderDiscoveryPages(pages: Map<String, ProviderDiscoveryPage>): Map<String, ProviderDiscoveryPage> {
    var remaining = ProviderDiscoveryViewModel.MAX_ITEMS
    return pages.mapValues { (_, page) ->
        val clipped = page.items.size > remaining
        (if (!clipped) page else page.copy(items = page.items.take(remaining),
            page = if (clipped) 0 else page.page, hasMore = page.hasMore || clipped,
            cachedAt = if (clipped) 0 else page.cachedAt, truncated = page.truncated || clipped)).also { remaining -= it.items.size }
    }
}

internal interface ProviderDiscoverySource {
    suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean = false): Map<String, ProviderDiscoveryPage>
    suspend fun page(session: PortalSession, category: Category, page: Int): PortalCatalogPage
    suspend fun save(cache: ProviderDiscoveryCache)
}

internal class ProviderDiscoveryRepository(private val app: Application) : ProviderDiscoverySource {
    private val store = ProfileStore(app)
    private val portal = StalkerPortalClient(app)
    private fun key(profile: String, type: CatalogType, preview: Boolean = false) = "discovery-provider-v1:$profile:${type.name}" + if (preview) ":rows" else ""
    override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean): Map<String, ProviderDiscoveryPage> {
        val own = CatalogDiskCache.read<ProviderDiscoveryCache>(app, key(profile, type, preview))
            ?.takeIf { it.profile == profile && it.type == type }?.pages.orEmpty()
        // Once materialized, don't reparse the potentially much larger global
        // search/browse catalogues on every return to this collection.
        if (categories.all { it.id in own })
            return categories.associate { it.id to own.getValue(it.id) }
        val other = CatalogDiskCache.read<ProviderDiscoveryCache>(app, key(profile, type, !preview))
            ?.takeIf { it.profile == profile && it.type == type }?.pages.orEmpty()
        val browse = store.browseCatalog(type, profile).first()?.takeIf { it.profileKey == profile && it.type == type }
        val search = store.searchCatalog(type, profile).first()?.takeIf { it.profileKey == profile && it.type == type }
        return withContext(Dispatchers.Default) {
            categories.associate { category ->
                val fromBrowse = browse?.itemsByCategory?.get(category.id)?.let { items ->
                    ProviderDiscoveryPage(items, browse.pagesByCategory[category.id] ?: 0,
                        browse.hasMoreByCategory[category.id] ?: true, browse.categoryCachedAtMillis[category.id] ?: 0)
                }
                val fromSearch = search?.takeIf { "*" in it.completedCategoryIds || category.id in it.completedCategoryIds }?.let { cache ->
                    ProviderDiscoveryPage(cache.items.filter { category.id == "*" || it.portalCategoryId == category.id }, 1, false, cache.cachedAtMillis)
                }
                // A metadata-only search index has no artwork. Prefer real provider records when available.
                val candidates = listOfNotNull(own[category.id], other[category.id]?.takeIf { preview || !it.truncated }, fromBrowse, fromSearch)
                val best = candidates.maxWithOrNull(compareBy<ProviderDiscoveryPage> { it.cachedAt }
                    .thenBy { !it.hasMore }.thenBy { it.items.size }) ?: ProviderDiscoveryPage()
                val presentation = candidates.flatMap { it.items }.filter { !it.logo.isNullOrBlank() || !it.command.isNullOrBlank() }.associateBy { it.id }
                category.id to best.copy(items = best.items.map { item -> presentation[item.id]?.let {
                    item.copy(logo = item.logo ?: it.logo, command = item.command ?: it.command)
                } ?: item })
            }
        }
    }
    override suspend fun page(session: PortalSession, category: Category, page: Int): PortalCatalogPage {
        // Page one means a real refresh, not the client's 30-minute in-memory response.
        if (page == 1) portal.invalidateCatalog(session.profile, category)
        return portal.catalogPage(session, category, page, includeEpg = false, background = true)
    }
    override suspend fun save(cache: ProviderDiscoveryCache) = writes.withLock {
        val identity = key(cache.profile, cache.type, cache.preview)
        val previous = CatalogDiskCache.read<ProviderDiscoveryCache>(app, identity)
            ?.takeIf { it.profile == cache.profile && it.type == cache.type }?.pages.orEmpty()
        val retained = previous.filterKeys { it !in cache.pages }.entries.sortedByDescending { it.value.cachedAt }.associate { it.toPair() }
        val pages = withContext(Dispatchers.Default) { boundProviderDiscoveryPages(cache.pages + retained) }
        CatalogDiskCache.write(app, identity, cache.copy(pages = pages))
    }
    private companion object { val writes = Mutex() }
}

internal data class ProviderDiscoveryState(val identity: String = "", val profile: String = "",
    val categories: Map<CatalogType, List<Category>> = emptyMap(),
    val pages: Map<CatalogType, Map<String, ProviderDiscoveryPage>> = emptyMap(),
    val items: Map<CatalogType, List<MediaItem>> = emptyMap(),
    val ready: Boolean = false, val loading: Set<CatalogType> = emptySet(),
    val errors: Map<CatalogType, String> = emptyMap(), val capped: Set<CatalogType> = emptySet(), val preview: Boolean = false) {
    fun hasMore(type: CatalogType) = type !in capped && categories[type].orEmpty().any { pages[type]?.get(it.id)?.hasMore != false }
}

/** One network operation at a time; two pages per visible request, never opens a media stream. */
internal class ProviderDiscoveryViewModel @JvmOverloads constructor(app: Application,
    private val source: ProviderDiscoverySource = ProviderDiscoveryRepository(app)) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(ProviderDiscoveryState())
    val state = mutable.asStateFlow()
    var retainedRows: Pair<String, Map<String, List<MediaItem>>> = "" to emptyMap()
    private var generation = 0L
    private var session: PortalSession? = null
    private var pageLoader: ProviderDiscoveryPageLoader? = null
    private var ttl = 6 * 60 * 60_000L
    private val jobs = mutableMapOf<CatalogType, Job>()
    private val network = Mutex()
    private var diskJob: Job? = null
    private var nextCategory = mutableMapOf<CatalogType, Int>()
    private var previewWindow: Map<CatalogType, Set<String>>? = null
    private var windowGeneration = 0L
    private var hydratedWindowGeneration = -1L
    private val loadGenerations = mutableMapOf<CatalogType, Long>()
    private val previewRecency = linkedSetOf<Pair<CatalogType, String>>()
    fun bind(session: PortalSession?, categories: Map<CatalogType, List<Category>>, ttlMillis: Long, preview: Boolean = false,
        pageLoader: ProviderDiscoveryPageLoader? = null, initialPreviewWindow: Map<CatalogType, Set<String>>? = null) {
        val sessionChanged = this.session != session
        this.session = session
        this.pageLoader = pageLoader
        ttl = ttlMillis.coerceAtLeast(60_000L)
        val profile = session?.profile?.cacheKey().orEmpty()
        if (mutable.value.profile != profile) retainedRows = "" to emptyMap()
        val selected = categories.mapValues { providerDiscoverySources(it.value) }.filterValues { it.isNotEmpty() }
        if (preview && initialPreviewWindow != null) {
            val identity = "$profile:windowed:" + selected.entries.sortedBy { it.key.name }.joinToString { (type, values) -> "$type:${values.map { it.id }}" }
            if (mutable.value.identity != identity) {
                pause(); nextCategory.clear(); previewRecency.clear(); previewWindow = emptyMap()
                mutable.value = ProviderDiscoveryState(identity, profile, selected, preview = true)
            } else if (sessionChanged) pause()
            updatePreviewWindow(initialPreviewWindow)
            return
        }
        previewWindow = null
        val identity = "$profile:$preview:" + selected.entries.sortedBy { it.key.name }.joinToString { (type, values) -> "$type:${values.map { it.id }}" }
        if (mutable.value.identity == identity && mutable.value.ready) return
        pause(); val token = generation
        nextCategory.clear()
        mutable.value = ProviderDiscoveryState(identity, profile, selected, preview = preview)
        diskJob = viewModelScope.launch {
            val pages = selected.mapValues { (type, cats) ->
                try {
                    val cached = source.cached(profile, type, cats, preview)
                    withContext(Dispatchers.Default) {
                        boundProviderDiscoveryPages(cached.mapValues { (_, page) ->
                            // Completed but expired categories remain eligible for demand refresh,
                            // including those outside the first two requests of the initial batch.
                            val bounded = if (preview) page.copy(items = page.items.take(PREVIEW_ITEMS), truncated = page.truncated || page.items.size > PREVIEW_ITEMS) else page
                            if (!providerDiscoveryFresh(bounded, System.currentTimeMillis(), ttl)) bounded.copy(hasMore = true) else bounded
                        })
                    }
                }
                catch (c: CancellationException) { throw c }
                catch (_: Exception) { emptyMap() }
            }
            if (token != generation) return@launch
            val items = withContext(Dispatchers.Default) { selected.mapValues { (type, cats) -> providerDiscoveryItems(cats, pages[type].orEmpty()).take(MAX_ITEMS) } }
            if (token == generation) mutable.update { it.copy(pages = pages, items = items, ready = true,
                capped = pages.filterValues { records -> records.values.sumOf { it.items.size } >= MAX_ITEMS }.keys) }
        }
    }
    /** Scroll demand changes independently of the complete selection and hydrated rows. */
    fun updatePreviewWindow(requested: Map<CatalogType, Set<String>>) {
        val previous = previewWindow ?: return
        val snapshot = mutable.value
        val window = snapshot.categories.mapValues { (type, cats) ->
            cats.map { it.id }.filterTo(linkedSetOf()) { it in requested[type].orEmpty() }
        }.filterValues { it.isNotEmpty() }
        if (window == previous && (hydratedWindowGeneration == windowGeneration || diskJob?.isActive == true)) return
        previewWindow = window
        val affected = (previous.keys + window.keys).filter { previous[it] != window[it] }
        affected.forEach { type ->
            loadGenerations[type] = (loadGenerations[type] ?: 0) + 1
            jobs.remove(type)?.cancel()
        }
        val token = generation; val request = ++windowGeneration
        diskJob?.cancel()
        window.forEach { (type, ids) -> ids.forEach { id -> previewRecency.remove(type to id); previewRecency.add(type to id) } }
        val needsCache = window.any { (type, ids) -> ids.any {
            !providerDiscoveryFresh(snapshot.pages[type]?.get(it), System.currentTimeMillis(), ttl)
        } }
        mutable.update { it.copy(loading = it.loading - affected.toSet(), errors = it.errors - affected.toSet(),
            ready = it.ready && !needsCache) }
        diskJob = viewModelScope.launch {
            val loaded = window.mapValues { (type, ids) ->
                val missing = snapshot.categories[type].orEmpty().filter { it.id in ids &&
                    !providerDiscoveryFresh(snapshot.pages[type]?.get(it.id), System.currentTimeMillis(), ttl) }
                if (missing.isEmpty()) emptyMap() else try {
                    withContext(Dispatchers.IO) { source.cached(snapshot.profile, type, missing, true) }
                        .filterKeys { id -> missing.any { it.id == id } }
                } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyMap() }
            }
            ensureActive()
            if (token != generation || request != windowGeneration || mutable.value.profile != snapshot.profile) return@launch
            for (type in snapshot.categories.keys) {
                mergePreviewPages(type, loaded[type].orEmpty(), networkResult = false) {
                    token == generation && request == windowGeneration
                }
            }
            if (token == generation && request == windowGeneration) {
                hydratedWindowGeneration = request
                mutable.update { it.copy(ready = true) }
            }
        }
    }
    private suspend fun mergePreviewPages(type: CatalogType, additions: Map<String, ProviderDiscoveryPage>, networkResult: Boolean,
        current: () -> Boolean): Map<String, ProviderDiscoveryPage>? {
        while (current()) {
            val before = mutable.value
            val window = previewWindow ?: return null
            val recency = previewRecency.toList()
            val pages = withContext(Dispatchers.Default) {
                val records = before.pages[type].orEmpty().toMutableMap()
                val now = System.currentTimeMillis()
                fun timestamp(value: Long) = value.takeIf { it in 1..now } ?: Long.MIN_VALUE
                additions.forEach { (id, page) ->
                    if (networkResult || timestamp(page.cachedAt) >= timestamp(records[id]?.cachedAt ?: 0)) {
                        val bounded = page.copy(items = page.items.take(PREVIEW_ITEMS), truncated = page.truncated || page.items.size > PREVIEW_ITEMS)
                        records[id] = if (providerDiscoveryFresh(bounded, now, ttl)) bounded else bounded.copy(hasMore = true)
                    }
                }
                retainPreviewPages(type, records, window, recency)
            }
            val items = withContext(Dispatchers.Default) { providerDiscoveryItems(before.categories[type].orEmpty(), pages) }
            currentCoroutineContext().ensureActive()
            if (!current()) return null
            val after = before.copy(pages = before.pages + (type to pages), items = before.items + (type to items),
                capped = if (items.size >= MAX_ITEMS) before.capped + type else before.capped - type)
            if (mutable.compareAndSet(before, after)) return pages
        }
        return null
    }
    private fun retainPreviewPages(type: CatalogType, records: Map<String, ProviderDiscoveryPage>, window: Map<CatalogType, Set<String>>,
        recency: List<Pair<CatalogType, String>>): Map<String, ProviderDiscoveryPage> {
        val active = window[type].orEmpty()
        val retained = active + recency.asReversed().filter { it.first == type }.map { it.second }
            .filter { it !in active }.take((100 - active.size).coerceAtLeast(0))
        return boundProviderDiscoveryPages(records.filterKeys { it in retained })
    }
    fun pause(release: Boolean = false) {
        generation++; windowGeneration++; jobs.values.forEach { it.cancel() }; jobs.clear(); diskJob?.cancel()
        if (release) { session = null; previewWindow = null; previewRecency.clear(); mutable.value = ProviderDiscoveryState(); retainedRows = "" to emptyMap() }
        else mutable.update { it.copy(loading = emptySet()) }
    }
    fun load(type: CatalogType, refresh: Boolean = false, categoryIds: Set<String>? = null) {
        val active = session ?: return
        val snapshot = mutable.value
        if ((!snapshot.ready && !(previewWindow != null && refresh)) || jobs[type]?.isActive == true || snapshot.categories[type].isNullOrEmpty() || (!refresh && type in snapshot.capped)) return
        val token = generation
        val loadToken = (loadGenerations[type] ?: 0) + 1
        loadGenerations[type] = loadToken
        fun current() = token == generation && loadGenerations[type] == loadToken
        jobs[type] = viewModelScope.launch {
            mutable.update { it.copy(loading = it.loading + type, errors = it.errors - type) }
            try {
                val cats = snapshot.categories.getValue(type).filter { (categoryIds == null || it.id in categoryIds) &&
                    (previewWindow == null || it.id in previewWindow?.get(type).orEmpty()) }
                if (cats.isEmpty()) return@launch
                var pages = snapshot.pages[type].orEmpty().let { pages ->
                    if (refresh) pages.mapValues { (id, page) ->
                        if (categoryIds == null || id in categoryIds) page.copy(cachedAt = 0, hasMore = true) else page
                    } else pages
                }
                if (refresh) mutable.update { it.copy(pages = it.pages + (type to pages), capped = it.capped - type) }
                var visited = 0
                var requests = 0
                var cursor = nextCategory[type] ?: 0
                while (visited < cats.size && requests < 2) {
                    val category = cats[cursor % cats.size]; cursor++; visited++
                    val old = pages[category.id]
                    val fresh = providerDiscoveryFresh(old, System.currentTimeMillis(), ttl)
                    if (!refresh && fresh && (old?.hasMore == false || snapshot.preview)) continue
                    val requested = if (refresh || !fresh || old?.truncated == true) 1 else (old?.page ?: 0) + 1
                    val result = network.withLock {
                        if (previewWindow != null) withContext(Dispatchers.IO) {
                            pageLoader?.invoke(active, category, requested, snapshot.preview) ?: source.page(active, category, requested)
                        } else pageLoader?.invoke(active, category, requested, snapshot.preview) ?: source.page(active, category, requested)
                    }
                    ensureActive(); if (!current()) return@launch
                    if (previewWindow != null) pages = mutable.value.pages[type].orEmpty()
                    val merged = withContext(Dispatchers.Default) {
                        ((if (requested > 1) old?.items.orEmpty() else emptyList()) + result.items).distinctBy { it.id }
                    }
                    val otherCount = pages.filterKeys { it != category.id }.values.sumOf { it.items.size }
                    val room = minOf(if (snapshot.preview) PREVIEW_ITEMS else MAX_ITEMS, (MAX_ITEMS - otherCount).coerceAtLeast(0))
                    pages = pages + (category.id to ProviderDiscoveryPage(merged.take(room), result.page,
                        result.hasMore, System.currentTimeMillis(), truncated = merged.size > room))
                    if (previewWindow != null) {
                        pages = mergePreviewPages(type, mapOf(category.id to pages.getValue(category.id)), networkResult = true, current = ::current) ?: return@launch
                    } else {
                        val items = withContext(Dispatchers.Default) { providerDiscoveryItems(cats, pages) }
                        if (!current()) return@launch
                        mutable.update { it.copy(pages = it.pages + (type to pages), items = it.items + (type to items),
                            capped = if (items.size >= MAX_ITEMS || (!snapshot.preview && merged.size > room)) it.capped + type else it.capped - type) }
                    }
                    requests++
                    if (type in mutable.value.capped) break
                    delay(100) // Yield between provider pages; no concurrent speculative provider requests.
                }
                nextCategory[type] = cursor % cats.size
                if (current() && requests > 0) source.save(ProviderDiscoveryCache(snapshot.profile, type, pages, snapshot.preview))
            } catch (c: CancellationException) { throw c }
              catch (error: Exception) {
                val kinds = generateSequence<Throwable>(error) { it.cause }.take(6).map { it.javaClass.simpleName }.joinToString(" / ")
                val status = Regex("HTTP status: (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1) ?: "unknown"
                Log.w("NikTvDiscovery", "$type category metadata failed: $kinds; HTTP=$status")
                if (current()) mutable.update { it.copy(errors = it.errors + (type to providerDiscoveryFailure(error))) }
              }
            finally { if (current()) mutable.update { it.copy(loading = it.loading - type) } }
        }
    }
    companion object { const val MAX_ITEMS = 12_000; const val PREVIEW_ITEMS = 30 }
}
