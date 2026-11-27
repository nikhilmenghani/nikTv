package com.nikhil.niktv.torrent

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.discovery.NativeDiscoveryFocusRestorer
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.PlaybackFixtureHostActivity
import com.nikhil.niktv.ui.InitialFocusEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Actual Home rails, with deterministic delayed shelves rather than network timing. */
class TorrentHomeScrollFocusDeviceTest {
    @Test fun tvDownNeverWrapsToHeaderWhileRowsLoad() = checkHome(false)
    @Test fun mobileLandscapeDownNeverWrapsToHeaderWhileRowsLoad() = checkHome(true)

    private fun checkHome(short: Boolean) {
        val ins = InstrumentationRegistry.getInstrumentation()
        check(ins.targetContext.packageName.endsWith(".regression"))
        ins.setInTouchMode(false)
        val host = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val sections = HOME_SECTIONS
        val data = mutableStateOf(TorrentHomeState(shelves = sections.mapIndexed { i, section ->
            TorrentHomeShelf(section, if (i == 5) emptyList() else listOf(DiscoveryCard(i + 1, section.series, "Tile $i", date = "2020-01-01")),
                loading = i == 5, loaded = i != 5)
        }))
        val requests = ConcurrentHashMap<String, FocusRequester>()
        val focus = AtomicReference<String?>()
        val events = CopyOnWriteArrayList<String>()
        val loadCalls = AtomicInteger()
        val nativeFocusRestorer = NativeDiscoveryFocusRestorer()
        fun tile(i: Int) = "home:${sections[i].name}:${if (sections[i].series) "tv" else "movie"}:${i + 1}"
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(50) }
            fail("$label; focus=$focus; events=$events")
        }
        fun key(code: Int) { ins.sendKeyDownUpSync(code); ins.waitForIdleSync() }
        fun visibleText(label: String): Boolean {
            if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
            fun contains(node: AccessibilityNodeInfo?): Boolean {
                node ?: return false
                if (node.text?.contains(label) == true || node.contentDescription?.contains(label) == true) return true
                return (0 until node.childCount).any { contains(node.getChild(it)) }
            }
            return contains(ins.uiAutomation.rootInActiveWindow)
        }
        fun settledPosition(k: String): Float {
            var previous = Float.NaN
            var stableFrames = 0
            val end = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < end) {
                val value = AtomicReference<Float?>()
                ins.runOnMainSync {
                    host.window.decorView.findFocus()?.takeIf { focus.get() == k }?.let { view ->
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        value.set(location[1].toFloat())
                    }
                }
                val current = value.get()
                if (current != null) {
                    stableFrames = if (current == previous) stableFrames + 1 else 0
                    if (stableFrames >= 4) return current
                    previous = current
                }
                Thread.sleep(50)
            }
            throw AssertionError("Viewport did not settle for $k")
        }
        try {
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                val cfg = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = if (short) 800 else 960
                    screenHeightDp = if (short) 360 else 540
                    smallestScreenWidthDp = if (short) 360 else 600
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                    uiMode = if (short) Configuration.UI_MODE_TYPE_NORMAL else Configuration.UI_MODE_TYPE_TELEVISION
                }
                CompositionLocalProvider(LocalConfiguration provides cfg) {
                    var pending by remember { mutableStateOf<String?>(null) }
                    val entryTarget = pending
                    val entryPolicy = remember(entryTarget) { InitialFocusEntry() }
                    fun request(k: String) = requests.getOrPut(k) { FocusRequester() }
                    fun restore() {
                        val k = entryTarget ?: return
                        entryPolicy.request {
                            val success = runCatching { requests[k]?.requestFocus() == true }.getOrDefault(false)
                            if (success) pending = null
                            success
                        }
                    }
                    fun modifier(k: String) = Modifier.focusRequester(request(k)).onPlaced { restore() }
                        .onFocusChanged {
                            if (it.isFocused) { focus.set(k); events += k }
                            else if (focus.compareAndSet(k, null)) events += "lost:$k"
                        }
                    SideEffect {
                        restore()
                    }
                    Column(Modifier.size(cfg.screenWidthDp.dp, cfg.screenHeightDp.dp)) {
                        DiscoveryAction("Search", modifier = modifier("search"), onClick = {})
                        TorrentHomeContent(data.value, TorrentLibraryState(),
                            TorrentHomeActions({ _, _ -> }, { _, _ -> }, {}, { if (it == sections.last()) loadCalls.incrementAndGet() },
                                {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }),
                            TorrentHomeFocus(::modifier, ::request, { restore() }, { k ->
                                if (focus.getAndSet(k) != k) events += k
                            }, { k, prepare ->
                                pending = k; prepare(); restore()
                            }), pending, Modifier.weight(1f),
                            TorrentDiscoveryConfiguration(sections, genres = false, providers = false),
                            series = false, showLibrary = false, presentation = {}, contentFocused = true,
                            popup = false, reduceMotion = true, nativeFocusRestorer = nativeFocusRestorer)
                    }
                }
            } } }
            await("Hero attached") { requests["home:hero"] != null }
            ins.runOnMainSync { requests.getValue("home:hero").requestFocus() }
            await("Hero focused") { focus.get() == "home:hero" }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("First tile focused") { focus.get() == tile(0) }
            for (i in 1..4) {
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down reaches row $i") { focus.get() == tile(i) }
            }
            settledPosition(tile(4))
            events.clear()
            val before = settledPosition(tile(4))
            events.clear()
            val loadsBefore = loadCalls.get()
            repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            assertTrue("Down requests the pending row", loadCalls.get() > loadsBefore)
            assertEquals("Loading keeps the current tile", tile(4), focus.get())
            assertTrue("No intermediate header or fallback focus: $events", events.isEmpty())
            // A row publication and artwork change must not steal focus either.
            ins.runOnMainSync {
                data.value = data.value.copy(shelves = data.value.shelves.mapIndexed { i, shelf ->
                    if (i == 5) shelf.copy(cards = listOf(DiscoveryCard(6, shelf.section.series, "Tile 5", date = "2020-01-01")), loading = false, loaded = true)
                    else shelf.copy(cards = shelf.cards.map { it.copy(poster = "", title = "Updated ${it.title}") })
                })
            }
            // The next rail may legitimately remain outside a short viewport.
            // Observe publication on the visible focused tile before pressing Down.
            await("Visible posters reflect published data") {
                val label = AtomicReference<String?>()
                ins.runOnMainSync { label.set(host.window.decorView.findFocus()?.contentDescription?.toString()) }
                label.get()?.contains("Updated Tile 4") == true
            }
            ins.waitForIdleSync()
            assertEquals("Publishing data retains focus", tile(4), focus.get())
            assertTrue("Publishing never focuses a fallback: $events", events.isEmpty())
            assertEquals("Publishing retains the viewport", before, settledPosition(tile(4)), 1f)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Next Down enters the loaded row") { focus.get() == tile(5) }
            val bottom = settledPosition(tile(5))
            events.clear()
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            assertEquals("Bottom Down retains the last tile", tile(5), focus.get())
            assertTrue("No intermediate focus while pressing Down at the bottom: $events", events.isEmpty())
            assertEquals("Bottom Down retains the viewport", bottom, settledPosition(tile(5)), 1f)
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up remains usable") { focus.get() == tile(4) }
            val savedTop = settledPosition(tile(4))
            ins.runOnMainSync { requests.getValue("search").requestFocus() }
            await("Header focus was explicitly selected") { focus.get() == "search" }
            events.clear()
            ins.runOnMainSync { assertTrue(nativeFocusRestorer.restore()) }
            await("Feed entry restores its actual native tile") { focus.get() == tile(4) }
            assertEquals("Entry does not flash the hero or another poster", listOf(tile(4)), events.filterNot { it.startsWith("lost:") })
            assertEquals("Entry keeps the native viewport", savedTop, settledPosition(tile(4)), 1f)
        } finally { ins.runOnMainSync { host.finish() }; ins.setInTouchMode(true) }
    }
}
