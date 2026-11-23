package com.nikhil.niktv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.UserProfile
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.DashboardSurface
import org.junit.Assert.*
import org.junit.Test

class WindowNavigationDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(ins.uiAutomation.rootInActiveWindow)
    }
    private fun await(label: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(40) }
        NavigationAuditEvidence.capture("window-navigation-failure")
        fail(label)
    }
    private fun bounds(label: String) = Rect().also { requireNotNull(find(label)).getBoundsInScreen(it) }
    private fun focused(label: String): Boolean {
        var node = find(label)
        while (node != null) { if (node.isFocused) return true; node = node.parent }
        return false
    }

    @Test fun rotationAndShortPhoneWindowsKeepHomeAndAllDestinationsVisible() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        var simulated by mutableStateOf<Configuration?>(null)
        var selected by mutableStateOf(DiscoveryPhoneDestination.HOME)
        val original = host.requestedOrientation
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync {
                host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                host.setContent {
                    val config = simulated ?: LocalConfiguration.current
                    val tab = remember { FocusRequester() }; val play = remember { FocusRequester() }
                    val phone = config.usesPhoneLayout(false)
                    CompositionLocalProvider(LocalConfiguration provides config,
                        LocalSelectedUserProfile provides UserProfile("fixture", "Family"),
                        LocalDirectPortalActions provides listOf("Stalker", "Xtream", "Sports").map { DirectPortalAction(it, Icons.Default.Tv, open = {}) }) {
                        MaterialTheme(colorScheme = NikColors) {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                Box(Modifier.size(minOf(maxWidth, config.screenWidthDp.dp), minOf(maxHeight, config.screenHeightDp.dp))) {
                                    if (phone) DiscoveryPhoneHeader(DashboardSurface.HOME, false, tab, play, {}, {}, {}, false, {}, null)
                                    else DiscoveryTopNavigation(DashboardSurface.HOME, "Fixture", tab, play, {}, {}, {}, {}, false, {}, null)
                                    NikTvSecondaryActionButton({}, Modifier.align(Alignment.Center).focusRequester(play)) { Text(if (phone) "Phone chrome" else "Wide chrome") }
                                    if (phone) DiscoveryPhoneBottomNavigation(selected, { selected = it }, Modifier.align(Alignment.BottomCenter))
                                }
                            }
                        }
                    }
                }
            }
            await("Portrait uses phone navigation") { find("Phone chrome") != null && find("Home") != null }
            ins.runOnMainSync { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("Full landscape uses wide navigation") { find("Wide chrome") != null && find("Home") != null }
            ins.runOnMainSync { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("Returning to portrait restores phone navigation") { find("Phone chrome") != null && find("Home") != null }
            for ((width, height) in listOf(320 to 220, 480 to 280)) {
                ins.runOnMainSync { simulated = Configuration(host.resources.configuration).apply { screenWidthDp = width; screenHeightDp = height } }
                await("Short $width dp window uses phone chrome") { find("Phone chrome") != null && find("Portals") != null }
                val home = bounds("Home"); val profile = bounds("Switch profile: Family")
                assertTrue("Home remains below the header", home.top >= profile.bottom)
                for (label in listOf("Home", "Movies", "Series", "Live TV", "My List", "Portals")) {
                    val target = bounds(label)
                    assertTrue("$label is fully visible", !target.isEmpty && target.left >= home.left && target.bottom <= home.bottom && target.top >= profile.bottom)
                }
                var node = requireNotNull(find("Home"))
                while (!node.isClickable) node = requireNotNull(node.parent)
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                for (label in listOf("Movies", "Series", "Live TV", "My List", "Portals")) {
                    ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                    await("D-pad reaches $label") { focused(label) }
                }
                NavigationAuditEvidence.capture("phone-short-window-$width")
            }
        } finally {
            ins.runOnMainSync { host.requestedOrientation = original; host.finish() }
        }
    }
}
