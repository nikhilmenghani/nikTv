package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters

/** Isolated fixture. No credentials, provider requests, personal history or normal app data. */
// Run full screen entry before the warm visible-tile microbenchmark.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DiscoveryNavigationDeviceTest {
    @Test fun heroActionsExpandOnlyTheFocusedLabelWithoutMovingTheRowVertically() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fixtureHost = host
        val play = FocusRequester()
        val info = FocusRequester()
        val categories = FocusRequester()
        val actions = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                Column {
                    DiscoveryHero(DiscoveryCard(123, false, "Fixture hero", date = "2020-01-01"), null,
                        height = 280.dp, landscape = true, compact = false, detail = false, loading = false,
                        inList = false, playRequester = play, infoRequester = info, play = { actions.add("Play") }, add = {}, info = {},
                        onFocus = {}, onDown = {}, previous = {}, next = {}, carousel = true, index = 0, count = 10,
                        categoryActions = {
                            DiscoveryAction("IPTV categories", androidx.compose.material.icons.Icons.Default.Tune) {}
                            DiscoveryAction("TMDB categories", androidx.compose.material.icons.Icons.Default.AutoAwesome,
                                modifier = Modifier.focusRequester(categories)) { actions.add("TMDB categories") }
                        })
                    androidx.compose.material3.Text("Media rows begin here")
                }
            } } }
            await("Hero actions are attached") { host.hasWindowFocus() && text("TMDB categories") != null }
            fun rect(label: String) = Rect().also { text(label)!!.getBoundsInScreen(it) }
            fun settledWidth(label: String): Int {
                var previous = -1; var stable = 0
                await("$label expansion settles") {
                    val now = rect(label).width()
                    stable = if (now == previous) stable + 1 else 0
                    previous = now
                    stable >= 5
                }
                return previous
            }
            val collapsed = settledWidth("TMDB categories")
            val rowTop = rect("Media rows begin here").top
            instrumentation.runOnMainSync { categories.requestFocus() }
            await("Focused categories retain their D-pad identity") { focused("TMDB categories") }
            assertTrue("Focused label expands", settledWidth("TMDB categories") > collapsed + 30)
            assertEquals("Label expansion must not shift media rows", rowTop, rect("Media rows begin here").top)
            instrumentation.runOnMainSync { play.requestFocus() }
            await("Play can receive focus directly") { focused("Play") }
            assertEquals("Unfocused categories collapse again", collapsed, settledWidth("TMDB categories"))
            assertEquals(rowTop, rect("Media rows begin here").top)
            repeat(2) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }
            await("Right traverses collapsed controls to the trailer") { focused("Watch trailer") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left returns to My List without focus jumps") { focused("My List") }
            assertEquals(rowTop, rect("Media rows begin here").top)
            assertTrue("Focus and expansion alone must not play or open categories", actions.isEmpty())
        } finally { instrumentation.runOnMainSync { host.finish() }; fixtureHost = null }
    }
    @Test fun televisionLandingSkipsFocusedMetadataAndKeepsExplicitActionIdentity() {
        wakeDisplay()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fixtureHost = host
        val profile = PortalProfile("TV landing fixture", "https://tv-landing.invalid", "")
        val category = Category("tv-landing-category", "Fixture IPTV collection", CatalogType.MOVIES)
        val provider = MediaItem("tv-landing-provider", "Fixture IPTV title", null, "fixture-command", portalCategoryId = category.id)
        val providerLookups = java.util.concurrent.atomic.AtomicInteger()
        val detailIds = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val playedIds = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val favoriteIds = java.util.concurrent.CopyOnWriteArrayList<String>()
        val providerOpens = java.util.concurrent.CopyOnWriteArrayList<Triple<CatalogType, String, List<String>>>()
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) =
                if (section.series) listOf(DiscoveryCard(101, true, "Fixture television row", date = "2020-01-01"))
                else listOf(DiscoveryCard(1, false, "Fixture hero one", date = "2020-01-01"),
                    DiscoveryCard(2, false, "Fixture movie two", date = "2020-01-01"))
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails {
                detailIds += card.id
                return DiscoveryDetails(card.copy(overview = "Explicit detail ${card.id}"))
            }
            override suspend fun providerDetails(item: MediaItem, type: CatalogType, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails {
                providerLookups.incrementAndGet()
                return DiscoveryDetails(DiscoveryCard(999, false, "Focused provider metadata", date = "2020-01-01"))
            }
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = emptyList<DiscoveryEpisode>()
        }
        lateinit var vm: DiscoveryViewModel
        var vmReady = false
        fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
        fun upToHero() {
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("IPTV poster Up reaches its Search / All header") { focused("Search / All") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Provider row returns to series row") { focused("Fixture television row") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Series row returns to movie row") { focused("Fixture hero one") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Movie row returns to hero Play") { focused("Play") }
        }
        try {
            await("TV landing fixture window") { host.hasWindowFocus() }
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                vm = DiscoveryViewModel(context.applicationContext as Application, source)
                vmReady = true
                vm.bind(profile.cacheKey(), DashboardSurface.HOME, emptyList())
                // A retained rich-mode presentation must not leak into TV hero actions.
                vm.presentFocusedCard("provider:MOVIES:${category.id}:${provider.id}",
                    DiscoveryCard(999, false, "Obsolete focused presentation", date = "2020-01-01"))
                host.setContent {
                    val config = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply {
                        uiMode = (uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK.inv()) or
                            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                    }
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalConfiguration provides config,
                        LocalProviderDiscoveryPageLoader provides { _, _, page, _ ->
                            PortalCatalogPage(if (page == 1) listOf(provider) else emptyList(), page, false)
                        },
                        LocalOpenProviderDiscovery provides { type, item, queue ->
                            providerOpens += Triple(type, item.id, queue.map { it.id })
                        }
                    ) { MaterialTheme {
                        DiscoveryPreviewScreen(NikTvState(savedProfile = profile,
                            session = PortalSession(profile, "fixture", "https://tv-landing.invalid", "", "", ""),
                            categoryFilters = mapOf("${profile.cacheKey()}|MOVIES" to listOf(category.id)),
                            rawCategoriesByType = mapOf(CatalogType.MOVIES to listOf(category)),
                            tmdbSectionsBySurface = mapOf(DashboardSurface.HOME to emptyList())),
                            DashboardSurface.HOME, {}, {}, {}, { playedIds += it.id }, { _, _, _ -> }, {}, { _, _ -> },
                            { favoriteIds += it.media.id }, {}, {}, {}, { _, _ -> }, { _, _ -> }, vm)
                    } }
                }
            }
            await("TV entry stays in active header") { focused("Home") }
            await("TV hero feed is ready") { text("Fixture hero one") != null }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("TV hero Play") { focused("Play") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("TV movie rail") { focused("Fixture hero one") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("TV series rail") { focused("Fixture television row") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("TV IPTV rail") { focused(provider.title) }
            Thread.sleep(500) // Rich mode previously started a public provider lookup after 150 ms.
            assertEquals("TV focus performs no provider presentation lookups", 0, providerLookups.get())
            assertTrue("TV focus also leaves explicit detail loading idle", detailIds.isEmpty())
            assertNull(vm.state.value.focusedEntryKey)
            assertNull(vm.state.value.focusedPresentation)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Provider tile retains its explicit open callback") { providerOpens.singleOrNull()?.second == provider.id }
            assertEquals(CatalogType.MOVIES, providerOpens.single().first)
            assertEquals(listOf(provider.id), providerOpens.single().third)
            upToHero()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Hero Play keeps hero identity after provider focus") { playedIds.singleOrNull() == 1 }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Hero My List") { focused("My List") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Hero favorite keeps its own TMDB identity") { favoriteIds.singleOrNull() == "tmdb-movie:1" }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Hero Watch trailer is reachable after My List") { focused("Watch trailer") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Hero Info is reachable") { focused("Info") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Explicit hero Info loads real details for the hero") { vm.state.value.details?.card?.id == 1 && vm.state.value.detail?.id == 1 }
            assertEquals(listOf(1), detailIds.toList())
            key(KeyEvent.KEYCODE_BACK)
            await("Detail Back restores originating hero Info") { focused("Info") && vm.state.value.detail == null }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Returning to movie rail retains its item") { focused("Fixture hero one") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Second movie item") { focused("Fixture movie two") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Explicit tile open also loads its own detail identity") { vm.state.value.details?.card?.id == 2 && vm.state.value.detail?.id == 2 }
            key(KeyEvent.KEYCODE_BACK)
            await("Detail Back restores originating tile") { focused("Fixture movie two") && vm.state.value.detail == null }
            assertEquals(listOf(1, 2), detailIds.toList())
            assertEquals(0, providerLookups.get())
        } finally {
            instrumentation.runOnMainSync { if (vmReady) vm.pause(); host.finish() }
        }
    }
    @Test fun partiallyVisibleRowFocusIsImmediateAndRevealDoesNotRebound() {
        wakeDisplay()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fixtureHost = host
        val target = FocusRequester()
        val first = FocusRequester()
        val transferred = java.util.concurrent.atomic.AtomicBoolean(false)
        val whileScrolling = java.util.concurrent.atomic.AtomicBoolean(false)
        val elapsed = java.util.concurrent.atomic.AtomicLong(0)
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        lateinit var coordinator: DiscoveryScrollCoordinator
        try {
            instrumentation.runOnMainSync { host.setContent {
                list = androidx.compose.foundation.lazy.rememberLazyListState()
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                coordinator = androidx.compose.runtime.remember { DiscoveryScrollCoordinator(scope) }
                DiscoveryVerticalFocusViewport(coordinator) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.height(300.dp).fillMaxWidth(), state = list) {
                        items(4) { index ->
                            Box(Modifier.height(180.dp).fillMaxWidth().then(
                                if (index == 1) Modifier.focusRequester(target) else if (index == 0) Modifier.focusRequester(first) else Modifier
                            ).focusable())
                        }
                    }
                }
            } }
            await("Partially visible target laid out") { list.layoutInfo.visibleItemsInfo.any { it.index == 1 } }
            await("Row reveal fixture owns input") { host.hasWindowFocus() }
            instrumentation.runOnMainSync { first.requestFocus() }
            val started = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                coordinator.move(list, 1, false) {
                    target.requestFocus().also { accepted ->
                        if (accepted) {
                            elapsed.set(SystemClock.uptimeMillis() - started)
                            whileScrolling.set(coordinator.isMoving)
                            transferred.set(true)
                        }
                    }
                }
            }
            await("Focus transfers during row reveal") { transferred.get() }
            assertTrue("Focus must not wait for the alignment spring to settle", whileScrolling.get())
            assertTrue("Composed row focus must not wait for reveal", elapsed.get() < 100)
            var last = 0
            var moving = true
            val deadline = SystemClock.uptimeMillis() + 3_000
            val rowPixels = (180 * context.resources.displayMetrics.density).toInt()
            while (moving && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    val position = list.firstVisibleItemIndex * rowPixels + list.firstVisibleItemScrollOffset
                    assertTrue("Downward reveal must not rebound ($last -> $position)", position >= last - 1)
                    last = position
                    moving = coordinator.isMoving
                }
                Thread.sleep(16)
            }
            assertFalse("Vertical alignment finishes", moving)
            diagnostic("Discovery partially visible row focus: ${elapsed.get()}ms; no reveal rebound")
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var fixtureHost: PlaybackFixtureHostActivity? = null
    private fun diagnostic(message: String) {
        println(message)
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$message\n") })
    }
    private fun shellInput(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input $command")
        ).use { it.readBytes() }
    }
    private fun wakeDisplay() = shellInput("keyevent 224")
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < until) { if (predicate()) return; Thread.sleep(40) }
        if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != instrumentation.targetContext.packageName) {
            diagnostic("Discovery fixture lost foreground focus; other-app content is omitted.")
            fail(label)
        }
        val active = find { it.isFocused }
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add)
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(active)
        diagnostic("Discovery failure focus: ${labels.take(15)}; window=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}")
        listOf("Home", "Movies", "Series", "Search", "Info", "Play").forEach { title ->
            text(title)?.let { node ->
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                diagnostic("Discovery failure bounds: label=$title bounds=$bounds visible=${node.isVisibleToUser} focused=${focused(title)}")
            }
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.cacheDir, "discovery-navigation-failure.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            diagnostic("Discovery fixture failure screenshot: cache/discovery-navigation-failure.png")
        }
        labels.clear(); collect(instrumentation.uiAutomation.rootInActiveWindow)
        diagnostic("Discovery failure content: ${labels.take(35)}")
        fail(label)
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun text(label: String) = find { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun fullTabLabelVisible(label: String): Boolean {
        val node = text(label.removeSuffix(" 2")) ?: text(label) ?: return false
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val paint = android.graphics.Paint().apply {
            textSize = 12f * metrics.scaledDensity
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val searchBounds = Rect()
        text("Search")?.getBoundsInScreen(searchBounds) ?: return false
        val sharesSearchRow = bounds.top < searchBounds.bottom && bounds.bottom > searchBounds.top
        val actualLabel = node.text?.toString() ?: label
        // Phone Search can be right of the tabs; tablet/TV Search is left.
        // Both are valid provided the rendered controls do not overlap.
        val separateFromSearch = bounds.right <= searchBounds.left || bounds.left >= searchBounds.right
        return node.isVisibleToUser && bounds.left >= 0 && bounds.right <= metrics.widthPixels &&
            bounds.top >= 0 && bounds.bottom <= metrics.heightPixels &&
            (!sharesSearchRow || separateFromSearch) && bounds.width() >= paint.measureText(actualLabel) * .9f
    }
    private fun focused(label: String): Boolean = find { node ->
        if (node.text?.toString() != label && node.contentDescription?.toString() != label) false
        else {
            var current: AccessibilityNodeInfo? = node
            var yes = false
            repeat(5) { if (current?.isFocused == true) yes = true; current = current?.parent }
            yes
        }
    } != null
    private fun assertHorizontalNavigationKeepsRowStationary() {
        fun top(): Int? {
            val bounds = Rect()
            val node = text("Trending Movies Today") ?: return null
            node.getBoundsInScreen(bounds)
            return bounds.top.takeIf { node.isVisibleToUser }
        }
        var previous: Int? = null
        var stable = 0
        await("Movie row alignment settles") {
            val current = top()
            stable = if (current != null && current == previous) stable + 1 else 0
            previous = current
            stable >= 5
        }
        val anchor = checkNotNull(top())
        var selected = 2
        // Ranked rails contain ten titles. Cross both lazy-row edges within that range,
        // then reverse direction without changing vertical rows.
        (List(8) { 1 } + List(8) { -1 } + listOf(1, -1, 1, -1)).forEach { direction ->
            selected += direction
            instrumentation.sendKeyDownUpSync(if (direction > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            await("Horizontal focus reaches title $selected") { focused("Fixture title $selected") }
            val until = SystemClock.uptimeMillis() + 160
            do {
                assertEquals("Horizontal input must preserve the vertical row anchor", anchor, top())
                Thread.sleep(20)
            } while (SystemClock.uptimeMillis() < until)
        }
    }
    private fun holdCenter() {
        // Compose can restore a tile's focus while the old popup window is still
        // finishing its exit transition. Send the next press only to the host.
        await("Host window accepts the next long press") { fixtureHost?.hasWindowFocus() == true }
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
    }
    private fun chooseMenuOption(label: String) {
        fun focusedMenuLabel(): String? {
            fun labelOf(node: AccessibilityNodeInfo?): String? {
                if (node == null) return null
                (node.text?.toString()?.takeIf(String::isNotBlank)
                    ?: node.contentDescription?.toString()?.takeIf(String::isNotBlank))?.let { return it }
                for (index in 0 until node.childCount) labelOf(node.getChild(index))?.let { return it }
                return null
            }
            return labelOf(find { it.isFocused })
        }
        fun settledFocus(): String {
            var previous: String? = null
            var settled: String? = null
            var stable = 0
            await("Popup focus settles before the next input") {
                instrumentation.waitForIdleSync()
                val current = focusedMenuLabel()
                stable = if (current != null && current == previous) stable + 1 else 0
                previous = current
                if (stable >= 3) settled = current
                settled != null
            }
            return checkNotNull(settled)
        }
        // Dropdown focus/accessibility updates lag behind synchronous input injection.
        // Observe a settled result after every arrow, otherwise a queued Down can
        // move past the requested action between its cached focus check and Center.
        await("Popup window owns menu input") { fixtureHost?.hasWindowFocus() == false }
        for (key in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)) {
            for (step in 0 until 20) {
                val before = settledFocus()
                if (before == label && focused(label)) {
                    diagnostic("Discovery deliberate menu selection: requested=$label focused=$before")
                    instrumentation.waitForIdleSync()
                    assertTrue("Requested menu action remains focused before Center: $label", focused(label))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    instrumentation.waitForIdleSync()
                    return
                }
                instrumentation.sendKeyDownUpSync(key)
                instrumentation.waitForIdleSync()
                if (settledFocus() == before) break
            }
        }
        fail("Popup option can receive focus: $label")
    }
    private fun swipeHero() {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        // Tablet/TV navigation scrolls above the hero; it is not a reliable
        // gesture anchor after D-pad entry. Use two visible hero-owned elements.
        val heading = Rect(); val action = Rect()
        var previousHeading = Rect(); var previousAction = Rect(); var stable = 0
        await("Hero heading and actions settle before the swipe") {
            val rank = find { it.isVisibleToUser && it.text?.toString()?.contains("TRENDING TODAY") == true }
            val info = text("Info")
            rank?.getBoundsInScreen(heading); info?.getBoundsInScreen(action)
            val visible = rank != null && info?.isVisibleToUser == true && action.top > heading.bottom && heading.top >= 0
            stable = if (visible && heading == previousHeading && action == previousAction) stable + 1 else 0
            previousHeading = Rect(heading); previousAction = Rect(action)
            stable >= 3
        }
        val y = (heading.bottom + action.top) / 2
        diagnostic("Discovery hero swipe: heading=$heading actions=$action y=$y")
        shellInput("swipe ${(metrics.widthPixels * .75f).toInt()} $y ${(metrics.widthPixels * .25f).toInt()} $y 300")
    }
    private fun tap(label: String) {
        val bounds = Rect()
        var previous = Rect(); var stable = 0
        await("Touch target settles: $label") {
            val node = text(label)
            node?.getBoundsInScreen(bounds)
            stable = if (node?.isVisibleToUser == true && bounds == previous) stable + 1 else 0
            previous = Rect(bounds)
            stable >= 3
        }
        println("Discovery tap $label: $bounds")
        shellInput("tap ${bounds.centerX()} ${bounds.centerY()}")
    }
    @Test fun remoteRowNavigationAndReversals() = verifyRemoteNavigation(motionOnly = true)
    @Test fun remoteInfoBackAndTouchKeepActionsAndOriginSeparate() = verifyRemoteNavigation(motionOnly = false)
    private fun verifyRemoteNavigation(motionOnly: Boolean) {
        wakeDisplay()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fixtureHost = host
        await("Fixture window ready") { host.hasWindowFocus() }
        val profile = PortalProfile("Fixture", "https://example.invalid", "")
        var plays = 0; var favorites = 0; var exited = false
        val selectedSurface = androidx.compose.runtime.mutableStateOf(DashboardSurface.HOME)
        val navigationId = androidx.compose.runtime.mutableLongStateOf(0L)
        var classicDestination: String? = null
        var refreshes = 0; var related = 0
        val favoriteCards = androidx.compose.runtime.mutableStateOf(emptyList<FavoriteItem>())
        val matchCounts = androidx.compose.runtime.mutableStateOf(emptyMap<String, Int>())
        val source = object : DiscoveryDataSource {
            private fun card(i: Int, series: Boolean) = DiscoveryCard(i, series, "Fixture title $i", "Fixture synopsis $i", date = "2020-01-01")
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) = (1..20).map { card(it, section.series) }
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = DiscoveryDetails(card, cast = listOf(DiscoveryPerson("Fixture cast", "Role")), seasons = if (card.series) listOf(1, 2) else emptyList())
            override suspend fun related(card: DiscoveryCard) = (21..30).map { card(it, false) }
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = listOf(DiscoveryEpisode(season, 1, "Fixture episode", date = "2020-01-01"))
        }
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                val vm = DiscoveryViewModel(context.applicationContext as Application, source)
                host.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalRefreshDiscoveryMatches provides { card ->
                            refreshes++
                            matchCounts.value = matchCounts.value + (discoveryAvailabilityKey(profile.cacheKey(), card) to 3)
                        },
                        LocalShowRecommendations provides { _, _ -> related++ },
                        LocalRecommendationTypeFor provides { _, type -> type }
                    ) { MaterialTheme {
                        DiscoveryPreviewScreen(NikTvState(savedProfile = profile, favorites = favoriteCards.value,
                            discoveryMatchCounts = matchCounts.value, discoveryNavigationId = navigationId.longValue), selectedSurface.value,
                            { selectedSurface.value = it; navigationId.longValue++ }, { exited = true }, { classicDestination = it },
                            { plays++ }, { _, _, _ -> plays++ }, {}, { _, _ -> }, { favorite ->
                                favorites++
                                favoriteCards.value = if (favoriteCards.value.any { it.key == favorite.key })
                                    favoriteCards.value.filterNot { it.key == favorite.key } else favoriteCards.value + favorite
                            }, {}, {}, {}, { _, _ -> }, { _, _ -> }, vm)
                    } }
                }
            }
            await("Hero ready") { text("Fixture title 1") != null }
            await("Entry focuses active trial tab") { focused("Home") }
            if (usesDiscoveryPhoneLayout(context.resources.configuration.smallestScreenWidthDp,
                    context.isTvLikeDevice(context.resources.configuration))) {
                listOf("Home", "Movies", "Series", "Live TV").forEach { label ->
                    await("Phone destination fully visible: $label") { fullTabLabelVisible(label) }
                }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down reaches Play") { focused("Play") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches My List") { focused("My List") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Watch trailer") { focused("Watch trailer") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right reaches Info") { focused("Info") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Info opens full detail page") { text("My List") != null && text("Info") == null }
            assertEquals(0, plays); assertEquals(0, favorites)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back restores originating Info control") { focused("Info") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters the first ranked row") { focused("Fixture title 1") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right keeps focus in the movie row") { focused("Fixture title 2") }
            assertHorizontalNavigationKeepsRowStationary()
            // Reverse a pending vertical transition: only its final destination may focus.
            listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN).forEach {
                instrumentation.sendKeyDownUpSync(it)
                Thread.sleep(25)
            }
            await("Down enters the series row after rapid reversals") { focused("Fixture title 1") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Up restores the movie row's selected item") { focused("Fixture title 2") }
            if (motionOnly) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await("Return to hero") { focused("Play") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await("Return to active menu") { focused("Home") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Movies menu entry keeps focus in the header") { selectedSurface.value == DashboardSurface.MOVIES && focused("Movies") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Reopening Home does not restore its previous tile") { selectedSurface.value == DashboardSurface.HOME && focused("Home") }
                return
            }
            holdCenter()
            await("Long-press release opens the menu without selecting Play") { text("Watch trailer") != null }
            assertEquals(0, plays); assertEquals(0, favorites); assertEquals(0, refreshes); assertEquals(0, related)
            listOf("Play", "Show Related", "Watch trailer", "Refresh IPTV matches", "View description", "Add to My List").forEach {
                assertNotNull("Legacy menu action retained: $it", text(it))
            }
            assertNull("Opening the tile already provides Info", text("Info"))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Menu dismissal restores its originating tile") { focused("Fixture title 2") && text("Watch trailer") == null }
            holdCenter(); await("Manual refresh menu") { text("Refresh IPTV matches") != null }
            chooseMenuOption("Refresh IPTV matches")
            await("Manual refresh is explicit and returns to its tile") { refreshes == 1 && focused("Fixture title 2") && focused("3") }
            assertTrue("Manual match badge belongs to the selected tile", focused("3"))
            holdCenter(); await("Description menu") { text("View description") != null }
            chooseMenuOption("View description")
            await("Full description opens") { text("Close") != null && text("Fixture synopsis 2") != null }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Description returns to its tile") { focused("Fixture title 2") && text("Close") == null }
            holdCenter(); await("Favorite menu") { text("Add to My List") != null }
            chooseMenuOption("Add to My List")
            await("Favorite action uses the existing representation") { favorites == 1 && favoriteCards.value.singleOrNull()?.media?.id == "tmdb-movie:2" }
            await("Favorite returns to tile") { focused("Fixture title 2") }
            holdCenter(); await("Updated favorite menu") { text("Remove from My List") != null }
            chooseMenuOption("Remove from My List")
            await("Favorite removal") { favorites == 2 && favoriteCards.value.isEmpty() && focused("Fixture title 2") }
            holdCenter(); await("Related menu") { text("Show Related") != null }
            chooseMenuOption("Show Related")
            await("Related action is explicit") { related == 1 && focused("Fixture title 2") }
            holdCenter(); await("Deliberate Play menu") { text("Watch trailer") != null }
            chooseMenuOption("Play")
            await("A separate deliberate press still plays") { plays == 1 && focused("Fixture title 2") }
            // Reset counters after the deliberate actions, then retain accidental-touch guards.
            plays = 0; favorites = 0
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Up from the first row restores hero Play") { focused("Play") }
            // Fire TV has no touch surface. Touch behavior is exercised on touchscreen devices.
            if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)) return
            tap("Next featured title")
            await("Touch advances carousel") { text("2 / 10") != null }
            assertEquals(0, plays); assertEquals(0, favorites)
            swipeHero()
            await("Touch swipe pages the hero") { text("3 / 10") != null }
            assertEquals(0, plays); assertEquals(0, favorites)
            tap("Info")
            await("Touch Info also opens detail") { text("Info") == null && text("My List") != null }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Touch detail returns to landing") { text("Info") != null }
            // Touch does not promise keyboard focus, and the tablet header
            // scrolls above the hero. Deliberately re-enter remote input and
            // navigate to the header before testing its touch destinations.
            instrumentation.setInTouchMode(false)
            await("Remote input restores the hero Info origin after touch") { focused("Info") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Up from hero reaches the visible Home navigation") {
                focused("Home") && fullTabLabelVisible("Home") && fullTabLabelVisible("Movies")
            }
            listOf("Movies" to DashboardSurface.MOVIES, "Series" to DashboardSurface.SERIES,
                "Home" to DashboardSurface.HOME).forEach { (label, destination) ->
                tap(label)
                await("Destination changes to $label") { selectedSurface.value == destination && fullTabLabelVisible(label) }
            }
            tap("Live TV")
            await("Live TV dispatches existing channel navigation") { classicDestination == "Live TV" }
            assertEquals(0, plays); assertEquals(0, favorites)
            assertFalse(exited)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
    @Test fun recentAndNewEpisodeMenusKeepTheirLegacyActions() {
        wakeDisplay()
        val context = instrumentation.targetContext
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fixtureHost = host
        val profile = PortalProfile("Fixture actions", "https://example.invalid", "")
        val profileKey = profile.cacheKey()
        val movie = MediaItem("movie", "Recent movie", null, null, description = "Recent synopsis")
        val series = MediaItem("series", "Watched show", null, null)
        val episode = MediaItem("episode", "Episode 2", null, null, seasonNumber = 1, episodeNumber = 2, description = "Episode synopsis")
        val watched = WatchedSeries(profileKey, series, categoryTitle = "Series", newEpisodes = listOf(episode))
        val saved = androidx.compose.runtime.mutableStateOf(emptyList<FavoriteItem>())
        var plays = 0; var recentRemoved = 0; var episodeRemoved = 0; var marked = 0; var related = 0
        var openedSeries: String? = null
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) =
                listOf(DiscoveryCard(1, section.series, "Feed title", date = "2020-01-01"))
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = DiscoveryDetails(card)
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = emptyList<DiscoveryEpisode>()
        }
        try {
            await("Actions fixture window") { host.hasWindowFocus() }
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                val vm = DiscoveryViewModel(context.applicationContext as Application, source)
                host.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalShowRecommendations provides { _, _ -> related++ },
                        LocalRecommendationTypeFor provides { _, type -> type },
                        LocalOpenDiscoverySeries provides { openedSeries = it.id }
                    ) { MaterialTheme {
                        DiscoveryPreviewScreen(NikTvState(savedProfile = profile, favorites = saved.value,
                            recentlyPlayed = listOf(RecentItem(FavoriteKind.MOVIE, movie, profileKey = profileKey)),
                            watchedSeries = listOf(watched)), DashboardSurface.HOME, {}, {}, {}, { plays++ }, { _, _, _ -> plays++ },
                            { plays++ }, { _, _ -> plays++ }, { favorite -> saved.value = saved.value + favorite }, {}, {},
                            { recentRemoved++ }, { _, item -> if (item.id == episode.id) marked++ },
                            { _, item -> if (item.id == episode.id) episodeRemoved++ }, vm)
                    } }
                }
            }
            await("Actions fixture entry") { focused("Home") && text("Feed title") != null }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Actions hero") { focused("Play") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Continue Watching entry") { focused("Recent movie") }
            holdCenter()
            await("Recent popup stays open after release") { text("Clear from this list") != null }
            listOf("Play", "Show Related", "Watch trailer", "View description", "Add to My List", "Clear from this list").forEach {
                assertNotNull("Continue Watching action retained: $it", text(it))
            }
            assertEquals(0, plays); assertEquals(0, recentRemoved)
            chooseMenuOption("Clear from this list")
            await("Recent clear explicitly dispatches") { recentRemoved == 1 && focused("Recent movie") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("New Episodes entry") { focused("Watched show") }
            holdCenter()
            await("Episode group popup stays open after release") { text("Mark as watched") != null }
            listOf("Play", "Show Related", "Mark as watched", "Open series", "View description", "Add to My List", "Clear from this list").forEach {
                assertNotNull("New Episodes action retained: $it", text(it))
            }
            assertEquals(0, plays); assertEquals(0, marked); assertEquals(0, episodeRemoved)
            chooseMenuOption("Add to My List")
            await("Episode favorite dispatches") { saved.value.size == 1 && focused("Watched show") }
            assertEquals(FavoriteKind.EPISODE, saved.value.single().kind)
            assertEquals(episode.id, saved.value.single().media.id)
            assertEquals(series.id, saved.value.single().series?.id)
            assertEquals(profileKey, saved.value.single().profileKey)
            holdCenter(); await("Episode watched action") { text("Mark as watched") != null }
            chooseMenuOption("Mark as watched")
            await("Mark watched is explicit") { marked == 1 && focused("Watched show") }
            holdCenter(); await("Series action") { text("Open series") != null }
            chooseMenuOption("Open series")
            await("Series action preserves parent identity") { openedSeries == series.id && focused("Watched show") }
            holdCenter(); await("Episode clear action") { text("Clear from this list") != null }
            chooseMenuOption("Clear from this list")
            await("Episode clear dispatches") { episodeRemoved == 1 && focused("Watched show") }
            assertEquals(0, plays)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
    @Test fun visiblePosterFocusTiming() {
        wakeDisplay()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fixtureHost = host
        val first = FocusRequester()
        val selected = java.util.concurrent.atomic.AtomicInteger(-1)
        val inputTime = java.util.concurrent.atomic.AtomicLong(0)
        val samples = java.util.concurrent.CopyOnWriteArrayList<Long>()
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { host.setContent {
                MaterialTheme { LazyRow(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items((0..8).toList()) { index ->
                        DiscoveryPosterTile("Timing fixture $index", null, "", 100.dp,
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                            onClick = {}, onFocus = {
                                selected.set(index)
                                inputTime.get().takeIf { it > 0 }?.let { samples.add(SystemClock.uptimeMillis() - it) }
                            })
                    }
                } }
            } }
            instrumentation.waitForIdleSync()
            await("Initial timing tile focused") {
                instrumentation.runOnMainSync { if (host.hasWindowFocus()) first.requestFocus() }
                selected.get() == 0
            }
            repeat(5) {
                listOf(KeyEvent.KEYCODE_DPAD_RIGHT to 1, KeyEvent.KEYCODE_DPAD_RIGHT to 2,
                    KeyEvent.KEYCODE_DPAD_LEFT to 1, KeyEvent.KEYCODE_DPAD_LEFT to 0).forEach { (key, target) ->
                    inputTime.set(SystemClock.uptimeMillis())
                    instrumentation.sendKeyDownUpSync(key)
                    await("Visible focus delivered to $target") { selected.get() == target }
                    inputTime.set(0)
                    Thread.sleep(50)
                }
            }
            val values = samples.sorted()
            assertEquals(20, values.size)
            @Suppress("DEPRECATION") val hz = host.windowManager.defaultDisplay.refreshRate
            println("Discovery visible focus: n=${values.size}, p50=${values[values.size / 2]}ms, p95=${values[(values.size * .95).toInt().coerceAtMost(values.lastIndex)]}ms, max=${values.last()}ms, twoFrames=${2000f / hz}ms, refresh=${hz}Hz")
            assertTrue("Visible focus must not stall", values.last() < 200)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
    @Test fun seriesSeasonEntryAndEpisodePlaybackUseSelectedIdentity() {
        wakeDisplay()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fixtureHost = host
        await("Series fixture window ready") { host.hasWindowFocus() }
        val profile = PortalProfile("Fixture series", "https://example.invalid", "")
        var chosen: Pair<Int, Int>? = null
        var episodePlays = 0
        var download: Pair<Int, Int>? = null
        lateinit var seriesModel: DiscoveryViewModel
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) =
                listOf(DiscoveryCard(1, true, "Fixture series", date = "2020-01-01"))
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = DiscoveryDetails(card, seasons = listOf(1, 2))
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = (1..3).map { DiscoveryEpisode(season, it, "Fixture episode $it", date = "2020-01-01") }
        }
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                val vm = DiscoveryViewModel(context.applicationContext as Application, source)
                seriesModel = vm
                host.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(LocalDownloadDiscoveryEpisode provides { _, season, number -> download = season to number }) {
                        MaterialTheme {
                            DiscoveryPreviewScreen(NikTvState(savedProfile = profile), DashboardSurface.SERIES, {}, {}, {}, {},
                                { _, season, number -> chosen = season to number; episodePlays++ }, {}, { _, _ -> }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, vm)
                        }
                    }
                }
            }
            await("Series hero ready") { focused("Series") && text("Fixture series") != null }
            await("Selected series tab label is fully visible") { fullTabLabelVisible("Series") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Series hero action") { focused("Play") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Series My List action") { focused("My List") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Series trailer action") { focused("Watch trailer") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Series info focused") { focused("Info") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            // Loaded metadata may precede the season row entering a short landscape viewport.
            // Down must reveal it; do not require it to be onscreen before that input.
            await("Season metadata loaded and detail entry focused") {
                seriesModel.state.value.details?.seasons == listOf(1, 2) && focused("Play")
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            val wideEpisodes = context.resources.configuration.smallestScreenWidthDp >= 600 ||
                context.isTvLikeDevice(context.resources.configuration)
            await("Down enters the episode toolbar") { focused(if (wideEpisodes) "Search episodes" else "Season 2") }
            await("The selected latest season is ready") {
                seriesModel.state.value.season == 2 && seriesModel.state.value.episodes.size == 3
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Newest episode gets focus") { focused("Fixture episode 3") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Playback carries exact season/episode identity") { chosen == (2 to 3) }
            holdCenter()
            await("Episode long release opens options") { text("Download") != null }
            assertNull("Release must not trigger Download", download)
            assertEquals("Release must not trigger another Play", 1, episodePlays)
            chooseMenuOption("Download")
            await("A separate press downloads the selected episode") { download == (2 to 3) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Series detail returns to its originating Info action") {
                seriesModel.state.value.detail == null && focused("Info")
            }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
