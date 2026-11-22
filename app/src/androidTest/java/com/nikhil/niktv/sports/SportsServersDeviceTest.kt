package com.nikhil.niktv.sports

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.NavigationAuditEvidence
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.SportsFixtureHostActivity
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Fake catalogue servers: exercises the real Sports menu/dialog without opening any stream. */
class SportsServersDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(60) }
        NavigationAuditEvidence.capture("sports-server-failure")
        assertTrue(label, condition())
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label || node.text?.toString()?.lineSequence()?.any { it == label } == true) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(ins.uiAutomation.rootInActiveWindow)
    }
    private fun button(label: String): AccessibilityNodeInfo? {
        var node = find(label)
        repeat(8) { if (node?.isClickable != true) node = node?.parent }
        return node
    }
    private fun click(label: String) { assertTrue("Clickable $label", button(label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) }
    private fun focused(label: String): Boolean {
        var node = find(label)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun key(code: Int) { ins.sendKeyDownUpSync(code); ins.waitForIdleSync() }
    private fun host(): SportsFixtureHostActivity {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        return ins.startActivitySync(Intent(ins.targetContext, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
    }

    @Test fun realMenuPickerKeepsFocusThroughChecksSelectionAndDismissal() {
        val activity = host()
        val longBase = "https://regional-${"a".repeat(45)}.example.test/"
        val longLabel = SportsServer(longBase).label
        val servers = mutableStateOf(SportsServersState(listOf(SportsServer(longBase), SportsServer("https://short.example.test/"))))
        val chosen = AtomicReference<String?>("not-selected")
        val checks = AtomicInteger()
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                SportsBrowseContent(SportsState(tab = SportsTab.CHANNELS, loading = false), Instant.now(),
                    "All sports", {}, "Now & next", {}, "", {}, {}, {}, {}, {},
                    servers = servers.value, checkServers = { checks.incrementAndGet() },
                    selectServer = { chosen.set(it); servers.value = servers.value.copy(preferred = it) })
            } } }
            await("Sports header ready") { find("Sports options") != null }
            click("Sports options"); await("Menu ready") { find("Sports servers") != null }; click("Sports servers")
            await("Automatic is the initial entry") { focused("Automatic") }
            assertEquals(1, checks.get())
            ins.runOnMainSync { servers.value = servers.value.copy(servers = servers.value.servers.map { it.copy(health = SportsServerHealth.AVAILABLE) }) }
            ins.waitForIdleSync()
            assertTrue("Health updates do not steal focus", focused("Automatic"))
            val before = Rect().also { requireNotNull(button(longLabel)).getBoundsInScreen(it) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad reaches long server row") { focused(longLabel) }
            await("The whole long row is revealed before drawing its focus outline") {
                val bounds = Rect().also { button(longLabel)?.getBoundsInScreen(it) }
                bounds.height() >= (72 * activity.resources.displayMetrics.density).toInt()
            }
            val after = Rect().also { requireNotNull(button(longLabel)).getBoundsInScreen(it) }
            assertEquals("Focus must not change row width; scrolling may change its position", before.width(), after.width())
            NavigationAuditEvidence.capture("sports-server-long-focused")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Selection returns directly to Options") { chosen.get() == longBase && focused("Sports options") }
            click("Sports options"); await("Menu reopened") { find("Sports servers") != null }; click("Sports servers")
            await("Preferred row receives entry focus") { focused(longLabel) }
            click("Close")
            await("Close restores origin") { find("Sports servers") == null && focused("Sports options") }
            click("Sports options"); await("Menu opened again") { find("Sports servers") != null }; click("Sports servers")
            await("Preferred entry remains reachable") { focused(longLabel) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Automatic can be selected") { focused("Automatic") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Automatic selection closes dialog") { chosen.get() == null && focused("Sports options") }
            NavigationAuditEvidence.capture("sports-server-origin-restored")
        } finally { ins.runOnMainSync { activity.finish() } }
    }

    @Test fun selectedLastServerIsInitiallyVisibleInAScrollingList() {
        val activity = host()
        val rows = (1..12).map { SportsServer("https://server-$it.example.test/", SportsServerHealth.AVAILABLE) }
        val dismissed = AtomicInteger()
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                SportsServersDialog(SportsServersState(rows, preferred = rows.last().base, active = rows.first().base), {}, {}, { dismissed.incrementAndGet() })
            } } }
            await("Last selected server is visible and focused") { focused(rows.last().label) }
            NavigationAuditEvidence.capture("sports-server-last-selected")
            click("Check servers")
            assertTrue("Check button stays mounted", find("Check servers") != null)
            key(KeyEvent.KEYCODE_BACK)
            await("Back dismisses") { dismissed.get() == 1 }
        } finally { ins.runOnMainSync { activity.finish() } }
    }
}
