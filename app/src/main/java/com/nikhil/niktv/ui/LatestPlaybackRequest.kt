package com.nikhil.niktv.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Called on the main dispatcher. Obsolete loads must finish cleanup before the next starts. */
internal class LatestPlaybackRequest {
    private var active: Job? = null
    private val mutex = Mutex()

    fun cancel() {
        active?.cancel()
        active = null
    }

    suspend fun run(block: suspend () -> Unit) = coroutineScope {
        val request = currentCoroutineContext()[Job]!!
        active?.cancel()
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
