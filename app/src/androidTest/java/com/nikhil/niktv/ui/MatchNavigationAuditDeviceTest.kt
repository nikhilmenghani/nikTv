package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.TmdbSeries
import com.nikhil.niktv.discovery.DiscoveryMatchScreen
import com.nikhil.niktv.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MatchNavigationAuditDeviceTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.clearCache()
        return i.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == value || node.contentDescription?.toString() == value) return node
        for (index in 0 until node.childCount) find(node.getChild(index), value)?.let { return it }
        return null
    }
    private fun label(value: String) = find(root(), value)
    private fun owner(value: String): AccessibilityNodeInfo? {
        var node = label(value)
        while (node != null && !node.isClickable) node = node.parent
        return node
    }
    private fun focused(value: String) = owner(value)?.let { it == root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } == true
    private fun key(code: Int) = i.sendKeyDownUpSync(code)
    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) { if (ready()) return; Thread.sleep(60) }
        fail(message)
    }
    private fun state(series: Boolean, cards: List<MediaItem>, loading: Boolean = false) = NikTvState(
        savedProfile = PortalProfile("Match navigation", "https://matches.invalid", ""), restoring = false,
        discoverySurface = if (series) DashboardSurface.SERIES else DashboardSurface.MOVIES,
        movieMatchSelection = if (series) null else TmdbMovie(810120, "Match movie", "Match movie", null, null, null, "2020-01-01", null),
        seriesMatchSelection = if (series) TmdbSeries(810121, "Match series", "Match series", null, null, null, "2020-01-01", null) else null,
        movieMatchCandidates = if (series) emptyList() else cards,
        seriesMatchCandidates = if (series) cards else emptyList(), movieMatchLoadingMore = loading)
    private fun cards() = (1..6).map { MediaItem("match-$it", "Provider version $it", null, null) }
    private fun fixture(phone: Boolean, initial: NikTvState,
        verify: (MutableState<NikTvState>, AtomicReference<String?>, AtomicBoolean) -> Unit) {
        check(context.packageName.endsWith(".regression"))
        val current = mutableStateOf(initial)
        val chosen = AtomicReference<String?>(null)
        val closed = AtomicBoolean()
        val host = i.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            i.setInTouchMode(false)
            i.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val config = Configuration(LocalConfiguration.current).apply {
                    if (phone) { screenWidthDp = 360; screenHeightDp = 680; smallestScreenWidthDp = 360
                        orientation = Configuration.ORIENTATION_PORTRAIT
                        uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_NORMAL }
                }
                BackHandler { closed.set(true) }
                CompositionLocalProvider(LocalConfiguration provides config) {
                    Box(Modifier.then(if (phone) Modifier.widthIn(max = 360.dp) else Modifier).fillMaxHeight()) {
                        DiscoveryMatchScreen(current.value, { closed.set(true) }, {}, {},
                            { chosen.set("movie:${it.id}") }, { chosen.set("series:${it.id}") })
                    }
                }
            } } }
            verify(current, chosen, closed)
        } finally { i.runOnMainSync { host.finish() } }
    }
    @Test fun phoneSingleColumnMovieAndSeriesCandidatesNavigateAndChooseTheLastItem() {
        for (series in listOf(false, true)) {
            val items = cards()
            fixture(true, state(series, items)) { _, chosen, closed ->
                await("First match has initial focus") { focused(items[0].title) }
                val first = Rect().also { checkNotNull(owner(items[0].title)).getBoundsInScreen(it) }
                val second = Rect().also { checkNotNull(owner(items[1].title)).getBoundsInScreen(it) }
                assertEquals("Phone matches use one column", first.left, second.left)
                assertTrue(second.top >= first.bottom)
                for (item in items.drop(1)) { key(KeyEvent.KEYCODE_DPAD_DOWN); await("Down reaches ${item.title}") { focused(item.title) } }
                NavigationAuditEvidence.capture("matches-phone-${if (series) "series" else "movie"}-last")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Choosing last row uses the correct media callback") { chosen.get() == "${if (series) "series" else "movie"}:${items.last().id}" }
                for (item in items.dropLast(1).reversed()) { key(KeyEvent.KEYCODE_DPAD_UP); await("Up reaches ${item.title}") { focused(item.title) } }
                key(KeyEvent.KEYCODE_DPAD_UP)
                await("First match Up reaches phone header") { focused("Switch profile: Match navigation") }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Phone header Down returns to first match") { focused(items.first().title) }
                key(KeyEvent.KEYCODE_BACK)
                await("Back returns to chooser parent") { closed.get() }
            }
        }
    }
    @Test fun loadingEmptyResultsHaveSafeHeaderNavigationAndLoadedRowsDoNotStealFocus() {
        val items = cards()
        fixture(true, state(false, emptyList(), loading = true)) { current, chosen, closed ->
            await("Empty chooser focuses header") { focused("Switch profile: Match navigation") }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down is safe before any result exists") { focused("Switch profile: Match navigation") }
            NavigationAuditEvidence.capture("matches-empty-loading")
            i.runOnMainSync { current.value = state(false, items.take(2), loading = true) }
            await("New results receive their first focus") { focused(items[0].title) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Second result is reachable while more results load") { focused(items[1].title) }
            i.runOnMainSync { current.value = state(false, items) }
            Thread.sleep(180)
            assertTrue("Appending results preserves the current match", focused(items[1].title))
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Newly appended third result is reachable") { focused(items[2].title) }
            assertNull("Navigation never implicitly plays a candidate", chosen.get())
            i.runOnMainSync { current.value = state(false, emptyList()) }
            await("Cleared results restore safe header focus") { focused("Switch profile: Match navigation") }
            key(KeyEvent.KEYCODE_BACK)
            await("Back works from empty results") { closed.get() }
        }
    }
    @Test fun tabletSeriesTwoColumnMatchesReturnThroughHeaderAndUseSeriesCallback() {
        assumeTrue(context.resources.configuration.smallestScreenWidthDp >= 600)
        val items = cards()
        fixture(false, state(true, items)) { _, chosen, closed ->
            await("Series first match has focus") { focused(items[0].title) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await("Right reaches second column") { focused(items[1].title) }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await("Down keeps the second column") { focused(items[3].title) }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await("Last second-column match is reachable") { focused(items[5].title) }
            key(KeyEvent.KEYCODE_DPAD_CENTER); await("Series chooser uses series identity") { chosen.get() == "series:${items[5].id}" }
            key(KeyEvent.KEYCODE_DPAD_UP); await("Up reaches fourth match") { focused(items[3].title) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up returns to the second first-row match") { focused(items[1].title) }
            key(KeyEvent.KEYCODE_DPAD_UP); await("Up reaches Series navigation") { focused("Series") }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await("Header Down reaches first result") { focused(items[0].title) }
            NavigationAuditEvidence.capture("matches-tablet-series-grid")
            key(KeyEvent.KEYCODE_BACK); await("Back leaves series chooser") { closed.get() }
        }
    }
}
