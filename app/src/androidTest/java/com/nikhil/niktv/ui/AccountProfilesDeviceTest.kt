package com.nikhil.niktv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.AccountSyncState
import com.nikhil.niktv.data.UserProfile
import com.nikhil.niktv.data.UserProfileType
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Token-free UI fixtures exercise the same phone, tablet and TV composables. */
class AccountProfilesDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(text: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() != instrumentation.targetContext.packageName) return null
        return walk(root)
    }
    private fun await(label: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 6_000
        while (!check() && SystemClock.uptimeMillis() < until) Thread.sleep(30)
        assertTrue(label, check())
    }
    private fun click(label: String) {
        var node = reveal(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("$label is actionable", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun reveal(label: String): AccessibilityNodeInfo? {
        fun scroll(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            return (0 until node.childCount).any { scroll(node.getChild(it)) }
        }
        repeat(5) {
            find(label)?.let { return it }
            scroll(instrumentation.uiAutomation.rootInActiveWindow)
            Thread.sleep(100)
        }
        return find(label)
    }
    private fun fixture(content: @Composable () -> Unit, verify: (PlaybackFixtureHostActivity) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            instrumentation.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) { Surface { content() } } } }
            verify(host)
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            instrumentation.targetContext.filesDir.resolve(name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun freshAccountShowsMaskedEmptyTokenBeforeContentAndOffersSettings() {
        val action = AtomicReference("")
        fixture({ GitHubAccountScreen(AccountSyncState(), { action.set("connect") }, {}, {}, { action.set("settings") }, {}) }) {
            await("Account screen appears first") { find("GitHub account") != null }
            val field = find("GitHub token")
            assertNotNull("Fresh install asks for token", field)
            fun hasPassword(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isPassword) return true
                return (0 until node.childCount).any { hasPassword(node.getChild(it)) }
            }
            assertTrue("Token field is masked", hasPassword(instrumentation.uiAutomation.rootInActiveWindow))
            assertNull("Content is gated", find("Portals"))
            assertNull("User profiles wait for sync", find("Who's watching?"))
            click("Settings")
            await("Settings is available before setup") { action.get() == "settings" }
            assertNotEquals("Blank token cannot connect", "connect", action.get())
        }
    }

    @Test fun applicationNavigationStartsAtAccountGateWithoutDevelopmentCredentials() {
        fixture({ NikTvApp() }) {
            await("Actual app navigation gates a fresh account") { find("GitHub account") != null }
            assertNotNull(find("GitHub token"))
            assertNull("Portals wait for successful sync and user selection", find("Portals"))
            assertNull(find("Who's watching?"))
            screenshot("account-gate-validation.png")
            click("Settings")
            await("Actual Settings remains accessible before account setup") { find("Playback") != null && find("GitHub token") == null }
        }
    }

    @Test fun initialFailureOffersRetryWithoutCachedAccountBypass() {
        val action = AtomicReference("")
        fixture({ GitHubAccountScreen(AccountSyncState(error = "Cannot connect. Check your network and retry."), {}, { action.set("retry") }, {}, {}, {}) }) {
            await("Initial setup error is useful") { find("Cannot connect. Check your network and retry.") != null }
            assertNull("Fresh failed setup cannot open cached profiles", find("Use cached profiles"))
            click("Retry sync")
            await("Retry is actionable") { action.get() == "retry" }
        }
    }

    @Test fun revokedAccountRequiresReconnectAndPreservesRecoveryActions() {
        fixture({ GitHubAccountScreen(AccountSyncState(accountId = "42", login = "fixture", initialized = true,
            reconnectRequired = true, error = "Token revoked. Enter a replacement token."), {}, {}, {}, {}, {}) }) {
            await("Replacement token is available") { find("Replacement GitHub token") != null }
            assertNull("Revoked token cannot bypass reconnect", find("User profiles"))
            assertNull(find("Use cached profiles"))
            assertNotNull(reveal("Retry sync"))
            assertNotNull(reveal("Settings"))
        }
    }

    @Test fun tvRemoteSelectsPersonBeforeAnyPortalAndManagementIsExplicit() {
        val selected = AtomicReference("")
        instrumentation.setInTouchMode(false)
        val people = listOf(UserProfile("family-fixture", "Family"), UserProfile("kids-fixture", "Kids", type = UserProfileType.KIDS))
        fixture({
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                UserProfilesScreen(people, AccountSyncState(initialized = true, message = "Up to date"), null, { selected.set(it.id) }, { _, _, _, _, _ -> }, {}, {}, {}, selectedProfileId = people.first().id)
            }
        }) {
            await("People chooser is visible") { find("Who's watching?") != null }
            assertNotNull(find("Watch as Family")); assertNotNull(find("Watch as Kids"))
            fun tileNodes(label: String): List<AccessibilityNodeInfo> {
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                var node = find(label)
                repeat(5) { node?.let(nodes::add); node = node?.parent }
                return nodes
            }
            fun announcesCurrentProfile() = tileNodes("Watch as Family").any {
                it.stateDescription?.toString() == "Current profile"
            }
            val until = SystemClock.uptimeMillis() + 6_000
            while (!announcesCurrentProfile() && SystemClock.uptimeMillis() < until) Thread.sleep(30)
            assertTrue("The supplied permanent person identity is announced. Native tile/parents: " +
                tileNodes("Watch as Family").joinToString { "package=${it.packageName}, text=${it.text}, description=${it.contentDescription}, state=${it.stateDescription}, selected=${it.isSelected}, checked=${it.isChecked}, extras=${it.extras}" },
                announcesCurrentProfile())
            val add = find("Add user profile")!!
            assertNull("Add is never announced as a selected person", add.stateDescription)
            assertNull("No claims of active Kids restrictions", find("Restricted content"))
            assertNull("Portal navigation waits for a person", find("Stalker"))
            fun focused(text: String): Boolean {
                var node = find(text)
                repeat(6) { if (node?.isFocused == true) return true; node = node?.parent }
                return false
            }
            await("First profile receives TV focus") { focused("Watch as Family") }
            screenshot("user-profiles-tv-validation.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("TV OK selects exact permanent profile identity") { selected.get() == "family-fixture" }
        }
    }
}
