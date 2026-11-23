package com.nikhil.niktv.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
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
