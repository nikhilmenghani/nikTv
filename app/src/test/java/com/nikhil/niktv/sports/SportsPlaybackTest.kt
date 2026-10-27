package com.nikhil.niktv.sports

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsPlaybackTest {
    private val a = SportsStream("https://media.example.test/a.m3u8", "A")
    private val b = SportsStream("https://media.example.test/b.m3u8", "B")
    private val c = SportsStream("https://media.example.test/c.m3u8", "C")

    @Test fun rapidReversalAndOutOfOrderResultsCannotReplaceTheLatestSelection() = runTest {
        val bResult = CompletableDeferred<SportsStream>()
        val cResult = CompletableDeferred<SportsStream>()
        val controller = SportsPlaybackController(this, { emptyList() }, {
            when (it) {
                b -> withContext(NonCancellable) { bResult.await() }
                c -> withContext(NonCancellable) { cResult.await() }
                else -> it
            }
        })
        controller.start("event", listOf(a, b, c)); runCurrent()
        val oldAttempt = controller.state.value.attemptId
        controller.start("event", listOf(a, b, c), 1); runCurrent()
        assertEquals(a, controller.state.value.current)
        assertEquals(0, controller.state.value.currentIndex)
        assertEquals(1, controller.state.value.selectedIndex)
        controller.start("event", listOf(a, b, c), 2); runCurrent()
        cResult.complete(c); runCurrent()
        val committed = controller.state.value.attemptId
        bResult.complete(b); runCurrent()
        controller.failed(oldAttempt); runCurrent()
        assertEquals(c, controller.state.value.current)
        assertEquals(2, controller.state.value.currentIndex)
        assertEquals(committed, controller.state.value.attemptId)
        assertFalse(controller.state.value.resolving)
        controller.stop()
    }

    @Test fun failedDirectSelectionRetainsCurrentStreamWithoutChoosingAnUnrequestedVariant() = runTest {
        val visited = mutableListOf<SportsStream>()
        var refreshes = 0
        val controller = SportsPlaybackController(this, { refreshes++; listOf(a, b, c) }, {
            visited += it
            if (it == b) error("Fixture unavailable")
            it
        })
        controller.start("event", listOf(a, b, c)); runCurrent()
        val committed = controller.state.value.attemptId
        controller.start("event", listOf(a, b, c), 1); runCurrent()
        assertEquals(listOf(a, b), visited)
        assertEquals(1, refreshes)
        assertEquals(a, controller.state.value.current)
        assertEquals(0, controller.state.value.currentIndex)
        assertEquals(committed, controller.state.value.attemptId)
        assertFalse(controller.state.value.resolving)
        assertNotNull(controller.state.value.error)
        controller.stop()
    }

    @Test fun terminalFailureFallsBackAndIgnoresAnOldPlayerCallback() = runTest {
        val controller = SportsPlaybackController(this, { emptyList() }, { it })
        controller.start("event", listOf(a, b, c)); runCurrent()
        val first = controller.state.value.attemptId
        controller.failed(first); runCurrent()
        assertEquals(b, controller.state.value.current)
        assertEquals(1, controller.state.value.currentIndex)
        val second = controller.state.value.attemptId
        controller.failed(first); runCurrent()
        assertEquals(second, controller.state.value.attemptId)
        assertEquals(b, controller.state.value.current)
        controller.stop()
    }

    @Test fun unchangedFailedOccurrencesAreNotRetriedAfterTheSingleMetadataRefresh() = runTest {
        var refreshes = 0
        var attempts = 0
        var broken = false
        val controller = SportsPlaybackController(this, { refreshes++; listOf(a, b) }, {
            attempts++
            if (broken) error("Fixture unavailable")
            it
        })
        controller.start("event", listOf(a, b)); runCurrent()
        val currentId = controller.state.value.attemptId
        broken = true
        controller.failed(currentId); runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, refreshes)
        assertEquals(a, controller.state.value.current)
        assertFalse(controller.state.value.resolving)
        assertNotNull(controller.state.value.error)
        controller.failed(currentId); runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, refreshes)
        controller.retry(); runCurrent()
        assertEquals(3, attempts)
        assertEquals(2, refreshes)
        assertFalse(controller.state.value.resolving)
        controller.stop()
    }

    @Test fun terminalFailureCampaignHasAFourResolutionBudgetUntilExplicitRetry() = runTest {
        val sources = (0..7).map { a.copy(link = "https://media.example.test/" + it + ".m3u8", title = "Source " + it) }
        val visited = mutableListOf<SportsStream>()
        var refreshes = 0
        val controller = SportsPlaybackController(this, { refreshes++; sources }, { visited += it; it })
        controller.start("event", sources); runCurrent()
        repeat(4) { controller.failed(controller.state.value.attemptId); runCurrent() }
        assertEquals(4, visited.size)
        assertEquals(3, controller.state.value.currentIndex)
        assertFalse(controller.state.value.resolving)
        assertNotNull(controller.state.value.error)
        controller.retry(); runCurrent()
        assertEquals(5, visited.size)
        assertEquals(1, refreshes)
        assertEquals(3, controller.state.value.currentIndex)
        controller.stop()
    }

    @Test fun aRotatedLinkGetsOneNewAttemptAndStopCancelsResolution() = runTest {
        val fresh = a.copy(link = a.link + "?token=fixture-new")
        var refreshes = 0
        val controller = SportsPlaybackController(this, { refreshes++; listOf(fresh) }, { it })
        controller.start("event", listOf(a)); runCurrent()
        controller.failed(controller.state.value.attemptId); runCurrent()
        assertEquals(fresh, controller.state.value.current)
        assertEquals(1, refreshes)
        assertEquals(2, controller.state.value.attempts)
        controller.stop()
        val pending = SportsPlaybackController(this, { emptyList() }, { awaitCancellation() })
        pending.start("event", listOf(a)); runCurrent(); pending.stop(); runCurrent()
        assertFalse(pending.state.value.active)
        assertNull(pending.state.value.current)
    }

    @Test fun manualRetryRefreshesSameUrlAndReconcilesItsOccurrenceAfterSourceReordering() = runTest {
        val metadata = CompletableDeferred<List<SportsStream>>()
        var resolutions = 0
        val controller = SportsPlaybackController(this, { metadata.await() }, { resolutions++; it })
        controller.start("event", listOf(a, b)); runCurrent()
        val previous = controller.state.value.attemptId
        controller.retry(); runCurrent()
        assertEquals(a, controller.state.value.current)
        assertTrue(controller.state.value.resolving)
        assertEquals(previous, controller.state.value.attemptId)
        metadata.complete(listOf(b, a)); runCurrent()
        assertEquals(a, controller.state.value.current)
        assertEquals(1, controller.state.value.currentIndex)
        assertTrue(controller.state.value.attemptId > previous)
        assertEquals(2, resolutions)
        controller.stop()
    }

    @Test fun identicalSourceOccurrencesRemainIndividuallySelectableAndRetryTheCorrectDuplicate() = runTest {
        val fresh = a.copy(link = a.link + "?token=fixture-new")
        val controller = SportsPlaybackController(this, { listOf(fresh, fresh) }, { it })
        controller.start("event", listOf(a, a), 1); runCurrent()
        assertEquals(1, controller.state.value.currentIndex)
        controller.retry(); runCurrent()
        assertEquals(fresh, controller.state.value.current)
        assertEquals(1, controller.state.value.currentIndex)
        val secondOccurrenceAttempt = controller.state.value.attemptId
        controller.start("event", listOf(fresh, fresh), 0); runCurrent()
        assertEquals(0, controller.state.value.currentIndex)
        assertTrue(controller.state.value.attemptId > secondOccurrenceAttempt)
        controller.stop()
    }

    @Test fun cancelAutomaticFallbackRetainsCurrentAndRejectsLateResolution() = runTest {
        val replacement = CompletableDeferred<SportsStream>()
        val controller = SportsPlaybackController(this, { emptyList() }, {
            if (it == b) withContext(NonCancellable) { replacement.await() } else it
        })
        controller.start("event", listOf(a, b)); runCurrent()
        val committed = controller.state.value.attemptId
        controller.failed(committed); runCurrent()
        assertTrue(controller.state.value.resolving)
        assertEquals(a, controller.state.value.current)
        controller.cancelPendingFallback()
        assertFalse(controller.state.value.resolving)
        assertEquals(a, controller.state.value.current)
        replacement.complete(b); runCurrent()
        assertEquals(committed, controller.state.value.attemptId)
        assertEquals(0, controller.state.value.currentIndex)
        controller.failed(committed); runCurrent()
        assertFalse(controller.state.value.resolving)
        controller.stop()
    }

    @Test fun pauseCancellationDoesNotCancelDirectSelection() = runTest {
        val replacement = CompletableDeferred<SportsStream>()
        val controller = SportsPlaybackController(this, { emptyList() }, {
            if (it == b) replacement.await() else it
        })
        controller.start("event", listOf(a, b)); runCurrent()
        controller.start("event", listOf(a, b), 1); runCurrent()
        controller.cancelPendingFallback()
        assertTrue(controller.state.value.resolving)
        replacement.complete(b); runCurrent()
        assertEquals(b, controller.state.value.current)
        assertFalse(controller.state.value.resolving)
        controller.stop()
    }

    @Test fun pauseCancelsFreshRetryAndDiscardsLateMetadataWithoutReplacingTheActiveFrame() = runTest {
        val metadata = CompletableDeferred<List<SportsStream>>()
        val controller = SportsPlaybackController(this,
            { withContext(NonCancellable) { metadata.await() } }, { it })
        controller.start("event", listOf(a, b)); runCurrent()
        val committed = controller.state.value.attemptId
        controller.retry(); runCurrent()
        assertTrue(controller.state.value.resolving)
        controller.cancelPendingFallback()
        assertFalse(controller.state.value.resolving)
        assertEquals(a, controller.state.value.current)
        metadata.complete(listOf(a.copy(link = a.link + "?late=fixture"), b)); runCurrent()
        assertEquals(a, controller.state.value.current)
        assertEquals(listOf(a, b), controller.state.value.sources)
        assertEquals(committed, controller.state.value.attemptId)
        controller.stop()
    }

    @Test fun backgroundCancellationAlsoStopsFreshRetryDuringUrlResolution() = runTest {
        val fresh = a.copy(link = a.link + "?new=fixture")
        val resolved = CompletableDeferred<SportsStream>()
        val controller = SportsPlaybackController(this, { listOf(fresh) }, {
            if (it == fresh) withContext(NonCancellable) { resolved.await() } else it
        })
        controller.start("event", listOf(a)); runCurrent()
        val committed = controller.state.value.attemptId
        controller.retry(); runCurrent()
        assertTrue(controller.state.value.resolving)
        controller.cancelPendingFallback()
        resolved.complete(fresh); runCurrent()
        assertFalse(controller.state.value.resolving)
        assertEquals(a, controller.state.value.current)
        assertEquals(committed, controller.state.value.attemptId)
        controller.stop()
    }

    @Test fun staleMetadataCannotReplaceANewerDirectSelection() = runTest {
        val metadata = CompletableDeferred<List<SportsStream>>()
        val controller = SportsPlaybackController(this, { withContext(NonCancellable) { metadata.await() } }, { it })
        controller.start("event", listOf(a, b, c)); runCurrent()
        controller.retry(); runCurrent()
        controller.start("event", listOf(a, b, c), 2); runCurrent()
        val committed = controller.state.value.attemptId
        metadata.complete(listOf(a.copy(link = a.link + "?obsolete"))); runCurrent()
        assertEquals(c, controller.state.value.current)
        assertEquals(listOf(a, b, c), controller.state.value.sources)
        assertEquals(committed, controller.state.value.attemptId)
        controller.stop()
    }

    @Test fun refreshCadenceHandlesExpiryLongAbsencesAndClockRollback() {
        assertTrue(sportsCatalogueNeedsRefresh(null, 1000))
        assertFalse(sportsCatalogueNeedsRefresh(1000, 120999))
        assertTrue(sportsCatalogueNeedsRefresh(1000, 121000))
        assertTrue(sportsCatalogueNeedsRefresh(1000, 1000 + 3 * 86400000L))
        assertTrue(sportsCatalogueNeedsRefresh(1000, 0))
    }
}
