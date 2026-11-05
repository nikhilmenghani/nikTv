package com.nikhil.niktv.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.RemoteCredentials
import org.junit.Assert.*
import org.junit.Test

/** Empty, token-free fixture only; no provider is contacted. */
class EmptyProfileDashboardDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun find(text: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true ||
                node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        return if (root?.packageName?.toString() == instrumentation.targetContext.packageName) walk(root) else null
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6_000
        while (!condition() && SystemClock.uptimeMillis() < end) Thread.sleep(30)
        assertTrue(message, condition())
    }

    private fun click(text: String) {
        var node = find(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("$text must be clickable", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun withDashboard(block: (PlaybackFixtureHostActivity, MutableState<Boolean>) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        assertFalse("Use an unconfigured disposable fixture", RemoteCredentials.configured(context))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val editor = mutableStateOf(false)
        try {
            instrumentation.runOnMainSync { host.setContent { MaterialTheme {
                ProfileScreen(null, emptyList(), editor.value, false,
                    {}, { fail("Must not authenticate") }, { fail("No profiles") },
                    { editor.value = true }, { editor.value = false }, {}, {})
            } } }
            await("Fresh devices show the dashboard") { find("Portals") != null }
            block(host, editor)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun tokenFreeDashboardShowsOnlyContentDestinationsAndPersonReturn() = withDashboard { _, _ ->
        for (label in listOf("Stalker", "Xtream", "Sports", "Who's watching?"))
            assertNotNull("Dashboard exposes $label without a profile", find(label))
        assertNotNull("Sports is a destination even before private configuration", find("Sports"))
        assertNull("Must not force the profile form", find("Portal URL"))
        for (removed in listOf("Offline", "GitHub account", "Configure portal", "User profiles", "Import backup", "Add profile", "NikTube"))
            assertNull("Portal chooser does not expose $removed", find(removed))
    }

    @Test fun missingConfigurationExplainsAvailabilityWithoutOpeningAnEditor() = withDashboard { _, editor ->
        click("Stalker")
        await("Unconfigured Stalker reports availability") { find("Stalker is not available yet") != null }
        assertNull(find("Portal URL"))
        assertFalse(editor.value)
        click("OK")
        await("OK dismisses the availability dialog") { find("Stalker is not available yet") == null }
        click("Xtream")
        await("Unconfigured Xtream reports availability") { find("Xtream is not available yet") != null }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("System Back dismisses the availability dialog") { find("Xtream is not available yet") == null }
        assertNotNull(find("Portals"))
        assertFalse("Unavailable connections never invoke the old editor callback", editor.value)
    }

    @Test fun sportsAvailabilityOnlyChangesTheSportsDestination() = withDashboard { host, _ ->
        val available = mutableStateOf(true)
        instrumentation.runOnMainSync { host.setContent { MaterialTheme {
            ProfileScreen(null, emptyList(), false, false, {}, {}, {}, {}, {}, {}, {},
                sportsAvailable = available.value)
        } } }
        await("Configured dashboard shows Sports") { find("Sports") != null }
        instrumentation.runOnMainSync { available.value = false }
        await("Configuration change removes Sports") { find("Sports") == null }
        assertNotNull(find("Stalker"))
        assertNotNull(find("Xtream"))
        assertNull(find("Offline"))
    }

    @Test fun emptyTvDashboardStartsRemoteFocusOnStalker() = withDashboard { host, _ ->
        instrumentation.setInTouchMode(false)
        instrumentation.runOnMainSync { host.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) { MaterialTheme {
                ProfileScreen(null, emptyList(), false, false, {}, {}, {}, {}, {}, {}, {})
            } }
        } }
        fun focused(text: String): Boolean {
            var node = find(text)
            repeat(6) { if (node?.isFocused == true) return true; node = node?.parent }
            return false
        }
        await("First TV focus reaches Stalker without a saved profile") { focused("Stalker") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("Right reaches the next available tile") { focused("Xtream") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        await("Left returns to Stalker") { focused("Stalker") }
    }
}
