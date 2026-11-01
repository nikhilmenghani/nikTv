package com.nikhil.niktv

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.model.PlaybackEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Private content provider in the separate test APK; no personal files or network. */
class ExternalVideoDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun main(action: () -> Unit) = instrument.runOnMainSync(action)
    private fun await(message: String, timeout: Long = 25_000, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { if (check()) return; Thread.sleep(80) }
        fail(message)
    }
    private fun node(text: String, prefix: Boolean = false): AccessibilityNodeInfo? {
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            root ?: return null
            if (!root.refresh()) return null
            val values = listOfNotNull(root.text?.toString(), root.contentDescription?.toString())
            if (values.any { if (prefix) it.startsWith(text) else it == text }) return root
            for (i in 0 until root.childCount) find(root.getChild(i))?.let { return it }
            return null
        }
        return find(instrument.uiAutomation.rootInActiveWindow)
    }
    private fun click(text: String, prefix: Boolean = false) {
        await("Missing control: $text") { node(text, prefix) != null }
        var item = node(text, prefix)
        while (item != null) {
            item.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            item = item.parent
        }
        fail("Control unreachable: $text")
    }
    private fun focused(text: String): Boolean {
        var current = node(text)
        repeat(6) { if (current?.isFocused == true) return true; current = current?.parent }
        return false
    }
    private fun seconds(): Long? = node("Current playback time ", true)?.contentDescription?.toString()
        ?.substringAfter("Current playback time ")?.split(':')?.mapNotNull(String::toLongOrNull)?.fold(0L) { total, value -> total * 60 + value }
    private fun visibleSeconds(): Long {
        var value: Long? = null
        await("Player controls return after popup dismissal") { seconds().also { value = it } != null }
        return requireNotNull(value)
    }
    private fun playerActivity(): ExternalVideoActivity? {
        var found: ExternalVideoActivity? = null
        main { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ExternalVideoActivity>().firstOrNull() }
        return found
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun media3Position(activity: ExternalVideoActivity): Pair<Long, Float>? {
        var value: Pair<Long, Float>? = null
        fun find(view: android.view.View): androidx.media3.common.Player? {
            if (view is androidx.media3.ui.PlayerView) return view.player
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        main { find(activity.window.decorView)?.let { value = it.currentPosition to it.playbackParameters.speed } }
        return value
    }
    @Test fun exoPrivateFileAndSpeed() = verify(PlaybackEngine.MEDIA3)
    @Test fun flowPrivateFileAndSpeed() = verify(PlaybackEngine.FLOW)
    @Test fun vlcPrivateFileAndSpeed() = verify(PlaybackEngine.VLC)
    @Test fun mpvPrivateFileAndSpeed() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 26)
        verify(PlaybackEngine.MPV)
    }
    private fun verify(engine: PlaybackEngine) {
        check(context.packageName.endsWith(".regression"))
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val store = ProfileStore(context)
        val old = runBlocking { store.playbackEngine.first() }
        val timeout = runBlocking { store.playerControlsTimeoutSeconds.first() }
        var activity: ExternalVideoActivity? = null
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        try {
            runBlocking { store.setPlaybackEngine(engine); store.setPlayerControlsTimeoutSeconds(0) }
            instrument.setInTouchMode(false)
            val nonce = java.util.UUID.randomUUID().toString()
            val uri = Uri.parse("content://${instrument.context.packageName}.externalfixture/video/$nonce")
            try { context.contentResolver.openFileDescriptor(uri, "r")?.close(); fail("Private file accessible before a grant") }
            catch (_: SecurityException) { }
            val handlers = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4"), 0)
            assertTrue("NikTV must appear in Android Open with", handlers.any { it.activityInfo.packageName == context.packageName && it.activityInfo.name == ExternalVideoActivity::class.java.name })
            context.startActivity(Intent().setClassName(instrument.context.packageName, ExternalVideoTestSender::class.java.name)
                .putExtra("target", context.packageName).putExtra("nonce", nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("NikTV direct player must open") { playerActivity()?.also { activity = it } != null }
            repeat(6) {
                if (seconds() == null) {
                    instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                    Thread.sleep(350)
                }
            }
            await("$engine private granted file must play") { (seconds() ?: -1) >= 1 }
            assertNotNull(context.contentResolver.openFileDescriptor(uri, "r")?.use { it.fileDescriptor })
            assertNotNull(node("File-manager fixture.ts"))
            fun steadyPosition(speed: Float): Long {
                visibleSeconds()
                if (engine in listOf(PlaybackEngine.MEDIA3, PlaybackEngine.FLOW)) {
                    await("$engine accepts rate $speed") { media3Position(requireNotNull(activity))?.second == speed }
                    // Sonic drains already queued audio at a rate change; measure
                    // steady playback rather than this short transition window.
                    Thread.sleep(2_000)
                    return requireNotNull(media3Position(requireNotNull(activity))).first
                }
                return visibleSeconds() * 1000
            }
            fun currentPosition() = if (engine in listOf(PlaybackEngine.MEDIA3, PlaybackEngine.FLOW))
                requireNotNull(media3Position(requireNotNull(activity))).first else visibleSeconds() * 1000
            if (node("Playback speed") == null) click("More controls")
            click("Playback speed")
            await("Speed picker defaults to normal rate") { focused("1×") }
            instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { java.io.File(context.getExternalFilesDir(null), "external-speed-panel.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                } } finally { bitmap.recycle() }
            }
            click("2×"); click("Close")
            val start = steadyPosition(2f)
            Thread.sleep(4_000)
            val fast = currentPosition() - start
            assertTrue("$engine 2× must advance faster: $fast ms in 4 seconds", fast in 6_000L..11_000L)
            click("Playback speed"); click("0.5×"); click("Close")
            val slowStart = steadyPosition(.5f)
            Thread.sleep(4_000)
            val slow = currentPosition() - slowStart
            assertTrue("$engine 0.5× must slow playback: $slow ms in 4 seconds", slow in 1_000L..3_000L)
            click("Playback speed"); click("0.1×")
            assertNotNull(node("Playback speed · 0.1×"))
            click("Close")
            val verySlow = steadyPosition(.1f)
            Thread.sleep(10_000)
            val slowest = currentPosition() - verySlow
            assertTrue("$engine 0.1× advances slowly: $slowest ms", slowest in 0L..2_000L)
            click("Playback speed"); click("4×"); assertNotNull(node("Playback speed · 4×")); click("Close")
            val veryFast = steadyPosition(4f)
            Thread.sleep(2_000)
            val fastest = currentPosition() - veryFast
            assertTrue("$engine 4× advances rapidly: $fastest ms", fastest in 6_000L..12_000L)
            click("Playback speed"); click("1×"); click("Close")
            // Closing the overlay restores its origin; D-pad OK can reopen it.
            await("Speed control focus is restored") { focused("Playback speed") }
            instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Speed focus returns to its originating control") { node("Playback speed · 1×") != null }
            click("Close")
            main {
                val model = ViewModelProvider(requireNotNull(activity))[ExternalVideoViewModel::class.java]
                assertTrue(model.state.value.media!!.externalPlayback)
                assertTrue(model.state.value.media!!.profileKey.isBlank())
            }
        } finally {
            main { activity?.finish() }
            instrument.waitForIdleSync(); Thread.sleep(350)
            runBlocking { store.setPlaybackEngine(old); store.setPlayerControlsTimeoutSeconds(timeout) }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
