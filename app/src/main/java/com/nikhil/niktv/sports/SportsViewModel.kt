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
    val resolving: Int? = null,
    val playing: SportsStream? = null,
    val playingIndex: Int? = null,
    val playback: SportsPlaybackSession = SportsPlaybackSession(),
    val lastCheckedAt: Long? = null,
) {
    val navigationIndex: Int? get() = resolving ?: playingIndex
}

internal class SportsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SportsRepository(country = { sportsDeviceCountry(application) })
    private val resolver = SportsStreamResolver(repository.transport, SportsWebResolver(application)::resolve)
    private val mutable = MutableStateFlow(SportsState())
    val state = mutable.asStateFlow()
    private var catalogueJob: Job? = null
    private var selectionJob: Job? = null
    private var catalogueGeneration = 0L
    private var selectionGeneration = 0L
    private val playback = SportsPlaybackController(viewModelScope, repository::streams, resolver::resolve)

    init {
        viewModelScope.launch {
            playback.state.collect(::applyPlayback)
        }
        refresh()
    }

    fun refreshIfStale() {
        if (mutable.value.event == null && catalogueJob?.isActive != true &&
            sportsCatalogueNeedsRefresh(mutable.value.lastCheckedAt, System.currentTimeMillis())) refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        catalogueJob?.cancel()
        val generation = ++catalogueGeneration
        catalogueJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val events = repository.events(refresh = force)
                if (generation == catalogueGeneration) mutable.update {
                    it.copy(events = events, loading = false, lastCheckedAt = System.currentTimeMillis())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == catalogueGeneration) mutable.update { it.copy(loading = false, error = message(failure)) }
            }
        }
    }

    fun open(event: SportsEvent) {
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        playback.stop()
        mutable.update { it.copy(event = event, streams = emptyList(), loadingStreams = true,
            streamError = null, resolving = null, playing = null, playingIndex = null, playback = playback.state.value) }
        selectionJob = viewModelScope.launch {
            try {
                val streams = repository.streams(event.slug)
                if (generation == selectionGeneration && mutable.value.event?.slug == event.slug)
                    mutable.update { it.copy(streams = streams, loadingStreams = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == selectionGeneration && mutable.value.event?.slug == event.slug)
                    mutable.update { it.copy(loadingStreams = false, streamError = message(failure)) }
            }
        }
    }

    fun play(index: Int) {
        val current = mutable.value
        val event = current.event ?: return
        val session = playback.state.value
        val sources = session.sources.takeIf { session.active && session.slug == event.slug } ?: current.streams
        if (index !in sources.indices) return
        if (session.current != null && session.currentIndex == index && !session.resolving) return
        playback.start(event.slug, sources, index)
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
        if (current.event == null) return false
        selectionGeneration++
        selectionJob?.cancel()
        playback.stop()
        val returningToSources = current.playing != null || current.resolving != null
        mutable.update { it.copy(event = current.event.takeIf { returningToSources },
            streams = if (returningToSources) current.streams else emptyList(), loadingStreams = false,
            streamError = null, resolving = null, playing = null, playingIndex = null, playback = playback.state.value) }
        return true
    }

    private fun message(failure: Exception) = (failure as? SportsException)?.message
        ?: "The sports service is unavailable. Please retry."

    private fun applyPlayback(session: SportsPlaybackSession) {
        mutable.update { current ->
            if (session.active && session.slug != current.event?.slug) current else current.copy(
                playback = session, playing = session.current, playingIndex = session.currentIndex,
                resolving = session.selectedIndex.takeIf { session.resolving },
                streams = if (session.active) session.sources else current.streams,
                streamError = if (session.active) session.error else current.streamError)
        }
    }
}
