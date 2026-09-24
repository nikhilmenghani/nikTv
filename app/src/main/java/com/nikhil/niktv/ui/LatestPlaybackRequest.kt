package com.nikhil.niktv.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Called on the main dispatcher. Obsolete loads must finish cleanup before the next starts. */
internal class LatestPlaybackRequest {
    private var active: Job? = null
    private val mutex = Mutex()
    private var generation = 0L

    fun cancel() {
        generation++
        active?.cancel()
        active = null
    }

    /** Reserve at the user action, before dispatch or authentication can suspend. */
    fun begin(): Long {
        cancel()
        return generation
    }

    fun isCurrent(id: Long): Boolean = id == generation

    val isRunning: Boolean get() = active?.isActive == true

    suspend fun run(id: Long = begin(), block: suspend () -> Unit) = coroutineScope {
        if (id != generation) throw CancellationException("Playback selection was superseded")
        val request = currentCoroutineContext()[Job]!!
        active = request
        try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                block()
            }
        } finally {
            if (active === request) active = null
        }
    }
}

/** Playback already retried authentication inside its cancellable request. */
internal class PlaybackRequestException(cause: Throwable) : Exception(cause.message, cause)
