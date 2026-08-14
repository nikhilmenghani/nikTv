package com.nikhil.niktv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.StalkerPortalClient
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class NikTvState(
    val savedProfile: PortalProfile? = null,
    val session: PortalSession? = null,
    val selectedType: CatalogType = CatalogType.LIVE_TV,
    val categories: List<Category> = emptyList(),
    val selectedCategory: Category? = null,
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val nowPlaying: Pair<String, String>? = null,
    val restoring: Boolean = true,
    val settingsOpen: Boolean = false,
    val selectedSeries: MediaItem? = null,
    val fullSearchItems: List<MediaItem>? = null,
    val fullSearchLoading: Boolean = false,
    val fullSearchCachedAtMillis: Long? = null
)

class NikTvViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProfileStore(application)
    private val portal = StalkerPortalClient(application)
    private val _state = MutableStateFlow(NikTvState())
    val state: StateFlow<NikTvState> = _state.asStateFlow()

    init { restoreSession() }

    private fun restoreSession() = viewModelScope.launch {
        val profile = store.activeProfile.first()
        if (profile == null) {
            _state.update { it.copy(restoring = false) }
            return@launch
        }
        _state.update { it.copy(savedProfile = profile) }
        runCatching {
            val saved = store.activeSession.first()?.takeIf { it.profile == profile }
            val session = saved?.takeIf { runCatching { portal.categories(it, CatalogType.LIVE_TV) }.isSuccess }
                ?: portal.authenticate(profile).also { store.save(it) }
            _state.update { it.copy(session = session, savedProfile = session.profile) }
            loadTypeInternal(session, CatalogType.LIVE_TV)
        }.onFailure { error ->
            store.clearSession()
            _state.update { it.copy(error = error.message ?: "Could not restore the saved session") }
        }
        _state.update { it.copy(restoring = false) }
    }

    fun connect(profile: PortalProfile) = task {
        val session = portal.authenticate(profile)
        store.save(session)
        _state.update { it.copy(session = session, savedProfile = session.profile) }
        loadTypeInternal(session, CatalogType.LIVE_TV)
    }

    fun reconnect() { _state.value.savedProfile?.let(::connect) }

    fun loadType(type: CatalogType) = task {
        val session = requireNotNull(_state.value.session)
        loadTypeInternal(session, type)
    }

    private suspend fun loadTypeInternal(session: PortalSession, type: CatalogType) {
        val categories = portal.categories(session, type)
        _state.update { it.copy(selectedType = type, categories = categories, selectedCategory = null, items = emptyList(), selectedSeries = null, fullSearchItems = null, fullSearchCachedAtMillis = null) }
        categories.firstOrNull()?.let { category ->
            _state.update { it.copy(selectedCategory = category) }
            _state.update { it.copy(items = portal.catalog(session, category)) }
        }
    }

    fun loadCategory(category: Category) = task {
        val session = requireNotNull(_state.value.session)
        _state.update { it.copy(selectedCategory = category, items = emptyList(), selectedSeries = null) }
        val items = portal.catalog(session, category)
        _state.update { it.copy(items = items) }
    }

    fun openMedia(item: MediaItem) {
        if (_state.value.selectedType == CatalogType.SERIES && _state.value.selectedSeries == null) {
            task {
                val session = requireNotNull(_state.value.session)
                _state.update { it.copy(selectedSeries = item, items = emptyList()) }
                _state.update { it.copy(items = portal.episodes(session, item)) }
            }
        } else play(item)
    }

    fun prepareFullSearch(forceRefresh: Boolean = false) {
        val snapshot = _state.value
        if (snapshot.selectedSeries != null || snapshot.fullSearchLoading) return
        val session = snapshot.session ?: return
        val type = snapshot.selectedType
        val categories = snapshot.categories
        val profileKey = session.profile.cacheKey()
        val now = System.currentTimeMillis()
        if (!forceRefresh && snapshot.fullSearchItems != null &&
            now - (snapshot.fullSearchCachedAtMillis ?: 0L) < SEARCH_CACHE_TTL_MILLIS) return
        viewModelScope.launch {
            _state.update { it.copy(fullSearchLoading = true) }
            if (!forceRefresh) {
                store.searchCatalog(type).first()?.takeIf { it.profileKey == profileKey }?.let { cached ->
                    _state.update { current ->
                        if (current.selectedType == type && current.selectedSeries == null)
                            current.copy(fullSearchItems = cached.items, fullSearchCachedAtMillis = cached.cachedAtMillis)
                        else current
                    }
                    if (now - cached.cachedAtMillis < SEARCH_CACHE_TTL_MILLIS) {
                        _state.update { it.copy(fullSearchLoading = false) }
                        return@launch
                    }
                }
            }
            runCatching {
                coroutineScope {
                    val categoryResults = categories.map { category -> async { runCatching { portal.catalog(session, category) } } }.awaitAll()
                    val failures = categoryResults.mapNotNull { it.exceptionOrNull() }
                    if (failures.isNotEmpty()) error("${failures.size} of ${categories.size} categories could not be loaded. ${failures.first().message.orEmpty()}")
                    categoryResults.flatMap { it.getOrThrow() }.distinctBy { it.id }
                }
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

    private fun play(item: MediaItem) = task {
        val s = _state.value
        val url = portal.playableUrl(requireNotNull(s.session), item, s.selectedType)
        _state.update { it.copy(nowPlaying = item.title to url) }
    }

    fun closeSeries() = task {
        val category = requireNotNull(_state.value.selectedCategory)
        val session = requireNotNull(_state.value.session)
        _state.update { it.copy(selectedSeries = null, items = emptyList()) }
        _state.update { it.copy(items = portal.catalog(session, category)) }
    }

    fun closePlayer() = _state.update { it.copy(nowPlaying = null) }
    fun openSettings() = _state.update { it.copy(settingsOpen = true) }
    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }
    fun reauthenticate() { _state.value.savedProfile?.let(::connect) }
    fun editProfile() = _state.update { it.copy(session = null, settingsOpen = false) }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun logout() = viewModelScope.launch { store.clear(); _state.value = NikTvState(restoring = false) }

    private fun task(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { block() }.onFailure { e -> _state.update { it.copy(error = e.message ?: "Unexpected error") } }
            _state.update { it.copy(loading = false) }
        }
    }

    private fun PortalProfile.cacheKey() = "$portalType|${portalUrl.trimEnd('/')}|${username.ifBlank { macAddress }}"

    companion object {
        private const val SEARCH_CACHE_TTL_MILLIS = 30 * 60 * 1000L
    }
}
