package com.nikhil.niktv.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.ProfilePin
import com.nikhil.niktv.data.UserProfile
import org.junit.Assert.*
import org.junit.Test

class ProfilePinDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun find(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache()
        fun visit(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            if (test(n)) return n
            for (i in 0 until n.childCount) visit(n.getChild(i))?.let { return it }
            return null
        }
        return visit(ins.uiAutomation.rootInActiveWindow)
    }
    private fun await(label: String, test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < until) { if (test()) return; Thread.sleep(40) }
        fail(label)
    }
    private fun contains(node: AccessibilityNodeInfo, label: String): Boolean {
        if (node.text?.toString() == label) return true
        for (i in 0 until node.childCount) node.getChild(i)?.let { if (contains(it, label)) return true }
        return false
    }
    private fun focused(label: String) = find { it.isFocused && contains(it, label) } != null

    @Test fun failedPasswordCheckOffersRetryThenUnlockWithoutSetup() {
        val context = ins.targetContext
        require(context.packageName.endsWith(".regression"))
        val unset = UserProfile("retry-fixture", "Retry fixture")
        val known = unset.copy(pinVerifier = ProfilePin.create("1357"))
        var reads = 0
        val request = ProfilePinRequest(unset, false) { if (++reads == 1) error("offline") else known }
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) { ProfilePinPrompt(request) } } }
            await("Verification failure offers retry") { request.checkError != null && focused("Cancel") }
            assertNull(find { it.text?.toString() == "Set password" })
            assertNull(find { it.text?.toString() == "New password (4 digits)" })
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Retry is reachable by D-pad") { focused("Retry") }
            NavigationAuditEvidence.capture("profile-password-retry")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Retry loads the remote password and focuses PIN") {
                find { it.text?.toString() == "Unlock Retry fixture" } != null && focused("PIN")
            }
            assertEquals(2, reads)
            assertNull(find { it.text?.toString() == "Set password" })
            assertFalse(request.result.isCompleted)
            NavigationAuditEvidence.capture("profile-password-remote-unlock")
        } finally { ins.runOnMainSync { host.finish() } }
    }

    @Test fun backgroundPasswordArrivalReplacesSetupAndRetainsDialogFocus() {
        val context = ins.targetContext
        require(context.packageName.endsWith(".regression"))
        val unset = UserProfile("arrival-fixture", "Arrival fixture")
        val known = unset.copy(pinVerifier = ProfilePin.create("1357"))
        val request = ProfilePinRequest(unset, false) { unset }
        val backgroundFocus = java.util.concurrent.CopyOnWriteArrayList<String>()
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) {
                Column {
                    NikTvSecondaryActionButton(onClick = {}, modifier = Modifier.onFocusChanged {
                        if (it.isFocused) backgroundFocus += "Back"
                    }) { Text("Fixture Back") }
                    ProfilePinPrompt(request)
                }
            } } }
            await("Verified unset profile offers setup") { focused("New password (4 digits)") }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Confirmation owns focus before the remote update") { focused("Confirm password") }
            backgroundFocus.clear()
            ins.runOnMainSync { request.refresh(known) }
            await("Remote password switches directly to Unlock") {
                find { it.text?.toString() == "Unlock Arrival fixture" } != null &&
                    find { it.text?.toString() == "Confirm password" } == null && focused("PIN")
            }
            assertTrue("Background Back must never gain focus during this transition", backgroundFocus.isEmpty())
            assertNull(find { it.text?.toString() == "Set password" })
            assertFalse(request.completeSetup("replacement"))
            NavigationAuditEvidence.capture("profile-password-arrived-during-setup")
        } finally { ins.runOnMainSync { host.finish() } }
    }
    @Test fun unavailableGithubAllowsOverrideUnlockWithoutPasswordSetup() {
        val context = ins.targetContext
        require(context.packageName.endsWith(".regression"))
        val request = ProfilePinRequest(UserProfile("offline-fixture", "Offline fixture"), false) { error("offline") }
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) { ProfilePinPrompt(request) } } }
            await("Offline unlock is offered after a failed read") { request.checkError != null && focused("Cancel") }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Retry remains in its usual place") { focused("Retry") }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Offline action is reachable") { focused("Offline") }
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Offline opens the PIN editor, not Set password") { focused("PIN") && request.usingOffline }
            assertNull(find { it.text?.toString() == "Set password" })
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("PIN editor activates") { find { it.isEditable && it.isFocused } != null }
            assertTrue(requireNotNull(find { it.isEditable && it.isFocused }).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "2564") }))
            var target = requireNotNull(find { it.text?.toString() == "Unlock" })
            while (!target.isClickable) target = requireNotNull(target.parent)
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Override opens the cached profile") { request.result.isCompleted }
            assertTrue(kotlinx.coroutines.runBlocking { request.result.await() })
            assertNull(request.newPinVerifier)
        } finally { ins.runOnMainSync { host.finish() } }
    }
    @Test fun firstEntrySetsAnyChosenPasswordInsteadOfUnlocking() {
        val context = ins.targetContext
        require(context.packageName.endsWith(".regression"))
        val profile = UserProfile("setup-fixture", "New profile")
        val request = ProfilePinRequest(profile, deleting = false) { profile }
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        try {
            ins.setInTouchMode(false)
            ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) { ProfilePinPrompt(request) } } }
            await("First entry clearly offers password setup") { find { it.text?.toString() == "Set password for New profile" } != null }
            assertNull(find { it.text?.toString() == "Unlock" })
            await("New password field owns initial D-pad focus") { focused("New password (4 digits)") }
            fun enter(value: String) {
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("OK activates the focused password field") { find { it.isEditable && it.isFocused } != null }
                assertTrue(requireNotNull(find { it.isEditable && it.isFocused }).performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
            }
            enter("4826")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            enter("4826")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("Set password is enabled and focused") {
                var target = find { it.text?.toString() == "Set password" }
                while (target != null && !target.isClickable) target = target.parent
                target?.let { it.isEnabled && it.isFocused } == true
            }
            NavigationAuditEvidence.capture("profile-set-password-focused")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Any chosen four-digit password completes setup") { request.result.isCompleted }
            assertTrue(kotlinx.coroutines.runBlocking { request.result.await() })
            assertTrue(ProfilePin.verifies(request.newPinVerifier, "4826"))
            assertFalse(ProfilePin.verifies(request.newPinVerifier, "0000"))
        } finally { ins.runOnMainSync { host.finish() } }
    }
    @Test fun ownerPinAndOverrideWorkButWrongPinCannotAuthorizeDeletion() {
        val context = ins.targetContext
        require(context.packageName.endsWith(".regression"))
        val profile = UserProfile("pin-test", "PIN fixture", pinVerifier = ProfilePin.create("1357"))
        for (correct in listOf("1357", "2564")) {
            val request = ProfilePinRequest(profile, deleting = true)
            val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
            try {
                ins.setInTouchMode(false)
                ins.runOnMainSync { host.setContent { MaterialTheme(colorScheme = NikColors) { ProfilePinPrompt(request) } } }
                await("PIN field appears") { find { it.text?.toString() == "PIN" } != null }
                ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                fun enter(pin: String) {
                    await("PIN editor activated by OK") { find { it.isEditable } != null }
                    assertTrue(requireNotNull(find { it.isEditable }).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin) }))
                    val button = requireNotNull(find { it.text?.toString() == "Unlock" })
                    var target: AccessibilityNodeInfo? = button
                    while (target != null && !target.isClickable) target = target.parent
                    assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                }
                enter("0000")
                await("Wrong PIN shows an error") { find { it.text?.toString() == "Incorrect PIN. Try again." } != null }
                assertFalse("Wrong PIN cannot delete", request.result.isCompleted)
                enter(correct)
                await("Owner or override authorizes the explicit deletion") { request.result.isCompleted }
                assertTrue(kotlinx.coroutines.runBlocking { request.result.await() })
            } finally { ins.runOnMainSync { host.finish() } }
        }
    }
    @Test fun rememberedUnlockIsPerAccountPerProfileAndInvalidatedByPinChange() {
        val context = ins.targetContext
        require(context.packageName.endsWith(".regression"))
        val profile = UserProfile("remember-fixture", "Fixture", pinVerifier = ProfilePin.create("1357"))
        assertFalse(ProfilePin.remembered(context, "account-A", profile))
        ProfilePin.remember(context, "account-A", profile)
        assertTrue(ProfilePin.remembered(context, "account-A", profile))
        assertFalse(ProfilePin.remembered(context, "account-B", profile))
        assertFalse(ProfilePin.remembered(context, "account-A", profile.copy(id = "different")))
        assertFalse(ProfilePin.remembered(context, "account-A", profile.copy(pinVerifier = ProfilePin.create("9753"))))
    }
}
