package com.nikhil.niktv.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class LatestPlaybackRequestTest {
    @Test fun rapidSelectionsOnlyStartLatestAfterPreviousCleanup() = runBlocking {
        val requests = LatestPlaybackRequest()
        val releaseCleanup = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.run {
                started += 0
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { releaseCleanup.await() }
                }
            }
        }
        val replacements = (1..12).map { index ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                requests.run { started += index }
            }
        }
        assertEquals(listOf(0), started)
        releaseCleanup.complete(Unit)
        first.join()
        replacements.forEach { it.join() }
        assertEquals(listOf(0, 12), started)
    }

    @Test fun closingPlaybackCancelsPendingSelection() = runBlocking {
        val requests = LatestPlaybackRequest()
        var committed = false
        val ready = CompletableDeferred<Unit>()
        val load = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.run { ready.await(); committed = true }
        }
        requests.cancel()
        ready.complete(Unit)
        load.join()
        assertFalse(committed)
        requests.run { committed = true }
        assertTrue(committed)
    }
}
