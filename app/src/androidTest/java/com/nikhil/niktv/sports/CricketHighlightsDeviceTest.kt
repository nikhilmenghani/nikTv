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

    @Test fun categoryRowsSupportHorizontalAndVerticalRemoteNavigation() {
        val activity = host()
        var played = ""
        var refreshed = 0
        val featured = (1..8).map { CricketHighlight("$it", "Fixture highlight $it", "24:27") }
        val series = (21..28).map { CricketHighlight("$it", "Series clip $it", "3:36") }
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                CricketHighlightsContent(CricketHighlightsState(videos = featured, configured = true,
                    sections = listOf(CricketVideoSection(null, "Featured highlights", featured),
                        CricketVideoSection(656, "India tour of New Zealand", series))), {}, {}, { refreshed++ }, { played = it })
            } } }
            await("Video rows displayed") { find("Videos") != null }
            ins.waitForIdleSync()
            ins.setInTouchMode(false)
            await("Highlights tab focused") { focused("Highlights") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("First row entered") { focused("Fixture highlight 1") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Horizontal tile navigation") { focused("Fixture highlight 2") }
            assertEquals("", played)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Moves to the same position in the next category") { focused("Series clip 22") }
            assertEquals("", played)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK explicitly selects the video") { played == "22" }
            click("Refresh highlights")
            await("Refresh action") { refreshed == 1 }
            assertNotNull(find("3:36 · Highlights"))
        } finally { activity.finish() }
    }

    @Test fun viewAllOpensCategoryGridAndLoadMoreThenReturnsToRows() {
        val activity = host()
        var collection: Int? = null
        var more = 0
        val clip = CricketHighlight("1", "Fixture batting clip", "3:36", type = "Top Knocks")
        val state = mutableStateOf(CricketHighlightsState(configured = true, hasMore = true,
            collections = listOf(CricketVideoCollection(1, "Latest Videos")), videos = listOf(clip),
            sections = listOf(CricketVideoSection(1, "Latest Videos", listOf(clip)))))
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                CricketHighlightsContent(state.value, {}, {}, {}, {}, selectCollection = {
                    collection = it; state.value = state.value.copy(collectionId = it)
                }, loadMore = { more++ })
            } } }
            await("Latest Videos category displayed") { find("View all Latest Videos") != null }
            assertNull(collection)
            assertEquals(0, more)
            click("View all Latest Videos")
            await("Full category opens") { collection == 1 && find("Load more videos") != null }
            assertNotNull(find("3:36 · Top Knocks"))
            click("Load more videos")
            await("Pagination explicitly requested") { more == 1 }
            click("Back")
            await("Returns to category rows") { collection == null && find("View all Latest Videos") != null }
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
