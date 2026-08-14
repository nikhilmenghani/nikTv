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
    val savedProfile: PortalProfile? = null,
    val session: PortalSession? = null,
    val selectedType: CatalogType = CatalogType.LIVE_TV,
    val categories: List<Category> = emptyList(),
    val selectedCategory: Category? = null,
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val nowPlaying: Pair<String, String>? = null
)

class NikTvViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProfileStore(application)
    private val portal = StalkerPortalClient(application)
    private val _state = MutableStateFlow(NikTvState())
    val state: StateFlow<NikTvState> = _state.asStateFlow()

    init { viewModelScope.launch { store.activeProfile.collect { _state.update { s -> s.copy(savedProfile = it) } } } }

    fun connect(profile: PortalProfile) = task {
        val session = portal.authenticate(profile)
        store.save(session.profile)
        _state.update { it.copy(session = session, savedProfile = session.profile) }
        loadType(CatalogType.LIVE_TV)
    }

    fun reconnect() { _state.value.savedProfile?.let(::connect) }

    fun loadType(type: CatalogType) = task {
        val session = requireNotNull(_state.value.session)
        val categories = portal.categories(session, type)
        _state.update { it.copy(selectedType = type, categories = categories, selectedCategory = null, items = emptyList()) }
        categories.firstOrNull()?.let(::loadCategory)
    }

    fun loadCategory(category: Category) = task {
        val session = requireNotNull(_state.value.session)
        _state.update { it.copy(selectedCategory = category, items = emptyList()) }
        val items = portal.catalog(session, category)
        _state.update { it.copy(items = items) }
    }

    fun play(item: MediaItem) = task {
        val s = _state.value
        val url = portal.playableUrl(requireNotNull(s.session), item, s.selectedType)
        _state.update { it.copy(nowPlaying = item.title to url) }
    }

    fun closePlayer() = _state.update { it.copy(nowPlaying = null) }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun logout() = viewModelScope.launch { store.clear(); _state.value = NikTvState() }

    private fun task(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { block() }.onFailure { e -> _state.update { it.copy(error = e.message ?: "Unexpected error") } }
            _state.update { it.copy(loading = false) }
        }
    }
}
