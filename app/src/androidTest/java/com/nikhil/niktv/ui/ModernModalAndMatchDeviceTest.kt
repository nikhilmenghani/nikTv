package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.discovery.DiscoveryMatchScreen
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic UI only; never connects a profile or plays media. */
class ModernModalAndMatchDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(30)
        assertTrue(message, condition())
    }
    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
    private fun find(text: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun button(text: String): AccessibilityNodeInfo? {
        var node = find(text)
        repeat(8) { if (node?.isClickable == true) return node; node = node?.parent }
        return null
    }
    private fun focused(text: String): Boolean {
        var node = find(text)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun fixture(content: @Composable () -> Unit, test: () -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            instrumentation.runOnMainSync { activity.setContent {
                val config = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                }
                CompositionLocalProvider(LocalConfiguration provides config) { MaterialTheme { content() } }
            } }
            test()
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun dialogInitialFocusNeverVisitsCancelBeforeExitAndDoesNotStealItBack() {
        val events = CopyOnWriteArrayList<String>()
        fixture({
            val exit = remember { FocusRequester() }
            AlertDialog(modifier = Modifier.initialFocusEntry(exit), onDismissRequest = {},
                title = { Text("Exit fixture?") }, confirmButton = {
                    Row {
                        NikTvSecondaryActionButton({}, Modifier.onFocusChanged { if (it.isFocused) events.add("Cancel") }) { Text("Cancel fixture") }
                        NikTvPrimaryActionButton({}, Modifier.focusRequester(exit).onFocusChanged { if (it.isFocused) events.add("Exit") }) { Text("Exit fixture") }
                    }
                })
        }) {
            await("Exit owns initial focus") { events.isNotEmpty() && focused("Exit fixture") }
            assertEquals("No Cancel flash before Exit", "Exit", events.first())
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("User can move to Cancel") { focused("Cancel fixture") }
            Thread.sleep(200)
            assertTrue("Fallback must not steal Cancel focus", focused("Cancel fixture"))
        }
    }
    @Test fun profilePopupStartsOnCurrentSecondProfileAndKeepsUserMovement() {
        val chosen = AtomicInteger(0)
        val profiles = listOf(PortalProfile("Fixture Wio", "https://one.invalid", ""), PortalProfile("Fixture Nikhil", "https://two.invalid", ""))
        fixture({ ModernProfileSwitchDialog(profiles, profiles[1], {}, { chosen.incrementAndGet() }) }) {
            await("Current profile owns initial focus") { focused("Fixture Nikhil") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up reaches the other profile") { focused("Fixture Wio") }
            Thread.sleep(200)
            assertTrue(focused("Fixture Wio"))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down returns to current profile") { focused("Fixture Nikhil") }
            assertEquals(0, chosen.get())
        }
    }
    @Test fun categoryHeaderFocusStaysInsideItsOriginalButtonBounds() {
        fixture({
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                item { CategoryBrowseControls(CategoryBrowseMode.COLLECTIONS, "", {}, {}, {}) }
            }
        }) {
            await("Browse control is composed") { button("Browse by") != null }
            val before = bounds(checkNotNull(button("Browse by")))
            assertTrue(checkNotNull(button("Browse by")).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            await("Browse control can receive focus") { focused("Browse by") }
            Thread.sleep(200)
            assertEquals("Focused border must not scale beyond the clipped viewport", before, bounds(checkNotNull(button("Browse by"))))
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.cacheDir, "modern-header-focus.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }
    @Test fun categoryRefreshIsDirectAndSelectedCountsAreVisible() {
        val calls = AtomicInteger(0)
        fixture({ CategoryBrowseControls(CategoryBrowseMode.PROVIDER, "", {}, {}, {},
            refresh = { calls.incrementAndGet() }, organized = false, visibleCount = 14, totalCount = 113) }) {
            await("Selected-versus-available count is visible") { find("14 of 113 categories") != null }
            assertTrue(checkNotNull(button("Refresh categories")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Refresh callback is direct") { calls.get() == 1 }
            assertNull("Wio does not gain Xtream-only grouping controls", find("Browse by"))
        }
    }
    @Test fun liveMenuOpensCategorySelectionDirectly() {
        val calls = CopyOnWriteArrayList<CatalogType>()
        fixture({
            var expanded by remember { mutableStateOf(true) }
            val options = remember { com.nikhil.niktv.discovery.DiscoveryLibraryOptions(true) }
            CompositionLocalProvider(
                com.nikhil.niktv.discovery.LocalDiscoveryLibraryOptions provides options,
                com.nikhil.niktv.discovery.LocalConfigureProviderDiscovery provides { type -> calls.add(type); Unit }) {
                com.nikhil.niktv.discovery.DiscoveryBrowseMenu(expanded, { expanded = it }, DashboardSurface.LIVE_TV, {}, null)
            }
        }) {
            await("IPTV categories is present") { button("IPTV categories") != null }
            assertTrue(checkNotNull(button("IPTV categories")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("First menu click reaches the category manager") { calls.isNotEmpty() }
            assertEquals(listOf(CatalogType.LIVE_TV), calls.toList())
        }
    }
    @Test fun compactModernMatchesUseTwoColumnsAndDpadReturnsThroughTheHeader() {
        val picked = AtomicInteger(0)
        val movie = TmdbMovie(123, "Fixture movie", "Fixture movie", null, null, null, "2026-01-01", null)
        val items = (1..6).map { MediaItem("fixture-$it", "Fixture movie (2026) Language $it", null, null) }
        val app = NikTvState(savedProfile = PortalProfile("Fixture", "https://fixture.invalid", ""),
            movieMatchSelection = movie, movieMatchCandidates = items, discoverySurface = DashboardSurface.MOVIES)
        fixture({ DiscoveryMatchScreen(app, {}, {}, {}, { picked.incrementAndGet() }, {}) }) {
            await("First match receives initial focus") { focused(items[0].title) }
            val first = bounds(checkNotNull(button(items[0].title)))
            val second = bounds(checkNotNull(button(items[1].title)))
            assertTrue("Two columns on TV/tablet", second.left > first.right)
            assertTrue("Compact result is shorter than the old 128dp card", first.height() < 110 * instrumentation.targetContext.resources.displayMetrics.density)
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await("Right reaches second match") { focused(items[1].title) }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await("Down reaches fourth match") { focused(items[3].title) }
            key(KeyEvent.KEYCODE_DPAD_UP); await("Up returns to second match") { focused(items[1].title) }
            key(KeyEvent.KEYCODE_DPAD_UP); await("Up reaches modern Movies navigation") { focused("Movies") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await("Header Down returns to first match") { focused(items[0].title) }
            assertEquals("Movement never selects a stream", 0, picked.get())
            assertTrue(checkNotNull(button(items[0].title)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Selection uses the existing playback callback") { picked.get() == 1 }
        }
    }
}
