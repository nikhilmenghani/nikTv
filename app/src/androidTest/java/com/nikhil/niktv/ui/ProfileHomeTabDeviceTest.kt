package com.nikhil.niktv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.ProfileBrowsePreferences
import com.nikhil.niktv.data.UserProfileType
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.DashboardSurface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Uses the actual profile settings row and both navigation layouts in the isolated app. */
class ProfileHomeTabDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()

    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        return find(ins.uiAutomation.rootInActiveWindow)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(35)
        assertTrue(message, condition())
    }

    @Test fun profileToggleControlsHomeVisibilityAndKeepsIptvTabs() {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val users = UserSyncStore.get(context)
        val previousOwner = users.selectedScope.value
        val previousAccount = users.selectedAccount.value
        val account = "991120"
        val owner = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.GENERAL))
        val other = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.KIDS))
        runBlocking {
            users.selectAccount(account)
            users.initializeDefaults(account)
            users.selectProfile(owner.profileId)
            ProfileBrowsePreferences.setShowHome(context, owner, false)
        }
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
        val editing = mutableStateOf(false)
        try {
            ins.runOnMainSync {
                host.setContent {
                    val enabled by remember { ProfileBrowsePreferences.observeShowHome(context, owner) }
                        .collectAsState(initial = false)
                    MaterialTheme(colorScheme = NikColors) {
                        CompositionLocalProvider(LocalShowHomeTab provides enabled) {
                            if (editing.value) {
                                CompositionLocalProvider(LocalSettingsPage provides SettingsPage.VIEWER_PORTALS) {
                                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                        ViewerProfileSettings(owner.profileId, emptyList())
                                    }
                                }
                            } else if (usesDiscoveryPhoneLayout(LocalConfiguration.current, false)) {
                                DiscoveryPhoneBottomNavigation(DiscoveryPhoneDestination.LIVE, {})
                            } else {
                                val live = remember { FocusRequester() }
                                val body = remember { FocusRequester() }
                                DiscoveryTopNavigation(DashboardSurface.LIVE_TV, "Fixture", live, body,
                                    {}, {}, {}, {}, false, {}, null, library = DiscoveryLibraryDestination.LIVE_TV)
                            }
                        }
                    }
                }
            }
            await("Default navigation shows IPTV tabs") {
                node("Movies") != null && node("Series") != null && node("Live TV") != null && node("My List") != null
            }
            assertNull(node("Home"))
            ins.runOnMainSync { editing.value = true }
            fun setting(): AccessibilityNodeInfo? {
                var candidate = node("Show Home tab")
                repeat(8) {
                    if (candidate?.isCheckable == true) return candidate
                    candidate = candidate?.parent
                }
                return null
            }
            await("Real settings switch is visible and off") { setting()?.isChecked == false }
            ins.setInTouchMode(false)
            assertTrue(requireNotNull(setting()).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            await("The switch has D-pad focus") { setting()?.isFocused == true }
            NavigationAuditEvidence.capture("home-tab-setting-focused")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("D-pad OK enables Home for this profile") { setting()?.isChecked == true }
            assertTrue("Switch retains focus", setting()?.isFocused == true)
            assertFalse(ProfileBrowsePreferences.showHome(context, other))
            ins.runOnMainSync { editing.value = false }
            await("Home appears beside IPTV navigation") { node("Home") != null && node("Live TV") != null }
            NavigationAuditEvidence.capture("home-tab-opt-in")
        } finally {
            ins.runOnMainSync { host.finish() }
            runBlocking {
                ProfileBrowsePreferences.setShowHome(context, owner, false)
                if (previousAccount == null) users.disconnect() else {
                    users.selectAccount(previousAccount)
                    previousOwner?.let { users.selectProfile(it.profileId) }
                }
            }
        }
    }
}
