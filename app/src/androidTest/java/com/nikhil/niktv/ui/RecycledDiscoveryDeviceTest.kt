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
    @Test fun heldRemoteActivationDoesNotPlayOrLeakRelease() = verify(true, true, exerciseHold = true)
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
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); awaitTag("a:action")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); awaitTag("b:first")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); awaitTag("c:first")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU); awaitTag("c:action")
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); awaitTag("b:first")
            i.runOnMainSync { remaining.value = listOf("a", "b", "c") }
            awaitTag("b:first")
        } finally { i.runOnMainSync { host.finish() } }
    }
    private fun verify(containedCaptions: Boolean, cardShadows: Boolean, withHero: Boolean = false, fixedHero: Boolean = false, exerciseHold: Boolean = false) {
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
            fail("$message; actual=$actual")
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
                            restoreSelection = restoreSelection.value,
                            heroHeightPx = if (fixedHero) (200 * c.resources.displayMetrics.density).toInt() else null,
                            heroActivity = if (withHero) ({ activity.set(it) }) else null,
                            hero = if (withHero) ({ focused, down, up ->
                                Box(Modifier.height(200.dp)) {
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
            i.runOnMainSync { entry.requestFocus() }
            if (withHero) {
                await("Header Down enters hero") { heroFocused.get() }
                await("Focused hero blocks rotation") { activity.get()?.let { it.visible && it.focused && !it.mayRotate } == true }
                i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            await("First card") { focus() == "r0:i0" }
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
