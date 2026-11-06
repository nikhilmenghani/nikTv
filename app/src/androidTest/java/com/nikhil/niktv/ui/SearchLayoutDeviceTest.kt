package com.nikhil.niktv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.discovery.components.DiscoverySearchContent
import com.nikhil.niktv.model.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Layout/scroll fixture in the isolated app; no provider, personal history or playback. */
class SearchLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var currentHost: PlaybackFixtureHostActivity? = null

    @Test fun compactControlsPreserveResultsDuringLoadingScrollingAndRemoteNavigation() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = host
        val originalRotation = host.display?.rotation ?: 0
        val automaticRotation = android.provider.Settings.System.getInt(context.contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) != 0
        val profile = PortalProfile("Search fixture", "https://search.invalid", "")
        val entries = globalSearchTypes.flatMap { type -> (1..60).map { index ->
            MediaItem("${type.name}-$index", "${type.title} fixture $index" +
                if (index % 3 == 0) " · A longer provider title with several words for readability" else "",
                null, null, description = "A detailed synopsis should not make a search row taller.", searchResultType = type)
        } }
        val state = mutableStateOf(NikTvState(savedProfile = profile, restoring = false,
            session = PortalSession(profile, "fixture", "https://search.invalid", "", "", ""),
            searchOpen = true, searchQuery = "fixture", searchType = SearchContentType.ALL,
            searchResults = entries, searchUsedServer = true, searchServerLoading = true,
            searchActivityDetail = "Categories 1–3 of 77", searchActivityProgress = .25f))
        val body = AtomicReference<androidx.compose.ui.geometry.Rect>()
        val query = AtomicReference<FocusRequester>()
        val searches = AtomicInteger()
        try {
            instrumentation.setInTouchMode(true)
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                DiscoverySearchScreen(state.value, {}, {}, {}) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { body.set(it.boundsInWindow()) }) {
                        val queryFocus = LocalDiscoverySearchQueryFocus.current
                        SideEffect { query.set(queryFocus) }
                        DiscoverySearchContent(state.value, {},
                            setType = { state.value = state.value.copy(searchType = it) }, setCategory = {},
                            setQuery = { state.value = state.value.copy(searchQuery = it) },
                            search = { searches.incrementAndGet() }, useRecent = {}, deleteRecent = {},
                            openResult = {}, loadMore = {}, toggleFavorite = {},
                            headerFocusRequester = LocalDiscoverySearchHeaderFocus.current,
                            queryFocusRequester = queryFocus)
                    }
                }
            } } }
            for ((name, orientation, expected) in listOf(
                Triple("landscape", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE),
                Triple("portrait", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT))) {
                instrumentation.runOnMainSync {
                    host.requestedOrientation = orientation
                    state.value = state.value.copy(searchServerLoading = true)
                }
                // Android 16 tablets can ignore per-activity orientation requests. Rotate the
                // actual test display, restoring its original rotation policy in finally.
                instrumentation.uiAutomation.setRotation(if (expected == Configuration.ORIENTATION_PORTRAIT)
                    android.app.UiAutomation.ROTATION_FREEZE_0 else android.app.UiAutomation.ROTATION_FREEZE_90)
                await("Search is visible in $name") {
                    host.hasWindowFocus() && host.resources.configuration.orientation == expected &&
                        body.get() != null && scrollable() != null && find(root()) {
                            it.text?.contains("Categories 1–3 of 77") == true
                        } != null
                }
                stableBounds()
                val loading = bounds(scrollable()!!)
                val proportion = loading.height() / body.get().height
                val tablet = host.resources.configuration.smallestScreenWidthDp >= 600
                screenshot(host, "compact-search-$name-loading")
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                    "SEARCH_LAYOUT $name resultFraction=$proportion viewport=$loading body=${body.get()} windowHeight=${host.window.decorView.height}\n") })
                if (tablet) assertTrue("$name results occupy at least 68% of Search: $proportion", proportion >= .68f)
                assertTrue("Results remain usable in $name", loading.height() > 200)

                instrumentation.runOnMainSync { state.value = state.value.copy(searchServerLoading = false) }
                await("Provider action settles") { label("Search provider again") != null }
                stableBounds()
                assertEquals("Loading must not push results down", loading.top, bounds(scrollable()!!).top)
                for (type in globalSearchTypes) {
                    assertTrue(clickable(label("${type.title} · 60")!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    stableBounds()
                    val count = visibleRecords("${type.title} fixture ")
                    instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                        "SEARCH_RECORDS $name ${type.title} fullyVisible=$count\n") })
                    if (tablet) assertTrue("$name ${type.title} shows more records: $count",
                        count >= if (name == "landscape") 7 else 12)
                    screenshot(host, "compact-search-$name-${type.name.lowercase()}-records")
                }
                assertTrue(clickable(label("Series · 60")!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                stableBounds()
                val action = bounds(label("Search provider again")!!)
                val firstVisible = find(root()) { it.isVisibleToUser && it.text?.startsWith("Series fixture ") == true }
                    ?.text?.toString()
                assertNotNull("A result is visible before scrolling", firstVisible)
                assertTrue("Scroll action is supported", scrollable()!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                await("Results scroll without moving controls") {
                    label(firstVisible!!) == null && bounds(label("Search provider again")!!) == action
                }
                screenshot(host, "compact-search-$name-scrolled")
                println("SEARCH_LAYOUT $name resultFraction=$proportion viewport=$loading body=${body.get()}")
            }
            instrumentation.runOnMainSync { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            await("Landscape navigation is ready") { host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            stableBounds()
            instrumentation.setInTouchMode(false)
            instrumentation.waitForIdleSync()
            await("Query accepts keyboard focus") {
                instrumentation.runOnMainSync { query.get().requestFocus() }
                focused("fixture")
            }
            for (target in listOf("Clear search", "Search", "Search provider again")) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right moves to $target") { focused(target) }
            }
            await("Provider action is reachable beside the query") { focused("Search provider again") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Provider action activates exactly once") { searches.get() == 1 }
            assertEquals(1, searches.get())
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            currentHost = null
            instrumentation.setInTouchMode(true)
            instrumentation.uiAutomation.setRotation(if (automaticRotation) android.app.UiAutomation.ROTATION_UNFREEZE else originalRotation)
        }
    }

    private fun root() = instrumentation.uiAutomation.rootInActiveWindow
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun label(text: String) = find(root()) { it.text?.toString() == text }
    private fun scrollable(): AccessibilityNodeInfo? {
        // The narrow navigation header also has a horizontal scroll container.
        // Measure and scroll the largest viewport, which is the results grid.
        var largest: AccessibilityNodeInfo? = null
        find(root()) { node ->
            if (node.isScrollable && bounds(node).height() > (largest?.let(::bounds)?.height() ?: 0)) largest = node
            false
        }
        return largest
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun clickable(node: AccessibilityNodeInfo) =
        generateSequence(node) { it.parent }.first { it.isClickable }
    private fun visibleRecords(prefix: String): Int {
        val viewport = bounds(scrollable()!!)
        val minimumHeight = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        val records = mutableSetOf<String>()
        find(scrollable()) { node ->
            val title = node.text?.toString()
            if (title?.startsWith(prefix) == true && node.isVisibleToUser) {
                val row = bounds(clickable(node))
                if (viewport.contains(row) && row.height() >= minimumHeight) records.add(title)
            }
            false
        }
        return records.size
    }
    private fun focused(text: String): Boolean {
        // The header and query submit action both have the accessible name Search.
        return find(root()) { node ->
            (node.text?.toString() == text || node.contentDescription?.toString() == text) &&
                generateSequence(node) { it.parent }.take(8).any { it.isFocused }
        } != null
    }
    private fun stableBounds() {
        var last: Rect? = null
        var stable = 0
        await("Results viewport settles") {
            val current = scrollable()?.let(::bounds)
            stable = if (current != null && current == last) stable + 1 else 0
            last = current
            stable >= 5
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(60) }
        currentHost?.let { host ->
            screenshot(host, "compact-search-failure")
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                "SEARCH_FAILURE orientation=${host.resources.configuration.orientation} focus=${host.hasWindowFocus()} root=${root()?.packageName}\n") })
        }
        fail(message)
    }
    private fun screenshot(host: PlaybackFixtureHostActivity, name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot()
        try { File(host.externalCacheDir, "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { image.recycle() }
    }
}
