package com.nikhil.niktv.ui

import com.nikhil.niktv.discovery.components.*

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
    @Test fun liveSeekbarRightEntersLiveActionWithoutSeeking() {
        val calls = CopyOnWriteArrayList<Long>()
        fixture({
            val bar = remember { FocusRequester() }
            val live = remember { FocusRequester() }
            Row(Modifier.width(650.dp).initialFocusEntry(bar)) {
                com.nikhil.niktv.sports.player.PlaybackProgressBar(20_000, 120_000, { calls.add(it) }, "live-navigation",
                    modifier = Modifier.weight(1f).focusRequester(bar),
                    onNavigateRight = { live.requestFocus() })
                NikTvActionButton("Watch live fixture", modifier = Modifier.focusRequester(live)
                    .focusProperties { left = bar }, onClick = {})
            }
        }) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters the LIVE action") { focused("Watch live fixture") }
            Thread.sleep(600)
            assertTrue("Navigation to LIVE must not seek", calls.isEmpty())
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            await("Dedicated fast-forward remains available on the bar") { calls.size == 1 }
            assertEquals(30_000L, calls.single())
        }
    }

    @Test fun suggestedArtworkMenuUpdatesAndPersistsWithoutChangingFeeds() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("discovery_experience", android.content.Context.MODE_PRIVATE)
        val hadPreference = prefs.contains("suggested_artwork")
        val old = com.nikhil.niktv.discovery.DiscoveryArtworkPreference.enabled(context, true)
        com.nikhil.niktv.discovery.DiscoveryArtworkPreference.setEnabled(context, false)
        try {
            fixture({
                var menu by remember { mutableStateOf(false) }
                val enabled by com.nikhil.niktv.discovery.rememberSuggestedArtwork()
                Column {
                    Text(if (enabled) "Artwork enabled fixture" else "Artwork disabled fixture")
                    com.nikhil.niktv.discovery.DiscoveryBrowseMenu(menu, { menu = it }, DashboardSurface.HOME,
                        navigate = {}, customize = {})
                }
            }) {
                await("Artwork starts disabled") { find("Artwork disabled fixture") != null }
                button("Options")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                await("Menu exposes artwork toggle") { button("Suggested artwork: Off") != null }
                button("Suggested artwork: Off")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                await("Preference updates the screen immediately") { find("Artwork enabled fixture") != null }
                assertTrue(com.nikhil.niktv.discovery.DiscoveryArtworkPreference.enabled(context, true))
                button("Options")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                await("Menu exposes saved On state") { button("Suggested artwork: On") != null }
                button("Suggested artwork: On")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                await("Artwork can be disabled again") { find("Artwork disabled fixture") != null }
            }
        } finally {
            if (hadPreference) com.nikhil.niktv.discovery.DiscoveryArtworkPreference.setEnabled(context, old)
            else prefs.edit().remove("suggested_artwork").apply()
        }
    }

    @Test fun heldSeekOnlyPreviewsAndCommitsOnceAfterRelease() {
        val calls = CopyOnWriteArrayList<Long>()
        fixture({
            val bar = remember { FocusRequester() }
            Box(Modifier.width(600.dp).initialFocusEntry(bar)) {
                com.nikhil.niktv.sports.player.PlaybackProgressBar(0L, 7_200_000L, { calls.add(it) }, "fixture-seek", modifier = Modifier.focusRequester(bar))
            }
        }) {
            Thread.sleep(250)
            val down = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            Thread.sleep(850)
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1))
            Thread.sleep(550)
            assertTrue("Holding never seeks the actual player", calls.isEmpty())
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            await("One accelerated seek after release") { calls.size == 1 }
            assertEquals(40_000L, calls.single())
            Thread.sleep(650)
            assertEquals(1, calls.size)
        }
    }
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

    @Test fun sessionActionsStayAlignedAndReauthenticationKeepsFocusWhileBusy() {
        val busy = mutableStateOf(false)
        val diagnostics = mutableStateOf(false)
        val calls = AtomicInteger()
        fixture({
            val reauth = remember { FocusRequester() }
            val close = remember { FocusRequester() }
            androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                Surface(Modifier.width(360.dp).initialFocusEntry(reauth)) {
                    Column(Modifier.padding(16.dp)) {
                        NikTvTextActionButton({ diagnostics.value = true }) { Text("Show diagnostics fixture") }
                        PortalDiagnosticsActions(true, busy.value, diagnostics.value, reauth, close,
                            close = {}, reauthenticate = { calls.incrementAndGet(); busy.value = true }, copy = {})
                    }
                }
            }
        }) {
            await("Re-authenticate receives initial focus") { focused("Re-authenticate") }
            val close = bounds(requireNotNull(button("Close")))
            val primary = bounds(requireNotNull(button("Re-authenticate")))
            assertEquals(close.top, primary.top); assertEquals(close.bottom, primary.bottom)
            assertTrue(close.right < primary.left)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.cacheDir, "session-actions-aligned.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Busy action keeps focus") { focused("Authenticating…") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(1, calls.get())
            instrumentation.runOnMainSync { busy.value = false; diagnostics.value = true }
            await("Expanded diagnostics retain Re-authenticate focus") { focused("Re-authenticate") && button("Copy diagnostics") != null }
            val copy = bounds(requireNotNull(button("Copy diagnostics")))
            val after = bounds(requireNotNull(button("Re-authenticate")))
            assertEquals(close.top, copy.top); assertEquals(copy.top, after.top)
            assertEquals(close.bottom, copy.bottom); assertEquals(copy.bottom, after.bottom)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Copy is reachable") { focused("Copy diagnostics") }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Close is reachable") { focused("Close") }
        }
    }

    @Test fun ordinaryDiagnosticsKeepCloseFocusedAndCopyInTheSameRow() {
        fixture({
            val close = remember { FocusRequester() }
            val reauth = remember { FocusRequester() }
            androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                Surface(Modifier.width(360.dp).initialFocusEntry(close)) {
                    Box(Modifier.padding(16.dp)) {
                        PortalDiagnosticsActions(false, false, true, reauth, close, {}, {}, {})
                    }
                }
            }
        }) {
            await("Close remains default for ordinary diagnostics") { focused("Close") }
            val close = bounds(requireNotNull(button("Close")))
            val copy = bounds(requireNotNull(button("Copy diagnostics")))
            assertEquals(close.top, copy.top); assertEquals(close.bottom, copy.bottom)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Copy is reachable from Close") { focused("Copy diagnostics") }
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
