package com.nikhil.niktv.sports

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.NavigationAuditEvidence
import org.junit.Assert.*
import org.junit.Test

/** Production portal Activities and controls; never selects a channel or opens a video stream. */
class PlayfyPortalDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun find(label: String, descriptionOnly: Boolean = false): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label || (!descriptionOnly &&
                    node.text?.toString()?.lineSequence()?.any { it == label } == true)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(ins.uiAutomation.rootInActiveWindow)
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
        NavigationAuditEvidence.capture("playfy-portal-failure")
        assertTrue(label, condition())
    }
    private fun action(label: String, action: Int, portal: Boolean = false) {
        var node = find(label, portal)
        repeat(8) {
            if ((action == AccessibilityNodeInfo.ACTION_FOCUS && node?.isFocusable != true) ||
                (action == AccessibilityNodeInfo.ACTION_CLICK && node?.isClickable != true)) node = node?.parent
        }
        assertTrue("Reachable $label", node?.performAction(action) == true)
    }
    private fun focused(label: String, portal: Boolean = false): Boolean {
        var node = find(label, portal)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun key(code: Int) { ins.sendKeyDownUpSync(code); ins.waitForIdleSync() }
    private fun start(): Activity {
        check(ins.targetContext.packageName.endsWith(".regression")) { "Run only against the separate regression package" }
        ins.setInTouchMode(false)
        return ins.startActivitySync(Intent(ins.targetContext, PlayfyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test fun productionPlayfyOptionsOpenOwnServerPickerAndRestoreOrigin() {
        val activity = start()
        try {
            await("Playfy production header") { find("Playfy options") != null }
            action("Playfy options", AccessibilityNodeInfo.ACTION_FOCUS)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Playfy-specific server action") { find("Playfy servers") != null }
            assertNull("Cricfy server action must not be present in Playfy", find("Sports servers"))
            action("Playfy servers", AccessibilityNodeInfo.ACTION_FOCUS)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Automatic gets server-picker entry focus") { focused("Automatic") }
            NavigationAuditEvidence.capture("playfy-portal-server-picker")
            action("Close", AccessibilityNodeInfo.ACTION_FOCUS)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Close restores the Playfy options origin") { find("Playfy servers") == null && focused("Playfy options") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Playfy menu reopens") { find("Playfy servers") != null }
            action("Playfy servers", AccessibilityNodeInfo.ACTION_CLICK)
            await("Server picker reopens") { focused("Automatic") }
            key(KeyEvent.KEYCODE_BACK)
            await("Back restores the same Playfy options origin") { find("Playfy servers") == null && focused("Playfy options") }
            NavigationAuditEvidence.capture("playfy-portal-options-restored")
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun realHeaderDpadSwitchesBetweenSeparateSportsAndPlayfyActivities() {
        val activities = mutableListOf(start())
        val sportsMonitor = ins.addMonitor(SportsActivity::class.java.name, null, false)
        val playfyMonitor = ins.addMonitor(PlayfyActivity::class.java.name, null, false)
        try {
            await("Playfy header and both portal targets") { find("Playfy options") != null && find("Playfy TV", true) != null && find("Sports", true) != null }
            action("Playfy TV", AccessibilityNodeInfo.ACTION_FOCUS, portal = true)
            await("Current Playfy portal accepts focus") { focused("Playfy TV", true) }
            NavigationAuditEvidence.capture("playfy-portal-focused")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left reaches the separate Sports portal") { focused("Sports", true) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val sports = ins.waitForMonitorWithTimeout(sportsMonitor, 10_000)
            assertNotNull("Sports opens a separate production Activity", sports)
            activities += requireNotNull(sports)
            assertEquals(SportsActivity::class.java, sports.javaClass)
            await("Cricfy keeps its Sports options") { find("Sports options") != null && find("Playfy options") == null }
            action("Sports", AccessibilityNodeInfo.ACTION_FOCUS, portal = true)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Playfy again") { focused("Playfy TV", true) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val playfy = ins.waitForMonitorWithTimeout(playfyMonitor, 10_000)
            assertNotNull("Playfy returns through its own production Activity", playfy)
            activities += requireNotNull(playfy)
            assertEquals(PlayfyActivity::class.java, playfy.javaClass)
            await("Returned Playfy header") { find("Playfy options") != null && find("Sports options") == null }
            action("Playfy TV", AccessibilityNodeInfo.ACTION_FOCUS, portal = true)
            NavigationAuditEvidence.capture("playfy-portal-returned")
        } finally {
            ins.removeMonitor(sportsMonitor)
            ins.removeMonitor(playfyMonitor)
            ins.runOnMainSync { activities.forEach { it.finish() } }
        }
    }
}
