package com.nikhil.niktv.sports

import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SportsDeviceTest {
    @Test fun liveCatalogueAndNativePlayback() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression")) { "Device test requires the isolated regression application." }
        val activity = instrumentation.startActivitySync(Intent(context, SportsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsActivity
        try {
            lateinit var vm: SportsViewModel
            instrumentation.runOnMainSync { vm = ViewModelProvider(activity)[SportsViewModel::class.java] }
            await(45_000) { !vm.state.value.loading }
            assertNull(vm.state.value.error)
            val events = vm.state.value.events
            assertTrue("Remote catalogue should contain events", events.isNotEmpty())
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue("Sports activity is displayed", activity.window.decorView.isShown) }
            val event = events.firstOrNull { it.status(Instant.now()) == SportsStatus.LIVE }
                ?: events.filter { it.sport == "Cricket" }.maxByOrNull { it.choices.size } ?: events.first()
            instrumentation.runOnMainSync { vm.open(event) }
            await(30_000) { !vm.state.value.loadingStreams }
            assertNull(vm.state.value.streamError)
            assertTrue("Selected event should have stream choices", vm.state.value.streams.isNotEmpty())
            val indexed = vm.state.value.streams.withIndex().toList()
            val choices = indexed.filter { it.value.type == 0 }.take(3) + indexed.filter { it.value.type == 7 }.take(3)
            assertTrue("A native stream is available", choices.isNotEmpty())
            val outcomes = mutableListOf<String>()
            var rendered = false
            for (choice in choices) {
                instrumentation.runOnMainSync { vm.play(choice.index) }
                await(35_000) { vm.state.value.resolving == null }
                val stream = vm.state.value.playing
                if (stream == null) { outcomes += "type ${choice.value.type}: resolver failure"; continue }
                val latch = CountDownLatch(1)
                var error: String? = null
                var firstFrame = false
                var player: ExoPlayer? = null
                instrumentation.runOnMainSync {
                    player = sportsPlayer(activity, stream)
                    player!!.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { firstFrame = true; latch.countDown() }
                        override fun onPlayerError(failure: PlaybackException) { error = failure.errorCodeName; latch.countDown() }
                    })
                    activity.setContentView(PlayerView(activity).apply {
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                        this.player = player
                    })
                }
                latch.await(20, TimeUnit.SECONDS)
                instrumentation.runOnMainSync { player?.release() }
                outcomes += "type ${stream.type}: ${if (firstFrame) "first video frame rendered" else error ?: "startup timeout"}"
                if (firstFrame) { rendered = true; break }
            }
            android.util.Log.i("NikTvSportsTest", outcomes.joinToString("; "))
            assertTrue("At least one stream must render video: ${outcomes.joinToString()}", rendered)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun await(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(150)
        assertTrue("Timed out waiting for Sports state", condition())
    }
}
