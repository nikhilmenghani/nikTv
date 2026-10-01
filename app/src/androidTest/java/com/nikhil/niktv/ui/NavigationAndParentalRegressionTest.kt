package com.nikhil.niktv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.ParentalControls
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NavigationAndParentalRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun find(node: AccessibilityNodeInfo?, check: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (check(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), check)?.let { return it }
        return null
    }
    private fun label(text: String) = find(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.toString() == text || it.contentDescription?.toString() == text
    }
    private fun click(text: String) {
        await("Action must be visible: $text") { label(text) != null }
        var node = label(text)
        while (node != null) { if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; node = node.parent }
        fail("Action cannot be clicked: $text")
    }
    private fun open(type: Class<out android.app.Activity>): android.app.Activity {
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".regression"))
        return instrumentation.startActivitySync(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun enterPin(value: String) {
        var field: AccessibilityNodeInfo? = null
        await("PIN field must be editable") { field = find(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable }; field != null }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
    }
    @Test fun previousAndNextActionsNavigateFromPlaybackFailure() {
        File(instrumentation.targetContext.cacheDir, "retry.wav").delete()
        val activity = open(PlaybackRegressionActivity::class.java)
        try {
            click("Next channel")
            await("Next must select the second fixture") { PlaybackRegressionActivity.currentMediaId == "second" }
            click("Previous channel")
            await("Previous must return to the first fixture") { PlaybackRegressionActivity.currentMediaId == "fixture" && PlaybackRegressionActivity.navigations == 2 }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            await("Transport Next must use window dispatch") { PlaybackRegressionActivity.currentMediaId == "second" }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            await("Transport Previous must use window dispatch") { PlaybackRegressionActivity.currentMediaId == "fixture" && PlaybackRegressionActivity.navigations == 4 }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun actualRotationRecreatesActivityAndPlaybackResumes() {
        val file = File(instrumentation.targetContext.cacheDir, "retry.wav")
        val samples = 44100 * 30
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort(0) }
        file.writeBytes(bytes.array())
        val activity = open(PlaybackRegressionActivity::class.java)
        val originalRotation = activity.windowManager.defaultDisplay.rotation
        val resolver = instrumentation.targetContext.contentResolver
        val automaticRotation = android.provider.Settings.System.getInt(resolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
        val configuredRotation = android.provider.Settings.System.getInt(resolver,
            android.provider.Settings.System.USER_ROTATION, 0)
        try {
            await("Fixture must play before rotation") { PlaybackRegressionActivity.progress > 3500 }
            val before = PlaybackRegressionActivity.progress
            val generation = PlaybackRegressionActivity.creations
            // API 36 ignores requestedOrientation on large screens. Rotate the actual
            // display instead, and restore both original rotation preferences below.
            val target = if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                android.app.UiAutomation.ROTATION_FREEZE_0 else android.app.UiAutomation.ROTATION_FREEZE_90
            assertTrue("Display rotation must be accepted", instrumentation.uiAutomation.setRotation(target))
            await("Rotation must recreate the activity") { PlaybackRegressionActivity.creations > generation }
            await("Playback must resume after rotation") { PlaybackRegressionActivity.progress > before + 1500 }
        } finally {
            instrumentation.uiAutomation.setRotation(originalRotation)
            fun restoreSetting(name: String, value: Int) {
                val descriptor = instrumentation.uiAutomation.executeShellCommand("settings put system $name $value")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
            restoreSetting("user_rotation", configuredRotation)
            restoreSetting("accelerometer_rotation", automaticRotation)
            instrumentation.runOnMainSync { PlaybackRegressionActivity.instance?.finish() }
            file.delete()
        }
    }
    @Test fun incorrectPinStaysLockedAndConfiguredPinUnlocksDialog() {
        val activity = open(ParentalRegressionActivity::class.java)
        try {
            enterPin("7002")
            click("Unlock")
            await("Incorrect PIN must show an error") { label("Incorrect PIN. Try again.") != null }
            assertFalse(ParentalRegressionActivity.unlocked)
            enterPin("7001")
            click("Unlock")
            await("Configured PIN must unlock") { ParentalRegressionActivity.unlocked && label("Fixture unlocked") != null }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun cancellingParentalDialogDoesNotUnlock() {
        val activity = open(ParentalRegressionActivity::class.java)
        try { click("Cancel"); await("Cancel must close the dialog") { ParentalRegressionActivity.cancelled }; assertFalse(ParentalRegressionActivity.unlocked) }
        finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun changingPinAndRelockPreferencesUseIsolatedDeviceStorage() {
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".regression"))
        ParentalControls.setPin(context, "7001")
        ParentalControls.setPin(context, "7003")
        assertFalse(ParentalControls.accepts(context, "7001"))
        assertTrue(ParentalControls.accepts(context, "7003"))
        ParentalControls.setRelockMinutes(context, 1)
        assertEquals(1, ParentalControls.relockMinutes(context))
        ParentalControls.setEnabled(context, false)
        assertFalse(ParentalControls.enabled(context))
        ParentalControls.setEnabled(context, true)
        assertTrue(ParentalControls.enabled(context))
    }
}
