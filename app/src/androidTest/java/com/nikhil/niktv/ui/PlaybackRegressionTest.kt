package com.nikhil.niktv.ui

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 35000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(200)
        }
        fail("$message; progress=${PlaybackRegressionActivity.progress}, duration=${PlaybackRegressionActivity.knownDuration}, retries=${PlaybackRegressionActivity.retries}, retryPosition=${PlaybackRegressionActivity.retryPosition}")
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    private fun retryNode() = find(instrumentation.uiAutomation.rootInActiveWindow, "Retry with fresh link")
        ?: find(instrumentation.uiAutomation.rootInActiveWindow, "Retry")
    private fun fixture(file: File) {
        val samples = 44100 * 20
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort(0) }
        file.writeBytes(bytes.array())
    }
    private fun exercise(engine: String) {
        val context = instrumentation.targetContext
        assertTrue("Tests must use the isolated regression package", context.packageName.endsWith(".regression"))
        val file = File(context.cacheDir, "retry.wav")
        file.delete()
        val intent = Intent(context, PlaybackRegressionActivity::class.java)
            .putExtra("engine", engine).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            await("Missing local fixture must show a Retry action") { retryNode() != null }
            fixture(file)
            var clicked = false
            instrumentation.runOnMainSync { }
            var node = retryNode()
            while (node != null && !clicked) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = node.parent
            }
            assertTrue("Retry action must be clickable", clicked)
            await("Manual Retry must invoke manual callback") { PlaybackRegressionActivity.retries == 1 }
            await("Retry of the identical URL must resume actual playback") { PlaybackRegressionActivity.progress > 3500L }
            assertEquals("Manual retry must not use automatic authorization callback", 0, PlaybackRegressionActivity.automaticRetries)
            await("Fatal playback dialog must clear after recovery") {
                find(instrumentation.uiAutomation.rootInActiveWindow, "This title can't be played right now") == null
            }
            assertTrue("Retry must retain the initial resume position", PlaybackRegressionActivity.retryPosition >= 2500L)
            assertTrue("Resume must stay at or beyond the requested position", PlaybackRegressionActivity.progress >= PlaybackRegressionActivity.retryPosition)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            file.delete()
        }
    }
    private fun exerciseEngineSwitch(engine: String, targetLabel: String) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val file = File(context.cacheDir, "retry.wav")
        file.delete()
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
            .putExtra("engine", engine).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("Error must offer the full player chooser") {
                find(instrumentation.uiAutomation.rootInActiveWindow, "Choose player") != null
            }
            var chooser = find(instrumentation.uiAutomation.rootInActiveWindow, "Choose player")
            while (chooser != null && !chooser.performAction(AccessibilityNodeInfo.ACTION_CLICK)) chooser = chooser.parent
            await("A startup error must offer $targetLabel") {
                find(instrumentation.uiAutomation.rootInActiveWindow, targetLabel) != null
            }
            fixture(file)
            var clicked = false
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, targetLabel)
            while (node != null && !clicked) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = node.parent
            }
            assertTrue(clicked)
            await("Switching engine must notify the user choice") { PlaybackRegressionActivity.enginePreferenceChanges == 1 }
            await("Engine switch must preserve the pending resume position") { PlaybackRegressionActivity.progress >= 3500L }
            assertEquals("Engine switches reuse the resolved URL", 0, PlaybackRegressionActivity.retries)
            assertEquals(0, PlaybackRegressionActivity.automaticRetries)
        } finally { instrumentation.runOnMainSync { activity.finish() }; file.delete() }
    }
    @Test fun media3ErrorCanSwitchToVlcWithoutLosingResume() = exerciseEngineSwitch("MEDIA3", "VLC")
    @Test fun vlcErrorCanSwitchToMedia3WithoutLosingPendingResume() = exerciseEngineSwitch("VLC", "ExoPlayer")

    @Test fun testMedia3SameUrlRetry() = exercise("MEDIA3")
    @Test fun testFlowSameUrlRetry() = exercise("FLOW")
    @Test fun flowErrorCanSwitchToVlcWithoutLosingResume() = exerciseEngineSwitch("FLOW", "VLC")
    @Test fun testVlcManualRetryAfterAutomaticAttempt() = exercise("VLC")

    @Test fun vlcExplicitRetryResetsPausedIntentForSameMedia() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val file = File(context.cacheDir, "retry.wav")
        fixture(file)
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
            .putExtra("engine", "VLC").putExtra("playWhenReady", false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            Thread.sleep(1000)
            assertTrue("Initial paused session must not advance", PlaybackRegressionActivity.progress <= 2500L)
            instrumentation.runOnMainSync { PlaybackRegressionActivity.retryNow!!.invoke() }
            await("Explicit same-media retry must adopt the new play intent") {
                PlaybackRegressionActivity.progress > 3500L
            }
            assertEquals(1, PlaybackRegressionActivity.retries)
        } finally { instrumentation.runOnMainSync { activity.finish() }; file.delete() }
    }

    @Test fun reauthenticateIsExplicitAndSeparateFromRetryAndAutomaticAuthorization() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val file = File(context.cacheDir, "retry.wav")
        file.delete()
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
            .putExtra("engine", "MEDIA3").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("Failure offers an explicit session refresh") {
                find(instrumentation.uiAutomation.rootInActiveWindow, "Reauthenticate") != null
            }
            assertEquals(0, PlaybackRegressionActivity.reauthentications)
            fixture(file)
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, "Reauthenticate")
            var clicked = false
            while (node != null && !clicked) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = node.parent
            }
            assertTrue(clicked)
            await("Explicit session-refresh callback runs once") { PlaybackRegressionActivity.reauthentications == 1 }
            await("Session refresh retains movie resume") { PlaybackRegressionActivity.progress >= 3500L }
            assertEquals(0, PlaybackRegressionActivity.retries)
            assertEquals(0, PlaybackRegressionActivity.automaticRetries)
        } finally { instrumentation.runOnMainSync { activity.finish() }; file.delete() }
    }

    @Test fun errorChooserListsSupportedEnginesAndBackRestoresChoosePlayerFocus() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        File(context.cacheDir, "retry.wav").delete()
        val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
            .putExtra("engine", "MEDIA3").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("Error shows Choose player") {
                find(instrumentation.uiAutomation.rootInActiveWindow, "Choose player") != null
            }
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, "Choose player")
            var clicked = false
            while (node != null && !clicked) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = node.parent
            }
            assertTrue(clicked)
            await("All device-supported engines are offered") {
                playerPickerOptions(android.os.Build.VERSION.SDK_INT).all {
                    find(instrumentation.uiAutomation.rootInActiveWindow, it.playerChoiceLabel()) != null
                }
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            Thread.sleep(250)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            await("OK reopens chooser from restored error-button focus") {
                find(instrumentation.uiAutomation.rootInActiveWindow, "Flow") != null
            }
            assertEquals(0, PlaybackRegressionActivity.retries)
            assertEquals(0, PlaybackRegressionActivity.reauthentications)
            assertEquals(0, PlaybackRegressionActivity.enginePreferenceChanges)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}

