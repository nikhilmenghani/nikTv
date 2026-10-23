package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternateSourceInputDeviceTest {
    @Test fun remoteFocusDoesNotOpenKeyboardButSelectDoes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        try {
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                AlternateSourceDialog(AlternateSourcePickerState(
                    AlternateSourceRequest(MediaItem("fixture", "Movie", null, null), CatalogType.MOVIES),
                    AlternateSourceTarget("Movie", false), "fixture"), {}, {}, {})
            } } }
            fun field(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.className == "android.widget.EditText") return node
                for (i in 0 until node.childCount) field(node.getChild(i))?.let { return it }
                return null
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var node: AccessibilityNodeInfo? = null
            while (node == null && SystemClock.elapsedRealtime() < deadline) {
                node = field(instrumentation.uiAutomation.rootInActiveWindow); Thread.sleep(100)
            }
            assertTrue("Title editor must be focusable", node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
            instrumentation.waitForIdleSync()
            fun imeVisible(): Boolean {
                var visible = false
                instrumentation.runOnMainSync { visible = ViewCompat.getRootWindowInsets(host.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                return visible
            }
            assertFalse("Focus alone must not open IME", imeVisible())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            val imeDeadline = SystemClock.elapsedRealtime() + 5_000
            while (!imeVisible() && SystemClock.elapsedRealtime() < imeDeadline) Thread.sleep(100)
            assertTrue("Select must activate the editor and keyboard", imeVisible())
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
