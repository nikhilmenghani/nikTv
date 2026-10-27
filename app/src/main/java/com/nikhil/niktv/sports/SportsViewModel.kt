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

internal data class SportsState(
    val events: List<SportsEvent> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val event: SportsEvent? = null,
    val streams: List<SportsStream> = emptyList(),
    val loadingStreams: Boolean = false,
    val streamError: String? = null,
    val playback: SportsPlaybackSession = SportsPlaybackSession(),
    val lastCheckedAt: Long? = null,
) {
    val playing: SportsStream? get() = playback.current
    val resolving: Int? get() = playback.selectedIndex.takeIf { playback.resolving }
}

internal class SportsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SportsRepository(country = { sportsDeviceCountry(application) })
    private val resolver = SportsStreamResolver(repository.transport, SportsWebResolver(application)::resolve)
    private val mutable = MutableStateFlow(SportsState())
    val state = mutable.asStateFlow()
    private var catalogueJob: Job? = null
    private var selectionJob: Job? = null
    private val playback = SportsPlaybackController(viewModelScope, repository::streams, resolver::resolve)
    init {
        viewModelScope.launch { playback.state.collect { session ->
            mutable.update { it.copy(playback = session, streams = if (session.active && session.slug == it.event?.slug) session.sources else it.streams) }
        } }
        refresh()
    }

    fun refreshIfStale() {
        if (mutable.value.event == null && catalogueJob?.isActive != true && sportsCatalogueNeedsRefresh(mutable.value.lastCheckedAt, System.currentTimeMillis())) refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        catalogueJob?.cancel()
        catalogueJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try { val events = repository.events(refresh = force); mutable.update { it.copy(events = events, loading = false, lastCheckedAt = System.currentTimeMillis()) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(loading = false, error = message(failure)) } }
        }
    }

    fun open(event: SportsEvent) {
        selectionJob?.cancel()
        playback.stop()
        mutable.update { it.copy(event = event, streams = emptyList(), loadingStreams = true, streamError = null) }
        selectionJob = viewModelScope.launch {
            try { val streams = repository.streams(event.slug); mutable.update { it.copy(streams = streams, loadingStreams = false) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(loadingStreams = false, streamError = message(failure)) } }
        }
    }

    fun play(index: Int) {
        val state = mutable.value
        val event = state.event ?: return
        if (index !in state.streams.indices) return
        playback.start(event.slug, state.streams, index)
    }

    fun playbackFailed(attemptId: Long) = playback.failed(attemptId)
    fun retryPlayback() = playback.retry()

    fun back(): Boolean {
        val current = mutable.value
        if (current.event == null) return false
        selectionJob?.cancel()
        playback.stop()
        if (!current.playback.active) mutable.update { it.copy(event = null, streams = emptyList(), loadingStreams = false, streamError = null) }
        return true
    }
    private fun message(failure: Exception) = (failure as? SportsException)?.message ?: "The sports service is unavailable. Please retry."
}
