package com.nikhil.niktv.torrent

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class TorrentSearchFocusDeviceTest {
    @Test fun arrowsExitReadOnlyFieldAndSecondOkReopensKeyboard() {
        val ins = InstrumentationRegistry.getInstrumentation()
        check(ins.targetContext.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(ins.targetContext, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val field = FocusRequester()
        val submissions = AtomicInteger()
        val focusHistory = CopyOnWriteArrayList<String>()
        fun await(label: String, check: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < until) { if (check()) return; Thread.sleep(50) }
            fail("$label; focus events=$focusHistory")
        }
        fun focused(label: String): Boolean {
            ins.uiAutomation.clearCache()
            fun find(n: AccessibilityNodeInfo?): Boolean {
                n ?: return false
                if (n.isFocused && ((label == "field" && n.className?.toString() == "android.widget.EditText") ||
                    (label == "next" && n.text?.toString() == "Next control"))) return true
                fun containsText(child: AccessibilityNodeInfo?): Boolean {
                    child ?: return false
                    if (child.text?.toString() == "Next control") return true
                    return (0 until child.childCount).any { containsText(child.getChild(it)) }
                }
                if (n.isFocused && label == "next" && containsText(n)) return true
                for (i in 0 until n.childCount) if (find(n.getChild(i))) return true
                return false
            }
            return find(ins.uiAutomation.rootInActiveWindow)
        }
        fun ime() = host.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                var value by remember { mutableStateOf("") }
                Column(Modifier.padding(top = 48.dp)) {
                    TorrentTextField(value, { value = it }, "Search movies or series",
                        Modifier.focusRequester(field).onFocusChanged { if (it.isFocused) focusHistory += "field" },
                        { submissions.incrementAndGet() })
                    NikTvActionButton("Next control", {}, Modifier.onFocusChanged { if (it.isFocused) focusHistory += "next" })
                }
            } } }
            ins.waitForIdleSync()
            ins.runOnMainSync { field.requestFocus() }
            await("Field focused") { focused("field") }
            assertFalse("Directional focus does not open IME", ime())
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down exits the read-only editor") { focused("next") }
            NavigationAuditEvidence.capture("torrent-search-next-focus")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            await("Up returns to field") { focused("field") }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK opens keyboard") { ime() }
            ins.sendStringSync("Bunny")
            focusHistory.clear()
            // Submit on DOWN, then release after the editor has become read-only.
            // That release must never be mistaken for a fresh OK activation.
            ins.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            await("Enter down submits once") { submissions.get() == 1 }
            ins.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            await("Search submits and returns its window") { submissions.get() == 1 && !ime() && host.hasWindowFocus() }
            ins.waitForIdleSync()
            assertFalse("Submit key release cannot reopen the IME", ime())
            assertTrue("Search retains editor focus", focused("field"))
            assertFalse("No temporary focus on another control", focusHistory.contains("next"))
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Second OK opens keyboard again") { ime() }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back returns the keyboard window to the app") { !ime() && host.hasWindowFocus() }
            assertTrue("IME dismissal retains field focus", focused("field"))
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down still navigates after second edit") { focused("next") }
        } finally { ins.runOnMainSync { host.finish() }; ins.setInTouchMode(true) }
    }
}
