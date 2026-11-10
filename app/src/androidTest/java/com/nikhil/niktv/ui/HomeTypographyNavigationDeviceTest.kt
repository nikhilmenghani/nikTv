package com.nikhil.niktv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.discovery.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class HomeTypographyNavigationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun await(label: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < end) { if (check()) return; Thread.sleep(40) }
        fail(label)
    }
    @Test fun recycledHomeTextStaysConsistentAfterDpadBack() = verify(true)
    @Test fun nativeHomeTextStaysConsistentAfterDpadBack() = verify(false)
    private fun verify(recycled: Boolean) {
        check(ins.targetContext.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val detail = mutableStateOf(false)
        val entry = FocusRequester(); val back = FocusRequester(); val play = FocusRequester()
        val backFocused = AtomicBoolean(); val playFocused = AtomicBoolean()
        val rows = listOf(NativeDiscoveryRow("row", "Trending series fixture", listOf(
            NativeDiscoveryItem("item", "Navigation title fixture", "2026", onClick = { detail.value = true }))))
        fun focused(): String? { var result: String? = null; ins.runOnMainSync { result = host.currentFocus?.tag as? String }; return result }
        fun text(label: String): TextView? {
            var result: TextView? = null
            ins.runOnMainSync {
                fun walk(v: View) {
                    if (v is TextView && v.text.toString() == label) result = v
                    if (v is ViewGroup) for (index in 0 until v.childCount) walk(v.getChildAt(index))
                }
                walk(host.window.decorView)
            }
            return result
        }
        fun render(label: String): Bitmap {
            lateinit var result: Bitmap
            val view = requireNotNull(text(label))
            ins.runOnMainSync {
                assertEquals("Caption keeps explicit SemiBold", 600, view.typeface.weight)
                result = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(result))
            }
            return result
        }
        fun screenshot(suffix: String) {
            ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(ins.targetContext.getExternalFilesDir(null), "home-font-${if (recycled) "recycled" else "native"}-$suffix.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                Surface(Modifier.fillMaxSize()) {
                    val saved = rememberSaveableStateHolder()
                    if (detail.value) Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Button({ detail.value = false }, Modifier.focusRequester(back).onFocusChanged { backFocused.set(it.isFocused) }) { Text("Back") }
                        Button({}, Modifier.focusRequester(play).onFocusChanged { playFocused.set(it.isFocused) }) { Text("Play fixture") }
                    } else saved.SaveableStateProvider("home") {
                        if (recycled) RecycledDiscoveryRows(rows, Modifier.fillMaxSize(), entry, {}, containedCaptions = true)
                        else NativeDiscoveryRows(rows, Modifier.fillMaxSize(), entry, {})
                    }
                    LaunchedEffect(detail.value) { withFrameNanos { }; if (detail.value) play.requestFocus() else entry.requestFocus() }
                }
            } } }
            await("Initial tile is focused") { focused() == "row:item" }
            val labels = listOf("Trending series fixture", "Navigation title fixture")
            val before = labels.associateWith(::render)
            try {
                screenshot("before")
                repeat(3) { round ->
                    backFocused.set(false); playFocused.set(false)
                    ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("Detail play action has focus") { playFocused.get() }
                    ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                    await("D-pad reaches back arrow") { backFocused.get() }
                    ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("Back restores the same tile") { focused() == "row:item" }
                    labels.forEach { label ->
                        val after = render(label)
                        try { assertTrue("$label glyphs stay identical after back $round", requireNotNull(before[label]).sameAs(after)) }
                        finally { after.recycle() }
                    }
                }
                screenshot("after")
            } finally { before.values.forEach { it.recycle() } }
        } finally { ins.runOnMainSync { host.finish() }; ins.setInTouchMode(true) }
    }
}
