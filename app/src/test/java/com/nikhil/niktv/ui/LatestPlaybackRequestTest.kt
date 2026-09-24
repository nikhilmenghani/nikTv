package com.nikhil.niktv.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class LatestPlaybackRequestTest {
    @Test fun closeInvalidatesSelectionBeforeItsCoroutineStarts() = runBlocking {
        val requests = LatestPlaybackRequest()
        val id = requests.begin()
        requests.cancel()
        val load = launch { requests.run(id) { fail("Closed player must not reopen") } }
        load.join()
        assertTrue(load.isCancelled)
    }

    @Test fun delayedOlderSelectionCannotCancelNewerSelection() = runBlocking {
        val requests = LatestPlaybackRequest()
        val oldId = requests.begin()
        val newId = requests.begin()
        val finish = CompletableDeferred<Unit>()
        var played = false
        val newest = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.run(newId) { finish.await(); played = true }
        }
        val stale = launch { requests.run(oldId) { fail("Stale selection ran") } }
        stale.join()
        finish.complete(Unit)
        newest.join()
        assertTrue(played)
        assertFalse(newest.isCancelled)
    }

    @Test fun closingDuringAuthenticationCannotReplayPlayback() = runBlocking {
        val requests = LatestPlaybackRequest()
        val authenticated = CompletableDeferred<Unit>()
        var played = false
        val retry = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.run {
                // Simulate a provider operation finishing after cancellation.
                withContext(NonCancellable) { authenticated.await() }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                played = true
            }
        }
        requests.cancel()
        authenticated.complete(Unit)
        retry.join()
        assertFalse(played)
    }

    @Test fun newerSelectionCancelsDecoderRestartDelay() = runBlocking {
        val requests = LatestPlaybackRequest()
        val releaseDecoder = CompletableDeferred<Unit>()
        val played = mutableListOf<String>()
        val retry = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.run { releaseDecoder.await(); played += "old" }
        }
        requests.run { played += "new" }
        releaseDecoder.complete(Unit)
        retry.join()
        assertEquals(listOf("new"), played)
    }

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
