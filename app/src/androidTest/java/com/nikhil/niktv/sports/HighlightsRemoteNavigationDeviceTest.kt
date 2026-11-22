package com.nikhil.niktv.sports

import android.content.Intent
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SportsCricket
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.SportsFixtureHostActivity
import com.nikhil.niktv.discovery.DirectPortalAction
import com.nikhil.niktv.discovery.LocalDirectPortalActions
import com.nikhil.niktv.discovery.LocalPortalFocusReturn
import com.nikhil.niktv.discovery.PortalFocusReturn
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Exercise mounted/virtualized focus destinations without accessing profiles or playing media. */
class HighlightsRemoteNavigationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val history = CopyOnWriteArrayList<String>()
    private var host: SportsFixtureHostActivity? = null
    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(roots(view.getChildAt(i)))
    }
    private fun nodes(): List<SemanticsNode> = roots(requireNotNull(host).window.decorView)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
    private fun labels(node: SemanticsNode): List<String> = buildList {
        addAll(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
        addAll(node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
        node.children.forEach { addAll(labels(it)) }
    }.distinct()
    private fun focusOnMain(): List<String> = if (host?.hasWindowFocus() != true) emptyList() else nodes()
        .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let(::labels).orEmpty()
    private fun focusLabels(): List<String> {
        val value = AtomicReference<List<String>>()
        ins.runOnMainSync { value.set(focusOnMain()) }
        return value.get()
    }
    private fun focusedTileFitsViewport(): Boolean {
        var fits = false
        ins.runOnMainSync {
            val all = nodes()
            val tile = all.firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            val viewport = all.firstOrNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            if (tile != null && viewport != null) fits =
                tile.positionInRoot.y >= viewport.positionInRoot.y - 1 &&
                    tile.positionInRoot.y + tile.size.height <= viewport.positionInRoot.y + viewport.size.height + 1
        }
        return fits
    }
    private fun composed(label: String): Boolean {
        var found = false
        ins.runOnMainSync { found = nodes().any { label in labels(it) } }
        return found
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(30)
        assertTrue(message, condition())
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        val root = ins.uiAutomation.rootInActiveWindow
        return if (root?.packageName?.toString() == ins.targetContext.packageName) walk(root) else null
    }
    private fun focused(label: String): Boolean {
        return label in focusLabels()
    }
    private fun popupFocused(label: String): Boolean {
        var node = find(label)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun focus(label: String) {
        var requested = false
        ins.runOnMainSync {
            requested = nodes().firstOrNull { label in labels(it) && it.config.getOrNull(SemanticsActions.RequestFocus) != null }
                ?.config?.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true
        }
        assertTrue("Focus action: $label", requested)
        await("Focused: $label") { focused(label) }
    }
    private fun key(code: Int) {
        assertTrue("Only send input to the fixture or its popup", host?.hasWindowFocus() == true ||
            ins.uiAutomation.rootInActiveWindow?.packageName?.toString() == ins.targetContext.packageName)
        ins.sendKeyDownUpSync(code); ins.waitForIdleSync()
    }
    private fun capture(name: String) {
        val bitmap = requireNotNull(ins.uiAutomation.takeScreenshot())
        try {
            java.io.File(ins.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
    private fun runScenario(grid: Boolean) {
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        val host = ins.startActivitySync(Intent(ins.targetContext, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
        this.host = host
        val allHistory = CopyOnWriteArrayList<String>()
        val observer = object : Choreographer.FrameCallback {
            var previous: String? = null
            override fun doFrame(frameTimeNanos: Long) {
                val label = focusOnMain().firstOrNull()
                if (label != null && label != previous) {
                    history += label
                    allHistory += "$frameTimeNanos $label"
                }
                previous = label
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        val sections = (0..17).map { row -> CricketVideoSection(if (row == 0) null else row, "Category $row", (0..7).map { column ->
            CricketHighlight("$row:$column", "Clip $row:$column" + if (column == 7) " — A long international championship highlight title" else "", "12:34")
        }, hasMore = row > 0) }
        val videos = sections.flatMap { it.videos }
        val version = "Version: 6.16.04"
        val region = "Region: Canada"
        val portalFocus = PortalFocusReturn()
        val portals = listOf(
            DirectPortalAction("Fixture Sports portal", Icons.Default.SportsCricket, selected = true, id = "sports", open = {}),
            DirectPortalAction("Fixture Playfy portal", Icons.Default.LiveTv, id = "playfy", open = {}))
        val uiState = mutableStateOf(CricketHighlightsState(configured = true, appVersion = "6.16.04",
            videos = videos, sections = sections, collectionId = if (grid) 0 else null))
        try {
            await("Fixture owns the window") { host.hasWindowFocus() }
            ins.runOnMainSync { Choreographer.getInstance().postFrameCallback(observer) }
            ins.runOnMainSync { host.setContent { CompositionLocalProvider(LocalDirectPortalActions provides portals,
                LocalPortalFocusReturn provides portalFocus) { MaterialTheme(colorScheme = NikColors) {
                CricketHighlightsContent(uiState.value, {}, {}, {}, {},
                    selectAppVersion = { uiState.value = uiState.value.copy(appVersion = it) },
                    selectRegion = { uiState.value = uiState.value.copy(region = it) })
            } } } }
            await("Highlights entry is ready") { focused("Highlights") }
            history.clear()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters Version") { focused(version) }
            await("Dropdown transition emits focus evidence") { history.any { it.contains(version) } }
            assertFalse("No transient header destination: $history", history.any { it == "Back" || it == "Sports" || it == "Live Events" })
            capture("highlights-${if (grid) "grid" else "rows"}-version-focus")
            repeat(2) {
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Each menu opening focuses the selected version") { popupFocused("6.16.04") }
                key(KeyEvent.KEYCODE_BACK)
                await("Version popup returns to its trigger") { focused(version) }
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Selected version is ready") { popupFocused("6.16.04") }
            key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Changing version keeps its trigger focused") { focused("Version: 16.11.01") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("New selected version owns popup focus") { popupFocused("16.11.01") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Restoring version retains focus") { focused(version) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Region is adjacent") { focused(region) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Region menu opens") { find("India") != null }
            // A stale portal handoff must not steal focus when the popup window closes.
            ins.runOnMainSync { portalFocus.clicked("sports") }
            history.clear()
            key(KeyEvent.KEYCODE_BACK)
            await("Back returns directly to Region") { focused(region) }
            assertFalse("Popup must not visit the header: $history", history.any { it in listOf("Back", "Highlights", "Sports", "Live Events", "Fixture Sports portal", "Fixture Playfy portal") })
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Region enters content") { focusLabels().any { it.startsWith("Clip ") } }
            repeat(7) {
                val before = focusLabels()
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down advances within content") { focusLabels().let { it != before && it.any { label -> label.startsWith("Clip ") } } }
            }
            await("First tile is virtualized") { !composed(videos.first().title) }
            // Reproduce arriving at the fixed header while the lazy viewport remains scrolled.
            focus("Highlights")
            assertFalse("Header focus must preserve the scrolled viewport", composed(videos.first().title))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Scrolled header still reaches Version") { focused(version) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Entry works with item zero unmounted") { focusLabels().any { it.startsWith("Clip ") } }
            await("Focused tile and its border fit the viewport") { focusedTileFitsViewport() }
            capture("highlights-${if (grid) "grid" else "rows"}-scrolled-reentry")
            history.clear()
            repeat(32) { key(KeyEvent.KEYCODE_DPAD_UP); Thread.sleep(45) }
            ins.waitForIdleSync()
            await("Fast Up returns through filters to Highlights") { focused("Highlights") }
            assertFalse("Fast scroll must not visit unrelated controls: $history", history.any { it in listOf("Back", "Live Events", "Sports") })
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Fast Up cannot trap focus in the header") { focused(version) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Content remains reachable after fast Up") { focusLabels().any { it.startsWith("Clip ") } }
        } finally {
            ins.runOnMainSync { Choreographer.getInstance().removeFrameCallback(observer); host.finish() }
            java.io.File(ins.targetContext.getExternalFilesDir(null), "highlights-${if (grid) "grid" else "rows"}-focus.txt")
                .writeText(allHistory.joinToString("\n"))
            this.host = null
        }
    }
    @Test fun rowsReachFiltersAndReenterAfterFastScroll() = runScenario(grid = false)
    @Test fun collectionGridReentersWithFirstTileOffscreen() = runScenario(grid = true)
}
