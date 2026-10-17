package com.nikhil.niktv.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Main-dispatcher ownership. Obsolete network cleanup never serializes a new selection. */
internal class LatestPlaybackRequest {
    private var active: Job? = null
    private var generation = 0L
    private var pendingGeneration: Long? = null

    fun cancel() {
        generation++
        active?.cancel()
        active = null
        pendingGeneration = null
    }

    /** Reserve at the user action, before dispatch or authentication can suspend. */
    fun begin(): Long {
        cancel()
        pendingGeneration = generation
        return generation
    }

    fun isCurrent(id: Long): Boolean = id == generation

    val isRunning: Boolean get() = pendingGeneration == generation

    suspend fun run(id: Long = begin(), block: suspend () -> Unit) = coroutineScope {
        if (id != generation) throw CancellationException("Playback selection was superseded")
        val request = currentCoroutineContext()[Job]!!
        val parent = active
        active = request
        try {
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            if (active === request) active = parent?.takeIf { id == generation && it.isActive }
            if (id == generation && parent == null) pendingGeneration = null
        }
    }
}

/** Playback already retried authentication inside its cancellable request. */
internal class PlaybackRequestException(cause: Throwable) : Exception(cause.message, cause)
