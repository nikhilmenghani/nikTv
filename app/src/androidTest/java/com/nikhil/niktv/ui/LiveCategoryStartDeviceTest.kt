package com.nikhil.niktv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.CatalogVisitIds
import com.nikhil.niktv.discovery.components.ModernIptvCollection
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test

/** Real Standard collection; local data only, with no accounts or playback. */
class LiveCategoryStartDeviceTest {
    private val test get() = InstrumentationRegistry.getInstrumentation()

    private fun node(label: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            if (n.text?.toString()?.lineSequence()?.any { it == label } == true || n.contentDescription == label) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        return find(test.uiAutomation.rootInActiveWindow)
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(50)
        }
        fail(label)
    }

    private fun assertTopStaysVisible(label: String) {
        // Sample through async projection/layout, rather than accepting one
        // transient frame before stable-key anchoring has run.
        repeat(10) {
            assertNotNull("First channel moved out of view: $label", node(label))
            Thread.sleep(50)
        }
    }

    private fun tileBounds(label: String): Rect {
        var n = requireNotNull(node(label)) { "Missing $label" }
        while (!n.isClickable && n.parent != null) n = n.parent
        return Rect().also(n::getBoundsInScreen)
    }

    private fun focused(label: String): Boolean {
        var n = node(label)
        while (n != null) { if (n.isFocused) return true; n = n.parent }
        return false
    }

    @Test fun firstBatchPagingAndAnotherCategoryStartAtFirstChannel() = scenario(false, false)
    @Test fun landscapeGuideUpdatesKeepUniformRowsAndStartAtTop() = scenario(true, false)
    @Test fun tvRemoteGuideUpdatesKeepFirstRowAndFocus() = scenario(true, true)

    private fun scenario(landscape: Boolean, remote: Boolean) {
        check(test.targetContext.packageName.endsWith(".regression"))
        test.setInTouchMode(!remote)
        val host = test.startActivitySync(Intent(test.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        test.runOnMainSync { host.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("Requested orientation") { host.resources.configuration.orientation ==
            if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
        val profile = PortalProfile("Viewport fixture", "https://viewport.invalid", "fixture")
        val category = mutableStateOf(Category("news", "Fixture news", CatalogType.LIVE_TV))
        val visit = mutableLongStateOf(CatalogVisitIds.next())
        val visible = mutableStateOf(true)
        val state = mutableStateOf(NikTvState(savedProfile = profile, loading = true, restoring = false,
            modernIptvCategoryOpenId = visit.longValue))
        fun rows(prefix: String, count: Int) = (0 until count).map {
            MediaItem("$prefix-$it", "Fixture $prefix channel $it", null, null, portalCategoryId = category.value.id)
        }
        try {
            test.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val holder = rememberSaveableStateHolder()
                if (!visible.value) Text("Local playback placeholder")
                else holder.SaveableStateProvider("modern") { key(visit.longValue) {
                    ModernIptvCollection(state.value, category.value, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, isTv = remote)
                } }
            } } }
            await("Loading screen") { node("Loading Fixture news…") != null }
            test.runOnMainSync { state.value = state.value.copy(items = rows("news", 14), loading = false, catalogHasMore = true) }
            await("First channel remains visible when loading is replaced with channels and footer") {
                node("Fixture news channel 0") != null
            }
            assertTopStaysVisible("Fixture news channel 0")
            val before = tileBounds("Fixture news channel 0")
            val now = System.currentTimeMillis()
            val enriched = state.value.items.mapIndexed { index, media ->
                if (index == 1) media.copy(liveSchedule = listOf(LiveProgramme("A longer programme title that wraps on a small tile", now - 300_000, now + 600_000))) else media
            }
            test.runOnMainSync { state.value = state.value.copy(items = enriched) }
            await("Programme delivered") { node("A longer programme title that wraps on a small tile") != null }
            assertTopStaysVisible("Fixture news channel 0")
            assertEquals("EPG cannot move the first row", before, tileBounds("Fixture news channel 0"))
            assertEquals("Tiles with and without EPG have equal height", before.height(), tileBounds("Fixture news channel 1").height())
            NavigationAuditEvidence.capture("live-uniform-${if (remote) "tv" else if (landscape) "landscape" else "phone"}")
            if (remote) {
                await("First channel owns remote focus") { focused("Fixture news channel 0") }
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down reaches the next row") { focused("Fixture news channel 4") }
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await("Up returns to first row") { focused("Fixture news channel 0") }
                assertTopStaysVisible("Fixture news channel 0")
            } else if (!landscape) {
                assertTrue(node("Find channel or programme")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                await("Search opens its editor") { node("Close channel search") != null }
                await("Search opens keyboard in one action") { host.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Back collapses search") { node("Close channel search") == null }
                await("Search trigger retains focus") { focused("Find channel or programme") }
                NavigationAuditEvidence.capture("live-phone-search-closed-focus")
            }
            test.runOnMainSync { state.value = state.value.copy(items = rows("news", 28), catalogHasMore = false) }
            test.waitForIdleSync()
            assertTopStaysVisible("Fixture news channel 0")

            // Change category using the same saveable browser scope as production.
            test.runOnMainSync { visible.value = false }
            test.waitForIdleSync()
            test.runOnMainSync {
                category.value = category.value.copy(id = "movies", title = "Fixture movies")
                visit.longValue = CatalogVisitIds.next()
                state.value = state.value.copy(items = emptyList(), loading = true,
                    modernIptvCategoryOpenId = visit.longValue)
                visible.value = true
            }
            await("Next category loading") { node("Loading Fixture movies…") != null }
            test.runOnMainSync { state.value = state.value.copy(items = rows("movies", 28), loading = false, catalogHasMore = true) }
            await("Another category starts at its first channel") { node("Fixture movies channel 0") != null }
            assertTopStaysVisible("Fixture movies channel 0")
        } finally { test.runOnMainSync { host.finish() } }
    }
}
