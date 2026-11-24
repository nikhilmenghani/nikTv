package com.nikhil.niktv.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.MainActivity
import com.nikhil.niktv.data.AccountSyncManager
import com.nikhil.niktv.data.AccountTokenVault
import com.nikhil.niktv.data.ProfilePin
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalType
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProfileNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun createsOpensEditsAndDeletesALocalProfile() {
        check(context.packageName.endsWith(".regression"))
        val account = "90000008113"
        val profileName = "Navigation QA"
        val incompleteName = "Incomplete QA"
        val portalName = "Navigation portal"
        val users = UserSyncStore.get(context)
        val manager = AccountSyncManager.get(context)
        val vault = AccountTokenVault(context)
        val authKeys = listOf("accountId", "login", "ciphertext", "iv")
        val oldAuth = authKeys.associateWith { vault.preferences.getString(it, null) }
        val initializedKey = "initialized:$account"
        val previousInitialization = vault.preferences.getBoolean(initializedKey, false)
            .takeIf { vault.preferences.contains(initializedKey) }
        val oldAccount = users.selectedAccount.value
        val oldScope = users.selectedScope.value
        val oldPullOnly = manager.manualPullOnly.value
        val oldAccessibilityFlags = automation.serviceInfo.flags
        val provider = LocalXtreamProvider()
        provider.start()
        val portal = PortalProfile(portalName, "http://127.0.0.1:${provider.listeningPort}", "",
            portalType = PortalType.XTREAM, username = "fixture", password = "fixture")
        var activity: MainActivity? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            runBlocking {
                manager.requestManualPullOnly(true).join()
                assertTrue(manager.manualPullOnly.value)
                users.selectAccount(account)
                users.clearSelection()
                users.profiles.first().forEach { users.deleteProfile(it.id) }
                val incomplete = users.createProfile(incompleteName)
                val secured = users.setProfilePin(incomplete.id, ProfilePin.create("0000"))
                ProfilePin.remember(context, account, secured)
                users.setProfileEnabled(incomplete.id, true)
            }
            // A synthetic cached login opens the normal gate without a GitHub credential.
            vault.save("local-navigation-fixture", account, "local-navigation")
            check(vault.preferences.edit().putBoolean(initializedKey, true).commit())
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            instrumentation.setInTouchMode(false)
            activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity

            await("People chooser on a fresh account") { find("Who's watching?") != null }
            click("Watch as $incompleteName")
            await("An existing viewer without portals enters the profile editor directly") {
                find("1 of 2 · Profile details") != null && focused("Display name") &&
                    nodes().any { it.isFocused && it.text?.toString() == incompleteName }
            }
            assertNull(find("Profiles & portals"))
            click("Cancel")
            await("Cancel returns the incomplete viewer to the people chooser") { find("Who's watching?") != null }

            click("Add user profile")
            await("The display name is the D-pad entry target") { focused("Display name") }
            enterFocusedText(profileName)
            key(KeyEvent.KEYCODE_BACK)
            await("Back leaves the name editor on the profile details page") {
                find("1 of 2 · Profile details") != null && focused("Display name") &&
                    keyboardHidden()
            }
            click("Next")
            await("Empty portal setup initially focuses Add portal") { focused("Add portal") }
            assertFalse("Creation needs a selected portal", control("Create & open")?.isEnabled == true)
            NavigationAuditEvidence.capture("profile-navigation-new-no-portal")

            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Portal editor enters at the portal type selector") { focused("Stalker / MAG") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad can reach Xtream") { focused("Xtream") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            editPortalField("Portal name", portalName)
            editPortalField("Portal URL", portal.portalUrl)
            editPortalField("Username", portal.username)
            editPortalField("Password", portal.password)
            click("Use portal")
            await("The new portal is selected in the profile editor") {
                control(portalName)?.isChecked == true
            }
            await("Returning from portal editor restores Add portal focus") { focused("Add portal") }
            NavigationAuditEvidence.capture("profile-navigation-portal-selected")

            click("Create & open")
            await("New profile opens one password prompt") { find("Set password for $profileName") != null }
            await("New password has initial D-pad focus") { focused("New password (4 digits)") }
            enterFocusedText("0000")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad moves to password confirmation") { focused("Confirm password") }
            enterFocusedText("0000")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad dismisses the keyboard and reaches Cancel") { focused("Cancel") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad reaches the enabled Set password action with the keyboard dismissed") {
                focused("Set password") && control("Set password")?.isEnabled == true && keyboardHidden()
            }
            instrumentation.waitForIdleSync()
            NavigationAuditEvidence.capture("profile-navigation-password-focused")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitDashboard(profileName)
            NavigationAuditEvidence.capture("profile-navigation-dashboard")

            val created = runBlocking { users.profiles.first().single { it.name == profileName } }
            val owner = UserScope(account, created.id)
            assertTrue(ProfilePin.verifies(created.pinVerifier, "0000"))
            assertEquals(owner, users.selectedScope.value)
            assertTrue(runBlocking { users.hasPortalAssociation(owner, portal) })
            assertTrue("The new profile stays in the local pending queue",
                runBlocking { users.pending(account).any { it.profileId == created.id } })
            assertTrue("The app authenticated with the loopback portal", provider.authentications.get() > 0)

            click("Switch profile: $profileName")
            await("The account can still choose an existing viewer") { find("Who's watching?") != null }
            click("Watch as $profileName")
            await("Choosing the existing viewer resumes the dashboard") {
                find("Switch profile: $profileName") != null && find("Live TV") != null
            }
            click("Options")
            click("Settings")
            click("Profiles & portals")
            click(profileName)
            await("Profile settings opens with Manage profile visible") { find("Manage profile") != null }
            click("Manage profile")
            await("Settings opens the same profile editor") { focused("Display name") }
            click("Blue")
            click("Next")
            await("The existing profile remembers its portal selection") { control(portalName)?.isChecked == true }
            click("Save & open")
            awaitDashboard(profileName)
            assertEquals(owner, users.selectedScope.value)
            assertEquals(0xFF1565C0L, runBlocking { users.profiles.first().single { it.id == created.id } }.color)
            NavigationAuditEvidence.capture("profile-navigation-dashboard-after-edit")

            click("Switch profile: $profileName")
            await("The people chooser opens on request") { find("Who's watching?") != null }
            click("Manage profiles")
            click(profileName)
            click("Delete profile")
            click("Delete on this device", "Delete across devices")
            await("Deletion requests the profile password") { find("Unlock $profileName") != null }
            await("Deletion password has initial D-pad focus") { focused("PIN") }
            enterFocusedText("0000")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad leaves the deletion password field") { focused("Cancel") }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad reaches the enabled Unlock action") { focused("Unlock") && control("Unlock")?.isEnabled == true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("The deleted profile disappears from the chooser") {
                find("Who's watching?") != null && find("Watch as $profileName") == null &&
                    runBlocking { users.profiles.first().none { it.id == created.id } }
            }
            assertNull(users.selectedScope.value)
            NavigationAuditEvidence.capture("profile-navigation-profile-deleted")
        } finally {
            provider.stop()
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            instrumentation.setInTouchMode(true)
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldAccessibilityFlags }
            runBlocking {
                users.profiles.first().filter { it.name in setOf(profileName, incompleteName) }
                    .forEach { users.deleteProfile(it.id) }
                ProfileStore(context).removeProfile(portal)
                if (oldAccount == null) users.disconnect() else users.selectAccount(oldAccount)
                oldScope?.let { users.selectProfile(it.profileId) }
                vault.preferences.edit().apply {
                    oldAuth.forEach { (key, value) -> putString(key, value) }
                    if (previousInitialization == null) remove(initializedKey)
                    else putBoolean(initializedKey, previousInitialization)
                }.commit()
                manager.restoreCachedAccount()
                manager.requestManualPullOnly(oldPullOnly).join()
            }
        }
    }

    private fun awaitDashboard(profileName: String) {
        var choseProfileAgain = false
        await("Dashboard opens directly for $profileName") {
            choseProfileAgain = choseProfileAgain || find("Who's watching?") != null
            find("Switch profile: $profileName") != null && find("Live TV") != null
        }
        assertFalse("Saving must not return to the profile chooser", choseProfileAgain)
    }

    private fun editPortalField(label: String, value: String) {
        click(label)
        setFocusedText(value)
        click("Done")
    }

    private fun enterFocusedText(value: String) {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        setFocusedText(value)
    }

    private fun setFocusedText(value: String) {
        var field: AccessibilityNodeInfo? = null
        await("The current text field is editable") {
            field = nodes().firstOrNull { it.isFocused && it.isEditable }
            field != null
        }
        assertTrue(requireNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    private fun keyboardHidden() = automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun click(vararg labels: String) {
        var target: AccessibilityNodeInfo? = null
        var scrollAt = SystemClock.uptimeMillis() + 400L
        var scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        await("Clickable ${labels.joinToString(" or ")}") {
            val snapshot = nodes()
            target = labels.firstNotNullOfOrNull { label ->
                control(label, snapshot)?.takeIf { node -> node.isEnabled && node.isVisibleToUser }
            }
            if (target == null && SystemClock.uptimeMillis() >= scrollAt) {
                val scroll = snapshot.firstOrNull { it.isScrollable && it.isVisibleToUser }
                if (scroll != null && !scroll.performAction(scrollAction)) {
                    scrollAction = if (scrollAction == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                scrollAt = SystemClock.uptimeMillis() + 400L
            }
            target != null
        }
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun control(label: String, snapshot: List<AccessibilityNodeInfo> = nodes()) = snapshot.firstNotNullOfOrNull { node ->
        if (node.matches(label)) generateSequence(node) { it.parent }.firstOrNull { it.isClickable } else null
    }

    private fun focused(label: String) = nodes().filter { it.matches(label) }.any { node ->
        generateSequence(node) { it.parent }.any { it.isFocused }
    }

    private fun AccessibilityNodeInfo.matches(label: String) =
        text?.toString() == label || contentDescription?.toString() == label

    private fun find(label: String) = nodes().firstOrNull { it.isVisibleToUser && it.matches(label) }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val root = automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName }
            ?: automation.windows.firstNotNullOfOrNull {
                it.root?.takeIf { node -> node.packageName?.toString() == context.packageName }
            }
        return buildList {
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                add(node)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(root)
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(40)
        }
        NavigationAuditEvidence.capture("profile-navigation-failure")
        val visible = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text ?: it.contentDescription }
        error("$message. Visible controls: $visible")
    }

    private class LocalXtreamProvider : NanoHTTPD("127.0.0.1", 0) {
        val authentications = AtomicInteger()

        override fun serve(session: IHTTPSession): Response {
            if (session.uri != "/player_api.php" || session.parameters["username"]?.singleOrNull() != "fixture" ||
                session.parameters["password"]?.singleOrNull() != "fixture") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
            }
            val body = if (session.parameters["action"].isNullOrEmpty()) {
                authentications.incrementAndGet()
                """{"user_info":{"auth":1,"status":"Active"}}"""
            } else "[]"
            return newFixedLengthResponse(Response.Status.OK, "application/json", body)
        }
    }
}
