package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.UserProfile
import com.nikhil.niktv.discovery.DiscoveryPhoneHeader
import com.nikhil.niktv.discovery.DiscoveryTopNavigation
import com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout
import com.nikhil.niktv.model.DashboardSurface
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Token-free routing checks use each device's actual phone or tablet/TV header. */
class DiscoveryViewerNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(30)
        assertTrue(label, condition())
    }

    private fun click(label: String) {
        var node = find(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("$label has a separate action", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun fixture(verify: (List<String>) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val actions = CopyOnWriteArrayList<String>()
        try {
            instrumentation.runOnMainSync {
                host.setContent {
                    val configuration = LocalConfiguration.current
                    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, context.isTvLikeDevice(configuration))
                    val tab = remember { FocusRequester() }
                    val play = remember { FocusRequester() }
                    CompositionLocalProvider(
                        LocalSelectedUserProfile provides UserProfile("c93b58be-3b8f-4e78-b8da-270dd3ad67aa", "Family", avatar = "F"),
                        LocalOpenUserProfiles provides { actions += "people" },
                    ) {
                        MaterialTheme(colorScheme = NikColors) { Surface { Box(Modifier.fillMaxSize()) {
                            if (phone) DiscoveryPhoneHeader(DashboardSurface.HOME, false, tab, play,
                                {}, {}, { actions += it }, false, {}, null, profile = "Stalker")
                            else DiscoveryTopNavigation(DashboardSurface.HOME, "Stalker", tab, play,
                                {}, {}, { actions += it }, {}, false, {}, null)
                        } } }
                    }
                }
            }
            await("Separate person and portal controls appear") {
                find("Portals") != null && find("Switch profile: Family") != null
            }
            val portalBounds = android.graphics.Rect().also { find("Portals")!!.getBoundsInScreen(it) }
            val personBounds = android.graphics.Rect().also { find("Switch profile: Family")!!.getBoundsInScreen(it) }
            assertFalse("Compact controls retain distinct touch targets", android.graphics.Rect.intersects(portalBounds, personBounds))
            verify(actions)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun profileAvatarOpensPeopleWithoutOpeningPortalMenu() = fixture { actions ->
        click("Switch profile: Family")
        await("Avatar opens the people chooser") { actions == listOf("people") }
    }

    @Test fun portalControlOpensPortalMenuWithoutOpeningPeople() = fixture { actions ->
        click("Portals")
        await("Portal control retains the portal route") { actions == listOf("Profile") }
    }
}
