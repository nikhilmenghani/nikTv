package com.nikhil.niktv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.AccountTokenVault
import com.nikhil.niktv.data.ProfileBrowsePreferences
import com.nikhil.niktv.data.ProfilePin
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.data.ViewerDestination
import com.nikhil.niktv.data.ViewerDestinationKind
import com.nikhil.niktv.data.ViewerLayout
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedLaunchDeviceTest {
    @Test fun cachedViewerSeesTheLogoThenHomeWithoutTheChooserOrPortalLoader() {
        val visible = launchWithCachedViewer(enabled = true, destination = "Live TV")
        assertTrue("The NikTV launch mark was visible before Home", "NikTV" in visible)
        assertFalse("The people chooser appeared during resume", "Who's watching?" in visible)
        assertFalse("The portal loader appeared during resume", visible.any { it.startsWith("Loading Stalker") })
    }

    @Test fun disabledRememberedViewerStillSeesThePeopleChooser() {
        val visible = launchWithCachedViewer(enabled = false, destination = "Who's watching?")
        assertFalse("A disabled viewer must not be restored to Home", "Live TV" in visible)
    }

    @Test fun profileCreatedFromChooserImmediatelyAppearsOnThisDevice() {
        launchWithCachedViewer(enabled = false, destination = "Who's watching?", createNewViewer = true)
    }

    private fun launchWithCachedViewer(enabled: Boolean, destination: String, createNewViewer: Boolean = false): Set<String> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val users = UserSyncStore.get(context)
        val vault = AccountTokenVault(context)
        val account = if (enabled) "90000008101" else "90000008102"
        val oldAccount = users.selectedAccount.value
        val oldViewer = users.selectedScope.value
        val authKeys = listOf("accountId", "login", "ciphertext", "iv")
        val oldAuth = authKeys.associateWith { vault.preferences.getString(it, null) }
        val portal = PortalProfile("Launch fixture", "http://127.0.0.1:1", "00:11:22:33:44:55")
        val owner = runBlocking {
            users.selectAccount(account)
            val viewer = users.createProfile("Launch fixture viewer")
            val secured = users.setProfilePin(viewer.id, ProfilePin.create("0000"))
            ProfilePin.remember(context, account, secured)
            val scope = UserScope(account, viewer.id)
            users.setProfileEnabled(viewer.id, enabled)
            users.selectProfile(viewer.id)
            ProfileBrowsePreferences.setLayout(context, scope, ViewerLayout.EASY)
            ProfileStore(context).scoped(scope).save(PortalSession(portal, "fixture", portal.portalUrl, "", "", ""))
            users.rememberDestination(scope, ViewerDestination(ViewerDestinationKind.PORTAL, portal.cacheKey()))
            users.clearSelection()
            scope
        }
        vault.save("isolated-launch-fixture", account, "launch-fixture")
        check(vault.preferences.edit().putBoolean("initialized:$account", true).commit())
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            instrumentation.runOnMainSync { activity.setContent { SetupRecoveryGate { NikTvApp() } } }
            val automation = instrumentation.uiAutomation
            val visible = mutableSetOf<String>()
            val end = SystemClock.uptimeMillis() + 12_000L
            fun labels(node: AccessibilityNodeInfo?) {
                if (node == null) return
                node.text?.toString()?.let(visible::add)
                node.contentDescription?.toString()?.let(visible::add)
                for (index in 0 until node.childCount) labels(node.getChild(index))
            }
            while (destination !in visible && SystemClock.uptimeMillis() < end) {
                if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
                val root = automation.rootInActiveWindow
                if (root?.packageName?.toString() == context.packageName) labels(root)
                if (destination !in visible) Thread.sleep(20)
            }
            NavigationAuditEvidence.capture(if (enabled) "cached-launch-home" else "cached-launch-people")
            assertTrue("$destination is expected after launch. Saw $visible", destination in visible)
            if (createNewViewer) {
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (match(node)) return node
                    for (index in 0 until node.childCount) find(node.getChild(index), match)?.let { return it }
                    return null
                }
                fun awaitNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
                    val deadline = SystemClock.uptimeMillis() + 8_000L
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
                        (listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root })
                            .filter { it.packageName?.toString() == context.packageName }
                            .firstNotNullOfOrNull { find(it, match) }?.let { return it }
                        Thread.sleep(30)
                    }
                    error("Expected profile control did not appear")
                }
                fun click(label: String) {
                    var node = awaitNode { it.text?.toString() == label || it.contentDescription?.toString() == label }
                    while (!node.isClickable) node = requireNotNull(node.parent)
                    check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                click("Add user profile")
                val field = awaitNode { it.className?.toString() == "android.widget.EditText" }
                val rect = android.graphics.Rect().also(field::getBoundsInScreen)
                val time = android.os.SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                    val event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                    instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
                }
                val editable = awaitNode { it.className?.toString() == "android.widget.EditText" && it.isEditable }
                check(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Created here fixture")
                }))
                if (automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                }
                click("Next")
                click(portal.name)
                instrumentation.setInTouchMode(false)
                click("Create & open")
                awaitNode { it.text?.toString() == "Set password for Created here fixture" }
                for (label in listOf("New password (4 digits)", "Confirm password")) {
                    awaitNode { node ->
                        node.text?.toString() == label && generateSequence(node) { it.parent }.any { it.isFocused }
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    val password = awaitNode { it.isFocused && it.isEditable && it.isPassword }
                    check(password.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0000")
                    }))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                }
                click("Set password")
                click("Switch profile: Created here fixture")
                awaitNode { it.text?.toString() == "Who's watching?" }
                awaitNode { it.contentDescription?.toString() == "Watch as Created here fixture" }
                val created = runBlocking { users.profiles.first().first { it.name == "Created here fixture" } }
                assertTrue(users.enabledProfileIds(account).contains(created.id))
                assertFalse(users.enabledProfileIds(account).contains(owner.profileId))
                NavigationAuditEvidence.capture("created-profile-visible")
            }
            return visible
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            if (createNewViewer) instrumentation.setInTouchMode(true)
            vault.preferences.edit().apply {
                oldAuth.forEach { (key, value) -> putString(key, value) }
                remove("initialized:$account")
            }.commit()
            runBlocking {
                if (createNewViewer) users.profiles.first().filter { it.name == "Created here fixture" }.forEach {
                    users.deleteProfile(it.id)
                }
                users.deleteProfile(owner.profileId)
                ProfileStore(context).removeProfile(portal)
                if (oldAccount == null) users.disconnect() else users.selectAccount(oldAccount)
                oldViewer?.let { users.selectProfile(it.profileId) }
            }
        }
    }
}
