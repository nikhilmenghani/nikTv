package com.nikhil.niktv.discovery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DiscoveryViewModel @JvmOverloads constructor(app: Application,
    private val repository: DiscoveryDataSource = DiscoveryRepository(app)) : AndroidViewModel(app) {
    private val preferences = app.getSharedPreferences("discovery_trial_v1", 0)
    private val _state = MutableStateFlow(DiscoveryState())
    val state = _state.asStateFlow()
    private val screen = DiscoveryGeneration()
    private val detail = DiscoveryGeneration()
    private val seasonGeneration = DiscoveryGeneration()
    private val heroGeneration = DiscoveryGeneration()
    private val focusGeneration = DiscoveryGeneration()
    private var completedFocusKey: String? = null
    var preparedPresentation: DiscoveryCard? = null
        private set
    fun retainPreparedPresentation(card: DiscoveryCard) { preparedPresentation = card }
    private var heroRequestKey: String? = null
    private val jobs = mutableMapOf<String, Job>()
    private val stack = mutableListOf<DiscoveryCard>()
    private fun preferenceKey(profile: String, surface: DashboardSurface) = "$profile:${surface.name}"
    fun bind(profile: String, surface: DashboardSurface, originalSections: List<TmdbHomeSection>?) {
        if (_state.value.profile == profile && _state.value.surface == surface) return
        jobs.values.forEach { it.cancel() }; jobs.clear(); stack.clear()
        focusGeneration.next(); preparedPresentation = null; completedFocusKey = null
        screen.next(); detail.next(); seasonGeneration.next(); heroGeneration.next()
        val key = preferenceKey(profile, surface)
        val defaults = originalSections ?: when (surface) {
            DashboardSurface.MOVIES -> listOf(TmdbHomeSection.TOP_RATED_MOVIES, TmdbHomeSection.NEW_HOLLYWOOD_MOVIES, TmdbHomeSection.NEW_BOLLYWOOD_MOVIES)
            DashboardSurface.SERIES -> listOf(TmdbHomeSection.TOP_RATED_SERIES, TmdbHomeSection.NEW_HOLLYWOOD_SERIES, TmdbHomeSection.NEW_BOLLYWOOD_SERIES)
            else -> listOf(TmdbHomeSection.TOP_RATED_MOVIES, TmdbHomeSection.TOP_RATED_SERIES, TmdbHomeSection.NEW_BOLLYWOOD_MOVIES)
        }
        if (!preferences.contains(key)) preferences.edit().putString(key, defaults.joinToString(",") { it.name }).apply()
        val sections = preferences.getString(key, "").orEmpty().split(',').mapNotNull { name ->
            TmdbHomeSection.entries.firstOrNull { it.name == name }
        }.filter { surface == DashboardSurface.HOME || it.series == (surface == DashboardSurface.SERIES) }.distinct()
        _state.value = DiscoveryState(profile = profile, surface = surface, sections = sections)
        if (surface != DashboardSurface.SERIES) loadFeed(TmdbHomeSection.TRENDING_MOVIES)
        if (surface != DashboardSurface.MOVIES) loadFeed(TmdbHomeSection.TRENDING_SERIES)
    }
    fun pause() {
        focusGeneration.next()
        screen.next(); detail.next(); seasonGeneration.next(); heroGeneration.next()
        jobs.values.forEach { it.cancel() }; jobs.clear()
        _state.update { it.copy(loading = emptySet()) }
    }
    fun resume(profile: String, surface: DashboardSurface) {
        val current = _state.value
        if (current.profile != profile || current.surface != surface || profile.isBlank()) return
        if (surface != DashboardSurface.SERIES) loadFeed(TmdbHomeSection.TRENDING_MOVIES, true)
        if (surface != DashboardSurface.MOVIES) loadFeed(TmdbHomeSection.TRENDING_SERIES, true)
        current.detail?.takeIf { current.details == null }?.let(::showDetails)
    }
    fun configure(sections: List<TmdbHomeSection>) {
        val s = _state.value
        preferences.edit().putString(preferenceKey(s.profile, s.surface), sections.joinToString(",") { it.name }).apply()
        _state.update { it.copy(sections = sections) }
    }
    private fun publishFeed(section: TmdbHomeSection, cards: List<DiscoveryCard>) = _state.update { current ->
        val feeds = current.feeds + (section to cards.filter { it.released() })
        val heroes = discoveryHeroes(current.surface,
            feeds[TmdbHomeSection.TRENDING_MOVIES].orEmpty(), feeds[TmdbHomeSection.TRENDING_SERIES].orEmpty())
        val selected = heroes.indexOfFirst { it.key == current.hero?.key }.takeIf { it >= 0 } ?: 0
        current.copy(feeds = feeds, heroes = heroes, heroIndex = selected)
    }
    fun loadFeed(section: TmdbHomeSection, retry: Boolean = false) {
        val key = "feed:${section.name}"
        if (jobs[key]?.isActive == true || (!retry && (section in _state.value.feeds || key in _state.value.errors))) return
        val token = screen.nextToken()
        jobs[key] = viewModelScope.launch {
            _state.update { it.copy(loading = it.loading + key, errors = it.errors - key) }
            try {
                val cards = repository.feed(section) { cached -> if (screen.accepts(token)) publishFeed(section, cached) }
                if (screen.accepts(token)) publishFeed(section, cards)
            } catch (c: CancellationException) { throw c }
              catch (_: Exception) { if (screen.accepts(token)) _state.update { it.copy(errors = it.errors + (key to "Could not load ${section.title}. Retry when connected.")) } }
            finally { if (screen.accepts(token)) _state.update { it.copy(loading = it.loading - key) } }
        }
    }
    fun rotate(delta: Int) {
        focusGeneration.next(); jobs.remove("focused-provider")?.cancel()
        _state.update { it.copy(focusedEntryKey = null, focusedPresentation = null) }
        pauseHeroMetadata()
        _state.update { if (it.heroes.isEmpty()) it else it.copy(heroIndex = Math.floorMod(it.heroIndex + delta, it.heroes.size)) }
    }
    fun pauseHeroMetadata() { heroGeneration.next(); jobs.remove("hero-metadata")?.cancel(); heroRequestKey = null }
    /** Leave the feed/hero intact when a TV renderer does not use focused-tile presentation. */
    fun clearFocusedPresentation() {
        focusGeneration.next()
        jobs.remove("focused-provider")?.cancel()
        completedFocusKey = null
        _state.update { current ->
            if (current.focusedEntryKey == null && current.focusedPresentation == null) current
            else current.copy(focusedEntryKey = null, focusedPresentation = null)
        }
    }
    fun presentFocusedCard(key: String, card: DiscoveryCard) {
        focusGeneration.next(); jobs.remove("focused-provider")?.cancel()
        completedFocusKey = key
        _state.update { it.copy(focusedEntryKey = key, focusedPresentation = card) }
    }
    fun presentProvider(key: String, item: MediaItem, type: CatalogType) {
        val snapshot = _state.value
        if (snapshot.focusedEntryKey == key && completedFocusKey == key) return
        val token = focusGeneration.next()
        completedFocusKey = null
        jobs.remove("focused-provider")?.cancel()
        _state.update { it.copy(focusedEntryKey = key, focusedPresentation = providerDetailFallback(item, type)) }
        jobs["focused-provider"] = viewModelScope.launch {
            fun publish(details: DiscoveryDetails) {
                if (focusGeneration.accepts(token)) _state.update { current ->
                    if (current.profile == snapshot.profile && current.surface == snapshot.surface && current.focusedEntryKey == key)
                        current.copy(focusedPresentation = details.card) else current
                }
            }
            try {
                publish(repository.providerDetails(item, type, ::publish))
                if (focusGeneration.accepts(token)) completedFocusKey = key
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* A metadata miss leaves the provider title usable. */ }
        }
    }
    /** Only public, cache-backed TMDB metadata; never resolve an IPTV stream while browsing. */
    fun prepareHero() {
        val snapshot = _state.value
        val card = snapshot.hero ?: return
        if (snapshot.detail != null || snapshot.heroPresentation?.key == card.key ||
            jobs["hero-metadata"]?.isActive == true && heroRequestKey == card.key) return
        pauseHeroMetadata()
        heroRequestKey = card.key
        val token = heroGeneration.next()
        jobs["hero-metadata"] = viewModelScope.launch {
            fun publish(value: DiscoveryDetails) {
                if (heroGeneration.accepts(token)) _state.update { current ->
                    if (current.profile == snapshot.profile && current.surface == snapshot.surface &&
                        current.detail == null && current.hero?.key == card.key) current.copy(heroPresentation = value.card) else current
                }
            }
            try { publish(repository.details(card, ::publish)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Optional title artwork must not block or show a global error. */ }
        }
    }
    suspend fun actorCredits(person: DiscoveryPerson, stale: (List<DiscoveryCard>) -> Unit) = repository.actorCredits(person, stale)
    fun openDetails(card: DiscoveryCard) { stack.add(card); showDetails(card) }
    fun closeDetails() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.lastOrNull()?.let(::showDetails) ?: run {
            detail.next(); seasonGeneration.next()
            jobs.filterKeys { !it.startsWith("feed:") }.values.forEach { it.cancel() }
            _state.update { it.copy(detail = null, details = null, episodes = emptyList(), related = emptyList(), collection = emptyList(), reviews = emptyList(), season = null,
                loading = it.loading.filter { key -> key.startsWith("feed:") }.toSet(), errors = it.errors.filterKeys { key -> key.startsWith("feed:") }) }
        }
    }
    private fun showDetails(card: DiscoveryCard) {
        pauseHeroMetadata()
        val token = detail.next(); seasonGeneration.next()
        jobs.filterKeys { !it.startsWith("feed:") }.values.forEach { it.cancel() }
        jobs.keys.removeAll { !it.startsWith("feed:") }
        _state.update { it.copy(detail = card, details = null, related = emptyList(), collection = emptyList(), reviews = emptyList(), episodes = emptyList(), season = null,
            errors = it.errors.filterKeys { key -> key.startsWith("feed:") }, loading = it.loading.filter { key -> key.startsWith("feed:") }.toSet()) }
        jobs["detail"] = viewModelScope.launch {
            _state.update { it.copy(loading = it.loading + "detail") }
            fun publish(value: DiscoveryDetails) { if (detail.accepts(token)) _state.update { it.copy(details = value) } }
            try { publish(repository.details(card, ::publish)) }
            catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (detail.accepts(token)) _state.update { it.copy(errors = it.errors + ("detail" to "Additional information is unavailable. You can still try Play.")) } }
            finally { if (detail.accepts(token)) _state.update { it.copy(loading = it.loading - "detail") } }
        }
    }
    fun loadSupplement(section: String) {
        val card = _state.value.detail ?: return
        val key = "extra:$section"
        if (key in jobs && (jobs[key]?.isActive == true || key !in _state.value.errors)) return
        val token = detail.nextToken()
        jobs[key] = viewModelScope.launch {
            _state.update { it.copy(loading = it.loading + key, errors = it.errors - key) }
            try {
                when (section) {
                    "related" -> repository.related(card).let { cards -> if (detail.accepts(token)) _state.update { it.copy(related = cards) } }
                    "reviews" -> if (!card.series) repository.reviews(card).let { reviews -> if (detail.accepts(token)) _state.update { it.copy(reviews = reviews) } }
                    "collection" -> _state.value.details?.collection?.id?.let { repository.collection(it) }?.let { cards -> if (detail.accepts(token)) _state.update { it.copy(collection = cards) } }
                }
            } catch (c: CancellationException) { throw c }
              catch (_: Exception) { if (detail.accepts(token)) _state.update { it.copy(errors = it.errors + (key to "This section is unavailable. Retry")) } }
            finally { if (detail.accepts(token)) _state.update { it.copy(loading = it.loading - key) } }
        }
    }
    fun loadSeason(season: Int) {
        val card = _state.value.detail?.takeIf { it.series } ?: return
        val token = detail.nextToken(); val seasonToken = seasonGeneration.next()
        jobs["season"]?.cancel()
        _state.update { it.copy(season = season, episodes = emptyList(), loading = it.loading + "season", errors = it.errors - "season") }
        jobs["season"] = viewModelScope.launch {
            try {
                val episodes = repository.episodes(card, season)
                if (detail.accepts(token) && seasonGeneration.accepts(seasonToken)) _state.update { it.copy(episodes = episodes) }
            } catch (c: CancellationException) { throw c }
              catch (_: Exception) { if (detail.accepts(token) && seasonGeneration.accepts(seasonToken)) _state.update { it.copy(errors = it.errors + ("season" to "Could not load episodes. Retry")) } }
            finally { if (detail.accepts(token) && seasonGeneration.accepts(seasonToken)) _state.update { it.copy(loading = it.loading - "season") } }
        }
    }
}
