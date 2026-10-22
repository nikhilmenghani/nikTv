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
