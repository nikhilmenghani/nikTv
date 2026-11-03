package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import org.junit.Assert.*
import org.junit.Test

class RecycledDiscoveryDeviceTest {
    @Test fun largeFeedRecyclesAndRestoresSelectionAcrossDetails() = verify(false, false)
    @Test fun styledFeedRetainsFocusAndFullyVisibleReturn() = verify(true, true)
    @Test fun heroScrollsWithFeedAndKeepsHeaderAndDetailReturn() = verify(true, true, true)
    @Test fun exactNativeHeroBoundsPreserveNavigationAndReturn() = verify(true, true, true, true)
    @Test fun homeHeightHeroKeepsReversedFirstRailFullyVisible() = verify(true, true, true, true, tallHero = true)
    @Test fun heldRemoteActivationDoesNotPlayOrLeakRelease() = verify(true, true, exerciseHold = true)
    @Test fun memoryPressureDropsLayersWithoutLosingNavigation() = verify(true, true, exercisePressure = true)
    @Test fun rapidHeaderEntryDoesNotReviveAnObsoleteHeroRequest() = verify(true, true, withHero = true, exerciseRapidEntry = true)
    @Test fun detailsReturnPreservesBothViewportAxes() = verify(true, true, withHero = true, exerciseViewport = true)
    @Test fun reducedFocusMotionRetainsNavigationAndRestoration() = verify(true, true, withHero = true, reducedFocusMotion = true)
    @Test fun homeCaptionsFitLongTitlesAndLargerFonts() {
        val i = InstrumentationRegistry.getInstrumentation()
        val c = i.targetContext
        check(c.packageName.endsWith(".regression"))
        val host = i.startActivitySync(Intent(c, PlaybackFixtureHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val title = "Taarak Mehta Ka Ooltah Chashmah"
        val subtitle = "Season 49 · Episode 4838 · 22 min left"
        try {
            for (scale in listOf(1f, 1.6f)) {
                val configuration = android.content.res.Configuration(c.resources.configuration).apply { fontScale = scale }
                val context = host.createConfigurationContext(configuration)
                i.runOnMainSync { host.setContent { MaterialTheme {
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context,
                        androidx.compose.ui.platform.LocalConfiguration provides configuration) {
                        RecycledDiscoveryRows(listOf(NativeDiscoveryRow("captions", "Captions", listOf(
                            NativeDiscoveryItem("long", title, subtitle, onClick = {}, source = DiscoveryTileSource.IPTV)))),
                            Modifier.fillMaxSize(), remember { FocusRequester() }, {}, containedCaptions = true)
                    }
                } } }
                val end = SystemClock.uptimeMillis() + 6000
                var found = false
                while (!found && SystemClock.uptimeMillis() < end) {
                    i.runOnMainSync {
                        val texts = mutableListOf<android.widget.TextView>()
                        fun collect(view: View) {
                            if (view is android.widget.TextView && view.text.toString() in listOf(title, subtitle)) texts += view
                            if (view is ViewGroup) for (n in 0 until view.childCount) collect(view.getChildAt(n))
                        }
                        collect(host.window.decorView)
                        found = texts.size == 2 && texts.all { it.layout != null && it.height > 0 }
                        if (found) texts.forEach { text ->
                            val rect = Rect()
                            assertTrue("Full caption visible at font scale $scale", text.getGlobalVisibleRect(rect) && rect.height() == text.height)
                            assertEquals("No caption ellipsis at font scale $scale", 0, text.layout.getEllipsisCount(text.layout.lineCount - 1))
                        }
                    }
                    if (!found) Thread.sleep(30)
                }
                assertTrue("Caption laid out at scale $scale", found)
            }
        } finally { i.runOnMainSync { host.finish() } }
    }
    @Test fun rowActionsRemoveNeighborsWithoutLosingRemoteFocus() {
        val i = InstrumentationRegistry.getInstrumentation()
        val c = i.targetContext
        check(c.packageName.endsWith(".regression"))
        val host = i.startActivitySync(Intent(c, PlaybackFixtureHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val remaining = mutableStateOf(listOf("a", "b", "c"))
        val entry = FocusRequester()
        fun awaitTag(expected: String) {
            val end = SystemClock.uptimeMillis() + 6000
            while (SystemClock.uptimeMillis() < end) {
                var tag: String? = null
                i.runOnMainSync { tag = host.currentFocus?.tag as? String }
                if (tag == expected) return
                Thread.sleep(30)
            }
            fail("Expected native focus $expected after row action")
        }
        try {
            i.setInTouchMode(false)
            i.runOnMainSync { host.setContent { MaterialTheme {
                RecycledDiscoveryRows(remaining.value.map { id -> NativeDiscoveryRow(id, id,
                    listOf(NativeDiscoveryItem("first", id, onClick = {})), actionLabel = "Remove $id",
                    action = { remaining.value = remaining.value - id }) }, Modifier.fillMaxSize(), entry, {})
            } } }
            i.waitForIdleSync()
            i.runOnMainSync { entry.requestFocus() }
            awaitTag("a:first")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); awaitTag("a:first")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); awaitTag("a:action")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); awaitTag("b:first")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); awaitTag("c:first")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU); awaitTag("c:action")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); awaitTag("b:first")
            i.runOnMainSync { remaining.value = listOf("a", "b", "c") }
            awaitTag("b:first")
        } finally { i.runOnMainSync { host.finish() } }
    }
    @Test fun rowTailControlsAndLateTilesKeepFocusStable() {
        val i = InstrumentationRegistry.getInstrumentation()
        val c = i.targetContext
        check(c.packageName.endsWith(".regression"))
        val host = i.startActivitySync(Intent(c, PlaybackFixtureHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val entry = FocusRequester()
        val loaded = mutableStateOf(false)
        val opened = java.util.concurrent.atomic.AtomicInteger()
        fun awaitTag(expected: String) {
            val end = SystemClock.uptimeMillis() + 6000
            while (SystemClock.uptimeMillis() < end) {
                var actual: String? = null
                i.runOnMainSync { actual = host.currentFocus?.tag as? String }
                if (actual == expected) return
                Thread.sleep(30)
            }
            var actual: String? = null
            i.runOnMainSync { actual = host.currentFocus?.tag as? String }
            fail("Expected $expected; actual=$actual")
        }
        try {
            i.setInTouchMode(false)
            i.runOnMainSync { host.setContent { MaterialTheme {
                RecycledDiscoveryRows(listOf(
                    NativeDiscoveryRow("continue", "Continue Watching", listOf(NativeDiscoveryItem("one", "One", onClick = {})), viewAll = { opened.incrementAndGet() }),
                    NativeDiscoveryRow("new", "New category", if (loaded.value) listOf(NativeDiscoveryItem("first", "First", onClick = {})) else emptyList(),
                        actionLabel = "Remove", action = {}, viewAll = { opened.incrementAndGet() }),
                    NativeDiscoveryRow("long", "Long category", (0..39).map { NativeDiscoveryItem("i$it", "Item $it", onClick = {}) },
                        actionLabel = "Remove long", action = {}, viewAll = { opened.incrementAndGet() })
                ), Modifier.fillMaxSize(), entry, {})
            } } }
            i.waitForIdleSync(); i.runOnMainSync { entry.requestFocus() }
            awaitTag("continue:one")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); awaitTag("continue:all")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(1, opened.get())
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); awaitTag("continue:one")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); awaitTag("new:loading")
            val handoff = mutableListOf<String>()
            val observer = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, next -> (next?.tag as? String)?.let(handoff::add) }
            i.runOnMainSync { host.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(observer); loaded.value = true }
            awaitTag("new:first")
            i.runOnMainSync {
                host.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(observer)
                assertFalse("Loading must not visit a tail control: $handoff", handoff.any { it == "new:all" || it == "new:action" })
            }
            Thread.sleep(250); awaitTag("new:first")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); awaitTag("new:all")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); awaitTag("new:action")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); awaitTag("new:all")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); awaitTag("continue:one")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); awaitTag("new:first")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); awaitTag("long:i0")
            for (index in 1..39) { i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); awaitTag("long:i$index") }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); awaitTag("long:all")
            i.runOnMainSync {
                val button = host.currentFocus!!
                val rect = Rect(); assertTrue(button.getGlobalVisibleRect(rect))
                assertEquals("Tail focus is fully visible", button.width, rect.width())
                val holder = button.parent as View
                val rail = holder.parent as RecyclerView
                val last = rail.findViewHolderForAdapterPosition(39)!!.itemView
                val lastBounds = Rect(); last.getGlobalVisibleRect(lastBounds)
                assertTrue("View all appears after the last media", rect.left >= lastBounds.right)
            }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); awaitTag("long:action")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); awaitTag("new:first")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); awaitTag("long:i39")
        } finally { i.runOnMainSync { host.finish() } }
    }
    private fun verify(containedCaptions: Boolean, cardShadows: Boolean, withHero: Boolean = false, fixedHero: Boolean = false, exerciseHold: Boolean = false, exercisePressure: Boolean = false, exerciseRapidEntry: Boolean = false, exerciseViewport: Boolean = false, reducedFocusMotion: Boolean = false, tallHero: Boolean = false) {
        val i = InstrumentationRegistry.getInstrumentation()
        val c = i.targetContext
        check(c.packageName.endsWith(".regression"))
        val host = i.startActivitySync(Intent(c, PlaybackFixtureHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fun await(message: String, block: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 6000
            while (SystemClock.uptimeMillis() < end) { if (block()) return; Thread.sleep(30) }
            var actual: Any? = null
            i.runOnMainSync { actual = host.currentFocus?.let { view ->
                val rect = Rect(); view.getGlobalVisibleRect(rect)
                "${view.tag}, visible=$rect, size=${view.width}x${view.height}"
            } }
            i.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(c.cacheDir, "phase18-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            fail("$message; actual=$actual windowFocused=${host.window.decorView.hasWindowFocus()}")
        }
        fun focus(): String? { var value: String? = null; i.runOnMainSync { value = host.currentFocus?.tag as? String }; return value }
        fun fullyVisible(): Boolean {
            var visible = false
            i.runOnMainSync {
                host.currentFocus?.let { view ->
                    val rect = Rect()
                    visible = view.getGlobalVisibleRect(rect) && rect.width() >= view.width && rect.height() >= view.height
                }
            }
            return visible
        }
        val entry = FocusRequester(); val header = FocusRequester()
        val heroEntry = FocusRequester()
        val activity = java.util.concurrent.atomic.AtomicReference<HeroActivity>()
        val heroFocused = java.util.concurrent.atomic.AtomicBoolean(false)
        val headerFocused = java.util.concurrent.atomic.AtomicBoolean(false)
        val rowsShown = mutableStateOf(true)
        val restoreSelection = mutableStateOf(true)
        val nativeRestorer = NativeDiscoveryFocusRestorer()
        val holds = java.util.concurrent.atomic.AtomicInteger()
        val rows = (0 until 80).map { r -> NativeDiscoveryRow("r$r", "Row $r", (0 until 40).map { n ->
            NativeDiscoveryItem("i$n", "Item $r $n", onClick = { rowsShown.value = false }, onLongClick = { holds.incrementAndGet() }) }) }
        val heroHeight = if (tallHero) trialHeroFrameHeight(trialHeroBaseHeight(c.resources.configuration.screenHeightDp.toFloat(), true), c.resources.configuration.fontScale) else 200f
        try {
            i.setInTouchMode(false)
            i.runOnMainSync { host.setContent { MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Text("Header", Modifier.height(48.dp).focusRequester(header).onFocusChanged { headerFocused.set(it.isFocused) }.onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { entry.requestFocus(); true } else false
                    }.focusable())
                    val saved = rememberSaveableStateHolder()
                    if (rowsShown.value) saved.SaveableStateProvider("feed") {
                        RecycledDiscoveryRows(rows, Modifier.weight(1f).fillMaxWidth(), entry, { header.requestFocus() },
                            containedCaptions = containedCaptions, cardShadows = cardShadows, focusRestorer = nativeRestorer,
                            animateFocus = !reducedFocusMotion,
                            cacheTileDrawing = exercisePressure,
                            showSkeletons = tallHero,
                            restoreSelection = restoreSelection.value,
                            heroHeightPx = if (fixedHero) (heroHeight * c.resources.displayMetrics.density).toInt() else null,
                            heroActivity = if (withHero) ({ activity.set(it) }) else null,
                            hero = if (withHero) ({ focused, down, up ->
                                Box(Modifier.height(heroHeight.dp)) {
                                    Button({}, Modifier.focusRequester(heroEntry).onFocusChanged {
                                        heroFocused.set(it.isFocused); if (it.isFocused) focused("play")
                                    }.onPreviewKeyEvent {
                                        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                            Key.DirectionDown -> { down(); true }
                                            Key.DirectionUp -> { up(); true }
                                            else -> false
                                        }
                                    }) { Text("Hero play") }
                                }
                            }) else null,
                            heroFocusEntry = { runCatching { heroEntry.requestFocus() }.getOrDefault(false) })
                    } else Text("Detail")
                }
            } } }
            await("Active initial layout") { host.window.decorView.isLaidOut && host.window.decorView.hasWindowFocus() }
            if (exerciseRapidEntry) {
                // Both requests occur before the deferred hero entry frame runs.
                i.runOnMainSync {
                    entry.requestFocus()
                    host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                    host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                }
                await("Newer first-rail selection wins over queued hero entry") { focus() == "r0:i0" }
                Thread.sleep(100)
                assertEquals("Obsolete entry must not reclaim focus", "r0:i0", focus())
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            } else i.runOnMainSync { entry.requestFocus() }
            if (withHero) {
                await("Header Down enters hero") { heroFocused.get() }
                await("Focused hero blocks rotation") { activity.get()?.let { it.visible && it.focused && !it.mayRotate } == true }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            await("First card") { focus() == "r0:i0" }
            if (reducedFocusMotion) i.runOnMainSync { assertEquals(1f, host.currentFocus!!.scaleX, 0f) }
            if (exercisePressure) {
                i.runOnMainSync {
                    val card = host.currentFocus!!
                    assertEquals("Foreground cache active", View.LAYER_TYPE_HARDWARE, card.layerType)
                    var ancestor: android.view.ViewParent? = card.parent
                    while (ancestor != null && ancestor !is android.content.ComponentCallbacks2) ancestor = ancestor.parent
                    assertNotNull("Native cache owns its memory callback", ancestor)
                    (ancestor as android.content.ComponentCallbacks2).onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                    assertEquals("Pressure drops layer without dropping card focus", View.LAYER_TYPE_NONE, card.layerType)
                    assertTrue(card.isFocused)
                }
            }
            if (exerciseHold) {
                i.setInTouchMode(true)
                var restored = false
                i.runOnMainSync { restored = nativeRestorer.restore() }
                assertTrue("Touch-to-remote restoration requests actual card focus", restored)
                await("Native restoration survives touch mode") { focus() == "r0:i0" }
                val down = SystemClock.uptimeMillis()
                i.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
                Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100)
                i.runOnMainSync { assertEquals("No popup before release", 0, holds.get()); assertTrue(rowsShown.value) }
                val up = SystemClock.uptimeMillis()
                i.sendKeySync(KeyEvent(down, up, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
                await("One long action on release") { holds.get() == 1 }
                i.sendKeySync(KeyEvent(down, up + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
                i.runOnMainSync { assertTrue("Release must not also click", rowsShown.value) }
                val canceled = SystemClock.uptimeMillis()
                i.sendKeySync(KeyEvent(canceled, canceled, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Move cancels old press") { focus() == "r1:i0" }
                i.sendKeySync(KeyEvent(canceled, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
                i.runOnMainSync { assertTrue(rowsShown.value); assertEquals(1, holds.get()) }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await("Return after cancel") { focus() == "r0:i0" }
            }
            for (n in 1..15) { i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); await("Horizontal $n") { focus() == "r0:i$n" } }
            if (exerciseViewport) {
                fun settled(): Boolean {
                    var value = true
                    i.runOnMainSync {
                        var view = host.currentFocus
                        while (view != null) {
                            if (view is RecyclerView && view.scrollState != RecyclerView.SCROLL_STATE_IDLE) value = false
                            view = view.parent as? View
                        }
                    }
                    return value
                }
                fun bounds(): Rect = Rect().also { rect -> i.runOnMainSync { host.currentFocus!!.getGlobalVisibleRect(rect) } }
                await("Source viewport settled", ::settled); Thread.sleep(650)
                val before = bounds()
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Detail hides source", { focus() == null })
                i.runOnMainSync { rowsShown.value = true }
                Thread.sleep(200)
                i.runOnMainSync { entry.requestFocus() }
                await("Selected source restored", { focus() == "r0:i15" })
                await("Restored viewport settled", ::settled); Thread.sleep(650)
                val after = bounds()
                assertTrue("Horizontal viewport retained: $before -> $after", kotlin.math.abs(before.left - after.left) <= 2)
                assertTrue("Vertical viewport retained: $before -> $after", kotlin.math.abs(before.top - after.top) <= 2)
            }
            for (r in 1..12) { i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); await("Vertical $r") { focus() == "r$r:i0" } }
            if (withHero) await("Offscreen hero blocks rotation") { activity.get()?.visible == false }
            var cards = 0
            i.runOnMainSync {
                fun count(v: View) { if (v.tag is String) cards++; if (v is ViewGroup) for (n in 0 until v.childCount) count(v.getChildAt(n)) }
                count(host.window.decorView)
            }
            assertTrue("3200 records must not create 3200 views: $cards", cards < 100)
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Detail removes list") { focus() == null }
            i.runOnMainSync { rowsShown.value = true }
            Thread.sleep(200)
            i.runOnMainSync { entry.requestFocus() }
            await("Return identity") { focus() == "r12:i0" }
            await("Returned card fits the viewport") { fullyVisible() }
            for (r in 11 downTo 0) { i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); await("Up $r") { focus() == "r$r:i${if (r == 0) 15 else 0}" } }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            if (withHero) {
                await("First rail Up enters hero") { heroFocused.get() }
                await("Hero focus reveals the entire hero") { activity.get()?.visible == true }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                await("Hero Up reaches header") { headerFocused.get() }
                await("Visible idle hero permits rotation") { activity.get()?.mayRotate == true }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Header reenters hero") { heroFocused.get() }
            }
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Header reentry") { focus() == "r0:i15" }
            repeat(3) { i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN) }
            repeat(3) { i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP) }
            await("Rapid direction reversal retains identity") { focus() == "r0:i15" }
            await("Reversed card fits the viewport") { fullyVisible() }
            if (withHero) {
                i.runOnMainSync { rowsShown.value = false }
                await("Leave feed for menu navigation") { focus() == null }
                i.runOnMainSync { restoreSelection.value = false; rowsShown.value = true }
                Thread.sleep(200)
                i.runOnMainSync { entry.requestFocus() }
                await("Menu entry overrides saved media cursor with hero") { heroFocused.get() }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Hero Down retains the row's saved column") { focus() == "r0:i15" }
            }
        } finally { i.runOnMainSync { host.finish() } }
    }
}
