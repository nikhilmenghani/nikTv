package com.nikhil.niktv.ui

import kotlinx.coroutines.*

/** Catalogue metadata only: never creates a media URL, decoder or provider stream. Main-thread owned. */
internal class SpeculativePageCache<T>(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val ttlMillis: Long = 60_000L
) {
    private data class Entry<T>(val key: String, val credentials: Any, val created: Long, val work: Deferred<Result<T>>)
    private var entry: Entry<T>? = null
    private var retiring: Job? = null

    fun cancelUnfinished() {
        entry?.takeIf { !it.work.isCompleted }?.let { retire(it.work); entry = null }
    }

    private fun retire(work: Job) {
        work.cancel()
        val previous = retiring
        // Only speculative work waits for obsolete cleanup; foreground requests never do.
        retiring = scope.launch { previous?.join(); work.join() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun schedule(key: String, credentials: Any, permitted: Boolean = true, load: suspend () -> T) {
        val current = entry
        if (!permitted) { current?.let { retire(it.work) }; entry = null; return }
        if (current != null && current.key == key && current.credentials == credentials &&
            clock() - current.created < ttlMillis && !current.work.isCancelled &&
            (!current.work.isCompleted || current.work.getCompleted().isSuccess)) return
        current?.let { retire(it.work) }
        val cleanup = retiring
        val work = scope.async {
            cleanup?.join()
            delay(750L)
            try { Result.success(load()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
        entry = Entry(key, credentials, clock(), work)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun takeReady(key: String, credentials: Any): T? {
        val current = entry ?: return null
        entry = null
        if (current.key != key || current.credentials != credentials ||
            clock() - current.created >= ttlMillis || !current.work.isCompleted || current.work.isCancelled) {
            retire(current.work)
            return null
        }
        currentCoroutineContext().ensureActive()
        return current.work.getCompleted().getOrNull()
    }
}
