package com.nikhil.niktv.ui

import com.nikhil.niktv.discovery.components.*

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.LiveProgramme
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic catalogue: never authenticates or reads personal profiles. */
class TileNavigationMotionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, test: () -> Boolean) {
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) { if (test()) return; Thread.sleep(30) }
        fail(label)
    }
    @Test fun visibleMovesAreSynchronousAndOffscreenRevealKeepsViewport() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        lateinit var grid: LazyGridState
        lateinit var scope: CoroutineScope
        val requesters = (0 until 60).associateWith { FocusRequester() }
        var ready = false
        var focused = -1
        var clicked = -1
        var menuOpens = 0
        val now = System.currentTimeMillis()
        try {
            instrumentation.runOnMainSync {
                host.setContent {
                    grid = rememberLazyGridState()
                    scope = rememberCoroutineScope()
                    SideEffect { ready = true }
                    MaterialTheme { StableTileFocusViewport {
                        LazyVerticalGrid(GridCells.Fixed(3), Modifier.width(740.dp).height(340.dp), state = grid,
                            contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(60, key = { it }) { index ->
                                ModernLiveChannelTile(MediaItem("fixture-$index", if (index == 0) "24/7 MISSION IMPOSSIBLE MOVIES (4K)" else "Fixture channel $index", null, null,
                                    liveProgramme = if (index == 0) LiveProgramme("Mission: Impossible - Dead Reckoning Part One", now - 1_200_000L, now + 4_200_000L) else null),
                                    onRefreshProgramme = {}, categoryTitle = "Local fixture", guideNow = now,
                                    programmeLoading = false, programmeWaitingUntil = null, programmeStatusNow = now,
                                    showQualityBadge = true, isFavorite = false, onFavorite = {}, isPinned = index == 0,
                                    onTogglePin = {}, onClick = { clicked = index }, isTv = true, compact = true,
                                    onFocusChange = { if (it) focused = index },
                                    onMenuVisibilityChange = { if (it) menuOpens++ },
                                    modifier = Modifier.focusRequester(requesters.getValue(index)))
                            }
                        }
                    } }
                }
            }
            await("Initial rows composed") { ready && grid.layoutInfo.visibleItemsInfo.size >= 6 }
            instrumentation.runOnMainSync {
                for (index in listOf(0, 3, 6, 3, 0, 1, 4, 1, 0)) {
                    assertTrue("Visible target $index must accept focus in this call", grid.focusVisibleTile(index, requesters.getValue(index)))
                    assertEquals("No asynchronous parking or frame wait", index, focused)
                }
                assertEquals(0, grid.firstVisibleItemIndex)
            }
            instrumentation.waitForIdleSync()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.externalCacheDir, "compact-live-guide-3columns.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            instrumentation.runOnMainSync {
                assertTrue("Clipped attached row must accept focus before scrolling", grid.focusVisibleTile(9, requesters.getValue(9)))
                assertEquals(9, focused)
            }
            await("Compose reveals the clipped row while it retains focus") {
                val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 9 }
                target != null && target.offset.y + target.size.height <= grid.layoutInfo.viewportEndOffset
            }
            instrumentation.runOnMainSync {
                assertEquals(9, focused)
                scope.launch { grid.revealTileRow(15, 3) }
            }
            await("Offscreen row smoothly revealed") {
                val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 15 }
                target != null && target.offset.y + target.size.height <= grid.layoutInfo.viewportEndOffset
            }
            instrumentation.runOnMainSync {
                assertTrue(grid.focusVisibleTile(15, requesters.getValue(15)))
                assertEquals(15, focused)
                assertTrue("Reveal retains preceding rows instead of snapping target to top", grid.firstVisibleItemIndex < 15)
                scope.launch { grid.revealTileRow(0, 3) }
            }
            await("Reverse direction returns to the first row") { grid.firstVisibleItemIndex == 0 && !grid.isScrollInProgress }
            instrumentation.runOnMainSync { assertTrue(grid.focusVisibleTile(0, requesters.getValue(0))) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK still activates selected tile") { clicked == 0 }
            assertEquals("Navigation must not open long-press menus", 0, menuOpens)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
