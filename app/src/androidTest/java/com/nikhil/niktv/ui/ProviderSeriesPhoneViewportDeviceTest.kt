package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.discovery.DiscoveryCache
import com.nikhil.niktv.discovery.components.DiscoverySeriesContent
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class ProviderSeriesPhoneViewportDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return ins.uiAutomation.rootInActiveWindow
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(root())
    }
    private fun owner(label: String): AccessibilityNodeInfo? {
        var node = find(label)
        while (node != null && !node.isClickable && node.parent != null) node = node.parent
        return node
    }
    private fun focused(label: String) = owner(label)?.isFocused == true
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
        NavigationAuditEvidence.capture("provider-phone-viewport-failure")
        fail(message)
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    private fun focus(label: String) {
        var node = find(label)
        while (node != null && !node.isFocusable) node = node.parent
        assertTrue("Can focus $label", node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
    }
    private fun swipe(x: Float, from: Float, to: Float) {
        val start = SystemClock.uptimeMillis()
        for (i in 0..16) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 16 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, from + (to - from) * i / 16f, 0).also {
                ins.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
            Thread.sleep(18)
        }
    }

    @Test fun phoneHeaderDownRevealsFullEpisodePaneAndRetainsCursorAcrossHeroReturn() {
        check(ins.targetContext.packageName.endsWith(".regression"))
        for (height in listOf(480, 800)) exercise(height)
    }

    private fun exercise(height: Int) {
        val context = ins.targetContext
        val series = MediaItem("phone-viewport-$height", "Phone viewport series $height", null, null,
            description = "A synopsis for checking the full artwork and the series information dialog.")
        runBlocking { DiscoveryCache(File(context.cacheDir, "discovery_preview")).write(
            "provider-presentation-v1:SERIES:null:${providerSeriesTmdbTitle(series.title)}", "[]") }
        val state = NikTvState(selectedSeries = series, selectedType = CatalogType.SERIES,
            availableSeriesSeasons = listOf(1), selectedSeriesSeason = 1,
            items = (1..24).map { MediaItem("phone-pane-$it", "Phone pane episode $it", null, null, seasonNumber = 1, episodeNumber = it) },
            useTmdbEpisodeMetadata = false, restoring = false, homeOpen = false)
        val plays = CopyOnWriteArrayList<String>()
        val frame = AtomicReference(Rect())
        ins.setInTouchMode(false)
        val activity = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                val phone = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 400; screenHeightDp = height; smallestScreenWidthDp = 400
                    orientation = Configuration.ORIENTATION_PORTRAIT
                }
                CompositionLocalProvider(LocalConfiguration provides phone) {
                    Box(Modifier.width(400.dp).height(height.dp).onGloballyPositioned {
                        val b = it.boundsInWindow()
                        frame.set(Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
                    }) {
                        DiscoverySeriesContent(state, play = { plays.add(it.id) }, closeSeries = {}, toggleFavorite = {},
                            toggleSeriesWatch = {}, loadSeriesSeason = {}, setUseTmdbEpisodeMetadata = {}, refreshTmdbEpisodeCache = {},
                            downloadForOffline = { _, _, _ -> }, removeOfflineDownload = { _, _ -> }, openSearch = {}, openSettings = {},
                            refreshCatalog = {}, loadMoreEpisodes = {}, openTmdbMovie = {}, openTmdbSeries = {})
                    }
                }
            } } }
            val density = context.resources.displayMetrics.density
            fun alignedToolbar() = find("Season 1")?.let { bounds(it).top <= frame.get().top + (80 * density).roundToInt() } == true
            fun assertFullPane() {
                var pane = find("Phone pane episode 24")
                while (pane != null && !pane.isScrollable) pane = pane.parent
                assertNotNull("Phone episodes retain their independent list", pane)
                assertTrue("Collapsed episode pane uses most of a ${height}dp viewport", bounds(pane!!).height() >= frame.get().height() * .55f)
                val fullRows = (1..24).count { number -> owner("Phone pane episode $number")?.let {
                    it.isVisibleToUser && frame.get().contains(bounds(it))
                } == true }
                assertTrue("Phone pane shows at least three complete records at ${height}dp, actual=$fullRows", fullRows >= 3)
            }
            await("Phone hero starts on Play latest at ${height}dp") { focused("Play latest") }
            NavigationAuditEvidence.capture("provider-phone-${height}-hero")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Header Down reaches the season toolbar and collapses artwork") { focused("Season 1") && alignedToolbar() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Toolbar Down reaches the first episode") { focused("Phone pane episode 24") }
            assertFullPane()
            NavigationAuditEvidence.capture("provider-phone-${height}-full-pane")
            for (number in 23 downTo 12) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Episode $number is reachable in the phone pane") { focused("Phone pane episode $number") }
            }
            focus("Season 1")
            await("Season toolbar accepts focus while preserving the nested cursor") { focused("Season 1") }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Toolbar Up restores Play in the artwork") { focused("Play latest") && find("Back")?.isVisibleToUser == true }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Header re-enters the toolbar") { focused("Season 1") && alignedToolbar() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Re-entering episodes restores the offscreen middle selection") { focused("Phone pane episode 12") }
            focus("Season 1")
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Header returns for secondary action navigation") { focused("Play latest") }
            for (label in listOf("Add to My List", "Watch trailer", "Related", "Series information")) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Phone hero reaches $label") { focused(label) }
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Info Down enters the episode toolbar") { focused("Season 1") && alignedToolbar() }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Toolbar Up restores the last Info action") { focused("Series information") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Series information opens with Close focused") { focused("Close") }
            key(KeyEvent.KEYCODE_BACK)
            await("Info dismissal preserves the originating action") { find("Close") == null && focused("Series information") }
            ins.setInTouchMode(true)
            val rect = frame.get()
            swipe(rect.exactCenterX(), rect.bottom - 40 * density, rect.top + 90 * density)
            await("A touch swipe can collapse the hero into the full pane") { alignedToolbar() }
            val season = bounds(checkNotNull(owner("Season 1")))
            swipe(season.exactCenterX(), season.exactCenterY(), (rect.bottom - 40 * density).coerceAtLeast(season.exactCenterY()))
            await("A downward toolbar swipe can reveal the artwork again") { find("Play latest")?.isVisibleToUser == true }
            assertTrue("Navigating and scrolling never starts playback", plays.isEmpty())
        } finally { ins.runOnMainSync { activity.finish() }; ins.setInTouchMode(true) }
    }
}
