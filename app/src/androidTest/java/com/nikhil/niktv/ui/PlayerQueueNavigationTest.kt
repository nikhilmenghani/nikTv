package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PlayerQueueNavigationTest {
    @org.junit.Before fun enableRemoteInput() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun focused(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (node.isFocused) return node
        for (index in 0 until node.childCount) focused(node.getChild(index))?.let { return it }
        return null
    }
    private fun bounds(): Rect? = focused(instrumentation.uiAutomation.rootInActiveWindow)?.let {
        Rect().also(it::getBoundsInScreen)
    }
    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    @Test fun touchRailEdgesKeepBordersAndNavigateToTheCorrectChannel() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val activity = instrumentation.startActivitySync(Intent(context, PlayerQueueRegressionActivity::class.java)
            .putExtra("touchRail", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun capture(name: String) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.externalCacheDir, name).outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
        try {
            val deadline = System.currentTimeMillis() + 8000
            while (bounds() == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            Thread.sleep(350)
            val first = requireNotNull(bounds())
            capture("queue-first-edge.png")
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT); Thread.sleep(200) }
            val last = requireNotNull(bounds())
            assertTrue("Fourth tile must remain fully on screen", last.right < context.resources.displayMetrics.widthPixels)
            capture("queue-last-edge.png")
            assertFalse("Focusing edge tiles must not select a channel", PlayerQueueRegressionActivity.selected)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val selectedDeadline = System.currentTimeMillis() + 4000
            while (PlayerQueueRegressionActivity.selectedId != "3" && System.currentTimeMillis() < selectedDeadline) Thread.sleep(100)
            assertEquals("Three Right presses must reach the fourth tile", "3", PlayerQueueRegressionActivity.selectedId)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun layoutToggleKeepsTheBrowsedChannelAndDoesNotPlayIt() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val activity = instrumentation.startActivitySync(Intent(context, PlayerQueueRegressionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 8000L
            while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
            fail(message)
        }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.text?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
            return null
        }
        try {
            await("Queue must focus its first tile") { bounds() != null }
            Thread.sleep(200)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            Thread.sleep(200)
            key(KeyEvent.KEYCODE_DPAD_UP)
            Thread.sleep(200)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Header toggles to list") { PlayerQueuePreferences.layout(context) == PlayerQueueLayout.LIST }
            assertFalse(PlayerQueueRegressionActivity.selected)
            Thread.sleep(200)
            repeat(2) { index ->
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                val expected = if (index == 0) PlayerQueueLayout.GRID else PlayerQueueLayout.LIST
                await("Repeated OK must toggle without leaving the header") { PlayerQueuePreferences.layout(context) == expected }
                Thread.sleep(200)
                assertFalse("Toggling must never play a channel", PlayerQueueRegressionActivity.selected)
            }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.externalCacheDir, "channel-list-toggle.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            Thread.sleep(200)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("List must retain the browsed channel") { PlayerQueueRegressionActivity.selectedId == "1" }
            var button = requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Tiles"))
            while (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) button = requireNotNull(button.parent)
            await("Header switches back to tiles") { PlayerQueuePreferences.layout(context) == PlayerQueueLayout.GRID }
            Thread.sleep(200)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            Thread.sleep(200)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals("Grid must retain the same channel", "1", PlayerQueueRegressionActivity.selectedId)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun horizontalNavigationKeepsScrolledRowStill() {
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".regression"))
        val activity = instrumentation.startActivitySync(
            Intent(context, PlayerQueueRegressionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val deadline = System.currentTimeMillis() + 10000
            while (bounds() == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN); Thread.sleep(400) }
            val before = requireNotNull(bounds())
            assertTrue("Focus must be on a compact card", before.height() < 250)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            Thread.sleep(200)
            assertEquals("Left at the first column must not navigate down", before, bounds())
            repeat(4) { move ->
                key(if (move % 2 == 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                repeat(20) {
                    bounds()?.let { current ->
                        assertTrue("Focused row bounced: before=$before current=$current", kotlin.math.abs(current.top - before.top) <= 2)
                    }
                    Thread.sleep(20)
                }
            }
            assertFalse("Navigation must not start a channel", PlayerQueueRegressionActivity.selected)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun listUsesVerticalNavigationAndLeftCloses() {
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".regression"))
        val activity = instrumentation.startActivitySync(Intent(context, PlayerQueueRegressionActivity::class.java)
            .putExtra("layout", "LIST").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val deadline = System.currentTimeMillis() + 10000
            while (bounds() == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val first = requireNotNull(bounds())
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            Thread.sleep(400)
            val second = requireNotNull(bounds())
            assertTrue("List moves down one item", second.top > first.top)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            Thread.sleep(200)
            assertEquals("Right must not behave like Down", second, bounds())
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            Thread.sleep(300)
            assertTrue("Left closes the side panel", activity.isFinishing || activity.isDestroyed)
            assertFalse(PlayerQueueRegressionActivity.selected)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
