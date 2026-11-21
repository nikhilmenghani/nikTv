package com.nikhil.niktv.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.sports.PlaybackSimulationActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the exported debug entry and actual popup player with deterministic local media. */
class PlaybackSimulationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun await(message: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        NavigationAuditEvidence.capture("playback-simulation-failure")
        fail(message)
    }

    private fun node(tag: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun find(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            current ?: return null
            val resource = current.viewIdResourceName.orEmpty()
            if (resource == tag || resource.endsWith(":id/$tag")) return current
            for (index in 0 until current.childCount) find(current.getChild(index))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun text(tag: String): String? {
        fun label(current: AccessibilityNodeInfo?): String? {
            current ?: return null
            current.text?.toString()?.takeIf(String::isNotBlank)?.let { return it }
            current.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let { return it }
            for (index in 0 until current.childCount) label(current.getChild(index))?.let { return it }
            return null
        }
        return label(node(tag))
    }

    private fun focused(tag: String): Boolean {
        var current = node(tag)
        repeat(8) {
            if (current?.isFocused == true) return true
            current = current?.parent
        }
        return false
    }

    private fun focus(tag: String) {
        await("Reachable $tag") { node(tag) != null }
        instrumentation.setInTouchMode(false)
        await("Focusable $tag") {
            var current = node(tag)
            repeat(8) { if (current?.isFocusable != true) current = current?.parent }
            focused(tag) || current?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true
        }
        await("$tag owns focus before activation") { focused(tag) }
    }

    private fun activate(tag: String) {
        focus(tag)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun frameCount(): Int = Regex("(\\d+) frames")
        .find(text("playbackFrames").orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun launch(uri: Uri): PlaybackSimulationActivity {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/dash+xml")
            .setClass(context, PlaybackSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as PlaybackSimulationActivity
        await("The actual handoff opens its player popup") { node("playbackPopup") != null }
        return activity
    }

    private fun withLocalPlayer(test: (PlaybackEngineFixtureTest.FixtureServer) -> Unit) {
        check(context.packageName.endsWith(".regression"))
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            PlaybackEngineFixtureTest().FixtureServer().use(test)
        } finally {
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
            instrumentation.setInTouchMode(true)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }

    @Test fun viewHandoffRendersAndAdvancesThenClosesDirectlyToItsOrigin() = withLocalPlayer { server ->
        val activity = launch(Uri.parse("${server.base}/manifest.mpd"))
        try {
            await("A real decoded frame reaches the popup") {
                text("playbackFirstFrame") == "First frame: verified by the player" && frameCount() > 0
            }
            val frames = frameCount()
            await("The real video keeps rendering new frames", 12_000) {
                frameCount() >= frames + 5
            }
            assertNotNull("DASH fixture was requested", server.paths.firstOrNull { it == "manifest.mpd" })
            assertEquals(0, server.rejected.get())
            assertTrue("Popup has reachable retry", node("retryPlayback") != null)
            focus("closePlayback")
            NavigationAuditEvidence.capture("playback-simulation-popup-focused")

            val focusEvents = CopyOnWriteArrayList<String>()
            instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                    val source = event.source
                    val identifier = source?.viewIdResourceName?.substringAfter(":id/")
                    val label = event.contentDescription ?: source?.contentDescription
                        ?: event.text.firstOrNull() ?: source?.text
                    (identifier ?: label?.toString())?.let(focusEvents::add)
                }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Close removes the popup and directly restores its origin") {
                node("playbackPopup") == null && focused("playExternal")
            }
            assertTrue("Origin is present in the focus transition: $focusEvents",
                focusEvents.any { it == "playExternal" || it == "Play supplied URL" })
            assertFalse("Closing must not focus Back, Home, or an unrelated source: $focusEvents",
                focusEvents.any { it in setOf("Back", "Home", "Close", "Retry", "retryPlayback", "playbackUrl") || it.startsWith("sample:") })
            NavigationAuditEvidence.capture("playback-simulation-closed-origin")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun rejectedLinkCanRetryTheSameHandoffAfterTheLocalSourceRecovers() = withLocalPlayer { server ->
        server.requiredHeaders = mapOf("X-NikTV-Fixture" to "reject-until-retry")
        val activity = launch(Uri.parse("${server.base}/manifest.mpd"))
        try {
            await("The popup reports source rejection") { node("playbackFailure") != null && server.rejected.get() > 0 }
            assertEquals("A rejected source cannot report a decoded frame", 0, frameCount())
            server.requiredHeaders = emptyMap()
            val requestsBeforeRetry = synchronized(server.paths) { server.paths.size }
            activate("retryPlayback")
            await("Retry starts a fresh request and renders the local video") {
                synchronized(server.paths) { server.paths.size > requestsBeforeRetry } &&
                    text("playbackFirstFrame") == "First frame: verified by the player" && frameCount() > 0
            }
            assertTrue("Successful retry clears the prior error", node("playbackFailure") == null)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun unsupportedViewHandoffShowsAnErrorAndCloseRemainsReachable() = withLocalPlayer { server ->
        val activity = launch(Uri.parse("ftp://example.invalid/video.mpd"))
        try {
            await("Unsupported VIEW scheme is rejected in the popup") {
                text("playbackFailure") == "This video link is not supported."
            }
            assertEquals(0, frameCount())
            assertTrue("Invalid input must not request fixture media", server.paths.isEmpty())
            activate("closePlayback")
            await("Invalid handoff can close without trapping remote focus") {
                node("playbackPopup") == null && focused("playExternal")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
