package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Production navigation and real focus targets. Does not perform any network sync. */
class ManualSyncSettingsDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun normalized(value: CharSequence?) = value?.toString()?.replace(Regex("\\s+"), " ")?.trim()
    private fun find(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (matches(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(ins.uiAutomation.rootInActiveWindow)
    }
    private fun label(value: String) = find { normalized(it.text) == value || normalized(it.contentDescription) == value }
    private fun target(value: String): AccessibilityNodeInfo? {
        var node = label(value)
        repeat(8) { if (node?.isClickable == true) return node; node = node?.parent }
        return null
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(40)
        if (!condition()) {
            val focused = ins.uiAutomation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            println("Focus at failure: ${focused?.text ?: focused?.contentDescription}; $message")
            capture("manual-sync-failure")
        }
        assertTrue(message, condition())
    }
    private fun click(value: String) {
        // Short landscape pages can place a child route below the current viewport.
        for (attempt in 0 until 8) {
            if (target(value) != null) break
            val content = find { node ->
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                node.isScrollable && bounds.right > ins.targetContext.resources.displayMetrics.widthPixels * .6
            }
            if (content?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) break
            ins.waitForIdleSync()
        }
        await("Available $value") { target(value) != null }
        target(value)!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        assertTrue("Clickable $value", target(value)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        ins.waitForIdleSync()
    }
    private fun focused(value: String) = target(value)?.isFocused == true
    private fun capture(name: String) {
        check(ins.uiAutomation.rootInActiveWindow?.packageName == ins.targetContext.packageName)
        val folder = java.io.File(ins.targetContext.externalCacheDir, "manual-sync-audit").apply { mkdirs() }
        val screenshot = requireNotNull(ins.uiAutomation.takeScreenshot())
        try { java.io.File(folder, "$name.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { screenshot.recycle() }
    }

    @Test fun productionSettingsHaveAParentAndReachableSyncControls() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("validateManualSyncUi") == "true")
        check(ins.targetContext.packageName == "com.nikhil.niktv.debug")
        ins.setInTouchMode(false)
        val activity = ins.startActivitySync(Intent(ins.targetContext, com.nikhil.niktv.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("Production navigation ready") { label("Settings") != null || label("Options") != null ||
                find { it.contentDescription?.startsWith("Switch profile:") == true } != null }
            if (label("Settings") == null) {
                val profile = find { it.contentDescription?.startsWith("Switch profile:") == true }
                if (profile != null) click(profile.contentDescription.toString()) else click("Options")
            }
            click("Settings")
            assertNull("Sync mode belongs inside its category", label("Manual pull only (debug)"))
            click("Profiles & portals")
            await("Profiles category exposes its sync child") { target("Profile sync") != null }
            click("Profile sync")
            await("Sync controls mounted") { target("Manual pull only (debug)") != null }
            assertTrue(target("Manual pull only (debug)")!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            await("Mode focused") { focused("Manual pull only (debug)") }
            capture("manual-sync-mode-focused")
            val action = if (label("Pull profile") != null) "Pull profile" else "Sync all enabled"
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down reaches sync action") { focused(action) }
            capture("manual-sync-action-focused")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Up returns to mode") { focused("Manual pull only (debug)") }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back returns to parent link") { label("Manual pull only (debug)") == null && focused("Profile sync") }
        } finally { ins.runOnMainSync { activity.finish() } }
    }
}
