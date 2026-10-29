package com.nikhil.niktv.sports

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.SportsFixtureHostActivity
import org.junit.Assert.*
import org.junit.Test

/** Provider-free UI fixtures; focus and metadata never start playback. */
class CricketHighlightsDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
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
    private fun await(label: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) { if (check()) return; Thread.sleep(80) }
        assertTrue(label, check())
    }
    private fun focused(label: String): Boolean {
        var node = find(label)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun click(label: String) {
        var node = find(label)
        repeat(8) { if (node?.isClickable != true) node = node?.parent }
        assertTrue("Clickable $label", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun key(value: Int) { ins.sendKeyDownUpSync(value); Thread.sleep(140) }
    private fun host(): SportsFixtureHostActivity {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        val activity = ins.startActivitySync(Intent(ins.targetContext, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
        ins.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        return activity
    }

    @Test fun featuredGridUsesExplicitPlaybackAndSupportsRemoteNavigation() {
        val activity = host()
        var played = ""
        var refreshed = 0
        val videos = (1..40).map { CricketHighlight("$it", "Fixture highlight $it", "24:27") }
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                CricketHighlightsContent(CricketHighlightsState(videos = videos, configured = true), {}, {}, { refreshed++ }, { played = it })
            } } }
            await("Highlight screen displayed") { find("Cricket highlights") != null }
            ins.waitForIdleSync()
            ins.setInTouchMode(false)
            await("Highlights tab focused") { focused("Highlights") }
            assertEquals("", played)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("First highlight focused") { focused("Fixture highlight 1") }
            assertEquals("", played)
            val width = activity.resources.configuration.screenWidthDp
            val columns = ((width - (if (width < 700) 28 else 48) + 10) / ((if (width < 700) 240 else 280) + 10)).coerceAtLeast(1)
            key(if (columns > 1) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN)
            await("Next highlight focused") { focused("Fixture highlight 2") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK explicitly selects the stable video ID") { played == "2" }
            click("Refresh highlights")
            await("Refresh action") { refreshed == 1 }
            assertNotNull(find("Featured in Canada"))
            assertNotNull(find("24:27 · Highlights"))
        } finally { activity.finish() }
    }

    @Test fun configurationAndPreviewErrorsStayReadableAndRetryable() {
        val activity = host()
        val state = mutableStateOf(CricketHighlightsState())
        var retried = false
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                CricketHighlightsContent(state.value, {}, {}, {}, {}, { retried = true }, { state.value = CricketHighlightsState() })
            } } }
            await("Provider configuration is explained") { find("Connect Cricbuzz") != null }
            ins.runOnMainSync { state.value = CricketHighlightsState(configured = true,
                selected = CricketHighlight("1", "Fixture match"),
                error = "The provider returned a preview instead of the full highlight.") }
            await("Preview failure dialog") { find("Highlight unavailable") != null && find("Refresh and retry") != null }
            assertNotNull(find("The provider returned a preview instead of the full highlight."))
            click("Refresh and retry")
            await("Fresh retry invoked") { retried }
            click("Back to highlights")
            await("Returns to catalogue") { find("Connect Cricbuzz") != null }
        } finally { activity.finish() }
    }
}
