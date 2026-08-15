package com.nikhil.niktv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.StalkerPortalClient
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NikTvState(
    val profiles: List<PortalProfile> = emptyList(),
    val profileEditorOpen: Boolean = false,
    val savedProfile: PortalProfile? = null,
    val session: PortalSession? = null,
    val selectedType: CatalogType = CatalogType.LIVE_TV,
    val categories: List<Category> = emptyList(),
    val rawCategoriesByType: Map<CatalogType, List<Category>> = emptyMap(),
    val selectedCategory: Category? = null,
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val nowPlaying: PlayingMedia? = null,
    val restoring: Boolean = true,
    val settingsOpen: Boolean = false,
    val selectedSeries: MediaItem? = null,
    val fullSearchItems: List<MediaItem>? = null,
    val fullSearchLoading: Boolean = false,
    val fullSearchCachedAtMillis: Long? = null,
    val favorites: List<FavoriteItem> = emptyList(),
    val favoritesOpen: Boolean = false,
    val seriesOpenedFromFavorites: Boolean = false,
    val recentlyPlayed: List<RecentItem> = emptyList(),
    val homeOpen: Boolean = true,
    val seriesOpenedFromHome: Boolean = false,
    val playbackProgress: List<PlaybackProgress> = emptyList(),
    val playbackUrls: List<PlaybackUrl> = emptyList(),
    val cacheIntervalMinutes: Int = 60,
    val uiExperience: UiExperience = UiExperience.MODERN,
    val browseCache: BrowseCatalogCache? = null,
    val searchOpen: Boolean = false,
    val searchType: SearchContentType = SearchContentType.SERIES,
    val searchQuery: String = "",
    val searchResults: List<MediaItem> = emptyList(),
    val searchServerLoading: Boolean = false,
    val searchUsedServer: Boolean = false,
    val recentSearches: List<RecentSearch> = emptyList(),
    val searchPage: Int = 0,
    val searchHasMore: Boolean = false,
    val searchCategories: List<Category> = emptyList(),
    val searchCategoryId: String = "*",
    val categoryFilters: Map<String, List<String>> = emptyMap(),
    val categoryManagerOpen: Boolean = false,
    val categoryManagerType: CatalogType = CatalogType.LIVE_TV
)

class NikTvViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProfileStore(application)
    private val portal = StalkerPortalClient(application)
    private val _state = MutableStateFlow(NikTvState())
    val state: StateFlow<NikTvState> = _state.asStateFlow()

    init {
        prepareProfileChooser()
        viewModelScope.launch { store.favorites.collect { favorites -> _state.update { it.copy(favorites = favorites) } } }
        viewModelScope.launch { store.recentlyPlayed.collect { recent -> _state.update { it.copy(recentlyPlayed = recent) } } }
        viewModelScope.launch { store.playbackProgress.collect { progress -> _state.update { it.copy(playbackProgress = progress) } } }
        viewModelScope.launch { store.playbackUrls.collect { urls -> _state.update { it.copy(playbackUrls = urls) } } }
        viewModelScope.launch { store.cacheIntervalMinutes.collect { minutes -> _state.update { it.copy(cacheIntervalMinutes = minutes) } } }
        viewModelScope.launch { store.uiExperience.collect { value -> _state.update { it.copy(uiExperience = value) } } }
        viewModelScope.launch { store.recentSearches.collect { searches -> _state.update { it.copy(recentSearches = searches) } } }
        viewModelScope.launch { store.profiles.collect { profiles -> _state.update { it.copy(profiles = profiles) } } }
        viewModelScope.launch {
            store.categoryFilters.collect { filters ->
                val snapshot = _state.value
                val profileKey = snapshot.session?.profile?.cacheKey()
                val raw = snapshot.rawCategoriesByType[snapshot.selectedType] ?: snapshot.categories
                val filtered = filterCategories(raw, profileKey, snapshot.selectedType, filters)
                val selected = if (snapshot.selectedCategory != null && filtered.any { it.id == snapshot.selectedCategory.id }) {
                    snapshot.selectedCategory
                } else {
                    filtered.firstOrNull { snapshot.selectedType != CatalogType.SERIES || it.id != "*" }
                        ?: filtered.firstOrNull()
                }
                val needsReload = selected != snapshot.selectedCategory
                _state.update { it.copy(categoryFilters = filters, categories = filtered, selectedCategory = selected) }
                if (needsReload && selected != null && snapshot.session != null) {
                    loadCategory(selected)
                }
            }
        }
    }

    private fun prepareProfileChooser() = viewModelScope.launch {
        // A cold app launch always starts at the profile chooser. Authentication is
        // restored only after the viewer deliberately chooses a profile.
        val profiles = store.profiles.first()
        _state.update { it.copy(profiles = profiles, session = null, savedProfile = null,
            profileEditorOpen = profiles.isEmpty(), restoring = false) }
    }

    fun connect(profile: PortalProfile) = task {
        val session = portal.authenticate(profile)
        store.save(session)
        _state.update { it.copy(session = session, savedProfile = session.profile, profileEditorOpen = false) }
        loadTypeInternal(session, CatalogType.LIVE_TV)
    }

    fun reconnect() { _state.value.savedProfile?.let(::connect) }

    fun loadType(type: CatalogType) {
        viewModelScope.launch {
            runCatching { loadTypeInternal(requireNotNull(_state.value.session), type) }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not load ${type.title}") } }
        }
    }

    private suspend fun loadTypeInternal(session: PortalSession, type: CatalogType, forceRefresh: Boolean = false, preferredCategoryId: String? = null) {
        val profileKey = session.profile.cacheKey()
        val maxAge = _state.value.cacheIntervalMinutes * 60_000L
        var rawCategories: List<Category>? = null
        var cachedItems: Map<String, List<MediaItem>> = emptyMap()
        var cachedBrowse: BrowseCatalogCache? = null

        if (!forceRefresh) {
            store.browseCatalog(type, profileKey).first()?.takeIf { it.categories.isNotEmpty() }?.let { cached ->
                rawCategories = cached.categories
                cachedItems = cached.itemsByCategory
                cachedBrowse = cached
            }
        }
        if (rawCategories == null) {
            rawCategories = portal.categories(session, type)
        }
        val allCategories = rawCategories.orEmpty()
        val filterKey = filterKey(profileKey, type)
        val enabledIds = _state.value.categoryFilters[filterKey]
        val filteredCategories = if (enabledIds == null) allCategories else allCategories.filter { it.id in enabledIds }
        val selected = filteredCategories.firstOrNull { it.id == preferredCategoryId }
            ?: filteredCategories.firstOrNull { type != CatalogType.SERIES || it.id != "*" }
            ?: filteredCategories.firstOrNull()

        val items = if (selected != null) {
            val cachedForSelected = cachedItems[selected.id]
            if (!forceRefresh && cachedBrowse != null && cachedForSelected != null && (System.currentTimeMillis() - cachedBrowse.cachedAtMillis < maxAge)) {
                cachedForSelected
            } else {
                portal.catalog(session, selected)
            }
        } else emptyList()

        val cache = cachedBrowse?.copy(categories = allCategories, itemsByCategory = if (selected != null) cachedItems + (selected.id to items) else cachedItems)
            ?: BrowseCatalogCache(profileKey, type, System.currentTimeMillis(), allCategories, selected?.let { mapOf(it.id to items) }.orEmpty())

        if (allCategories.isNotEmpty() && (selected == null || items.isNotEmpty())) store.saveBrowseCatalog(cache)

        _state.update { current ->
            val updatedRaw = current.rawCategoriesByType + (type to allCategories)
            current.copy(
                selectedType = type,
                rawCategoriesByType = updatedRaw,
                categories = filteredCategories,
                selectedCategory = selected,
                items = items,
                selectedSeries = null,
                fullSearchItems = null,
                fullSearchCachedAtMillis = null,
                browseCache = cache
            )
        }
    }

    fun loadCategory(category: Category) = task {
        val session = requireNotNull(_state.value.session)
        _state.update { it.copy(selectedCategory = category, items = emptyList(), selectedSeries = null) }
        val cached = _state.value.browseCache?.takeIf { it.type == category.type }?.itemsByCategory?.get(category.id)
        val items = cached ?: portal.catalog(session, category)
        if (cached == null) {
            val existing = _state.value.browseCache
            if (existing != null && existing.type == category.type) {
                val updated = existing.copy(itemsByCategory = existing.itemsByCategory + (category.id to items))
                store.saveBrowseCatalog(updated)
                _state.update { it.copy(browseCache = updated) }
            }
        }
        _state.update { it.copy(items = items) }
    }

    fun refreshCatalog() = task {
        val snapshot = _state.value
        val series = snapshot.selectedSeries
        val session = requireNotNull(snapshot.session)
        if (series != null) {
            _state.update { it.copy(items = emptyList()) }
            val episodes = portal.episodes(session, series)
            _state.update { it.copy(items = episodes) }
        } else {
            loadTypeInternal(session, snapshot.selectedType, forceRefresh = true,
                preferredCategoryId = snapshot.selectedCategory?.id)
        }
    }

    fun setCacheIntervalMinutes(minutes: Int) = viewModelScope.launch {
        store.setCacheIntervalMinutes(minutes)
    }
    fun setUiExperience(value: UiExperience) = viewModelScope.launch { store.setUiExperience(value) }

    fun openMedia(item: MediaItem) {
        if (_state.value.selectedType == CatalogType.SERIES && _state.value.selectedSeries == null) {
            task {
                val session = requireNotNull(_state.value.session)
                _state.update { it.copy(selectedSeries = item, items = emptyList(), seriesOpenedFromFavorites = false, seriesOpenedFromHome = false) }
                _state.update { it.copy(items = portal.episodes(session, item)) }
            }
        } else play(item, _state.value.selectedType, _state.value.selectedSeries, _state.value.items)
    }

    fun prepareFullSearch(forceRefresh: Boolean = false) {
        val snapshot = _state.value
        if (snapshot.selectedSeries != null || snapshot.fullSearchLoading) return
        val session = snapshot.session ?: return
        val type = snapshot.selectedType
        val categories = snapshot.categories
        val profileKey = session.profile.cacheKey()
        val now = System.currentTimeMillis()
        val searchCacheTtl = snapshot.cacheIntervalMinutes * 60_000L
        if (!forceRefresh && snapshot.fullSearchItems != null &&
            now - (snapshot.fullSearchCachedAtMillis ?: 0L) < searchCacheTtl) return
        viewModelScope.launch {
            _state.update { it.copy(fullSearchLoading = true) }
            if (!forceRefresh) {
                store.searchCatalog(type, profileKey).first()?.let { cached ->
                    _state.update { current ->
                        if (current.selectedType == type && current.selectedSeries == null)
                            current.copy(fullSearchItems = cached.items, fullSearchCachedAtMillis = cached.cachedAtMillis)
                        else current
                    }
                    if (now - cached.cachedAtMillis < searchCacheTtl) {
                        _state.update { it.copy(fullSearchLoading = false) }
                        return@launch
                    }
                }
            }
            runCatching {
                portal.fullCatalog(session, type, categories)
            }.onSuccess { results ->
                val cachedAt = System.currentTimeMillis()
                store.saveSearchCatalog(SearchCatalogCache(profileKey, type, cachedAt, results))
                _state.update { current ->
                    if (current.selectedType == type && current.selectedSeries == null)
                        current.copy(fullSearchItems = results, fullSearchLoading = false, fullSearchCachedAtMillis = cachedAt)
                    else current.copy(fullSearchLoading = false)
                }
            }.onFailure { error ->
                _state.update { it.copy(fullSearchLoading = false, error = error.message ?: "Could not search the full catalog") }
            }
        }
    }

    fun refreshFullSearch() = prepareFullSearch(forceRefresh = true)

    fun openSearch() {
        _state.update { it.copy(searchOpen = true, settingsOpen = false, favoritesOpen = false) }
        loadSearchCategories(_state.value.searchType)
    }
    fun closeSearch() = _state.update { it.copy(searchOpen = false, searchServerLoading = false) }
    fun setSearchType(type: SearchContentType) = _state.update {
        it.copy(searchType = type, searchResults = emptyList(), searchUsedServer = false, searchPage = 0,
            searchHasMore = false, searchCategoryId = "*", searchCategories = emptyList())
    }.also { loadSearchCategories(type) }
    fun setSearchCategory(categoryId: String) = _state.update {
        it.copy(searchCategoryId = categoryId, searchResults = emptyList(), searchUsedServer = false, searchPage = 0, searchHasMore = false)
    }

    private fun loadSearchCategories(type: SearchContentType) = viewModelScope.launch {
        val catalogType = when (type) {
            SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
            SearchContentType.MOVIES -> CatalogType.MOVIES
            else -> CatalogType.SERIES
        }
        val session = _state.value.session ?: return@launch
        val profileKey = session.profile.cacheKey()
        val cached = store.browseCatalog(catalogType, profileKey).first()?.categories.orEmpty()
        val raw = if (cached.isNotEmpty()) cached else runCatching { portal.categories(session, catalogType) }.getOrDefault(emptyList())
        val filtered = filterCategories(raw, profileKey, catalogType, _state.value.categoryFilters)
        _state.update { current ->
            if (current.searchType == type) current.copy(searchCategories = filtered.distinctBy { it.id }) else current
        }
    }
    fun setSearchQuery(query: String) = _state.update { it.copy(searchQuery = query) }

    fun search(forceServer: Boolean = false) {
        val snapshot = _state.value
        val query = snapshot.searchQuery.trim()
        if (query.isBlank() || snapshot.searchServerLoading) return
        viewModelScope.launch {
            rememberSearch(query, snapshot.searchType)
            val saved = store.pagedSearches.first().firstOrNull {
                it.profileKey == snapshot.session?.profile?.cacheKey() && it.type == snapshot.searchType &&
                    it.categoryId == snapshot.searchCategoryId && it.query.equals(query, true)
            }
            val local = (localSearch(snapshot.searchType, query) + saved?.items.orEmpty()).distinctBy { it.id }
            _state.update { it.copy(searchResults = local, searchUsedServer = saved != null,
                searchPage = saved?.lastPage ?: 0, searchHasMore = saved?.hasMore ?: false) }
            if (!forceServer && local.isNotEmpty()) return@launch
            fetchSearchPage(query, snapshot.searchType, snapshot.searchCategoryId, 1, emptyList())
        }
    }

    fun loadMoreSearch() {
        val snapshot = _state.value
        if (!snapshot.searchHasMore || snapshot.searchServerLoading || snapshot.searchQuery.isBlank()) return
        viewModelScope.launch {
            repeat(3) {
                val current = _state.value
                if (!current.searchHasMore || current.searchServerLoading) return@launch
                val previousPage = current.searchPage
                fetchSearchPage(current.searchQuery.trim(), current.searchType, current.searchCategoryId,
                    current.searchPage + 1, current.searchResults)
                if (_state.value.searchPage == previousPage) return@launch
            }
        }
    }

    private suspend fun fetchSearchPage(query: String, type: SearchContentType, categoryId: String, page: Int, existing: List<MediaItem>) {
            _state.update { it.copy(searchServerLoading = true) }
            val session = requireNotNull(_state.value.session)
            runCatching { portal.search(session, type, query, page, categoryId) }
                .onSuccess { result ->
                    val combined = (existing + result.items).distinctBy { it.id }
                    val cache = SearchResultCache(session.profile.cacheKey(), type, query, categoryId, result.page, result.hasMore, combined)
                    store.savePagedSearch(cache)
                    _state.update { current -> current.copy(searchResults = combined,
                        searchServerLoading = false, searchUsedServer = true,
                        searchPage = result.page, searchHasMore = result.hasMore) }
                }.onFailure { error ->
                    _state.update { it.copy(searchServerLoading = false, error = error.message ?: "Server search failed") }
                }
    }

    private suspend fun localSearch(type: SearchContentType, query: String): List<MediaItem> {
        val catalogType = when (type) {
            SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
            SearchContentType.SERIES -> CatalogType.SERIES
            else -> CatalogType.MOVIES
        }
        val profileKey = _state.value.session?.profile?.cacheKey()
        val indexed = store.searchCatalog(catalogType, profileKey).first()?.items.orEmpty()
        val browsed = store.browseCatalog(catalogType, profileKey).first()?.itemsByCategory?.values?.flatten().orEmpty()
        val episodes = if (type == SearchContentType.EPISODES) {
            (_state.value.favorites.filter { it.kind == FavoriteKind.EPISODE }.map { it.media } +
                _state.value.recentlyPlayed.filter { it.kind == FavoriteKind.EPISODE }.map { it.media } +
                _state.value.items.filter { it.episodeNumber != null })
        } else emptyList()
        val source = if (type == SearchContentType.EPISODES) episodes else indexed + browsed
        val categoryId = _state.value.searchCategoryId
        return source.distinctBy { it.id }.filter { (categoryId == "*" || it.portalCategoryId == categoryId) && it.title.matchesSearchKeywords(query) }
    }

    private suspend fun rememberSearch(query: String, type: SearchContentType) {
        val entry = RecentSearch(query, type)
        store.saveRecentSearches((listOf(entry) + _state.value.recentSearches.filterNot { it.key == entry.key }).take(20))
    }
    fun useRecentSearch(search: RecentSearch) {
        _state.update { it.copy(searchQuery = search.query, searchType = search.type, searchOpen = true,
            searchCategoryId = "*", searchCategories = emptyList(), searchResults = emptyList()) }
        loadSearchCategories(search.type)
        search()
    }
    fun deleteRecentSearch(search: RecentSearch) = viewModelScope.launch {
        store.saveRecentSearches(_state.value.recentSearches.filterNot { it.key == search.key })
    }
    fun openSearchResult(item: MediaItem) {
        when (_state.value.searchType) {
            SearchContentType.LIVE_TV -> play(item, CatalogType.LIVE_TV)
            SearchContentType.SERIES -> task {
                val session = requireNotNull(_state.value.session)
                _state.update { it.copy(searchOpen = false, homeOpen = false, selectedType = CatalogType.SERIES, selectedSeries = item, items = emptyList()) }
                _state.update { it.copy(items = portal.episodes(session, item)) }
            }
            SearchContentType.MOVIES -> play(item, CatalogType.MOVIES)
            SearchContentType.EPISODES -> play(item, CatalogType.SERIES)
        }
    }

    private fun play(item: MediaItem, type: CatalogType, series: MediaItem? = null, episodes: List<MediaItem> = emptyList()) = task {
        playInternal(item, type, series, episodes)
    }

    private suspend fun playInternal(item: MediaItem, type: CatalogType, series: MediaItem?, episodes: List<MediaItem>) {
        var session = requireNotNull(_state.value.session)
        val urlKey = "${type.name}:${item.id}"
        val cachedUrl = _state.value.playbackUrls.firstOrNull { it.key == urlKey }?.url
        val url = cachedUrl ?: runCatching { portal.playableUrl(session, item, type) }.getOrElse { firstError ->
            if (session.profile.portalType != PortalType.STALKER || !firstError.isAuthenticationFailure()) throw firstError
            session = refreshSession(session.profile)
            portal.playableUrl(session, item, type)
        }.also { resolved ->
            val updated = (listOf(PlaybackUrl(urlKey, resolved)) + _state.value.playbackUrls.filterNot { it.key == urlKey })
                .take(MAX_PLAYBACK_URLS)
            _state.update { it.copy(playbackUrls = updated) }
            store.savePlaybackUrls(updated)
        }
        val orderedEpisodes = if (type == CatalogType.SERIES) episodes.sortedWith(
            compareBy<MediaItem>({ it.seasonNumber ?: Int.MAX_VALUE }, { it.title.episodeOrderFromTitle() ?: Int.MAX_VALUE }, { it.title.lowercase() })
        ) else emptyList()
        val nextEpisode = orderedEpisodes.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
            ?.let { orderedEpisodes.getOrNull(it + 1) }
        // Use a profile-scoped, content-based key so resume survives session/token refreshes.
        val progressKey = progressKeyFor(session.profile, item, type, series)
        val legacyProgressKey = legacyProgressKeyFor(item, type)
        // Keep backward compatibility with already-saved progress entries.
        val saved = _state.value.playbackProgress.firstOrNull { it.key == progressKey }
            ?: _state.value.playbackProgress
                .asSequence()
                .filter { it.key == legacyProgressKey }
                .maxByOrNull { it.updatedAtMillis }
        val resumePosition = saved?.positionMillis?.takeIf { saved.durationMillis <= 0L || saved.durationMillis - it > 5_000L } ?: 0L
        _state.update { it.copy(nowPlaying = PlayingMedia(item, url, nextEpisode, series, orderedEpisodes, resumePosition, progressKey)) }
        recordRecent(item, type, series)
    }

    private suspend fun refreshSession(profile: PortalProfile): PortalSession {
        val refreshed = portal.authenticate(profile)
        store.save(refreshed)
        _state.update { it.copy(session = refreshed, savedProfile = refreshed.profile) }
        return refreshed
    }

    fun savePlaybackProgress(key: String, positionMillis: Long, durationMillis: Long) {
        if (key.isBlank()) return
        viewModelScope.launch {
            val current = _state.value.playbackProgress
            val updated = if (durationMillis > 0L && positionMillis >= durationMillis - 5_000L) {
                current.filterNot { it.key == key }
            } else {
                (listOf(PlaybackProgress(key, positionMillis.coerceAtLeast(0L), durationMillis)) + current.filterNot { it.key == key })
                    .take(MAX_PROGRESS_ITEMS)
            }
            _state.update { it.copy(playbackProgress = updated) }
            store.savePlaybackProgress(updated)
        }
    }

    fun playNextEpisode() {
        val playing = _state.value.nowPlaying ?: return
        val next = playing.nextEpisode ?: return
        viewModelScope.launch {
            runCatching { playInternal(next, CatalogType.SERIES, playing.series, playing.episodeQueue) }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not play the next episode") } }
        }
    }

    private suspend fun recordRecent(item: MediaItem, type: CatalogType, series: MediaItem?) {
        val additions = buildList {
            if (type == CatalogType.SERIES && series != null) {
                add(RecentItem(FavoriteKind.SERIES, series, lastPlayed = item))
            } else add(RecentItem(when (type) {
                CatalogType.LIVE_TV -> FavoriteKind.CHANNEL
                CatalogType.MOVIES -> FavoriteKind.MOVIE
                CatalogType.SERIES -> FavoriteKind.SERIES
                CatalogType.RADIO -> FavoriteKind.CHANNEL
            }, item, series))
        }
        val updated = (additions + _state.value.recentlyPlayed.filterNot { it.kind == FavoriteKind.EPISODE })
            .distinctBy { it.key }.take(MAX_RECENT_ITEMS)
        _state.update { it.copy(recentlyPlayed = updated) }
        store.saveRecentlyPlayed(updated)
    }

    fun closeSeries() = task {
        if (_state.value.seriesOpenedFromHome) {
            _state.update { it.copy(selectedSeries = null, seriesOpenedFromHome = false, homeOpen = true) }
            return@task
        }
        if (_state.value.seriesOpenedFromFavorites) {
            _state.update { it.copy(selectedSeries = null, seriesOpenedFromFavorites = false, favoritesOpen = true) }
            return@task
        }
        val category = requireNotNull(_state.value.selectedCategory)
        val session = requireNotNull(_state.value.session)
        _state.update { it.copy(selectedSeries = null, items = emptyList()) }
        _state.update { it.copy(items = portal.catalog(session, category)) }
    }

    fun closePlayer() = _state.update { it.copy(nowPlaying = null) }
    fun openSettings() = _state.update { it.copy(settingsOpen = true) }
    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }
    fun reauthenticate() { _state.value.savedProfile?.let(::connect) }
    fun editProfile() = _state.update { it.copy(session = null, settingsOpen = false, profileEditorOpen = true) }
    fun addProfile() = _state.update { it.copy(session = null, savedProfile = null, settingsOpen = false, profileEditorOpen = true) }
    fun cancelProfileEditor() = _state.update { current ->
        val fallback = current.profiles.firstOrNull()
        current.copy(profileEditorOpen = false, savedProfile = fallback)
    }
    fun openCategoryManager(type: CatalogType = _state.value.selectedType) {
        _state.update { it.copy(categoryManagerOpen = true, categoryManagerType = type) }
        loadRawCategoriesFor(type)
    }
    fun closeCategoryManager() = _state.update { it.copy(categoryManagerOpen = false) }
    fun setCategoryManagerType(type: CatalogType) {
        _state.update { it.copy(categoryManagerType = type) }
        loadRawCategoriesFor(type)
    }
    fun loadRawCategoriesFor(type: CatalogType) {
        val snapshot = _state.value
        if (snapshot.rawCategoriesByType[type]?.isNotEmpty() == true) return
        val session = snapshot.session ?: return
        viewModelScope.launch {
            val cached = store.browseCatalog(type, session.profile.cacheKey()).first()?.categories.orEmpty()
            val categories = if (cached.isNotEmpty()) cached else runCatching { portal.categories(session, type) }.getOrDefault(emptyList())
            if (categories.isNotEmpty()) {
                _state.update { current ->
                    val updated = current.rawCategoriesByType + (type to categories)
                    current.copy(rawCategoriesByType = updated)
                }
            }
        }
    }
    fun setCategoryFilter(type: CatalogType, enabledCategoryIds: List<String>) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        val raw = _state.value.rawCategoriesByType[type].orEmpty()
        if (raw.isNotEmpty() && enabledCategoryIds.size >= raw.size && raw.all { it.id in enabledCategoryIds }) {
            store.clearCategoryFilter(profileKey, type)
        } else {
            store.saveCategoryFilter(profileKey, type, enabledCategoryIds)
        }
    }
    fun toggleCategoryFilter(type: CatalogType, categoryId: String) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        val raw = _state.value.rawCategoriesByType[type].orEmpty()
        val filterKey = filterKey(profileKey, type)
        val currentEnabled = _state.value.categoryFilters[filterKey] ?: raw.map { it.id }
        val updated = if (categoryId in currentEnabled) {
            currentEnabled - categoryId
        } else {
            currentEnabled + categoryId
        }
        setCategoryFilter(type, updated)
    }
    fun selectAllCategories(type: CatalogType) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        store.clearCategoryFilter(profileKey, type)
    }
    fun deselectAllCategories(type: CatalogType) = viewModelScope.launch {
        val profileKey = _state.value.session?.profile?.cacheKey() ?: return@launch
        store.saveCategoryFilter(profileKey, type, emptyList())
    }

    fun switchProfile(profile: PortalProfile) = task {
        store.activate(profile)
        val session = store.sessionFor(profile) ?: portal.authenticate(profile).also { store.save(it) }
        _state.update { current -> current.copy(session = session, savedProfile = profile, settingsOpen = false, profileEditorOpen = false,
            searchOpen = false, favoritesOpen = false, homeOpen = true, categories = emptyList(), rawCategoriesByType = emptyMap(),
            categoryManagerOpen = false, items = emptyList(),
            selectedSeries = null, browseCache = null, fullSearchItems = null, playbackUrls = emptyList()) }
        loadTypeInternal(session, CatalogType.LIVE_TV)
    }
    fun removeProfile(profile: PortalProfile) = viewModelScope.launch {
        store.removeProfile(profile)
        if (_state.value.savedProfile?.cacheKey() == profile.cacheKey()) {
            _state.update { it.copy(session = null, savedProfile = null, settingsOpen = false) }
        }
    }
    fun openFavorites() = _state.update { it.copy(favoritesOpen = true, homeOpen = false, settingsOpen = false, searchOpen = false) }
    fun closeFavorites() = _state.update { it.copy(favoritesOpen = false) }
    fun openHome() = _state.update { it.copy(homeOpen = true, favoritesOpen = false, settingsOpen = false, searchOpen = false) }
    fun closeHome() = _state.update { it.copy(homeOpen = false) }

    fun toggleFavorite(item: MediaItem) {
        val snapshot = _state.value
        val kind = when {
            snapshot.selectedType == CatalogType.LIVE_TV -> FavoriteKind.CHANNEL
            snapshot.selectedType == CatalogType.MOVIES -> FavoriteKind.MOVIE
            snapshot.selectedSeries != null -> FavoriteKind.EPISODE
            else -> FavoriteKind.SERIES
        }
        toggleFavorite(FavoriteItem(kind, item, if (kind == FavoriteKind.EPISODE) snapshot.selectedSeries else null))
    }

    fun toggleFavorite(favorite: FavoriteItem) = viewModelScope.launch {
        val updated = _state.value.favorites.toMutableList().apply {
            val index = indexOfFirst { it.key == favorite.key }
            if (index >= 0) removeAt(index) else add(favorite)
        }
        _state.update { it.copy(favorites = updated) }
        store.saveFavorites(updated)
    }

    fun openFavorite(favorite: FavoriteItem) {
        when (favorite.kind) {
            FavoriteKind.CHANNEL -> play(favorite.media, CatalogType.LIVE_TV)
            FavoriteKind.MOVIE -> play(favorite.media, CatalogType.MOVIES)
            FavoriteKind.EPISODE -> task {
                val episodes = favorite.series?.let { portal.episodes(requireNotNull(_state.value.session), it) }.orEmpty()
                playInternal(favorite.media, CatalogType.SERIES, favorite.series, episodes)
            }
            FavoriteKind.SERIES -> task {
                val session = requireNotNull(_state.value.session)
                _state.update { it.copy(favoritesOpen = false, selectedType = CatalogType.SERIES, selectedSeries = favorite.media, seriesOpenedFromFavorites = true, items = emptyList()) }
                _state.update { it.copy(items = portal.episodes(session, favorite.media)) }
            }
        }
    }
    fun openRecent(recent: RecentItem) {
        when (recent.kind) {
            FavoriteKind.CHANNEL -> play(recent.media, CatalogType.LIVE_TV)
            FavoriteKind.MOVIE -> play(recent.media, CatalogType.MOVIES)
            FavoriteKind.EPISODE -> task {
                val episodes = recent.series?.let { portal.episodes(requireNotNull(_state.value.session), it) }.orEmpty()
                playInternal(recent.media, CatalogType.SERIES, recent.series, episodes)
            }
            FavoriteKind.SERIES -> task {
                val session = requireNotNull(_state.value.session)
                val episodes = portal.episodes(session, recent.media)
                val resumeEpisode = recent.lastPlayed?.let { saved -> episodes.firstOrNull { it.id == saved.id } ?: saved }
                if (resumeEpisode != null) playInternal(resumeEpisode, CatalogType.SERIES, recent.media, episodes)
                else {
                    _state.update { it.copy(homeOpen = false, selectedType = CatalogType.SERIES, selectedSeries = recent.media, seriesOpenedFromFavorites = false, seriesOpenedFromHome = true, items = episodes) }
                }
            }
        }
    }
    fun removeRecent(recent: RecentItem) = viewModelScope.launch {
        val updated = _state.value.recentlyPlayed.filterNot { it.key == recent.key }
        _state.update { it.copy(recentlyPlayed = updated) }
        store.saveRecentlyPlayed(updated)
    }
    fun clearRecent(kind: FavoriteKind) = viewModelScope.launch {
        val updated = _state.value.recentlyPlayed.filterNot { it.kind == kind }
        _state.update { it.copy(recentlyPlayed = updated) }
        store.saveRecentlyPlayed(updated)
    }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun logout() = viewModelScope.launch { store.clear(); _state.value = NikTvState(restoring = false) }

    private fun task(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { block() }.onFailure { e -> _state.update { it.copy(error = e.message ?: "Unexpected error") } }
            _state.update { it.copy(loading = false) }
        }
    }

    private fun filterKey(profileKey: String, type: CatalogType): String = "$profileKey|${type.name}"
    private fun filterCategories(raw: List<Category>, profileKey: String?, type: CatalogType, filters: Map<String, List<String>>): List<Category> {
        if (profileKey == null) return raw
        val key = filterKey(profileKey, type)
        val enabledIds = filters[key] ?: return raw
        val enabledSet = enabledIds.toSet()
        return raw.filter { it.id in enabledSet }
    }
    private fun progressKeyFor(profile: PortalProfile, item: MediaItem, type: CatalogType, series: MediaItem?): String {
        // Keep keys bounded for storage while still differentiating profile/type/media.
        val base = buildString {
            append("progress-v2|")
            append(profile.cacheKey())
            append('|')
            append(type.name)
            append('|')
            append(stableMediaKeyPart(item, type, series))
        }
        return if (base.length <= 240) base else base.take(240)
    }
    private fun legacyProgressKeyFor(item: MediaItem, type: CatalogType): String = "${type.name}:${item.id}"
    private fun stableMediaKeyPart(item: MediaItem, type: CatalogType, series: MediaItem?): String {
        val normalizedTitle = item.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
        // Fingerprint the command path (without query params) to avoid volatile-token mismatches.
        val commandFingerprint = item.command.orEmpty()
            .substringAfter(' ')
            .substringBefore('?')
            .substringAfter("://", missingDelimiterValue = item.command.orEmpty().substringAfter(' ').substringBefore('?'))
            .substringAfter('/')
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}/._-]+"), "")
            .take(64)
        return when (type) {
            CatalogType.SERIES -> "series:${series?.id ?: item.id}|s:${item.seasonNumber ?: -1}|e:${item.episodeNumber ?: -1}|$normalizedTitle|$commandFingerprint"
            CatalogType.MOVIES -> "movie:$normalizedTitle|$commandFingerprint"
            else -> "item:${item.id}"
        }
    }
    private fun String.episodeOrderFromTitle(): Int? = listOf(
        Regex("(?i)S\\d+[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d+)"),
        Regex("(?i)\\bEP(?:ISODE)?[ ._:-]*(\\d+)"),
        Regex("(?i)\\bE[ ._:-]*(\\d+)"),
        Regex("\\b(\\d+)\\b")
    ).firstNotNullOfOrNull { it.findAll(this).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun String.matchesSearchKeywords(query: String): Boolean {
        val words = lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
        val keys = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
        return keys.isNotEmpty() && keys.all { key -> words.any { word -> word.contains(key) } }
    }

    private fun Throwable.isAuthenticationFailure(): Boolean = message.orEmpty().let { text ->
        text.contains("Authorization failed", ignoreCase = true) ||
            text.contains("HTTP status: 401") || text.contains("HTTP status: 403")
    }

    companion object {
        private const val MAX_RECENT_ITEMS = 100
        private const val MAX_PROGRESS_ITEMS = 200
        private const val MAX_PLAYBACK_URLS = 500
    }
}
