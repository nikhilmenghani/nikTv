package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Shared Media3/VLC queue surface, without provider accounts or playback. */
class PlayerEpisodeQueueDeviceTest {
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
    private fun focused(label: String): Boolean {
        var node = find(label)
        while (node != null && !node.isFocusable) node = node.parent
        return node?.isFocused == true
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(50) }
        NavigationAuditEvidence.capture("episode-queue-failure")
        fail(message)
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    private fun fixture(layout: PlayerQueueLayout, playing: Int = 1, touch: Boolean = false, phone: Boolean = false,
        block: (PlayerQueueRegressionActivity) -> Unit) {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        val activity = ins.startActivitySync(Intent(ins.targetContext, PlayerQueueRegressionActivity::class.java)
            .putExtra("episodes", true).putExtra("layout", layout.name).putExtra("playingId", "episode-$playing")
            .putExtra("touchRail", touch).putExtra("phone", phone)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlayerQueueRegressionActivity
        try {
            await("Episode $playing owns initial queue focus") { focused("Fixture episode $playing") }
            block(activity)
        } finally { ins.runOnMainSync { activity.finish() }; ins.setInTouchMode(true) }
    }

    @Test fun deepGridAndListReachViewControlsWithoutClimbingAndRetainEpisode() =
        fixture(PlayerQueueLayout.GRID, playing = 61) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Episode 62 is beside episode 61") { focused("Fixture episode 62") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Episode 63 is at the row edge") { focused("Fixture episode 63") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right edge reaches fixed List control from a deep row") { focused("List") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Layout changes without playing or losing header focus") {
                PlayerQueuePreferences.layout(ins.targetContext) == PlayerQueueLayout.LIST && focused("Tiles")
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("List restores episode 63") { focused("Fixture episode 63") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("List Right reaches fixed Tiles control immediately") { focused("Tiles") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Grid is selected while the fixed control retains focus") {
                PlayerQueuePreferences.layout(ins.targetContext) == PlayerQueueLayout.GRID && focused("List")
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Grid restores the same browsed episode 63") { focused("Fixture episode 63") }
            assertFalse("Navigation/layout switches never play", PlayerQueueRegressionActivity.selected)
            NavigationAuditEvidence.capture("player-episode-queue-deep-grid")
        }

    @Test fun listTopUpStopsAndLeftDismissesWithoutPlaying() = fixture(PlayerQueueLayout.LIST) { activity ->
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_UP) }
        await("Up at the first list item remains there") { focused("Fixture episode 1") }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right reaches the layout control") { focused("Tiles") }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await("Control Down restores first episode") { focused("Fixture episode 1") }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await("Left dismisses the list") { activity.isFinishing || activity.isDestroyed }
        assertFalse(PlayerQueueRegressionActivity.selected)
    }

    @Test fun remoteHoldOpensEpisodeMenuInBothLayouts() {
        for (layout in PlayerQueueLayout.entries) fixture(layout, playing = 61) {
            val down = SystemClock.uptimeMillis()
            ins.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            ins.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            verifyMenuAndReturn("Fixture episode 61")
        }
    }

    @Test fun shortPressStillPlaysTheSelectedEpisodeInBothLayouts() {
        for (layout in PlayerQueueLayout.entries) fixture(layout, playing = 61) {
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Short OK plays episode 61 in $layout") { PlayerQueueRegressionActivity.selectedId == "episode-61" }
        }
    }

    @Test fun phoneTouchHoldOpensEpisodeMenuInBothLayouts() {
        for (layout in PlayerQueueLayout.entries) fixture(layout, playing = 61, touch = true, phone = true) {
            ins.setInTouchMode(true)
            val bounds = Rect().also(checkNotNull(find("Fixture episode 61"))::getBoundsInScreen)
            val down = SystemClock.uptimeMillis()
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0).also {
                ins.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0).also {
                ins.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
            await("Touch hold opens episode options in $layout") { find("Play") != null && find("Add to My List") != null }
            assertFalse("Holding never starts playback", PlayerQueueRegressionActivity.selected)
            NavigationAuditEvidence.capture("phone-player-episode-menu-${layout.name.lowercase()}")
            key(KeyEvent.KEYCODE_BACK)
            await("Touch menu closes") { find("Play") == null }
            assertFalse(PlayerQueueRegressionActivity.selected)
        }
    }

    private fun verifyMenuAndReturn(origin: String) {
        await("Remote hold opens episode options") { find("Play") != null && find("Add to My List") != null }
        assertFalse("Long press never plays an episode", PlayerQueueRegressionActivity.selected)
        assertNull("Episode menu has no channel-only pin action", find("Pin to top"))
        NavigationAuditEvidence.capture("player-episode-options-menu")
        key(KeyEvent.KEYCODE_BACK)
        await("Menu dismissal restores originating episode") { find("Play") == null && focused(origin) }
        assertFalse(PlayerQueueRegressionActivity.selected)
    }
}
