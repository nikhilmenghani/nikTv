package com.nikhil.niktv

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.PlaybackEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Independent file-manager UID grants one synthetic video; the sender then leaves and finishes. */
class LanExternalVideoDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun await(message: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            root ?: return null
            if (root.text?.toString() == label || root.contentDescription?.toString() == label) return root
            for (i in 0 until root.childCount) find(root.getChild(i))?.let { return it }
            return null
        }
        return find(instrument.uiAutomation.rootInActiveWindow)
    }
    private fun click(label: String) {
        await("Missing action $label") { node(label) != null }
        var candidate = node(label)
        while (candidate != null) { if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; candidate = candidate.parent }
        fail("Cannot click $label")
    }
    private fun activity(): ExternalVideoActivity? {
        var found: ExternalVideoActivity? = null
        instrument.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ExternalVideoActivity>().firstOrNull() }
        return found
    }
    @Suppress("DEPRECATION")
    private fun sharing() = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(100)
        .any { it.service.className == LanMediaShareService::class.java.name && it.foreground }
    private fun phase(name: String) { instrument.sendStatus(0, Bundle().apply { putString("stream", "LAN_PHASE $name\n") }) }
    @Test fun selectedFileStreamsWhileOtherAppsAreOpenAndAfterActivityFinishes() {
        check(context.packageName.endsWith(".regression"))
        val args = InstrumentationRegistry.getArguments()
        val host = requireNotNull(args.getString("receiverHost"))
        val port = requireNotNull(args.getString("receiverPort")).toInt()
        val code = requireNotNull(args.getString("receiverPin"))
        runBlocking { ProfileStore(context).setPlaybackEngine(PlaybackEngine.MEDIA3); ProfileStore(context).setPlayerControlsTimeoutSeconds(0) }
        instrument.setInTouchMode(false)
        val sender = Intent().setClassName(instrument.context.packageName, "com.nikhil.niktv.ExternalVideoTestSender")
            .putExtra("target", context.packageName).putExtra("nonce", java.util.UUID.randomUUID().toString())
            .putExtra("mp4", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(sender)
        var player: ExternalVideoActivity? = null
        var controller: LanPlaybackController? = null
        try {
            await("File manager launches NikTV external player") { activity().also { player = it } != null }
            val active = requireNotNull(player)
            lateinit var model: ExternalVideoViewModel
            instrument.runOnMainSync { model = ViewModelProvider(active)[ExternalVideoViewModel::class.java] }
            await("Synthetic granted video is loaded") { model.state.value.media != null }
            val selected = requireNotNull(model.state.value.media)
            repeat(6) { if (node("More controls") == null) { instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP); Thread.sleep(350) } }
            controller = model.lanPlayback
            instrument.runOnMainSync { controller!!.pair(LanDevice("Tablet fixture receiver", host, port), code) }
            await("On-screen receiver code pairs") { controller!!.state.value.target != null }
            // The real player action owns the handoff and pauses/removes the local player.
            click("Play on NikTV")
            await("Receiver accepts file and sender enters remote state", 45_000) { controller!!.state.value.remoteVideo == selected.media.title }
            assertTrue(sharing())
            fun receiverReady(): LanReply {
                var status: LanReply? = null
                await("Receiver actually renders the selected file", 60_000) {
                    status = runBlocking { withContext(Dispatchers.Main) { controller!!.send("status") } }
                    assertFalse(requireNotNull(status).message, requireNotNull(status).message.startsWith("Playback failed"))
                    requireNotNull(status).videoReady && requireNotNull(status).positionMillis > 1000
                }
                return requireNotNull(status)
            }
            val before = receiverReady().positionMillis
            phase("REMOTE_FILE_STARTED")
            instrument.uiAutomation.executeShellCommand("am start -a android.settings.SETTINGS").close()
            Thread.sleep(20_000)
            assertTrue("Foreground share survives another app", sharing())
            assertTrue("Receiver advances while sender uses another app", receiverReady().positionMillis > before + 5000)
            phase("OTHER_APP_STREAMING")
            instrument.runOnMainSync { active.finish() }
            Thread.sleep(15_000)
            // The activity's original grant has ended. The started service still owns a read grant.
            context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(selected.url), "r")!!.use { assertTrue(it.parcelFileDescriptor.statSize > 0) }
            assertTrue("Sharing survives sender activity destruction", sharing())
            // Force a fresh receiver HTTP open after the activity grant is gone, rather than relying on buffered bytes.
            val retained = requireNotNull(controller!!.sharedVideo)
            runBlocking { withContext(Dispatchers.Main) { controller!!.send("play", video = retained.copy(positionMillis = 50_000)) } }
            assertTrue("Receiver reopens and seeks the file after activity destruction", receiverReady().positionMillis >= 48_000)
            phase("ACTIVITY_FINISHED_STREAMING")
            Thread.sleep(15_000)
            runBlocking { withContext(Dispatchers.Main) { controller!!.control("stop").join() } }
            await("Stop closes foreground sharing") { !sharing() }
        } finally {
            LanMediaSharing.stop(context)
            controller?.let { runBlocking { withContext(Dispatchers.Main) { runCatching { it.send("stop") }; it.disconnect() } } }
            instrument.runOnMainSync { player?.finish() }
            instrument.setInTouchMode(true)
        }
    }
}
