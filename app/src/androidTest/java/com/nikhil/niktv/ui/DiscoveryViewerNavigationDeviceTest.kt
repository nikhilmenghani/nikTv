package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.UserProfile
import com.nikhil.niktv.discovery.DiscoveryPhoneHeader
import com.nikhil.niktv.discovery.DiscoveryTopNavigation
import com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout
import com.nikhil.niktv.discovery.DirectPortalAction
import com.nikhil.niktv.discovery.LocalDirectPortalActions
import com.nikhil.niktv.discovery.LocalPortalFocusReturn
import com.nikhil.niktv.discovery.PortalFocusReturn
import com.nikhil.niktv.discovery.DiscoverySearchScreen
import com.nikhil.niktv.discovery.DiscoveryLibraryScreen
import com.nikhil.niktv.discovery.DiscoveryLibraryDestination
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.PortalProfile
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
        val root = instrumentation.uiAutomation.rootInActiveWindow
        return if (root?.packageName?.toString() == instrumentation.targetContext.packageName) walk(root) else null
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

    private fun fixture(detail: Boolean = false, forcePhone: Boolean = false, extraPortals: Boolean = false,
        verify: (List<String>) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val actions = CopyOnWriteArrayList<String>()
        try {
            instrumentation.runOnMainSync {
                host.setContent {
                    val configuration = LocalConfiguration.current
                    val phone = forcePhone || usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, context.isTvLikeDevice(configuration))
                    val tab = remember { FocusRequester() }
                    val play = remember { FocusRequester() }
                    CompositionLocalProvider(
                        LocalSelectedUserProfile provides UserProfile("c93b58be-3b8f-4e78-b8da-270dd3ad67aa", "Family", avatar = "F"),
                        LocalOpenUserProfiles provides { actions += "people" },
                        LocalOpenLanPlayback provides { actions += "cast" },
                        LocalDirectPortalActions provides listOf(
                            DirectPortalAction("Stalker", Icons.Default.Tv, selected = true) { actions += "Stalker" },
                            DirectPortalAction("Xtream", Icons.Default.Tv) { actions += "Xtream" },
                            DirectPortalAction("Sports", Icons.Default.Tv) { actions += "Sports" }) +
                            if (extraPortals) (4..6).map { number ->
                                DirectPortalAction("Portal $number", Icons.Default.Tv) { actions += "Portal $number" }
                            } else emptyList(),
                    ) {
                        MaterialTheme(colorScheme = NikColors) { Surface { Box(Modifier.fillMaxSize()) {
                            if (phone) DiscoveryPhoneHeader(DashboardSurface.HOME, detail, tab, play,
                                { actions += "Back" }, {}, { actions += it }, false, {}, null, profile = "Stalker")
                            else DiscoveryTopNavigation(DashboardSurface.HOME, "Stalker", tab, play,
                                {}, {}, { actions += it }, {}, false, {}, null)
                        } } }
                    }
                }
            }
            await("Separate person and portal controls appear") {
                find("Xtream") != null && find("Switch profile: Family") != null
            }
            val portalBounds = android.graphics.Rect().also { find("Xtream")!!.getBoundsInScreen(it) }
            val personBounds = android.graphics.Rect().also { find("Switch profile: Family")!!.getBoundsInScreen(it) }
            assertFalse("Compact controls retain distinct touch targets", android.graphics.Rect.intersects(portalBounds, personBounds))
            val configuration = context.resources.configuration
            if (!forcePhone && !usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, context.isTvLikeDevice(configuration))) {
                val minimumGap = (8 * context.resources.displayMetrics.density).toInt()
                assertTrue("Tablet portal and avatar tiles have separate space between them",
                    personBounds.left - portalBounds.right >= minimumGap)
            }
            verify(actions)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun profileAvatarOpensPeopleWithoutOpeningPortalMenu() = fixture { actions ->
        click("Switch profile: Family")
        await("Avatar opens the people chooser") { actions == listOf("people") }
    }

    @Test fun portalControlSwitchesDirectlyWithoutOpeningPeopleOrPortalPage() = fixture { actions ->
        click("Xtream")
        await("Portal control switches without an intermediate page") { actions == listOf("Xtream") }
    }

    @Test fun phoneDetailKeepsPortalProfileAndCastActionsReachable() = fixture(detail = true, forcePhone = true) { actions ->
        click("Xtream")
        click("Switch profile: Family")
        click("Play on NikTV")
        click("Back")
        await("Detail header has independent actions") { actions == listOf("Xtream", "people", "cast", "Back") }
        NavigationAuditEvidence.capture("phone-detail-direct-portals")
    }

    @Test fun phoneDirectPortalsFollowTheirVisualDpadOrder() = fixture(forcePhone = true) { actions ->
        fun focused(label: String): Boolean {
            var node = find(label)
            while (node != null) { if (node.isFocused) return true; node = node.parent }
            return false
        }
        instrumentation.setInTouchMode(false)
        try {
            var node = find("Stalker")
            while (node != null && !node.isClickable) node = node.parent
            assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters Xtream") { focused("Xtream") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Right enters Sports") { focused("Sports") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("D-pad switches the focused portal directly") { actions == listOf("Sports") }
            NavigationAuditEvidence.capture("phone-direct-portals-dpad")
        } finally { instrumentation.setInTouchMode(true) }
    }

    @Test fun phoneCanReachNewPortalsWithoutOpeningAChooser() = fixture(forcePhone = true, extraPortals = true) { actions ->
        instrumentation.setInTouchMode(false)
        try {
            var node = find("Stalker")
            while (node != null && !node.isClickable) node = node.parent
            assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
            for (name in listOf("Xtream", "Sports", "Portal 4", "Portal 5", "Portal 6")) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("The strip scrolls to $name") {
                    var target = find(name)
                    while (target != null && !target.isFocused) target = target.parent
                    target?.isFocused == true
                }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("New portals switch directly") { actions == listOf("Portal 6") }
            NavigationAuditEvidence.capture("phone-direct-portals-overflow-dpad")
        } finally { instrumentation.setInTouchMode(true) }
    }

    @Test fun searchPortalSwitchRestoresTheClickedPortalAfterTheQueryEntryPass() = portalSwitchRestoresFocus(search = true)
    @Test fun libraryPortalSwitchRestoresTheClickedPortalAfterTheHeaderEntryPass() = portalSwitchRestoresFocus(search = false)

    private fun portalSwitchRestoresFocus(search: Boolean) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val selected = mutableStateOf("Stalker")
        val switching = mutableStateOf(false)
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        fun focused(label: String): Boolean {
            var node = find(label)
            while (node != null) { if (node.isFocused) return true; node = node.parent }
            return false
        }
        try {
            instrumentation.runOnMainSync { host.setContent {
                val portalReturn = remember { PortalFocusReturn() }
                CompositionLocalProvider(LocalPortalFocusReturn provides portalReturn,
                    LocalDirectPortalActions provides listOf("Stalker", "Xtream").map { portal ->
                        DirectPortalAction(portal, Icons.Default.Tv, selected = selected.value == portal) {
                            if (portal != selected.value) switching.value = true
                        }
                    }) {
                    MaterialTheme(colorScheme = NikColors) {
                        if (switching.value) Text("Switching portal fixture")
                        else {
                            val profile = PortalProfile(selected.value, "https://${selected.value.lowercase()}.invalid", "fixture")
                            val state = NikTvState(savedProfile = profile, searchOpen = search,
                                favoritesOpen = !search, homeOpen = false, searchQuery = "Friends")
                            val queryFocus = remember { FocusRequester() }
                            val body: @Composable () -> Unit = {
                                Text("Query entry fixture", Modifier.focusRequester(queryFocus).focusable())
                                if (search) LaunchedEffect(profile.cacheKey()) {
                                    // Match Search's normal one-frame query focus entry.
                                    withFrameNanos { }; queryFocus.requestFocus()
                                }
                            }
                            if (search) DiscoverySearchScreen(state, {}, {}, {}, body)
                            else DiscoveryLibraryScreen(state, DiscoveryLibraryDestination.MY_LIST, {}, {}, {}, content = body)
                        }
                    }
                }
            } }
            await("Portal controls are attached") { find("Xtream") != null }
            click("Xtream")
            await("Portal switch leaves its shell during authentication") { find("Switching portal fixture") != null }
            instrumentation.runOnMainSync { selected.value = "Xtream"; switching.value = false }
            await("The activated portal owns focus on the retained ${if (search) "Search" else "My List"} shell") { focused("Xtream") }
            Thread.sleep(250)
            assertTrue("Initial query/header focus cannot reclaim the activated portal", focused("Xtream"))
            NavigationAuditEvidence.capture("portal-switch-focus-${if (search) "search" else "library"}")
        } finally {
            instrumentation.runOnMainSync { host.finish() }
            instrumentation.setInTouchMode(true)
        }
    }
}
