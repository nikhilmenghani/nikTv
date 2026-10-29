package com.nikhil.niktv.sports

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Isolated presentation tests: fake events, no source lookup, profile access or audio. */
class SportsUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
        assertTrue(message, condition())
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun click(text: String) {
        await("Reachable action: $text") {
            var node = find(text)
            repeat(8) { if (node?.isClickable != true) node = node?.parent }
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    private fun focused(text: String): Boolean {
        var node = find(text)
        repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
        return false
    }
    private fun host(): SportsFixtureHostActivity {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val activity = instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
        await("Fixture window is ready") { activity.hasWindowFocus() }
        return activity
    }

    private fun assertNavigationPosition(activity: SportsFixtureHostActivity) {
        val phone = activity.resources.configuration.smallestScreenWidthDp < 600
        val bounds = Rect().also { requireNotNull(find("Live Events")).getBoundsInScreen(it) }
        val height = activity.resources.displayMetrics.heightPixels
        assertTrue("Phone navigation is at the bottom; tablet navigation stays in the header",
            if (phone) bounds.centerY() > height * .75 else bounds.centerY() < height * .3)
        listOf("Sports", "Categories", "Highlights").forEach { assertNotNull("Destination: $it", find(it)) }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun sharedHeaderKeepsPhoneTitlesOnOneLineAndActionsReachableOnEveryTab() {
        val activity = host()
        val tab = mutableStateOf(SportsTab.CATEGORIES)
        var refreshes = 0
        var configurationImports = 0
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                if (tab.value == SportsTab.HIGHLIGHTS) CricketHighlightsContent(
                    CricketHighlightsState(configured = true), {}, { tab.value = it }, { refreshes++ }, {},
                    importConfiguration = { configurationImports++ })
                else SportsBrowseContent(SportsState(tab = tab.value, loading = false), Instant.now(),
                    "All sports", {}, "Now & next", {}, "", {}, {}, { refreshes++ }, {}, {},
                    selectTab = { tab.value = it })
            } } }
            for (destination in SportsTab.entries) {
                instrumentation.runOnMainSync { tab.value = destination }
                instrumentation.waitForIdleSync()
                await("Shared overflow entry on $destination") {
                    find("Sports options") != null && (activity.resources.configuration.smallestScreenWidthDp >= 600 ||
                        find("Sports page title")?.text?.toString() == if (destination == SportsTab.HIGHLIGHTS) "Highlights" else "Sports")
                }
                if (activity.resources.configuration.smallestScreenWidthDp < 600) {
                    val title = requireNotNull(find("Sports page title"))
                    assertEquals(if (destination == SportsTab.HIGHLIGHTS) "Highlights" else "Sports", title.text?.toString())
                    val titleBounds = Rect().also(title::getBoundsInScreen)
                    val backBounds = Rect().also(requireNotNull(find("Back"))::getBoundsInScreen)
                    val density = activity.resources.displayMetrics.density
                    assertTrue("Title has room for a complete word on $destination", titleBounds.width() > 100 * density)
                    assertTrue("Title stays on one line on $destination", titleBounds.height() < 40 * density)
                    assertTrue("Title fits between Back and Options", titleBounds.left >= backBounds.right)
                }
                screenshot("sports-header-${destination.name.lowercase()}")
                click("Sports options")
                val refreshLabel = if (destination == SportsTab.HIGHLIGHTS) "Refresh highlights" else "Refresh sports"
                await("Actions remain reachable on $destination") { find(refreshLabel) != null }
                assertNotNull(find(if (destination == SportsTab.HIGHLIGHTS) "Search highlights" else "Search sports"))
                if (destination == SportsTab.CATEGORIES) {
                    assertNotNull(find("Favourites"))
                    click("Add playlist")
                    await("Playlist action opens its dialog") { find("Playlist name") != null }
                    click("Cancel")
                    click("Sports options")
                }
                if (destination == SportsTab.HIGHLIGHTS) {
                    click("Cricbuzz configuration")
                    await("Configuration action remains connected") { configurationImports == 1 }
                    click("Sports options")
                }
                val previous = refreshes
                click(refreshLabel)
                await("Menu action executes and dismisses on $destination") { refreshes == previous + 1 && find(refreshLabel) == null }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun eventDropdownSavesSelectionAndCategoryRowsSurviveReopening() {
        val activity = host()
        val context = instrumentation.targetContext
        val prefs = SportsBrowsePreferences(context)
        val previous = prefs.read()
        val now = Instant.parse("2026-10-27T10:00:00Z")
        val event = SportsEvent("1", "Cricket fixture", "fixture", "Cricket", "Fixture", "", "A", "B",
            now.minusSeconds(60), now.plusSeconds(3600), emptyList())
        val football = event.copy(id = "2", slug = "football", title = "Football fixture", sport = "Football")
        val upcoming = event.copy(id = "3", slug = "upcoming", title = "Upcoming fixture", start = now.plusSeconds(7200), end = now.plusSeconds(10800))
        try {
            prefs.setStatus("Now & next")
            fun render() {
                instrumentation.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                    var status by remember { mutableStateOf(prefs.read().status) }
                    SportsBrowseContent(SportsState(events = listOf(event, football, upcoming), loading = false), now,
                        "All sports", {}, status, { status = it }, "", {}, {}, {}, {}, {},
                        saveDefaultStatus = { prefs.setStatus(it) })
                } } }
            }
            render()
            await("Category rows render") { find("All sports") != null && find("Cricket") != null }
            assertNavigationPosition(activity)
            screenshot("sports-live-events")
            click("Live events: Now & next")
            await("Status dropdown opens") { find("Live") != null }
            click("Live")
            await("Live selection applies") { find("Live events: Live") != null && find("2 events") != null }
            assertEquals("Live", SportsBrowsePreferences(context).read().status)
            instrumentation.runOnMainSync { activity.setContent {} }
            render()
            await("Reload restores saved selection") { find("Live events: Live") != null && find("2 events") != null }
            click("Live events: Live")
            await("Upcoming option available") { find("Upcoming") != null }
            click("Upcoming")
            await("Upcoming selection filters the rows") { find("1 event") != null && find("Upcoming fixture") != null && find("Football fixture") == null }
        } finally {
            prefs.setSport(previous.sport); prefs.setStatus(previous.status)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun highlightsDropdownsSaveIndependentlyAndKeepCategoryNavigation() {
        val activity = host()
        val prefs = CricketBrowsePreferences(instrumentation.targetContext)
        val previous = prefs.read()
        val videos = listOf(CricketHighlight("one", "India vs New Zealand highlights", "12:30"),
            CricketHighlight("two", "Australia vs England highlights", "10:15"))
        var picked: String? = null
        var selectedCollection: Int? = null
        try {
            prefs.setVersion("16.11.01"); prefs.setRegion("CA")
            fun render() { instrumentation.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                var defaults by remember { mutableStateOf(prefs.read()) }
                CricketHighlightsContent(CricketHighlightsState(configured = true, videos = videos,
                    appVersion = defaults.version, region = defaults.region,
                    sections = listOf(CricketVideoSection(7, "Match highlights", videos), CricketVideoSection(8, "Interviews", videos))),
                    {}, {}, {}, { picked = it }, selectCollection = { selectedCollection = it },
                    selectRegion = { prefs.setRegion(it); defaults = prefs.read() },
                    selectAppVersion = { prefs.setVersion(it); defaults = prefs.read() })
            } } } }
            render()
            await("Highlights row is visible") { find("Match highlights") != null && find(videos[0].title) != null }
            assertNavigationPosition(activity)
            screenshot("sports-highlights")
            click("Version: 16.11.01")
            await("Version dropdown opens") { find("6.16.04") != null }
            click("6.16.04")
            await("Version updates") { find("Version: 6.16.04") != null }
            click("Region: Canada")
            await("Region dropdown opens") { find("India") != null }
            click("India")
            await("Region updates") { find("Region: India") != null }
            instrumentation.runOnMainSync { activity.setContent {} }
            render()
            await("Both selections restore") { find("Region: India") != null && find("Version: 6.16.04") != null }
            assertEquals(CricketBrowseDefaults("6.16.04", "IN"), CricketBrowsePreferences(instrumentation.targetContext).read())
            click("View all Match highlights")
            assertEquals(7, selectedCollection)
            assertNull("Browsing does not play video", picked)
        } finally {
            prefs.setVersion(previous.version); prefs.setRegion(previous.region)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun sportsCatalogueAndSourceCardsSupportTouchAndDpadWithoutBackgroundPlayback() {
        val activity = host()
        val now = Instant.parse("2026-10-27T10:00:00Z")
        val cricket = SportsEvent("1", "India vs New Zealand", "fixture-cricket", "Cricket", "Fixture international", "", "India", "New Zealand",
            now.minusSeconds(60), now.plusSeconds(3600), listOf("English", "Hindi"))
        val football = cricket.copy(id = "2", title = "United vs City", slug = "fixture-football", sport = "Football", teamA = "United", teamB = "City")
        val model = mutableStateOf(SportsState(events = listOf(cricket, football), loading = false))
        var picked = -1
        var refreshes = 0
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                var sport by remember { mutableStateOf("All sports") }
                var status by remember { mutableStateOf("Now & next") }
                var query by remember { mutableStateOf("") }
                SportsBrowseContent(model.value, now, sport, { sport = it }, status, { status = it }, query, { query = it },
                    back = { model.value = model.value.copy(event = null, streams = emptyList(), resolving = null) },
                    refresh = { refreshes++ }, open = { event -> model.value = model.value.copy(event = event,
                        streams = listOf(SportsStream("https://fixture.invalid/english", "English commentary"),
                            SportsStream("https://fixture.invalid/hindi", "Hindi commentary"))) }, play = { picked = it; model.value = model.value.copy(resolving = it) })
            } } }
            await("Catalogue renders with the selected tab focused") { focused("Live Events") && find("India vs New Zealand") != null }
            assertTrue("Browsing Sports keeps the display awake", activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertEquals(-1, picked)
            click("Sports options")
            click("Search sports")
            await("Search opens on explicit activation") { find("Find a team or event") != null }
            fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isEditable) return node
                for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
                return null
            }
            val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "India") }
            await("Search field accepts input after the options menu closes") {
                editable(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
            }
            await("Search is local and filters events") { find("1 event") != null && find("United vs City") == null }
            click("Close sports search")
            click("India vs New Zealand")
            await("Compact stream cards appear") { find("English commentary") != null && find("Hindi commentary") != null }
            repeat(8) {
                if (!focused("English commentary")) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                Thread.sleep(100)
            }
            await("D-pad reaches the first stream without selecting it") { focused("English commentary") }
            assertEquals(-1, picked)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK chooses the focused source and retains focus during resolution") { picked == 0 && focused("English commentary") && find("Preparing stream…") != null }
            assertEquals(0, refreshes)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun brightnessAndVolumeTextStayWhiteEvenWithAnInheritedBlackContentColor() {
        val activity = host()
        val brightness = mutableStateOf(true)
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        PlayerLevelFeedback(brightness.value, .5f, compact = false)
                    }
                }
            } } }
            for (label in listOf("Brightness", "Volume")) {
                instrumentation.runOnMainSync { brightness.value = label == "Brightness" }
                await("Gesture label is visible: $label") { find(label) != null }
                instrumentation.waitForIdleSync()
                val bounds = Rect().also { requireNotNull(find(label)).getBoundsInScreen(it) }
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    var white = 0
                    for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height))
                        for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
                            val pixel = bitmap.getPixel(x, y)
                            if (android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) > 220 && android.graphics.Color.blue(pixel) > 220) white++
                        }
                    assertTrue("$label text has an explicit high-contrast white foreground", white > 5)
                } finally { bitmap.recycle() }
            }
            instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
            await("Leaving Sports releases its keep-awake flag") { activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0 }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
