package com.nikhil.niktv.sports

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SportsState(
    val events: List<SportsEvent> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val event: SportsEvent? = null,
    val streams: List<SportsStream> = emptyList(),
    val loadingStreams: Boolean = false,
    val streamError: String? = null,
    val resolving: Int? = null,
    val playing: SportsStream? = null,
    val playingIndex: Int? = null,
    val playback: SportsPlaybackSession = SportsPlaybackSession(),
    val lastCheckedAt: Long? = null,
    val tab: SportsTab = SportsTab.EVENTS,
    val filters: List<SportsEventFilter> = emptyList(),
    val channels: List<SportsChannel> = emptyList(),
    val categories: List<SportsCategory> = emptyList(),
    val collection: SportsCategory? = null,
    val collectionChannels: List<SportsChannel> = emptyList(),
    val channel: SportsChannel? = null,
    val detail: SportsChannelDetail = SportsChannelDetail(),
    val favourites: List<SportsChannel> = emptyList(),
    val favouritesOnly: Boolean = false,
    val customPlaylists: List<SportsCategory> = emptyList(),
    val libraryError: String? = null,
    val importing: Boolean = false,
    val restoreBrowseFocus: Boolean = false,
    val channelFlags: Map<String, SportsChannelFlag> = emptyMap(),
    val availabilityFilter: SportsAvailabilityFilter = SportsAvailabilityFilter.ALL,
) {
    val navigationIndex: Int? get() = resolving ?: playingIndex
    val selected: SportsChannel? get() = channel ?: event?.asChannel()
    val route: String get() = selected?.let { "detail:${it.key}" }
        ?: if (favouritesOnly) "favourites:${tab.name}" else collection?.let { "collection:${it.id}" } ?: "tab:${tab.name}"
}

internal class SportsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SportsRepository(country = { sportsDeviceCountry(application) })
    private val resolver = SportsStreamResolver(repository.transport, SportsWebResolver(application)::resolve)
    private val library = SportsLibraryStore(application)
    private val availability = SportsAvailabilityStore(application)
    private val mutable = MutableStateFlow(SportsState(favourites = library.favourites(), customPlaylists = library.playlists(),
        channelFlags = availability.flags(), availabilityFilter = availability.filter()))
    val state = mutable.asStateFlow()
    private var catalogueJob: Job? = null
    private var selectionJob: Job? = null
    private var catalogueGeneration = 0L
    private var selectionGeneration = 0L
    private val playback = SportsPlaybackController(viewModelScope, { key ->
        val channel = mutable.value.selected?.takeIf { it.key == key } ?: throw SportsException("This channel is no longer selected.")
        loadDetail(channel, fresh = true).streams
    }, resolver::resolve)
    private val checked = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch {
            playback.state.collect(::applyPlayback)
        }
        refresh()
    }

    fun refreshIfStale() {
        val value = mutable.value
        if (value.selected == null && !value.favouritesOnly && catalogueJob?.isActive != true &&
            sportsCatalogueNeedsRefresh(checked[value.route], System.currentTimeMillis())) refresh(force = false)
    }

    fun selectTab(tab: SportsTab) {
        if (mutable.value.tab == tab && mutable.value.collection == null && !mutable.value.favouritesOnly) return
        cancelSelection()
        mutable.update { it.copy(tab = tab, collection = null, collectionChannels = emptyList(), event = null, channel = null,
            detail = SportsChannelDetail(), streams = emptyList(), favouritesOnly = false, lastCheckedAt = null, error = null, restoreBrowseFocus = false) }
        refresh(false)
    }

    fun openCategory(category: SportsCategory) {
        cancelSelection()
        mutable.update { it.copy(tab = SportsTab.CATEGORIES, collection = category, collectionChannels = emptyList(),
            channel = null, event = null, favouritesOnly = false, lastCheckedAt = null, restoreBrowseFocus = false) }
        refresh(false)
    }

    fun toggleFavourites() {
        if (mutable.value.selected != null) return
        catalogueJob?.cancel(); catalogueGeneration++
        mutable.update { it.copy(favouritesOnly = !it.favouritesOnly, loading = false, error = null) }
        if (!mutable.value.favouritesOnly) refresh(false)
    }

    fun toggleFavourite(item: SportsChannel) { val saved = library.toggle(item); mutable.update { it.copy(favourites = saved) } }

    fun toggleChannelFlag(item: SportsChannel) {
        val saved = if (item.key in mutable.value.channelFlags) availability.clear(item.key)
            else availability.flag(item.key, SportsFlagReason.MANUAL)
        mutable.update { it.copy(channelFlags = saved) }
    }

    fun setAvailabilityFilter(filter: SportsAvailabilityFilter) {
        availability.setFilter(filter)
        mutable.update { it.copy(availabilityFilter = filter) }
    }

    fun playbackRendered(attemptId: Long) {
        val session = playback.state.value
        if (!session.active || session.resolving || session.current == null || session.attemptId != attemptId || session.error != null) return
        val selected = mutable.value.selected?.takeIf { it.key == session.slug } ?: return
        if (mutable.value.channelFlags[selected.key]?.reason == SportsFlagReason.PLAYBACK_FAILED) {
            val saved = availability.clear(selected.key, automaticOnly = true)
            mutable.update { it.copy(channelFlags = saved) }
        }
    }

    fun addPlaylist(title: String, url: String) {
        try {
            val added = library.addUrl(title, url)
            mutable.update { it.copy(customPlaylists = library.playlists(), libraryError = null) }
            openCategory(added)
        } catch (failure: Exception) { mutable.update { it.copy(libraryError = message(failure)) } }
    }

    fun importPlaylist(uri: android.net.Uri, title: String) {
        mutable.update { it.copy(importing = true, libraryError = null) }
        viewModelScope.launch {
            try {
                val category = withContext(Dispatchers.IO) { library.importFile(uri, title) }
                mutable.update { it.copy(customPlaylists = library.playlists(), importing = false) }
                openCategory(category)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(importing = false, libraryError = message(failure)) } }
        }
    }

    fun removePlaylist(category: SportsCategory) {
        val saved = library.remove(category)
        mutable.update { it.copy(customPlaylists = saved) }
        if (mutable.value.collection?.id == category.id) { mutable.update { it.copy(collection = null, collectionChannels = emptyList()) }; refresh(false) }
    }
    fun dismissLibraryError() { mutable.update { it.copy(libraryError = null) } }

    fun refresh(force: Boolean = true) {
        if (mutable.value.tab == SportsTab.HIGHLIGHTS) {
            mutable.update { it.copy(loading = false, error = null) }
            return
        }
        val selected = mutable.value.selected
        if (selected != null) { openChannel(selected, mutable.value.event); return }
        if (mutable.value.favouritesOnly) { mutable.update { it.copy(favourites = library.favourites(), error = null, loading = false) }; return }
        catalogueJob?.cancel()
        val generation = ++catalogueGeneration
        val origin = mutable.value
        catalogueJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                // Await any first-run/schema-upgrade sync before decoding encrypted catalogues.
                // Existing configurations retain the shared daily request throttle.
                try { com.nikhil.niktv.data.RemoteCredentials.refresh(getApplication()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Cached credentials may still be usable offline. */ }
                val result = when {
                    origin.collection != null -> origin.copy(collectionChannels = collectionChannels(origin.collection, force))
                    origin.tab == SportsTab.EVENTS -> {
                        val events = repository.events(refresh = force)
                        val filters = try { repository.eventFilters(refresh = force) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { origin.filters }
                        origin.copy(events = events, filters = filters)
                    }
                    origin.tab == SportsTab.CHANNELS -> origin.copy(channels = repository.channels(refresh = force))
                    else -> origin.copy(categories = repository.categories(refresh = force))
                }
                if (generation == catalogueGeneration) {
                    val time = System.currentTimeMillis(); checked[origin.route] = time
                    mutable.update { it.copy(events = result.events, filters = result.filters, channels = result.channels,
                        categories = result.categories, collectionChannels = result.collectionChannels, loading = false, lastCheckedAt = time) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == catalogueGeneration) mutable.update { it.copy(loading = false, error = message(failure)) }
            }
        }
    }

    fun open(event: SportsEvent) = openChannel(event.asChannel(), event)

    fun openChannel(channel: SportsChannel) = openChannel(channel, mutable.value.events.firstOrNull { it.slug == channel.slug })

    private fun openChannel(channel: SportsChannel, event: SportsEvent?) {
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        catalogueJob?.cancel(); catalogueGeneration++
        playback.stop()
        mutable.update { it.copy(event = event, channel = channel, detail = SportsChannelDetail(), streams = emptyList(), loadingStreams = true,
            loading = false, streamError = null, resolving = null, playing = null, playingIndex = null, playback = playback.state.value) }
        selectionJob = viewModelScope.launch {
            try {
                val detail = loadDetail(channel)
                if (generation == selectionGeneration && mutable.value.selected?.key == channel.key)
                    mutable.update { it.copy(streams = detail.streams, detail = detail, loadingStreams = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == selectionGeneration && mutable.value.selected?.key == channel.key)
                    mutable.update { it.copy(loadingStreams = false, streamError = message(failure)) }
            }
        }
    }

    private suspend fun collectionChannels(category: SportsCategory, refresh: Boolean): List<SportsChannel> =
        if (category.link.startsWith("local:")) withContext(Dispatchers.IO) { SportsPlaylist.parse(library.localBody(category), category) }
        else repository.channels(category, refresh)

    private suspend fun loadDetail(channel: SportsChannel, fresh: Boolean = false): SportsChannelDetail {
        val category = channel.playlist ?: return repository.detail(channel.slug)
        val item = if (fresh || mutable.value.collection?.id != category.id) {
            val currentCategory = if (category.custom) category else repository.categories(refresh = fresh).firstOrNull { it.id == category.id } ?: category
            collectionChannels(currentCategory, fresh).firstOrNull { it.key == channel.key }
                ?: throw SportsException("This channel is no longer in the playlist. Refresh the category.")
        } else channel
        if (!sportsGeoAvailable(item.geo, sportsDeviceCountry(getApplication()))) throw SportsException("This channel is not available in your region.")
        return SportsChannelDetail(listOfNotNull(item.direct))
    }

    fun adjacentChannel(direction: Int) {
        val current = mutable.value
        val slug = if (direction < 0) current.detail.previous else current.detail.next
        if (slug == null) return
        val known = (current.detail.related + current.channels + current.collectionChannels).firstOrNull { it.slug == slug }
        openChannel(known ?: SportsChannel(slug, slug.replace('-', ' '), slug))
    }

    fun play(index: Int) {
        val current = mutable.value
        val channel = current.selected ?: return
        val session = playback.state.value
        val sources = session.sources.takeIf { session.active && session.slug == channel.key } ?: current.streams
        if (index !in sources.indices) return
        if (session.current != null && session.currentIndex == index && !session.resolving) return
        playback.start(channel.key, sources, index)
        applyPlayback(playback.state.value)
    }

    fun playbackFailed(attemptId: Long) { playback.failed(attemptId); applyPlayback(playback.state.value) }
    fun retryPlayback() { playback.retry(); applyPlayback(playback.state.value) }
    fun cancelPendingFallback() { playback.cancelPendingFallback(); applyPlayback(playback.state.value) }
    fun dismissStreamError() {
        playback.dismissError()
        mutable.update { it.copy(streamError = null) }
    }

    fun back(): Boolean {
        val current = mutable.value
        if (current.selected != null) {
            val returningToSources = current.playing != null || current.resolving != null
            cancelSelection()
            mutable.update { it.copy(event = current.event.takeIf { returningToSources }, channel = current.channel.takeIf { returningToSources },
                streams = if (returningToSources) current.streams else emptyList(), loadingStreams = false,
                streamError = null, resolving = null, playing = null, playingIndex = null, playback = playback.state.value, restoreBrowseFocus = !returningToSources) }
            return true
        }
        if (current.favouritesOnly) { toggleFavourites(); return true }
        if (current.collection != null) {
            catalogueJob?.cancel(); catalogueGeneration++
            mutable.update { it.copy(collection = null, collectionChannels = emptyList(), error = null, restoreBrowseFocus = true) }; refresh(false); return true
        }
        return false
    }

    private fun cancelSelection() { selectionGeneration++; selectionJob?.cancel(); playback.stop() }

    private fun message(failure: Exception) = (failure as? SportsException)?.message
        ?: "The sports service is unavailable. Please retry."

    private fun applyPlayback(session: SportsPlaybackSession) {
        val current = mutable.value
        if (session.active && session.slug == current.selected?.key && session.error != null && current.playback.error != session.error) {
            val saved = availability.flag(session.slug, SportsFlagReason.PLAYBACK_FAILED)
            mutable.update { it.copy(channelFlags = saved) }
        }
        mutable.update { current ->
            if (session.active && session.slug != current.selected?.key) current else current.copy(
                playback = session, playing = session.current, playingIndex = session.currentIndex,
                resolving = session.selectedIndex.takeIf { session.resolving },
                streams = if (session.active) session.sources else current.streams,
                streamError = if (session.active) session.error else current.streamError)
        }
    }
}
