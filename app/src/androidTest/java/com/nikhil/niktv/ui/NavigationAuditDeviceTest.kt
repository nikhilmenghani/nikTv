package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import com.nikhil.niktv.discovery.components.DiscoverySearchContent
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.TmdbSeries
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Real retained discovery + real primary-route actions, plus secondary-screen remote sweeps. */
class NavigationAuditDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun host(): PlaybackFixtureHostActivity {
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        return instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
    }
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow?.takeIf { it.packageName == context.packageName }
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun await(message: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; Thread.sleep(50) }
        NavigationAuditEvidence.capture("failure-${message.take(50)}")
        fail(message)
    }
    private fun click(label: String) {
        await("Control appears: $label") { find(root(), label) != null }
        var node = find(root(), label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Activate $label", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); Thread.sleep(80) }
    private fun navigationOwner(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var result = node
        while (result != null && !result.isFocusable && !result.isClickable) result = result.parent
        return result
    }
    private fun inputFocused(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isFocused) return node
        for (i in 0 until node.childCount) inputFocused(node.getChild(i))?.let { return it }
        return null
    }
    private fun navigationLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
            navigationOwner(node)?.takeIf { it.isEnabled && it.isVisibleToUser }?.let { return it }
        }
        for (i in 0 until node.childCount) navigationLabel(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun sameFocusOwner(target: AccessibilityNodeInfo, focused: AccessibilityNodeInfo): Boolean {
        fun ancestorOf(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Boolean {
            var current: AccessibilityNodeInfo? = child
            repeat(12) { if (current == parent) return true; current = current?.parent }
            return false
        }
        // Some semantic labels are themselves marked focusable while input focus
        // belongs to their clickable Surface. They still describe one control.
        return ancestorOf(target, focused) || ancestorOf(focused, target)
    }
    private fun focusTrace(message: String) = instrumentation.sendStatus(2,
        android.os.Bundle().apply { putString("stream", "\n$message\n") })
    private fun focusLabel(label: String) {
        repeat(50) {
            val screen = root()
            val destination = navigationLabel(screen, label)
            // Compose can return an obsolete virtual node from findFocus(), even
            // with isFocused=false. Read the current tree's focused flag instead.
            val current = navigationOwner(inputFocused(screen))
            if (destination != null && current != null && sameFocusOwner(destination, current)) return
            if (current == null) { Thread.sleep(80); return@repeat }
            // Lazy content removes the header from accessibility while scrolled.
            // Return toward it instead of walking farther down an absent target.
            val direction = if (destination == null) KeyEvent.KEYCODE_DPAD_UP
            else {
                val target = Rect().also(destination::getBoundsInScreen)
                val from = Rect().also(current::getBoundsInScreen)
                val dy = target.centerY() - from.centerY(); val dx = target.centerX() - from.centerX()
                if (kotlin.math.abs(dy) > minOf(target.height(), from.height()) / 2)
                    if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                else if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            }
            if (it < 30) {
                fun describe(node: AccessibilityNodeInfo?): String {
                    node ?: return "absent"
                    val bounds = Rect().also(node::getBoundsInScreen)
                    val children = (0 until node.childCount).mapNotNull { child -> node.getChild(child)?.let { it.text ?: it.contentDescription } }
                    return "${node.text ?: node.contentDescription ?: children} bounds=$bounds focused=${node.isFocused} clickable=${node.isClickable}"
                }
                focusTrace("audit-focus target=$label step=$it key=$direction current=${describe(current)} destination=${describe(destination)}")
            }
            key(direction)
        }
        NavigationAuditEvidence.capture("failure-dpad-${label.take(40)}")
        fail("D-pad reaches $label")
    }
    private fun sweep(label: String, down: Int = 16): Int {
        val visited = mutableSetOf<String>()
        repeat(down) {
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            inputFocused(root())?.let { node ->
                val r = Rect().also(node::getBoundsInScreen)
                assertTrue("$label focus has visible bounds", r.width() > 0 && r.height() > 0)
                visited += "${node.text}|${node.contentDescription}|$r"
            }
        }
        NavigationAuditEvidence.capture("$label-down")
        repeat(down) { key(KeyEvent.KEYCODE_DPAD_UP) }
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_LEFT) }
        assertTrue("$label keeps a focused remote target", inputFocused(root()) != null)
        return visited.size
    }

    @Test fun selectedHeaderDestinationsDismissRetainedDetailsOnFirstActivation() {
        val activity = host()
        val profile = PortalProfile("Navigation audit", "https://navigation-audit.invalid", "")
        val movie = DiscoveryCard(8101, false, "Navigation movie", date = "2020-01-01", overview = "A fixed metadata fixture for navigation and layout.")
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) = listOf(movie)
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = DiscoveryDetails(card)
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = emptyList<DiscoveryEpisode>()
        }
        lateinit var app: NikTvViewModel
        lateinit var discovery: DiscoveryViewModel
        lateinit var state: MutableStateFlow<NikTvState>
        val landingReady = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        fun landingIsReady() = landingReady.get() == app.state.value.discoveryNavigationId
        val phone = usesDiscoveryPhoneLayout(context.resources.configuration.smallestScreenWidthDp,
            context.isTvLikeDevice(context.resources.configuration))
        try {
            main {
                app = NikTvViewModel(context.applicationContext as Application)
                app.viewModelScope.coroutineContext.cancelChildren()
                @Suppress("UNCHECKED_CAST")
                state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(app) as MutableStateFlow<NikTvState>
                state.value = NikTvState(savedProfile = profile, session = PortalSession(profile, "fixture", profile.portalUrl, "", "", ""),
                    restoring = false, discoverySurface = DashboardSurface.HOME)
                discovery = DiscoveryViewModel(context.applicationContext as Application, source)
                activity.setContent { MaterialTheme {
                    val current by app.state.collectAsState()
                    val surface = current.discoverySurface ?: DashboardSurface.HOME
                    val details by discovery.state.collectAsState()
                    val configuration = LocalConfiguration.current
                    val phoneLayout = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, context.isTvLikeDevice(configuration))
                    val navigate: (String) -> Unit = { label -> when (label) {
                        "Home" -> app.openHome()
                        "Movies" -> app.openDiscovery(DashboardSurface.MOVIES)
                        "Series" -> app.openDiscovery(DashboardSurface.SERIES)
                        "Search" -> app.openGlobalSearch()
                        "Live TV" -> app.openCatalogType(CatalogType.LIVE_TV)
                        "My List" -> app.openFavorites()
                    } }
                    fun closeMatches() { if (current.movieMatchSelection != null) app.closeTmdbMovieMatches() else app.closeTmdbSeriesMatches() }
                    BackHandler(current.movieMatchSelection != null || current.seriesMatchSelection != null || current.searchOpen) {
                        if (current.movieMatchSelection != null || current.seriesMatchSelection != null) closeMatches() else app.closeSearch()
                    }
                    Box(Modifier.fillMaxSize()) {
                        when {
                            current.movieMatchSelection != null || current.seriesMatchSelection != null ->
                                DiscoveryMatchScreen(current, ::closeMatches, app::openDiscovery, navigate, {}, {})
                            current.searchOpen -> DiscoverySearchScreen(current, app::closeSearch, app::openDiscovery, navigate) {
                                DiscoverySearchContent(current, app::closeSearch, {}, {}, {}, {}, {}, {}, {}, {}, {},
                                    headerFocusRequester = LocalDiscoverySearchHeaderFocus.current,
                                    queryFocusRequester = LocalDiscoverySearchQueryFocus.current,
                                    headerHasFocus = LocalDiscoverySearchHeaderFocused.current)
                            }
                            else -> CompositionLocalProvider(LocalDiscoveryNavigationTrace provides { event ->
                                if (event.startsWith("landing-ready nav=")) landingReady.set(event.substringAfter("nav=").toLong())
                                focusTrace("audit-route $event")
                            }) {
                                DiscoveryPreviewScreen(current, surface, app::openDiscovery, app::closeDiscovery,
                                    navigate, {}, { _, _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, discovery)
                            }
                        }
                        if (phoneLayout && (details.detail == null || current.searchOpen) &&
                            current.movieMatchSelection == null && current.seriesMatchSelection == null) {
                            val selected = if (current.searchOpen) null else when (surface) {
                                DashboardSurface.MOVIES -> DiscoveryPhoneDestination.MOVIES
                                DashboardSurface.SERIES -> DiscoveryPhoneDestination.SERIES
                                else -> DiscoveryPhoneDestination.HOME
                            }
                            DiscoveryPhoneBottomNavigation(selected, { destination -> navigate(destination.title) },
                                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
                        }
                    }
                } }
            }
            await("Landing is ready") { landingIsReady() && discovery.state.value.hero != null && find(root(), "Home") != null }
            NavigationAuditEvidence.capture("home-landing")
            for (surface in listOf(DashboardSurface.HOME, DashboardSurface.MOVIES, DashboardSurface.SERIES)) {
                main { app.openDiscovery(surface) }
                instrumentation.waitForIdleSync()
                await("Header changes to $surface") { landingIsReady() && discovery.state.value.surface == surface }
                main {
                    if (!phone) discovery.openDetails(movie.copy(id = 8103, title = "Previous nested movie"))
                    discovery.openDetails(movie)
                }
                await("Detail is visible on $surface") { discovery.state.value.details?.card?.id == movie.id }
                NavigationAuditEvidence.capture("${surface.name.lowercase()}-movie-detail")
                val title = surface.name.lowercase().replaceFirstChar { it.uppercase() }
                if (phone) {
                    // Production phone details expose Back; their landing tabs live below the feed.
                    focusLabel("Back"); key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("Phone detail Back restores its landing") { discovery.state.value.detail == null }
                }
                click(title)
                await("Selected $surface header replaces retained detail") { landingIsReady() && discovery.state.value.detail == null }
                focusLabel(title)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("D-pad reselect $surface remains on landing") { landingIsReady() && discovery.state.value.detail == null }
                sweep("${surface.name.lowercase()}-landing", 5)
            }
            if (!phone) {
                main { app.openHome() }; instrumentation.waitForIdleSync()
                await("Home landing consumes navigation before opening detail") { landingIsReady() }
                main { discovery.openRootDetails(movie) }
                await("Detail opens before Search overlay") { discovery.state.value.details?.card?.id == movie.id }
                focusLabel("Search"); key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Search overlays the retained detail") { app.state.value.searchOpen }
                key(KeyEvent.KEYCODE_BACK)
                await("Search Back restores the same movie detail") { !app.state.value.searchOpen && discovery.state.value.detail?.id == movie.id }
                NavigationAuditEvidence.capture("detail-search-back")
                focusLabel("Home"); key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Home explicitly leaves the restored detail") { discovery.state.value.detail == null }
            }
            for (series in listOf(false, true)) {
                main { state.value = state.value.copy(searchQuery = ""); app.openHome() }
                instrumentation.waitForIdleSync()
                await("Home landing consumes navigation before Search") { landingIsReady() }
                focusLabel("Search"); key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Search opens through real primary navigation") { app.state.value.searchOpen }
                val result = MediaItem("iptv-navigation-fixture", "Navigation IPTV title", null, null,
                    searchResultType = if (series) SearchContentType.SERIES else SearchContentType.MOVIES)
                main { state.value = state.value.copy(searchQuery = "Navigation", searchResults = listOf(result),
                    searchType = if (series) SearchContentType.SERIES else SearchContentType.MOVIES,
                    searchLocalLoading = false, searchServerLoading = false) }
                await("IPTV search results appear") { find(root(), result.title) != null }
                NavigationAuditEvidence.capture("search-${if (series) "series" else "movie"}-results")
                focusLabel("Home"); key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Home leaves IPTV search on first activation") { !app.state.value.searchOpen && discovery.state.value.detail == null }
                main { state.value = state.value.copy(searchOpen = true,
                    movieMatchSelection = if (series) null else TmdbMovie(8101, "Fixture movie", "Fixture movie", null, null, null, null, null),
                    movieMatchCandidates = if (series) emptyList() else listOf(result),
                    seriesMatchSelection = if (!series) null else TmdbSeries(8102, "Fixture series", "Fixture series", null, null, null, null, null),
                    seriesMatchCandidates = if (!series) emptyList() else listOf(result)) }
                await("IPTV version chooser appears") { find(root(), "Choose IPTV version") != null }
                NavigationAuditEvidence.capture("match-${if (series) "series" else "movie"}")
                if (phone) {
                    key(KeyEvent.KEYCODE_BACK)
                    await("Phone chooser Back returns to Search") {
                        app.state.value.movieMatchSelection == null && app.state.value.seriesMatchSelection == null && app.state.value.searchOpen
                    }
                }
                focusLabel("Home"); key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Home dismisses the version chooser and search together") {
                    !app.state.value.searchOpen && app.state.value.movieMatchSelection == null && app.state.value.seriesMatchSelection == null
                }
            }
        } finally { main { discovery.pause(); app.viewModelScope.coroutineContext.cancelChildren(); activity.finish() } }
    }

    @Test fun settingsDestinationsAndOfflineSupportRemoteTraversalAndReturn() {
        val activity = host()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val profile = PortalProfile("Navigation audit", "https://navigation-audit.invalid", "")
        val settingsVisible = mutableStateOf(true)
        val configuration = context.resources.configuration
        val compactSettings = !context.isTvLikeDevice(configuration) &&
            (configuration.screenWidthDp < 600 || configuration.smallestScreenWidthDp < 600)
        try {
            main { activity.setContent { MaterialTheme { if (!settingsVisible.value) {
                Button(onClick = { settingsVisible.value = true }) { Text("Open settings") }
            } else ModernSettingsScreen(
                state = NikTvState(savedProfile = profile, profiles = listOf(profile), restoring = false),
                closeSettings = { closed.incrementAndGet(); settingsVisible.value = false }, reauthenticate = {}, editProfile = {}, addProfile = {},
                exportBackup = {}, importBackup = {}, importBackupContent = {}, switchProfile = {}, removeProfile = {},
                setPreconfiguredProfileEnabled = { _, _ -> }, logout = {}, setCacheIntervalMinutes = {}, setInitialCatalogItems = {},
                setPlayerControlsTimeoutSeconds = {}, setKeepAwakeOnlyDuringPlayback = {}, setAutomaticReauthentication = {},
                setPlaybackEngine = {}, setSeriesStartSeason = {}, setBrowseLayout = {}, openCategoryManager = {}) } } }
            await("Settings destinations appear") { find(root(), "Playback") != null }
            NavigationAuditEvidence.capture("settings-index")
            for (destination in SettingsDestination.entries) {
                if (!settingsVisible.value) {
                    focusLabel("Open settings"); key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("Settings reopened") { find(root(), "Playback") != null }
                }
                focusLabel(destination.title)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                // The wide rail keeps focus until Right explicitly enters the page.
                if (navigationLabel(root(), destination.title)?.isSelected == true) key(KeyEvent.KEYCODE_DPAD_RIGHT)
                Thread.sleep(300)
                assertTrue("${destination.title} offers remote navigation", sweep("settings-${destination.name.lowercase()}", 10) > 0)
                key(KeyEvent.KEYCODE_BACK)
                await("Back leaves ${destination.title} or returns to its mobile category index") {
                    if (compactSettings) SettingsDestination.entries.all { navigationLabel(root(), it.title) != null }
                    else !settingsVisible.value
                }
            }
            val closesBeforeOffline = closed.get()
            main { activity.setContent { MaterialTheme {
                OfflineDownloadsScreen(NikTvState(savedProfile = profile, restoring = false), {}, {}, { _, _ -> }, { closed.incrementAndGet() })
            } } }
            await("Offline controls appear") { find(root(), "Back") != null }
            NavigationAuditEvidence.capture("offline-empty")
            sweep("offline", 5)
            focusLabel("Back"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Offline Back leaves the screen") { closed.get() > closesBeforeOffline }
        } finally { main { activity.finish() } }
    }
}
