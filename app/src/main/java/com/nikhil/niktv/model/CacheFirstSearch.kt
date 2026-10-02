package com.nikhil.niktv.model

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Publish device results first; a miss continues to the provider without another user action. */
internal suspend fun <T> cacheFirstSearch(
    forceProvider: Boolean,
    cached: suspend () -> List<T>,
    publish: suspend (List<T>) -> Unit,
    provider: suspend (List<T>) -> Unit
) {
    val local = cached()
    currentCoroutineContext().ensureActive()
    publish(local)
    currentCoroutineContext().ensureActive()
    if (forceProvider || local.isEmpty()) provider(local)
}
