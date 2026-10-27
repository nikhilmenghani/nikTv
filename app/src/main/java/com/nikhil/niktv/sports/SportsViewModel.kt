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
)

internal class SportsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SportsRepository(country = { sportsDeviceCountry(application) })
    private val resolver = SportsStreamResolver(repository.transport, SportsWebResolver(application)::resolve)
    private val mutable = MutableStateFlow(SportsState())
    val state = mutable.asStateFlow()
    private var catalogueJob: Job? = null
    private var selectionJob: Job? = null
    init { refresh() }

    fun refresh() {
        catalogueJob?.cancel()
        catalogueJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try { val events = repository.events(refresh = true); mutable.update { it.copy(events = events, loading = false) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(loading = false, error = message(failure)) } }
        }
    }

    fun open(event: SportsEvent) {
        selectionJob?.cancel()
        mutable.update { it.copy(event = event, streams = emptyList(), loadingStreams = true, streamError = null, resolving = null, playing = null) }
        selectionJob = viewModelScope.launch {
            try { val streams = repository.streams(event.slug); mutable.update { it.copy(streams = streams, loadingStreams = false) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(loadingStreams = false, streamError = message(failure)) } }
        }
    }

    fun play(index: Int) {
        val stream = mutable.value.streams.getOrNull(index) ?: return
        selectionJob?.cancel()
        mutable.update { it.copy(resolving = index, streamError = null, playing = null) }
        selectionJob = viewModelScope.launch {
            try { val resolved = resolver.resolve(stream); mutable.update { it.copy(playing = resolved, resolving = null) } }
            catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) { mutable.update { it.copy(resolving = null, streamError = "This stream took too long. Choose another stream or retry.") } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(resolving = null, streamError = message(failure)) } }
        }
    }

    fun back(): Boolean {
        val current = mutable.value
        if (current.event == null) return false
        selectionJob?.cancel()
        mutable.update { if (it.playing != null) it.copy(playing = null, resolving = null)
            else it.copy(event = null, streams = emptyList(), loadingStreams = false, streamError = null, resolving = null) }
        return true
    }
    private fun message(failure: Exception) = (failure as? SportsException)?.message ?: "The sports service is unavailable. Please retry."
}
