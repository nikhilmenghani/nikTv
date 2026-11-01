package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewConfiguration
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Native renderer input coverage; fixture package only, with no personal data or network images. */
class NativeDiscoveryRowsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(30)
        }
        fail(message)
    }
    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    @Test fun nativeRowsKeepColumnFocusAndSeparateClickFromLongPress() = verifyNativeInput(false)
    @Test fun nativeMotionKeepsColumnFocusAndSeparateClickFromLongPress() = verifyNativeInput(true)
    // TV remote coverage is separate from the full touch/accessibility contract above.
    @Test fun nativeRowsRemoteOnlyHeaderAndReturn() = verifyNativeInput(false, remoteOnly = true)
    @Test fun nativeMotionRemoteOnlyHeaderAndReturn() = verifyNativeInput(true, remoteOnly = true)

    private fun verifyNativeInput(animateFocus: Boolean, remoteOnly: Boolean = false) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val entry = FocusRequester()
        val header = FocusRequester()
        val ready = AtomicBoolean()
        val headerFocused = AtomicBoolean()
        val headerOptionsFocused = AtomicBoolean()
        val headerActions = AtomicInteger()
        val clicks = AtomicInteger()
        val longClicks = AtomicInteger()
        val showRows = mutableStateOf(true)
        val rows = (0..3).map { row -> NativeDiscoveryRow("row$row", "Native row $row",
            (0..7).map { column -> NativeDiscoveryItem("item$column", "Native episode $row $column", "Subtitle",
                onClick = { clicks.incrementAndGet() }, onLongClick = { longClicks.incrementAndGet() }) }) }
        fun nativeRoot(): View? {
            var result: View? = null
            instrumentation.runOnMainSync {
                fun walk(view: View): View? {
                    if (view.javaClass.simpleName == "NativeDiscoveryScroll") return view
                    if (view is ViewGroup) for (index in 0 until view.childCount)
                        walk(view.getChildAt(index))?.let { return it }
                    return null
                }
                result = walk(host.window.decorView)
            }
            return result
        }
        fun focused(): String? {
            var result: String? = null
            instrumentation.runOnMainSync { result = host.currentFocus?.tag as? String }
            return result
        }
        try {
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync {
                host.window.decorView.isSoundEffectsEnabled = false
                host.window.decorView.isHapticFeedbackEnabled = false
                host.setContent { MaterialTheme {
                    Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
                    SideEffect { ready.set(true) }
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Native header", Modifier.weight(1f).fillMaxHeight().focusRequester(header)
                            .onFocusChanged { headerFocused.set(it.isFocused) }
                            .onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                                    entry.requestFocus(); true
                                } else false
                            }.focusable())
                        Text("Header options", Modifier.width(120.dp).fillMaxHeight()
                            .onFocusChanged { headerOptionsFocused.set(it.isFocused) }
                            .clickable { headerActions.incrementAndGet() })
                        }
                        val saved = rememberSaveableStateHolder()
                        if (showRows.value) saved.SaveableStateProvider("native") {
                            NativeDiscoveryRows(rows, Modifier.weight(1f).fillMaxWidth(), entry, up = { header.requestFocus() }, animateFocus = animateFocus)
                        } else Text("Detail destination")
                    }
                    } }
                } }
            }
            await("Fixture ready") { ready.get() }
            instrumentation.runOnMainSync { entry.requestFocus() }
            await("Native first poster owns focus") { focused() == "row0:item0" }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters the next poster") { focused() == "row0:item1" }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down preserves the column") { focused() == "row1:item1" }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left stays in the current row") { focused() == "row1:item0" }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("Up restores the previous row selection") { focused() == "row0:item1" }
            key(KeyEvent.KEYCODE_DPAD_UP)
            await("First row Up returns to the header") { headerFocused.get() }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right while in header moves to header options") { headerOptionsFocused.get() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Center activates header options instead of a native poster") { headerActions.get() == 1 }
            assertEquals("Header input must not play native content", 0, clicks.get())
            assertEquals("Header input must not long-press native content", 0, longClicks.get())
            var nativeSelection: Any? = null
            val native = nativeRoot()
            instrumentation.runOnMainSync {
                nativeSelection = native?.javaClass?.getDeclaredField("currentFocusId")?.apply { isAccessible = true }?.get(native)
            }
            assertEquals("Header navigation must retain the native return selection", "row0:item1", nativeSelection)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left returns to the primary header control") { headerFocused.get() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Reentry restores the native tile") { focused() == "row0:item1" }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Click reaches its action once") { clicks.get() == 1 }
            assertEquals(0, longClicks.get())
            val down = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150L)
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            await("Long press reaches its distinct action") { longClicks.get() == 1 }
            assertEquals("Releasing a long press must not also click", 1, clicks.get())
            assertEquals("row0:item1", focused())
            // Invoke the native update and header transfer in one UI turn, before
            // its posted restore runs. This deterministically covers the feed-update race.
            fun updateNativeFeed(title: String, leaveForHeader: Boolean) {
                instrumentation.runOnMainSync {
                    var owner: View? = host.currentFocus
                    while (owner != null && owner !is ScrollView) owner = owner.parent as? View
                    val native = requireNotNull(owner)
                    val method = native.javaClass.getDeclaredMethod("render", List::class.java).apply { isAccessible = true }
                    method.invoke(native, rows.mapIndexed { index, row -> if (index == 0) row.copy(title = title) else row })
                    if (leaveForHeader) header.requestFocus()
                }
            }
            updateNativeFeed("Updated within native island", false)
            instrumentation.waitForIdleSync()
            await("Feed update preserves focus when native still owns it") { focused() == "row0:item1" }
            updateNativeFeed("Updated before header focus", true)
            instrumentation.waitForIdleSync()
            assertTrue("Queued feed restoration must not steal header focus", headerFocused.get())
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Header Down still restores the native selection") { focused() == "row0:item1" }
            if (!remoteOnly) {
            fun nativeCard(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.contentDescription?.toString() == "Native episode 0 0, Subtitle") return node
                for (index in 0 until node.childCount) nativeCard(node.getChild(index))?.let { return it }
                return null
            }
            await("Native cards are exposed to accessibility alongside the Compose header") {
                nativeCard(instrumentation.uiAutomation.rootInActiveWindow)?.isVisibleToUser == true
            }
            instrumentation.setInTouchMode(true)
            val bounds = Rect()
            instrumentation.runOnMainSync {
                val card = host.window.decorView.findViewWithTag<View>("row0:item0")
                assertNotNull(card)
                assertTrue(card.isShown)
                val location = IntArray(2)
                card.getLocationOnScreen(location)
                bounds.set(location[0], location[1], location[0] + card.width, location[1] + card.height)
            }
            val tapTime = SystemClock.uptimeMillis()
            val tapDown = MotionEvent.obtain(tapTime, tapTime, MotionEvent.ACTION_DOWN,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            val tapUp = MotionEvent.obtain(tapTime, tapTime + 40L, MotionEvent.ACTION_UP,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                instrumentation.sendPointerSync(tapDown)
                instrumentation.sendPointerSync(tapUp)
            } finally { tapDown.recycle(); tapUp.recycle() }
            await("A single physical touch activates without an initial focus-only tap") { clicks.get() == 2 }
            assertEquals(1, longClicks.get())
            instrumentation.setInTouchMode(false)
            instrumentation.runOnMainSync { entry.requestFocus() }
            await("Remote reentry restores the tile selected by touch") { focused() == "row0:item0" }
            } else {
                assertEquals("Remote-only coverage retains its pre-touch native selection", "row0:item1", focused())
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
            val returnTarget = focused()
            assertNotNull(returnTarget)
            instrumentation.runOnMainSync { showRows.value = false }
            await("Native view is actually removed before returning from details") { nativeRoot() == null }
            instrumentation.runOnMainSync { showRows.value = true }
            await("Returned native view is attached and laid out before focus entry") {
                nativeRoot()?.let { it.isAttachedToWindow && it.isLaidOut && it.width > 0 && it.height > 0 } == true
            }
            instrumentation.runOnMainSync { entry.requestFocus() }
            try {
                await("Detail return restores the originating native tile") { focused() == returnTarget }
                await("Restored native tile is fully visible rather than only logically focused") {
                    val native = nativeRoot()
                    var fullyVisible = false
                    instrumentation.runOnMainSync {
                        val target = host.currentFocus
                        if (native != null && target != null && target.tag == returnTarget) {
                            val viewportLocation = IntArray(2)
                            val tileLocation = IntArray(2)
                            native.getLocationOnScreen(viewportLocation)
                            target.getLocationOnScreen(tileLocation)
                            val viewport = Rect(viewportLocation[0], viewportLocation[1],
                                viewportLocation[0] + native.width, viewportLocation[1] + native.height)
                            val tile = Rect(tileLocation[0], tileLocation[1],
                                tileLocation[0] + target.width, tileLocation[1] + target.height)
                            fullyVisible = target.isShown && tile.width() > 0 && tile.height() > 0 && viewport.contains(tile)
                        }
                    }
                    fullyVisible
                }
            } catch (failure: AssertionError) {
                var diagnostic = ""
                instrumentation.runOnMainSync {
                    fun nativeView(view: View): View? {
                        if (view.javaClass.simpleName == "NativeDiscoveryScroll") return view
                        if (view is ViewGroup) for (index in 0 until view.childCount)
                            nativeView(view.getChildAt(index))?.let { return it }
                        return null
                    }
                    val native = nativeView(host.window.decorView)
                    fun field(name: String): Any? = native?.let { target ->
                        runCatching { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) }
                            .getOrElse { it.javaClass.simpleName }
                    }
                    val callback = field("onPosition")
                    val holder = callback?.javaClass?.declaredFields?.firstOrNull {
                        it.type.simpleName == "NativeRowsPosition"
                    }?.let { runCatching { it.isAccessible = true; it.get(callback) }.getOrNull() }
                    fun holderField(name: String): Any? = holder?.let {
                        runCatching { it.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(it) }.getOrNull()
                    }
                    val actual = host.currentFocus
                    diagnostic = "expected=$returnTarget actual=${actual?.javaClass?.simpleName}:${actual?.tag} " +
                        "nativePresent=${native != null} nativeFocus=${native?.isFocused}/${native?.hasFocus()} " +
                        "attached=${native?.isAttachedToWindow} window=${native?.hasWindowFocus()} touch=${native?.isInTouchMode} " +
                        "restored=${field("restoredFocus")} current=${field("currentFocusId")} pending=${field("initialFocusPending")} " +
                        "revision=${field("renderRevision")} scroll=${native?.scrollY} restoredTop=${field("restoredTop")} " +
                        "savedFocus=${holderField("focus")} savedTop=${holderField("top")}"
                }
                throw AssertionError("${failure.message}; $diagnostic", failure)
            }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
