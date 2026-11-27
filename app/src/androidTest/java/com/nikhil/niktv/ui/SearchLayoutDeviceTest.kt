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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

    @Test fun inlineTabletSearchKeepsHeaderGeometryAndReturnsFocusWithoutFallback() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = host
        val profile = PortalProfile("Inline search fixture", "https://fixture.invalid", "")
        val app = mutableStateOf(NikTvState(savedProfile = profile, restoring = false, discoverySurface = DashboardSurface.HOME))
        val shared = SharedDiscoveryHeader()
        val homeFocus = FocusRequester()
        val queryFocus = AtomicReference<FocusRequester?>()
        val artworkBounds = AtomicReference<androidx.compose.ui.geometry.Rect?>()
        val focusEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
        val movie = MediaItem("movie", "Friends movie fixture", null, null, searchResultType = SearchContentType.MOVIES)
        val series = MediaItem("series", "Friends series fixture", null, null, searchResultType = SearchContentType.SERIES)
        fun results(type: SearchContentType) = listOf(movie, series).filter { type == SearchContentType.ALL || it.searchResultType == type }
        fun control(name: String) = find(root()) { it.contentDescription?.toString() == name }
        fun queryNode() = find(root()) { it.isEditable || it.className?.toString()?.endsWith("EditText") == true }
        try {
            instrumentation.setInTouchMode(true)
            instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
                if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
                    focusEvents += (event.contentDescription?.toString() ?: event.text.joinToString())
            }
            instrumentation.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                CompositionLocalProvider(LocalSharedDiscoveryHeader provides shared) {
                    val tab = homeFocus; val body = remember { FocusRequester() }
                    if (app.value.searchOpen) DiscoverySearchScreen(app.value,
                        back = { app.value = app.value.closeSearchNavigation() }, select = {}, navigate = {},
                        underlay = { Box(Modifier.fillMaxSize().safeDrawingPadding()
                            .onGloballyPositioned { artworkBounds.set(it.boundsInWindow()) }) }) {
                        DiscoverySearchContent(app.value, close = { app.value = app.value.closeSearchNavigation() },
                            setType = { app.value = app.value.copy(searchType = it, searchResults = results(it)) }, setCategory = {},
                            setQuery = { app.value = app.value.copy(searchQuery = it, searchResults = results(app.value.searchType)) }, search = {},
                            useRecent = {}, deleteRecent = {}, openResult = {}, loadMore = {}, toggleFavorite = {},
                            headerFocusRequester = LocalDiscoverySearchHeaderFocus.current,
                            queryFocusRequester = LocalDiscoverySearchQueryFocus.current.also { queryFocus.set(it) })
                    } else Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { DiscoveryTopNavigation(DashboardSurface.HOME, profile.displayName, tab, body, {}, {},
                        navigate = { if (it == "Search") app.value = app.value.copy(searchOpen = true) },
                        headerFocus = {}, menu = false, changeMenu = {}, customize = null, shareAcrossTabs = true) }
                }
            } } }
            await("IPTV menu is ready") { control("Search") != null }
            val menuTop = bounds(clickable(control("Search")!!)).top
            val menuRight = bounds(clickable(control("Options")!!)).right
            await("Compact menu width is measured") { shared.menuWidth != null }
            val menuWidth = shared.menuWidth
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { homeFocus.requestFocus() }
            await("Header entry lands on Home") { shared.focusedTab == "Home" }
            for (tab in listOf("Movies", "Series", "Live TV", "My List", "Options")) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right reaches $tab") { focused(tab) }
            }
            for (tab in listOf("My List", "Live TV", "Series", "Movies", "Home", "Search", "Back")) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                await("Left reaches $tab") { focused(tab) }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right from Back reaches Search") { focused("Search") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right from Search reaches Home") { focused("Home") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left from Home reaches Search") { shared.focusedTab == "Search" }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Search opens its editor and keyboard together") {
                queryNode()?.isFocused == true && host.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            }
            assertTrue("Editor stays in the menu row: $menuTop / ${bounds(queryNode()!!).top}", kotlin.math.abs(menuTop - bounds(queryNode()!!).top) <= 12)
            assertEquals("Opening search preserves the original menu width", menuWidth, shared.menuWidth)
            assertTrue("Search must not grow beyond the original menu", bounds(clickable(control("Close search")!!)).right <= menuRight + 2)
            await("Artwork underlay is measured") { artworkBounds.get() != null }
            val artworkWithKeyboard = artworkBounds.get()
            val input = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "friends") }
            assertTrue(queryNode()!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
            await("Attached filters and results appear") { typeNode("Series") != null && label(series.title) != null && label(movie.title) != null }
            assertTrue("Filters are below the menu query", bounds(typeNode("Series")!!).top > bounds(queryNode()!!).bottom)
            assertTrue("Filters attach directly below the query", bounds(typeNode("Series")!!).top - bounds(queryNode()!!).bottom <
                (24 * host.resources.displayMetrics.density).toInt())
            assertTrue(clickable(typeNode("Series")!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Filter preserves query and scopes results") { app.value.searchQuery == "friends" && label(movie.title) == null && label(series.title) != null }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("First Back dismisses IME while keeping search open") { app.value.searchOpen && host.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false }
            instrumentation.waitForIdleSync()
            assertEquals("Keyboard must not resize the artwork underlay", artworkWithKeyboard, artworkBounds.get())
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { queryFocus.get()!!.requestFocus() }
            await("Query accepts remote focus without opening IME") { queryNode()?.isFocused == true }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down reaches selected filter") { focused("Series") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Movies") { focused("Movies") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK applies Movies without losing focus") { app.value.searchType == SearchContentType.MOVIES && focused("Movies") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Up returns to query without editing") { queryNode()?.isFocused == true }
            assertFalse(host.window.decorView.rootWindowInsets!!.isVisible(android.view.WindowInsets.Type.ime()))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Clear") { control("Clear search")?.let { clickable(it).isFocused } == true }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Clear retains surviving query focus") { app.value.searchQuery.isEmpty() && control("Clear search") == null && queryNode()?.isFocused == true }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches submit") {
                find(root()) { it.contentDescription?.toString() == "Search" && bounds(it).left > bounds(queryNode()!!).right && clickable(it).isFocused } != null
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Close remains reachable to the right of Search") { focused("Close search") }
            instrumentation.runOnMainSync { app.value = app.value.copy(searchQuery = "avengers") }
            await("Populated search retains Close focus") { focused("Close search") && control("Clear search") != null }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("X closes the populated search") { !app.value.searchOpen }
            await("Search origin receives focus directly") { control("Search")?.let { clickable(it).isFocused } == true }
            assertFalse("Closing must not focus Back", focusEvents.takeLast(3).any { it == "Back" })
            assertFalse("Closing must not focus Home", focusEvents.takeLast(3).any { it == "Home" })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Reopening starts with a blank editable search") { app.value.searchOpen && queryNode()?.isFocused == true && app.value.searchQuery.isEmpty() }
            screenshot(host, "iptv-inline-menu-search-closed")
        } finally {
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
            instrumentation.runOnMainSync { host.finish() }; currentHost = null
        }
    }

    @Test fun incomingResultsStayAtTopUntilRemoteBrowsingStarts() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val profile = PortalProfile("Search offset fixture", "https://search.invalid", "")
        val state = mutableStateOf(NikTvState(savedProfile = profile, restoring = false, searchOpen = true,
            session = PortalSession(profile, "fixture", "https://search.invalid", "", "", ""),
            searchQuery = "rrr", searchType = SearchContentType.ALL, searchServerLoading = true, searchUsedServer = true))
        fun rows(range: IntRange) = range.map { MediaItem("result-$it", "Offset result $it", null, null, searchResultType = SearchContentType.MOVIES) }
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = host
        val query = FocusRequester()
        instrumentation.setInTouchMode(false)
        try {
            instrumentation.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val config = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 960; screenHeightDp = 540; smallestScreenWidthDp = 600
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                }
                CompositionLocalProvider(LocalConfiguration provides config) {
                    Box(Modifier.width(960.dp).height(540.dp)) {
                        DiscoverySearchContent(state.value, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, queryFocusRequester = query)
                    }
                }
            } } }
            instrumentation.runOnMainSync { query.requestFocus() }
            instrumentation.runOnMainSync { state.value = state.value.copy(searchResults = rows(3..63)) }
            await("First batch appears") { label("Offset result 3") != null }
            val firstTop = bounds(clickable(label("Offset result 3")!!)).top
            instrumentation.runOnMainSync { state.value = state.value.copy(searchResults = rows(0..63), searchServerLoading = false) }
            await("Earlier matches appear fully at the top") {
                label("Offset result 0")?.let { bounds(clickable(it)).top == firstTop } == true
            }
            NavigationAuditEvidence.capture("search-incoming-batches-top")
            assertTrue(clickable(label("Offset result 0")!!).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            await("Results take focus") { focused("Offset result 0") }
            repeat(8) { index ->
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down browses result ${(index + 1) * 2}") { focused("Offset result ${(index + 1) * 2}") }
            }
            val browsed = bounds(clickable(label("Offset result 16")!!))
            instrumentation.runOnMainSync { state.value = state.value.copy(searchResults = rows(0..80)) }
            instrumentation.waitForIdleSync()
            assertTrue(focused("Offset result 16"))
            assertEquals("Appending must preserve the browsed viewport", browsed, bounds(clickable(label("Offset result 16")!!)))
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            currentHost = null
            instrumentation.setInTouchMode(true)
        }
    }

    @Test fun easyArtworkCardsKeepEqualBoundsForLongTitlesAndProgress() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = host
        val poster = File(host.cacheDir, "easy-artwork-fixture.png")
        android.graphics.Bitmap.createBitmap(60, 80, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.rgb(40, 110, 160))
            poster.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val titles = listOf("Short title", "A much longer provider title that needs two lines and should never make its card taller than its neighbours", "Missing artwork")
        try {
            for (compact in listOf(false, true)) {
                val focus = FocusRequester()
                instrumentation.setInTouchMode(false)
                instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                    val config = Configuration(LocalConfiguration.current).apply {
                        screenHeightDp = if (compact) 360 else 780
                        screenWidthDp = if (compact) 800 else 390
                    }
                    CompositionLocalProvider(LocalConfiguration provides config) {
                        Column(Modifier.width(if (compact) 800.dp else 390.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            titles.forEachIndexed { index, title ->
                                EasyBrowseTile(EasyBrowseEntry("card-$index", title, Icons.Default.PlayArrow,
                                    kindLabel = listOf("Series", "Movies", "Live TV")[index],
                                    progress = if (index == 1) .6f else null, status = if (index == 1) "4 mins left" else null,
                                    artwork = MediaItem("media-$index", title, if (index < 2) poster.toURI().toString() else null, null)),
                                    {}, if (index == 0) Modifier.focusRequester(focus) else Modifier)
                            }
                        }
                    }
                } } }
                await("All artwork cards appear") { titles.all { label(it) != null } }
                val cards = titles.map { bounds(clickable(label(it)!!)) }
                assertEquals("All titles, artwork fallbacks and progress use the same card height", 1, cards.map { it.height() }.distinct().size)
                assertEquals(1, cards.map { it.width() }.distinct().size)
                instrumentation.runOnMainSync { focus.requestFocus() }
                await("First media card accepts focus") { focused(titles[0]) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("D-pad reaches long-title card") { focused(titles[1]) }
                assertEquals("Focus must not resize a card", cards[1], bounds(clickable(label(titles[1])!!)))
                Thread.sleep(250) // Let the local fixture image finish before the visual reference.
                screenshot(host, "easy-artwork-cards-${if (compact) "landscape" else "portrait"}")
            }
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            poster.delete()
            currentHost = null
            instrumentation.setInTouchMode(true)
        }
    }

    @Test fun queryAndSubmitBoundsStayStableAcrossTypingAndLoading() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val profile = PortalProfile("Search fixture", "https://search.invalid", "")
        val initial = NikTvState(savedProfile = profile, restoring = false,
            session = PortalSession(profile, "fixture", "https://search.invalid", "", "", ""), searchOpen = true)
        val state = mutableStateOf(initial)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = host
        try {
            for (easy in listOf(true, false)) for (mode in 0..3) {
                val query = FocusRequester()
                instrumentation.setInTouchMode(false)
                instrumentation.runOnMainSync {
                    state.value = initial
                    host.setContent { MaterialTheme {
                        val width = when (mode) { 0 -> 390; 1 -> 800; 2 -> 960; else -> 800 }
                        val height = if (mode == 3) 360 else 540
                        val config = Configuration(LocalConfiguration.current).apply {
                            screenWidthDp = width; screenHeightDp = height
                            smallestScreenWidthDp = if (mode == 0 || mode == 3) 390 else 600
                            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                                if (mode == 2) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
                        }
                        CompositionLocalProvider(LocalConfiguration provides config) {
                            Box(Modifier.width(width.dp).height(height.dp)) {
                                DiscoverySearchContent(state.value, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                                    queryFocusRequester = query, easyHome = if (easy) ({}) else null)
                            }
                        }
                    } }
                }
                fun editor() = find(root()) { it.className == "android.widget.EditText" }
                fun action(loading: Boolean): AccessibilityNodeInfo? {
                    val title = if (loading) "Searching" else "Search"
                    return find(root()) { node ->
                        (node.text?.toString() == title || node.contentDescription?.toString() == title) &&
                            generateSequence(node) { it.parent }.any { it.isClickable }
                    }
                }
                await("Search controls appear in mode $mode, easy=$easy") { editor() != null && action(false) != null }
                instrumentation.runOnMainSync { query.requestFocus() }
                await("Query receives D-pad focus") { editor()?.isFocused == true }
                val fieldBounds = bounds(editor()!!)
                val submitBounds = bounds(clickable(action(false)!!))
                val stages = listOf(
                    initial.copy(searchQuery = "bigg"),
                    initial.copy(searchQuery = "bigg", searchLocalLoading = true),
                    initial.copy(searchQuery = "bigg", searchServerLoading = true,
                        searchActivityTitle = "Searching IPTV", searchActivityProgress = .5f),
                    initial.copy(searchQuery = "bigg", searchUsedServer = true), initial)
                for ((index, stage) in stages.withIndex()) {
                    instrumentation.runOnMainSync { state.value = stage }
                    val loading = stage.searchLocalLoading || stage.searchServerLoading
                    await("Loading label settles") { action(loading) != null && editor()?.text?.toString() == stage.searchQuery }
                    assertEquals("Query moved at stage $index, mode $mode, easy=$easy", fieldBounds, bounds(editor()!!))
                    assertEquals("Submit moved at stage $index, mode $mode, easy=$easy", submitBounds, bounds(clickable(action(loading)!!)))
                    assertTrue("Loading must preserve editor focus", editor()!!.isFocused)
                }
                // Empty Clear is a reserved layout slot, never a remote focus stop.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right skips the empty Clear slot") { focused("Search") }
                assertEquals(submitBounds, bounds(clickable(action(false)!!)))
                screenshot(host, "stable-search-${if (easy) "easy" else "standard"}-$mode")
            }
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            currentHost = null
            instrumentation.setInTouchMode(true)
        }
    }

    @Test fun spacedTypingKeepsCaretAtEndWhileSearchResultsUpdate() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val profile = PortalProfile("Search fixture", "https://search.invalid", "")
        val flow = kotlinx.coroutines.flow.MutableStateFlow(NikTvState(savedProfile = profile,
            session = PortalSession(profile, "fixture", "https://search.invalid", "", "", ""), searchOpen = true))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        currentHost = host
        try {
            instrumentation.setInTouchMode(true)
            instrumentation.runOnMainSync { host.setContent {
                val state by flow.collectAsState()
                MaterialTheme { DiscoverySearchContent(state, {}, {}, {}, { query ->
                    flow.value = flow.value.copy(searchQuery = query, searchResults = emptyList(), searchLocalLoading = true)
                }, {}, {}, {}, {}, {}, {}, easyHome = {}) }
            } }
            await("Search editor is present") { find(root()) { it.className == "android.widget.EditText" } != null }
            val field = find(root()) { it.className == "android.widget.EditText" }!!
            val rect = bounds(field)
            val now = SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                    rect.exactCenterX(), rect.exactCenterY(), 0)
                instrumentation.uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
            await("Touch opens the editor") { find(root()) { it.className == "android.widget.EditText" && it.isFocused } != null }
            var prefix = ""
            for (letter in "bigg") {
                instrumentation.sendStringSync(letter.toString())
                prefix += letter
                await("Typed prefix remains in order") { find(root()) { it.className == "android.widget.EditText" && it.text?.toString() == prefix } != null }
                instrumentation.runOnMainSync { flow.value = flow.value.copy(searchLocalLoading = false,
                    searchResults = listOf(MediaItem("result-$prefix", "Bigg Boss result", null, null, searchResultType = SearchContentType.SERIES))) }
                Thread.sleep(1100)
                val editor = find(root()) { it.className == "android.widget.EditText" }!!
                assertEquals(prefix, editor.text.toString())
                // Android can omit the selection range from accessibility (-1).
                // Each following insertion must still append to the same prefix.
                assertTrue(editor.isFocused)
            }
            screenshot(host, "search-spaced-typing-caret")
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            currentHost = null
            instrumentation.setInTouchMode(true)
        }
    }

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
                if (target == "Search provider again") {
                    await("Down reaches the category or provider control") { focused(target) || focused("All categories") }
                    // On a narrow phone the category control has a row of its own.
                    if (focused("All categories")) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                }
                await("Down reaches $target") { focused(target) }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Center opens the exact movie") { opened.get()?.id == movie.id }
            instrumentation.runOnMainSync {
                state.value = state.value.copy(searchServerLoading = true, searchHasMore = true,
                    searchActivityTitle = "Searching IPTV", searchActivityDetail = "Movies · Action · Page 2",
                    searchActivityProgress = .5f)
            }
            await("Standard Search reports where the provider is searching") {
                label("Movies · Action · Page 2") != null && find(root()) { it.contentDescription == "Searching" } != null
            }
            instrumentation.runOnMainSync { state.value = state.value.copy(searchServerLoading = false, searchHasMore = false) }
            await("A finished search displays its end state") { label("No more results") != null }
            assertNull(label("Show more results"))
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
        // Material's active-tab focus wrapper can surround the clickable child.
        if (current != null && find(current, ::matches) != null) return true
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
