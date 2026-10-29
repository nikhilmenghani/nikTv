package com.nikhil.niktv.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CricketHighlightsState(
    val videos: List<CricketHighlight> = emptyList(),
    val configured: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val preparingId: String? = null,
    val selected: CricketHighlight? = null,
    val playing: SportsStream? = null,
    val attempt: Long = 0,
)

internal class CricketHighlightsViewModel(
    private val repository: CricketHighlightsRepository = CricketHighlightsRepository(),
) : ViewModel() {
    private val mutable = MutableStateFlow(CricketHighlightsState())
    val state = mutable.asStateFlow()
    private var catalogueJob: Job? = null
    private var playbackJob: Job? = null
    private var generation = 0L
    private var checkedAt = 0L

    init { refresh() }

    fun refreshIfStale() {
        if (mutable.value.playing == null && mutable.value.preparingId == null && catalogueJob?.isActive != true &&
            (repository.configured != mutable.value.configured || System.currentTimeMillis() - checkedAt >= 120_000)) refresh()
    }

    fun refresh() {
        catalogueJob?.cancel()
        val configured = repository.configured
        mutable.update { it.copy(configured = configured, loading = configured, error = null) }
        if (!configured) { mutable.update { it.copy(videos = emptyList()) }; return }
        catalogueJob = viewModelScope.launch {
            try {
                val videos = repository.featured(refresh = true)
                checkedAt = System.currentTimeMillis()
                mutable.update { it.copy(videos = videos, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(loading = false, error = message(failure)) } }
        }
    }

    fun configurationFailed() { mutable.update { it.copy(error = "Could not import the Cricbuzz configuration. Choose a valid configuration file and retry.") } }

    fun play(id: String) {
        val selected = mutable.value.videos.firstOrNull { it.id == id } ?: return
        playbackJob?.cancel()
        val current = ++generation
        mutable.update { it.copy(selected = selected, preparingId = id, playing = null, error = null, attempt = current) }
        playbackJob = viewModelScope.launch {
            try {
                val stream = repository.fullStream(id)
                if (current == generation) mutable.update { it.copy(playing = stream, preparingId = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current == generation) mutable.update { it.copy(preparingId = null, error = message(failure)) }
            }
        }
    }

    fun retryPlayback() { mutable.value.selected?.id?.let(::play) }
    fun cancelPreparation() { if (mutable.value.preparingId != null) stopPlayback() }
    fun stopPlayback() {
        generation++; playbackJob?.cancel()
        mutable.update { it.copy(playing = null, preparingId = null, selected = null, error = null) }
    }
    fun playbackFailed(attempt: Long) {
        if (attempt == generation) mutable.update { it.copy(playing = null, error = "The highlight could not play. Refresh its provider access and retry.") }
    }

    private fun message(failure: Exception) = when (failure) {
        is SportsException, is CricketAccessDenied -> failure.message
        else -> null
    }
        ?: "The highlights provider is unavailable. Please retry."
}
