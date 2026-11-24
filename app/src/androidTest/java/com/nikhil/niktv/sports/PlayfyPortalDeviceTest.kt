package com.nikhil.niktv.sports

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.NavigationAuditEvidence
import com.nikhil.niktv.ui.isTvLikeDevice
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
        // The Fire OS accessibility cache can still describe the previous window.
        val readCompose = Build.VERSION.SDK_INT in 29..32
        fun shown(label: String) = if (readCompose) activeSemantics { nodes -> nodes.any { it.matches(label) } } else find(label) != null
        fun ownsFocus(label: String) = if (readCompose) activeSemantics { nodes ->
            nodes.any { it.matches(label) && it.config.getOrNull(SemanticsProperties.Focused) == true }
        } else focused(label)
        fun activate(label: String, focus: Boolean) {
            if (!readCompose) return action(label, if (focus) AccessibilityNodeInfo.ACTION_FOCUS else AccessibilityNodeInfo.ACTION_CLICK)
            await("Reachable $label") { activeSemantics { nodes ->
                val key = if (focus) SemanticsActions.RequestFocus else SemanticsActions.OnClick
                nodes.filter { it.matches(label) }.firstNotNullOfOrNull { it.config.getOrNull(key)?.action }?.invoke() == true
            } }
        }
        try {
            await("Playfy production header") { shown("Playfy options") }
            activate("Playfy options", focus = true)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Playfy-specific server action") { shown("Playfy servers") }
            assertFalse("Cricfy server action must not be present in Playfy", shown("Sports servers"))
            activate("Playfy servers", focus = true)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Automatic gets server-picker entry focus") { ownsFocus("Automatic") }
            NavigationAuditEvidence.capture("playfy-portal-server-picker")
            activate("Close", focus = true)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Close restores the Playfy options origin") { !shown("Playfy servers") && ownsFocus("Playfy options") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Playfy menu reopens") { shown("Playfy servers") }
            activate("Playfy servers", focus = false)
            await("Server picker reopens") { ownsFocus("Automatic") }
            key(KeyEvent.KEYCODE_BACK)
            await("Back restores the same Playfy options origin") { !shown("Playfy servers") && ownsFocus("Playfy options") }
            NavigationAuditEvidence.capture("playfy-portal-options-restored")
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    private fun SemanticsNode.matches(label: String) = label in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.lineSequence().any { line -> line == label } }

    private fun <T : Any> activeSemantics(read: (Sequence<SemanticsNode>) -> T): T {
        fun roots(view: View): Sequence<ViewRootForTest> = sequence {
            if (view is ViewRootForTest) yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(roots(view.getChildAt(index)))
        }
        lateinit var result: T
        ins.runOnMainSync {
            result = read(WindowInspector.getGlobalWindowViews().asSequence().filter { it.hasWindowFocus() }.flatMap(::roots)
                .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).asSequence() }
                .filter { !it.boundsInWindow.isEmpty })
        }
        return result
    }

    @Test fun realHeaderDpadSwitchesBetweenSeparateSportsAndPlayfyActivities() {
        val activities = mutableListOf(start())
        val sportsMonitor = ins.addMonitor(SportsActivity::class.java.name, null, false)
        val playfyMonitor = ins.addMonitor(PlayfyActivity::class.java.name, null, false)
        val focusEvents = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, String>>()
        ins.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                val label = event.contentDescription ?: event.source?.contentDescription ?: event.text.firstOrNull()
                if (label != null) focusEvents += event.windowId to label.toString()
            }
        }
        val host = activities.first()
        val configuration = host.resources.configuration
        val compact = com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout(configuration, host.isTvLikeDevice(configuration))
        fun assertEntryFocus(portal: String) {
            val expected = if (compact) "Portals" else portal
            await("$expected receives focus from the portal switch") { focused(expected, true) }
            val window = requireNotNull(find(expected, true)).windowId
            // Some Android builds expose initial Compose focus in the tree without
            // dispatching TYPE_VIEW_FOCUSED while the activity window is attaching.
            focusEvents.firstOrNull { it.first == window }?.let { (_, firstLabel) ->
                assertEquals("The destination must focus its portal before any other control: $focusEvents", expected, firstLabel)
            }
            if (compact) {
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("The menu focuses its selected portal") { focused(portal) }
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK keeps focus at the active portal") { focused(expected, true) }
        }
        try {
            await("Playfy header and portal navigation") {
                find("Playfy options") != null && if (compact) find("Portals", true) != null
                    else find("Playfy TV", true) != null && find("Sports", true) != null
            }
            action(if (compact) "Portals" else "Playfy TV", AccessibilityNodeInfo.ACTION_FOCUS, portal = true)
            if (compact) {
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Current Playfy portal accepts focus") { focused("Playfy TV") }
            } else await("Current Playfy portal accepts focus") { focused("Playfy TV", true) }
            NavigationAuditEvidence.capture("playfy-portal-focused")
            key(if (compact) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT)
            await("Direction reaches the separate Sports portal") { focused("Sports", !compact) }
            focusEvents.clear()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val sports = ins.waitForMonitorWithTimeout(sportsMonitor, 10_000)
            assertNotNull("Sports opens a separate production Activity", sports)
            activities += requireNotNull(sports)
            assertEquals(SportsActivity::class.java, sports.javaClass)
            await("Cricfy keeps its Sports options") { find("Sports options") != null && find("Playfy options") == null }
            assertEntryFocus("Sports")
            NavigationAuditEvidence.capture("sports-portal-entry-focus")
            if (compact) {
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Sports is selected in its portal menu") { focused("Sports") }
            }
            key(if (compact) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Direction reaches Playfy again") { focused("Playfy TV", !compact) }
            focusEvents.clear()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val playfy = ins.waitForMonitorWithTimeout(playfyMonitor, 10_000)
            assertNotNull("Playfy returns through its own production Activity", playfy)
            activities += requireNotNull(playfy)
            assertEquals(PlayfyActivity::class.java, playfy.javaClass)
            await("Returned Playfy header") { find("Playfy options") != null && find("Sports options") == null }
            assertEntryFocus("Playfy TV")
            NavigationAuditEvidence.capture("playfy-portal-returned")
        } finally {
            ins.uiAutomation.setOnAccessibilityEventListener(null)
            ins.removeMonitor(sportsMonitor)
            ins.removeMonitor(playfyMonitor)
            ins.runOnMainSync { activities.forEach { it.finish() } }
        }
    }
}
