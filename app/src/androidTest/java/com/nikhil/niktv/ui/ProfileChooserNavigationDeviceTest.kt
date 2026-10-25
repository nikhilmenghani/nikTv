package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.PortalProfile
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Fake chooser profiles only: navigation never connects to a provider. */
class ProfileChooserNavigationDeviceTest {
    @Test fun reversalsAndUnevenRowsKeepRemoteFocusWithoutOpeningTiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val selections = CopyOnWriteArrayList<String>()
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
            return null
        }
        fun focused(text: String): Boolean {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, text)
            repeat(6) { if (node?.isFocused == true) return true; node = node?.parent }
            return false
        }
        fun await(text: String) {
            val end = SystemClock.uptimeMillis() + 6000
            while (!focused(text) && SystemClock.uptimeMillis() < end) Thread.sleep(30)
            assertTrue("Remote focus reaches $text", focused(text))
        }
        fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
        try {
            instrumentation.runOnMainSync { host.setContent {
                val config = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                }
                val profiles = listOf(PortalProfile("Fixture Nikhil", "https://nikhil.invalid", ""), PortalProfile("Fixture Wio", "https://wio.invalid", ""))
                CompositionLocalProvider(LocalConfiguration provides config) { MaterialTheme {
                    ProfileScreen(profiles.first(), profiles, false, false,
                        { selections.add("settings") }, { fail("Never connect") }, { selections.add(it.name) },
                        { selections.add("add") }, {}, {}, {})
                } }
            } }
            await("Fixture Nikhil")
            repeat(3) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT); await("Fixture Wio")
                key(KeyEvent.KEYCODE_DPAD_LEFT); await("Fixture Nikhil")
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN); await("Settings")
            key(KeyEvent.KEYCODE_DPAD_RIGHT); await("Add profile")
            key(KeyEvent.KEYCODE_DPAD_LEFT); await("Settings")
            key(KeyEvent.KEYCODE_DPAD_UP); await("Fixture Nikhil")
            assertTrue("Focus movement must not launch a tile", selections.isEmpty())
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val end = SystemClock.uptimeMillis() + 3000
            while (selections.isEmpty() && SystemClock.uptimeMillis() < end) Thread.sleep(30)
            assertEquals(listOf("Fixture Nikhil"), selections.toList())
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
