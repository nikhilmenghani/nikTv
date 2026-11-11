package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Changes only window presentation; the touch device never impersonates a television. */
class PlayerQueueOrientationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(root())
    }
    private fun owner(label: String): AccessibilityNodeInfo? {
        var node = find(label)
        while (node != null && !node.isFocusable && node.parent != null) node = node.parent
        return node
    }
    private fun focused(label: String): Boolean {
        var node = find(label)
        while (node != null) {
            if (node.isFocused) return true
            node = node.parent
        }
        return false
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(50) }
        NavigationAuditEvidence.capture("player-queue-orientation-failure")
        fail(message)
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    private fun touch(action: Int, x: Float, y: Float, down: Long) {
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
            ins.uiAutomation.injectInputEvent(it, true); it.recycle()
        }
    }
    private fun fixture(initialLandscape: Boolean,
        body: (rotate: (Boolean) -> Unit, plays: CopyOnWriteArrayList<String>, dismissals: AtomicInteger) -> Unit) {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        org.junit.Assume.assumeFalse(context.isTvLikeDevice(context.resources.configuration))
        val oldLayout = PlayerQueuePreferences.layout(context)
        PlayerQueuePreferences.setLayout(context, PlayerQueueLayout.GRID)
        val landscape = mutableStateOf(initialLandscape)
        val plays = CopyOnWriteArrayList<String>()
        val dismissals = AtomicInteger()
        val dismissQueue: () -> Unit = { dismissals.incrementAndGet(); Unit }
        val episodes = (1..100).map { MediaItem("orientation-$it", "Orientation episode $it", null, null, seasonNumber = 1, episodeNumber = it) }
        ins.setInTouchMode(false)
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            ins.runOnMainSync { host.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = if (landscape.value) 800 else 400
                    screenHeightDp = if (landscape.value) 400 else 800
                    smallestScreenWidthDp = 400
                    orientation = if (landscape.value) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) { MaterialTheme(colorScheme = NikColors) {
                    PlayerQueueOverlay(episodes, "orientation-61", catalogType = CatalogType.SERIES,
                        onToggleFavorite = {}, onDismiss = dismissQueue,
                        onSelect = { item, _ -> plays.add(item.id) })
                } }
            } }
            await("The current deep episode owns initial focus") { focused("Orientation episode 61") }
            body({ next -> ins.runOnMainSync { landscape.value = next } }, plays, dismissals)
        } finally {
            ins.runOnMainSync { host.finish() }
            PlayerQueuePreferences.setLayout(context, oldLayout)
            ins.setInTouchMode(true)
        }
    }

    @Test fun portraitRailAndLandscapeGridKeepDeepEpisodeAndListPreference() = fixture(false) { rotate, plays, dismissals ->
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Portrait rail reaches episode 62") { focused("Orientation episode 62") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Portrait rail reaches episode 63") { focused("Orientation episode 63") }
        rotate(true)
        await("Landscape grid keeps the browsed episode") { focused("Orientation episode 63") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Landscape uses vertical grid rows instead of a horizontal rail") { focused("Orientation episode 66") }
        rotate(false)
        await("Portrait rail restores episode 66") { focused("Orientation episode 66") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Portrait keeps horizontal browsing") { focused("Orientation episode 67") }
        rotate(true)
        await("Second landscape entry retains episode 67") { focused("Orientation episode 67") }
        for (number in 68..69) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Landscape row reaches episode $number") { focused("Orientation episode $number") }
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Landscape row edge reaches the fixed view control") { focused("List") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("List selection retains header focus") { PlayerQueuePreferences.layout(ins.targetContext) == PlayerQueueLayout.LIST && focused("Tiles") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("List retains the deep episode") { focused("Orientation episode 69") }
        rotate(false)
        await("Portrait preserves List and the selected episode") { PlayerQueuePreferences.layout(ins.targetContext) == PlayerQueueLayout.LIST && focused("Orientation episode 69") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("List retains quick access to its view control") { focused("Tiles") }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Portrait can return to the rail") { focused("List") && PlayerQueuePreferences.layout(ins.targetContext) == PlayerQueueLayout.GRID }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Rail restores the same episode after switching views") { focused("Orientation episode 69") }
        NavigationAuditEvidence.capture("player-queue-portrait-rotation-return")
        assertTrue("Rotation and navigation never play", plays.isEmpty())
        assertEquals("Rotation never dismisses the queue", 0, dismissals.get())
    }

    @Test fun landscapeGridKeepsTouchScrollingAndLongPressOptions() = fixture(true) { _, plays, dismissals ->
        val first = bounds(checkNotNull(owner("Orientation episode 61")))
        assertEquals("Landscape touch grid has three columns", first.top, bounds(checkNotNull(owner("Orientation episode 63"))).top)
        assertTrue("The next three episodes form another row", bounds(checkNotNull(owner("Orientation episode 64"))).top > first.top)
        ins.setInTouchMode(true)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, first.exactCenterX(), first.exactCenterY(), down)
        Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        touch(MotionEvent.ACTION_UP, first.exactCenterX(), first.exactCenterY(), down)
        await("Touch hold opens the episode menu in the wide grid") { find("Play") != null && find("Add to My List") != null }
        assertTrue("A touch hold never plays", plays.isEmpty())
        NavigationAuditEvidence.capture("landscape-player-queue-touch-menu")
        key(KeyEvent.KEYCODE_BACK)
        await("The episode menu closes") { find("Play") == null }
        ins.setInTouchMode(true)
        var grid = find("Orientation episode 61")
        while (grid != null && !grid.isScrollable) grid = grid.parent
        val viewport = bounds(checkNotNull(grid))
        val started = SystemClock.uptimeMillis()
        for (i in 0..16) {
            touch(when (i) { 0 -> MotionEvent.ACTION_DOWN; 16 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },
                viewport.exactCenterX(), viewport.bottom - 12f - (viewport.height() - 24f) * i / 16f, started)
            Thread.sleep(18)
        }
        await("Touch drag scrolls vertically through wide-grid episodes") { find("Orientation episode 61")?.isVisibleToUser != true }
        assertTrue(plays.isEmpty())
        assertEquals("Touch browsing keeps the queue open", 0, dismissals.get())
    }

    @Test fun rotatingToGridUpdatesPointerBoundsAndDeepDragDismissal() = fixture(false) { rotate, plays, dismissals ->
        rotate(true)
        await("Rotated wide grid retains its current episode") { focused("Orientation episode 61") && find("Orientation episode 64") != null }
        ins.setInTouchMode(true)
        fun tapControl(label: String) {
            val control = bounds(checkNotNull(owner(label)))
            val tap = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, control.exactCenterX(), control.exactCenterY(), tap)
            touch(MotionEvent.ACTION_UP, control.exactCenterX(), control.exactCenterY(), tap)
        }
        // The new grid header is above the old portrait rail's outside boundary.
        tapControl("List")
        await("A tap inside the taller sheet changes view") { PlayerQueuePreferences.layout(ins.targetContext) == PlayerQueueLayout.LIST && find("Tiles") != null }
        assertEquals("Rotated sheet header taps are not misclassified as outside taps", 0, dismissals.get())
        tapControl("Tiles")
        await("Touch can return to the wide grid") { PlayerQueuePreferences.layout(ins.targetContext) == PlayerQueueLayout.GRID && find("List") != null }
        ins.waitForIdleSync()
        var grid = find("Orientation episode 61")
        while (grid != null && !grid.isScrollable) grid = grid.parent
        val viewport = bounds(checkNotNull(grid))
        fun drag(from: Float, to: Float) {
            val start = SystemClock.uptimeMillis()
            for (i in 0..16) {
                touch(when (i) { 0 -> MotionEvent.ACTION_DOWN; 16 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },
                    viewport.exactCenterX(), from + (to - from) * i / 16f, start)
                Thread.sleep(18)
            }
        }
        val gestureInset = 56 * ins.targetContext.resources.displayMetrics.density
        drag(viewport.bottom - gestureInset, viewport.top + 12f)
        await("Touch browsing reaches a deep grid row") { find("Orientation episode 61")?.isVisibleToUser != true }
        drag(viewport.top + 12f, viewport.bottom - gestureInset)
        Thread.sleep(200)
        assertEquals("Downward drag inside a deep grid scrolls instead of using the old rail dismiss gesture", 0, dismissals.get())
        assertTrue("View changes and dragging never play an episode", plays.isEmpty())
        NavigationAuditEvidence.capture("player-queue-rotated-grid-pointer")
    }
}
