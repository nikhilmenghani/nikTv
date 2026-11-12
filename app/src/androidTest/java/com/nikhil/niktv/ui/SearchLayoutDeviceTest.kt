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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
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

    @Test fun underlineTabsShowMixedResultsAndKeepSearchActionsSeparate() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = host
        val profile = PortalProfile("Search fixture", "https://search.invalid", "")
        val movie = MediaItem("movie-only", "Movie-only fixture", null, null, searchResultType = SearchContentType.MOVIES)
        val state = mutableStateOf(NikTvState(savedProfile = profile, restoring = false,
            session = PortalSession(profile, "fixture", "https://search.invalid", "", "", ""),
            searchOpen = true, searchQuery = "fixture", searchType = SearchContentType.ALL,
            searchUsedServer = true, searchResults = listOf(movie)))
        val query = AtomicReference<FocusRequester>()
        val opened = AtomicReference<MediaItem>()
        try {
            instrumentation.setInTouchMode(true)
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                DiscoverySearchScreen(state.value, {}, {}, {}) {
                    val queryFocus = LocalDiscoverySearchQueryFocus.current
                    SideEffect { query.set(queryFocus) }
                    DiscoverySearchContent(state.value, {},
                        setType = { type -> state.value = state.value.copy(searchType = type,
                            searchResults = if (type in listOf(SearchContentType.ALL, SearchContentType.MOVIES)) listOf(movie) else emptyList()) },
                        setCategory = {}, setQuery = { state.value = state.value.copy(searchQuery = it) },
                        search = {}, useRecent = {}, deleteRecent = {}, openResult = opened::set,
                        loadMore = {}, toggleFavorite = {},
                        headerFocusRequester = LocalDiscoverySearchHeaderFocus.current, queryFocusRequester = queryFocus)
                }
            } } }
            await("One filter row and mixed results appear") { typeNode("Movies") != null && label(movie.title) != null }
            assertNull("The duplicate boxed result tabs are removed", label("Movies · 1"))
            assertTrue(clickable(typeNode("Series")!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Series scopes the provider results") { label(movie.title) == null }
            assertTrue(clickable(typeNode("Movies")!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Movies remain selectable from the same row") { label(movie.title) != null }
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { query.get().requestFocus() }
            await("Query accepts D-pad focus") { focused("fixture") }
            for (target in listOf("Clear search", "Search")) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right reaches $target") { focused(target) }
            }
            val queryBounds = bounds(label("fixture")!!)
            val clearBounds = bounds(find(root()) { it.contentDescription?.toString() == "Clear search" }!!)
            assertTrue("Clear is outside the query field", clearBounds.left > queryBounds.right)
            for (target in listOf("Search type: Movies", "Search provider again", movie.title)) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down reaches $target") { focused(target) }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Center opens the exact movie") { opened.get()?.id == movie.id }
            screenshot(host, "underline-search-mixed-results")
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            currentHost = null
            instrumentation.setInTouchMode(true)
        }
    }

    @Test fun deletingRecentSearchKeepsFocusOnReplacementOrPreviousRow() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = host
        val originalRotation = host.display?.rotation ?: 0
        val automaticRotation = android.provider.Settings.System.getInt(context.contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) != 0
        val profile = PortalProfile("History fixture", "https://search.invalid", "")
        val history = (1..10).map { RecentSearch("Recent fixture $it", SearchContentType.ALL,
            searchedAtMillis = 100L - it, profileKey = profile.cacheKey()) }
        val state = mutableStateOf(NikTvState(savedProfile = profile, restoring = false,
            session = PortalSession(profile, "fixture", "https://search.invalid", "", "", ""),
            searchOpen = true, searchType = SearchContentType.ALL, recentSearches = history))
        val query = AtomicReference<FocusRequester>()
        val opened = AtomicInteger()
        try {
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                DiscoverySearchScreen(state.value, {}, {}, {}) {
                    val queryFocus = LocalDiscoverySearchQueryFocus.current
                    SideEffect { query.set(queryFocus) }
                    DiscoverySearchContent(state.value, {}, setType = {}, setCategory = {}, setQuery = {},
                        search = {}, useRecent = { opened.incrementAndGet() }, deleteRecent = { recent ->
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                state.value = state.value.copy(recentSearches = state.value.recentSearches
                                    .filterNot { it.key == recent.key })
                            }, 80L)
                        }, openResult = {}, loadMore = {}, toggleFavorite = {},
                        headerFocusRequester = LocalDiscoverySearchHeaderFocus.current,
                        queryFocusRequester = queryFocus)
                }
            } } }
            await("History is visible") { label("Recent fixture 1") != null }
            stableBounds()
            await("History query accepts focus") {
                instrumentation.runOnMainSync { query.get().requestFocus() }
                find(root()) { it.className == "android.widget.EditText" && it.isFocused } != null
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Arrow receives focus beside the query") { focused("Search") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Scope receives focus") { focused("Search type: All") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("History receives focus") { focused("Recent fixture 1") }
            for (index in 2..8) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down reaches recent $index") { focused("Recent fixture $index") }
            }
            fun removeAndExpect(removed: Int, replacement: Int?) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right reaches remove $removed") { focused("Remove Recent fixture $removed") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Deleting $removed retains the nearest available target") {
                    state.value.recentSearches.none { it.query == "Recent fixture $removed" } &&
                        if (replacement != null) focused("Recent fixture $replacement") &&
                            !focused("Remove Recent fixture $replacement")
                        else find(root()) { it.className == "android.widget.EditText" && it.isFocused } != null
                }
            }
            removeAndExpect(8, 9) // The next older row enters the eight-entry window.
            removeAndExpect(9, 10)
            removeAndExpect(10, 7) // Last entry: retain the preceding row.
            screenshot(host, "recent-search-delete-last-focused")
            for (index in 6 downTo 4) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await("Up reaches recent $index") { focused("Recent fixture $index") }
            }
            removeAndExpect(4, 5) // Middle entry: select its successor.
            for (index in 3 downTo 1) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await("Up reaches recent $index") { focused("Recent fixture $index") }
            }
            removeAndExpect(1, 2)
            for ((removed, replacement) in listOf(2 to 3, 3 to 5, 5 to 6, 6 to 7)) {
                removeAndExpect(removed, replacement)
            }
            removeAndExpect(7, null)
            assertTrue(state.value.recentSearches.isEmpty())
            assertEquals("Removing a recent entry must never execute its search", 0, opened.get())
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            currentHost = null
            instrumentation.setInTouchMode(true)
            instrumentation.uiAutomation.setRotation(if (automaticRotation) android.app.UiAutomation.ROTATION_UNFREEZE else originalRotation)
        }
    }

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
        val openedResult = AtomicReference<MediaItem>()
        try {
            instrumentation.setInTouchMode(true)
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                DiscoverySearchScreen(state.value, {}, {}, {}) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { body.set(it.boundsInWindow()) }) {
                        val queryFocus = LocalDiscoverySearchQueryFocus.current
                        SideEffect { query.set(queryFocus) }
                        DiscoverySearchContent(state.value, {},
                            setType = { type -> state.value = state.value.copy(searchType = type, searchResults = if (type == SearchContentType.ALL) entries else entries.filter { it.searchResultType == type }) }, setCategory = {},
                            setQuery = { state.value = state.value.copy(searchQuery = it) },
                            search = { searches.incrementAndGet() }, useRecent = {}, deleteRecent = {},
                            openResult = { openedResult.set(it) }, loadMore = {}, toggleFavorite = {},
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
                        body.get() != null && scrollable() != null && label("Searching provider…") != null
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
                    assertTrue(clickable(typeNode(type.title)!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    stableBounds()
                    val count = visibleRecords("${type.title} fixture ")
                    instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                        "SEARCH_RECORDS $name ${type.title} fullyVisible=$count\n") })
                    if (tablet) assertTrue("$name ${type.title} shows more records: $count",
                        count >= if (name == "landscape") 12 else 24)
                    screenshot(host, "compact-search-$name-${type.name.lowercase()}-records")
                }
                assertTrue(clickable(typeNode("Series")!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                stableBounds()
                val action = bounds(clickable(label("Search provider again")!!))
                val firstVisible = find(root()) { it.isVisibleToUser && it.text?.startsWith("Series fixture ") == true }
                    ?.text?.toString()
                assertNotNull("A result is visible before scrolling", firstVisible)
                assertTrue("Scroll action is supported", scrollable()!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                await("Results scroll without moving controls") {
                    val oldRow = label(firstVisible!!)
                    val viewport = bounds(scrollable()!!)
                    val scrolledAway = oldRow == null || !oldRow.isVisibleToUser ||
                        bounds(oldRow).bottom <= viewport.top || bounds(oldRow).top >= viewport.bottom
                    scrolledAway && bounds(clickable(label("Search provider again")!!)) == action
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
            for (target in listOf("Clear search", "Search")) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right moves to $target") { focused(target) }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down reaches the selected underline tab") { focused("Search type: Series") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Movies") { focused("Search type: Movies") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Live TV") { focused("Search type: Live TV") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches the category dropdown") { focused("All categories") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Provider action remains reachable") { focused("Search provider again") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Provider action activates exactly once") { searches.get() == 1 }
            assertEquals(1, searches.get())
            instrumentation.runOnMainSync { query.get().requestFocus() }
            for (target in listOf("Clear search", "Search")) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right returns to $target") { focused(target) }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Arrow Down enters the underline filters") { focused("Search type: Series") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Next Down reaches provider action") { focused("Search provider again") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters the first result") { focused(entries.first { it.id == "SERIES-1" }.title) }
            for ((key, index) in listOf(KeyEvent.KEYCODE_DPAD_RIGHT to 2, KeyEvent.KEYCODE_DPAD_DOWN to 4,
                KeyEvent.KEYCODE_DPAD_LEFT to 3, KeyEvent.KEYCODE_DPAD_UP to 1)) {
                instrumentation.sendKeyDownUpSync(key)
                await("Two-column navigation reaches record $index") { focused(entries.first { it.id == "SERIES-$index" }.title) }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("The focused record opens") { openedResult.get()?.id == "SERIES-1" }
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            currentHost = null
            instrumentation.setInTouchMode(true)
            instrumentation.uiAutomation.setRotation(if (automaticRotation) android.app.UiAutomation.ROTATION_UNFREEZE else originalRotation)
        }
    }

    @Test fun underlineFiltersNavigateOnPhoneAndTvLayouts() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = host
        val layout = mutableStateOf(0)
        val state = mutableStateOf(NikTvState(searchOpen = true, searchQuery = "fixture", searchType = SearchContentType.ALL,
            searchUsedServer = true, searchResults = listOf(MediaItem("series", "Series fixture", null, null,
                searchResultType = SearchContentType.SERIES))))
        val query = FocusRequester()
        val opened = AtomicReference<MediaItem>()
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = if (layout.value == 0) 390 else if (layout.value == 1) 960 else 800
                    screenHeightDp = if (layout.value == 0) 844 else if (layout.value == 1) 540 else 360
                    smallestScreenWidthDp = if (layout.value == 1) 540 else 390
                    orientation = if (layout.value == 0) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                        if (layout.value == 1) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    Box(Modifier.width(if (layout.value == 0) 390.dp else if (layout.value == 1) 960.dp else 800.dp).height(if (layout.value == 2) 360.dp else 540.dp)) {
                        DiscoverySearchContent(state.value, {}, {}, {}, {}, {}, {}, {}, opened::set, {}, {},
                            queryFocusRequester = query)
                    }
                }
            } } }
            for (mode in 0..2) {
                val isPhone = mode == 0
                instrumentation.runOnMainSync { layout.value = mode; opened.set(null) }
                await("Configured search appears") { typeNode("All") != null && label("Series fixture") != null }
                instrumentation.runOnMainSync { query.requestFocus() }
                await("Configured query focuses") { focused("fixture") }
                for (target in listOf("Clear search", "Search")) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                    await("Configured Right reaches $target") { focused(target) }
                }
                val path = listOf("Search type: All") + (if (isPhone) listOf("All categories") else emptyList()) +
                    listOf("Search provider again", "Series fixture")
                for (target in path) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                    await("Configured Down reaches $target") { focused(target) }
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Configured result opens") { opened.get()?.id == "series" }
                screenshot(host, if (mode == 0) "underline-search-phone" else if (mode == 1) "underline-search-tv" else "underline-search-phone-landscape")
            }
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            instrumentation.setInTouchMode(true)
            currentHost = null
        }
    }

    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun typeNode(title: String) = find(root()) { it.contentDescription?.toString() == "Search type: $title" }
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
        fun matches(node: AccessibilityNodeInfo) =
            node.text?.toString()?.lineSequence()?.any { it == text } == true ||
                node.contentDescription?.toString() == text
        val tree = root()
        val current = tree?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        current?.refresh()
        val currentOwner = current?.let { generateSequence(it) { it.parent }.firstOrNull { it.isClickable } ?: it }
        // The header and query submit action both have the accessible name Search.
        // Match the nearest action so a focused row does not also count as its X.
        return find(tree) { node ->
            if (!matches(node)) false else {
                val owner = generateSequence(node) { it.parent }.firstOrNull { it.isClickable } ?: node
                owner.refresh()
                owner.isFocused || owner == currentOwner
            }
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
            val focusDetails = mutableListOf<String>()
            find(root()) { node ->
                if (node.isFocused || node.text?.contains("Recent fixture") == true)
                    focusDetails += "text=${node.text} description=${node.contentDescription} focused=${node.isFocused} class=${node.className}"
                false
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                "SEARCH_FAILURE orientation=${host.resources.configuration.orientation} focus=${host.hasWindowFocus()} root=${root()?.packageName}\n${focusDetails.joinToString("\n")}\n") })
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
