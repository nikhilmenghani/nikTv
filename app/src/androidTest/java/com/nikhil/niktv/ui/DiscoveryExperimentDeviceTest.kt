package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import org.junit.Assert.*
import org.junit.Test

/** Dedicated fixture package only: no personal history, provider requests, images or playback. */
class DiscoveryExperimentDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var fixture: PlaybackFixtureHostActivity? = null

    @Test fun externalSeriesMenuReturnKeepsScrolledViewport() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        context.getSharedPreferences("home2_presentation", 0).edit().clear().commit()
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fixture = host
        val profile = PortalProfile("Return fixture", "https://fixture.invalid", "")
        val showDetails = mutableStateOf(false)
        val navigationEvents = java.util.ArrayDeque<String>()
        val traceEnabled = InstrumentationRegistry.getArguments().getString("traceFocus") == "true"
        val series = MediaItem("series-1", "Fixture series", null, null)
        val recent = RecentItem(FavoriteKind.SERIES, series, series, profileKey = profile.cacheKey())
        val cards = (1..20).map { DiscoveryCard(it, false, "Fixture movie $it", date = "2020-01-01") }
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) = cards.map { it.copy(series = section.series) }
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = DiscoveryDetails(card)
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = emptyList<DiscoveryEpisode>()
        }
        lateinit var vm: DiscoveryViewModel
        var returnVmReady = false
        fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); Thread.sleep(160) }
        fun tag(): String? { var value: String? = null; instrumentation.runOnMainSync { value = host.currentFocus?.tag as? String }; return value }
        fun bounds(): android.graphics.Rect = android.graphics.Rect().also { rect -> instrumentation.runOnMainSync { host.currentFocus?.let { view ->
            val parent = view.parent as View
            val location = IntArray(2); parent.getLocationInWindow(location)
            rect.set(location[0] + view.left - parent.scrollX, location[1] + view.top - parent.scrollY,
                location[0] + view.right - parent.scrollX, location[1] + view.bottom - parent.scrollY)
        } } }
        fun scale(): Float { var scale = 0f; instrumentation.runOnMainSync { scale = host.currentFocus?.scaleX ?: 0f }; return scale }
        try {
            instrumentation.runOnMainSync {
                vm = DiscoveryViewModel(context.applicationContext as Application, source)
                returnVmReady = true
                host.window.decorView.isSoundEffectsEnabled = false
                host.setContent { MaterialTheme {
                    val saved = rememberSaveableStateHolder()
                    if (showDetails.value) {
                        BackHandler { showDetails.value = false }
                        Text("External series details")
                    } else saved.SaveableStateProvider("landing") {
                        CompositionLocalProvider(com.nikhil.niktv.ui.LocalPlayAlternateSource provides {}, LocalDiscoveryExperiment provides true,
                            LocalDiscoveryNavigationTrace provides if (traceEnabled) ({ event ->
                                if (navigationEvents.size >= 160) navigationEvents.removeFirst()
                                navigationEvents.addLast(event)
                            }) else null,
                            LocalOpenDiscoverySeries provides { showDetails.value = true }) {
                            DiscoveryPreviewScreen(
                                app = NikTvState(savedProfile = profile, homeOpen = true, recentlyPlayed = listOf(recent),
                                    discoverySurface = DashboardSurface.HOME, tmdbSectionsBySurface = mapOf(DashboardSurface.HOME to emptyList())),
                                surface = DashboardSurface.HOME, selectSurface = {}, exit = {}, navigate = {},
                                playCard = {}, playEpisode = { _, _, _ -> }, openRecent = {}, openWatched = { _, _ -> },
                                toggleFavorite = {}, openCategory = {}, openSection = {}, removeRecent = {},
                                markWatched = { _, _ -> }, dismissEpisode = { _, _ -> }, vm = vm, experiment = true)
                        }
                    }
                } }
            }
            await("Fixture feed is ready") { vm.state.value.feeds[TmdbHomeSection.TRENDING_MOVIES]?.size == 20 }
            instrumentation.setInTouchMode(false)
            repeat(6) { key(KeyEvent.KEYCODE_DPAD_UP) }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("First activity tile owns focus") { tag()?.startsWith("continue:") == true }
            repeat(InstrumentationRegistry.getArguments().getString("externalRuns")?.toIntOrNull()?.coerceIn(1, 20) ?: 1) {
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_UP) }
            await("Activity tile returns after scrolling") { tag()?.startsWith("continue:") == true }
            Thread.sleep(650)
            val before = bounds(); val origin = tag(); val beforeScale = scale()
            val down = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100)
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            await("Open series is available") { semantic("Open series") { true } }
            await("Menu starts on Play") { semantic("Play") { it.config.getOrNull(SemanticsProperties.Focused) == true } }
            await("Popup owns input before the first arrow") {
                !host.hasWindowFocus() && android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() }
            }
            instrumentation.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Alternate source follows Play") { semantic("Play from another source") { it.config.getOrNull(SemanticsProperties.Focused) == true } }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Open series owns focus before activation") { semantic("Open series") { it.config.getOrNull(SemanticsProperties.Focused) == true } }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("External series page opens") { showDetails.value }
            await("External details own the window after the menu closes") {
                host.hasWindowFocus() && semantic("External series details") { true }
            }
            key(KeyEvent.KEYCODE_BACK)
            await("Back closes the external series page") { !showDetails.value }
            await("External series return restores its tile") { tag() == origin }
            Thread.sleep(650)
            val after = bounds()
            assertTrue("External series viewport retained: $before -> $after", kotlin.math.abs(before.top - after.top) <= 2)
            assertTrue("External series column retained: $before -> $after", kotlin.math.abs(before.left - after.left) <= 2)
            assertEquals("Focus scale is settled after external return", beforeScale, scale(), .0015f)
            }
        } catch (error: AssertionError) {
            captureReturn(host, "external-failure")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "external-windows.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            for (label in listOf("Play", "Play from another source", "Open series")) {
                var flags = ""
                semantic(label) { flags += "${it.id}:${it.config.getOrNull(SemanticsProperties.Focused)};"; false }
                println("MENU $label=$flags")
            }
            var diagnostic = ""
            instrumentation.runOnMainSync { diagnostic = "focus=${host.currentFocus?.javaClass?.simpleName}:${host.currentFocus?.tag}\n" + navigationEvents.joinToString("\n") }
            throw AssertionError("${error.message}\n$diagnostic", error)
        } finally {
            instrumentation.runOnMainSync { if (returnVmReady) vm.viewModelScope.cancel(); host.finish(); fixture = null }
        }
    }

    @Test fun trialMenuBudgetsDoNotTrimBackingFeedOrAffectOriginalHome() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        // This fixture deliberately selects Stage 1 at the end. Reset only its
        // isolated package preferences so repeated runs start with the full hero.
        context.getSharedPreferences("home2_presentation", 0).edit().clear().commit()
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fixture = host
        val profile = PortalProfile("Performance fixture", "https://fixture.invalid", "")
        val cards = (1..20).map { DiscoveryCard(it, false, "Fixture movie $it", date = "2020-01-01") }
        val source = object : DiscoveryDataSource {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) =
                cards.map { it.copy(series = section.series) }
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) =
                DiscoveryDetails(card.copy(overview = "Full detail for ${card.id}"))
            override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
            override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
            override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
            override suspend fun episodes(card: DiscoveryCard, season: Int) = emptyList<DiscoveryEpisode>()
        }
        lateinit var vm: DiscoveryViewModel
        var vmReady = false
        val experiment = mutableStateOf(true)
        // A previously installed experiment must not keep changing original Home.
        val legacyPreferences = context.getSharedPreferences("niktv_discovery_rendering", 0)
        legacyPreferences.edit().putBoolean("recycled", true).commit()
        try {
            instrumentation.runOnMainSync {
                vm = DiscoveryViewModel(context.applicationContext as Application, source)
                vmReady = true
                host.window.decorView.isSoundEffectsEnabled = false
                host.window.decorView.isHapticFeedbackEnabled = false
                host.setContent {
                    CompositionLocalProvider(LocalDiscoveryExperiment provides experiment.value) { MaterialTheme {
                        DiscoveryPreviewScreen(
                            app = NikTvState(savedProfile = profile, homeOpen = true,
                                discoverySurface = DashboardSurface.HOME,
                                tmdbSectionsBySurface = mapOf(DashboardSurface.HOME to emptyList())),
                            surface = DashboardSurface.HOME, selectSurface = {}, exit = {}, navigate = {},
                            playCard = {}, playEpisode = { _, _, _ -> }, openRecent = {}, openWatched = { _, _ -> },
                            toggleFavorite = {}, openCategory = {}, openSection = {}, removeRecent = {},
                            markWatched = { _, _ -> }, dismissEpisode = { _, _ -> }, vm = vm,
                            experiment = experiment.value)
                    } }
                }
            }
            await("Fixture Home feed is ready") { vm.state.value.feeds[TmdbHomeSection.TRENDING_MOVIES]?.size == 20 }
            // Exercise the real preview shell + Compose hero + native rails together.
            // The renderer-only fixture cannot detect focus handoff regressions here.
            instrumentation.setInTouchMode(false)
            fun nativeTag(): String? { var tag: String? = null; instrumentation.runOnMainSync { tag = host.currentFocus?.tag as? String }; return tag }
            fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); Thread.sleep(150) }
            fun focused(label: String): Boolean {
                if (semantic(label) { it.config.getOrNull(SemanticsProperties.Focused) == true }) return true
                var node = text(label); var found = false
                repeat(4) { if (node?.isFocused == true) found = true; node = node?.parent }
                return found
            }
            repeat(8) { key(KeyEvent.KEYCODE_DPAD_UP) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down enters the first media directly: ${nativeTag()}") { nativeTag()?.startsWith("daily-movies:") == true && nativeTag() != "daily-movies:all" }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_UP) }
            await("First media returns after repeated row reversal") { nativeTag()?.startsWith("daily-movies:") == true }
            await("Full hero shell keeps the returned media entirely visible") {
                var visible = false
                instrumentation.runOnMainSync { host.currentFocus?.let { view ->
                    val rect = android.graphics.Rect()
                    visible = view.getGlobalVisibleRect(rect) && rect.height() >= view.height - 2 && rect.width() >= view.width - 2
                } }
                visible
            }
            repeat(8) { key(KeyEvent.KEYCODE_DPAD_UP) }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Rapid Up returns through the tab and hero exactly once: ${nativeTag()}") { nativeTag()?.startsWith("daily-movies:") == true && nativeTag() != "daily-movies:all" }
            click("Options")
            click("Home display options")
            click("Close")
            await("Trial settings restores its originating Options button") { focused("Options") }
            repeat(12) { if (!focused("Home")) key(KeyEvent.KEYCODE_DPAD_LEFT) }
            await("Left reaches active Home tab") { focused("Home") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Closing trial settings retains direct media entry: ${nativeTag()}") { nativeTag()?.startsWith("daily-movies:") == true && nativeTag() != "daily-movies:all" }
            click("Options"); click("Home display options")
            await("The baseline stage chooser is ready") { semantic(DiscoveryScrollVariant.RECYCLED_BASELINE.title) { true } || text(DiscoveryScrollVariant.RECYCLED_BASELINE.title)?.isVisibleToUser == true }
            // The chooser scrolls as experiments are added; offscreen options need not be composed.
            click(DiscoveryScrollVariant.RECYCLED_BASELINE.title)
            assertEquals("Preview limits never truncate repository data", 20, vm.state.value.feeds[TmdbHomeSection.TRENDING_MOVIES]?.size)
            // A title outside the eight-tile preview remains a normal, complete detail destination.
            instrumentation.runOnMainSync { vm.openDetails(cards.last()) }
            await("Full detail remains accessible outside the trial preview") { vm.state.value.details?.card?.id == 20 }
            assertEquals("Full detail for 20", vm.state.value.details?.card?.overview)
            instrumentation.runOnMainSync { vm.closeDetails(); experiment.value = false }
            await("Original Home is restored") { vm.state.value.detail == null && (semantic("Options") { true } || text("Options") != null) }
            click("Options")
            assertFalse("Original Home does not expose trial settings", semantic("Home display options") { true })
            assertNull("Original Home does not expose trial settings", text("Home display options"))
            assertEquals(20, vm.state.value.feeds[TmdbHomeSection.TRENDING_MOVIES]?.size)
        } finally {
            legacyPreferences.edit().clear().commit()
            instrumentation.runOnMainSync { if (vmReady) vm.viewModelScope.cancel(); host.finish(); fixture = null }
        }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < until) { if (predicate()) return; Thread.sleep(30) }
        fail(label)
    }
    private fun captureReturn(host: PlaybackFixtureHostActivity, name: String) {
        val bitmap = android.graphics.Bitmap.createBitmap(host.window.decorView.width, host.window.decorView.height, android.graphics.Bitmap.Config.ARGB_8888)
        val done = java.util.concurrent.CountDownLatch(1)
        var status = -1
        android.view.PixelCopy.request(host.window, bitmap, { status = it; done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, status)
        java.io.File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun text(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun click(label: String) {
        // Fire OS may return a null global accessibility root, including for its
        // launcher. Inspect this fixture's own Compose roots, including dialogs,
        // rather than treating that system service failure as an app failure.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            await("$label has a visible semantic click action") {
                semantic(label) { it.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true }
            }
            return
        }
        await("$label is visible") { text(label)?.isVisibleToUser == true }
        var node = text(label)
        repeat(8) {
            if (node?.isClickable == true) {
                assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            node = node?.parent
        }
        fail("$label has no clickable control")
    }
    private fun semantic(label: String, action: (SemanticsNode) -> Boolean): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            fun roots(view: View): List<ViewRootForTest> = buildList {
                if (view is ViewRootForTest) add(view)
                if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
            }
            fun labels(node: SemanticsNode): List<String> = buildList {
                addAll(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                addAll(node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
                node.children.forEach { addAll(labels(it)) }
            }
            val views = if (android.os.Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews()
                else listOfNotNull(fixture?.window?.decorView)
            found = views.filter { it.isShown }.flatMap(::roots).any { root ->
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).any { node ->
                    label in labels(node) && node.boundsInWindow.width > 0 && node.boundsInWindow.height > 0 && action(node)
                }
            }
        }
        return found
    }
}
