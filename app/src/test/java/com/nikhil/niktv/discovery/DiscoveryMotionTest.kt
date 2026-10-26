package com.nikhil.niktv.discovery

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DiscoveryMotionTest {
    @Test fun boundedCurveStartsAndEndsExactlyWithoutASettlingTail() {
        val curve = DiscoveryScrollCurve(400f, 0f)
        assertEquals(0f, curve.fraction(0f), 0f)
        assertEquals(1f, curve.fraction(1f), 0f)
        assertEquals(0f, curve.velocity(0f), 0f)
        assertEquals(0f, curve.velocity(1f), 0f)
        assertEquals(1f, curve.fraction(2f), 0f)
        assertEquals(0f, curve.velocity(2f), 0f)
        assertTrue("One complete row move must not retain the old long curve", DiscoveryScrollDurationMillis <= 200)
    }

    @Test fun sameDirectionRetargetStartsWithTheCurrentVelocityInsteadOfStopping() {
        val old = DiscoveryScrollCurve(400f, 0f)
        val velocity = old.velocity(.4f)
        assertTrue(velocity > 0f)
        val retarget = DiscoveryScrollCurve(650f, velocity)
        assertEquals(velocity, retarget.velocity(0f), .01f)
        assertTrue("The first frame must continue the motion", retarget.fraction(.01f) > DiscoveryScrollCurve(650f, 0f).fraction(.01f))
    }

    @Test fun reversalDiscardsOpposingMomentumAndImmediatelyMovesTowardTheNewTarget() {
        val oldVelocity = DiscoveryScrollCurve(400f, 0f).velocity(.4f)
        val reversal = DiscoveryScrollCurve(-200f, oldVelocity)
        assertEquals(0f, reversal.velocity(0f), 0f)
        assertTrue(reversal.velocity(.01f) < 0f)
        assertTrue(-200f * reversal.fraction(.01f) < 0f)
    }

    @Test fun closeDestinationsClampMomentumWithoutOvershootOrBackwardSettling() {
        for (distance in listOf(-600f, -1f, 0f, 1f, 600f)) {
            for (velocity in listOf(-50_000f, -400f, 0f, 400f, 50_000f)) {
                val curve = DiscoveryScrollCurve(distance, velocity)
                var previous = 0f
                repeat(101) { frame ->
                    val fraction = curve.fraction(frame / 100f)
                    assertTrue("A destination is approached monotonically", fraction + .000001f >= previous)
                    assertTrue(fraction in 0f..1f)
                    previous = fraction
                }
                assertEquals(1f, previous, 0f)
                assertEquals(0f, curve.velocity(1f), 0f)
            }
        }
    }

    @Test fun cancelingAnOldMenuEntryCannotReleaseANewerEntrysAutomaticRevealOwnership() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val coordinator = DiscoveryScrollCoordinator(scope)
        try {
            val old = scope.launch { coordinator.withAutomaticRevealSuppressed { awaitCancellation() } }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(coordinator.blocksAutomaticReveal)
            assertFalse(coordinator.isMoving)
            val latest = scope.launch { coordinator.withAutomaticRevealSuppressed { awaitCancellation() } }
            shadowOf(Looper.getMainLooper()).idle()
            old.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("A canceled entry must not release another entry's ownership", coordinator.blocksAutomaticReveal)
            latest.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("Cancellation must always restore automatic edge reveal", coordinator.blocksAutomaticReveal)
        } finally { scope.cancel() }
    }

    @Test fun aNewDestinationCancelsAnUnlaidOutOldRowWithoutFocusingIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + androidx.compose.runtime.BroadcastFrameClock())
        val coordinator = DiscoveryScrollCoordinator(scope)
        val calls = mutableListOf<Int>()
        val list = LazyListState()
        try {
            val old = coordinator.move(list, 2, false) { calls += 2; true }
            shadowOf(Looper.getMainLooper()).idle()
            val latest = coordinator.move(list, 3, false) { calls += 3; true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(old.isCancelled)
            assertTrue(latest.isActive)
            assertTrue(calls.isEmpty())
            coordinator.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(latest.isCancelled)
            assertTrue(calls.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun focusAlignmentForTheCurrentDestinationDoesNotCancelItsMove() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + androidx.compose.runtime.BroadcastFrameClock())
        val coordinator = DiscoveryScrollCoordinator(scope)
        val list = LazyListState()
        try {
            val move = coordinator.move(list, 2, false) { true }
            shadowOf(Looper.getMainLooper()).idle()
            coordinator.align(list, 2, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(move.isActive)
            coordinator.align(list, 3, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(move.isCancelled)
        } finally { coordinator.cancel(); scope.cancel() }
    }

    @Test fun revealOnlyNavigationKeepsItsFocusOwnerUntilReplacedOrCanceled() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + androidx.compose.runtime.BroadcastFrameClock())
        val coordinator = DiscoveryScrollCoordinator(scope)
        val list = LazyListState()
        val calls = mutableListOf<Int>()
        try {
            val old = coordinator.move(list, 2, false, revealOnly = true) { calls += 2; true }
            shadowOf(Looper.getMainLooper()).idle()
            coordinator.align(list, 2, false, revealOnly = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("A focus callback must not replace its own reveal-only move", old.isActive)
            val latest = coordinator.move(list, 3, false, revealOnly = true) { calls += 3; true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(old.isCancelled)
            assertTrue(latest.isActive)
            assertTrue("Unlaid-out destinations must not acquire stale focus", calls.isEmpty())
            coordinator.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(latest.isCancelled)
            assertTrue(calls.isEmpty())
        } finally { coordinator.cancel(); scope.cancel() }
    }
    @Test fun reversingGridNavigationCancelsTheOldFocusTransfer() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + androidx.compose.runtime.BroadcastFrameClock())
        val coordinator = DiscoveryScrollCoordinator(scope)
        val grid = androidx.compose.foundation.lazy.grid.LazyGridState()
        val calls = mutableListOf<Int>()
        try {
            val old = coordinator.moveGrid(grid, 16, 8, true, false) { calls += 16; false }
            shadowOf(Looper.getMainLooper()).idle()
            val latest = coordinator.moveGrid(grid, 8, 8, true, false) { calls += 8; false }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(old.isCancelled)
            assertTrue(latest.isActive)
            val count = calls.count { it == 16 }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(count, calls.count { it == 16 })
            coordinator.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(latest.isCancelled)
        } finally { scope.cancel() }
    }
}
