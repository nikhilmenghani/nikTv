package com.nikhil.niktv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

/** Exact paused seeks and cold resume, using the original sparse MKVs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MkvCaptionSeekDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = ins.runOnMainSync(action)
    private fun await(label: String, timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(30)
        }
        fail(label)
    }
    private fun playerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) playerView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun text(player: Player): String {
        var result = ""
        main { result = player.currentCues.cues.joinToString("\n") { it.text?.toString().orEmpty() } }
        return result
    }

    @Test fun directBackwardForwardAndColdResumeRetainSparseSubripAndAss() {
        for ((name, token) in listOf("case03-subrip-ar.mkv" to "Case03", "case06-ass-styled-en.mkv" to "Case06")) {
            fixture(name, token, 0) { player, folder ->
                main { player.seekTo(3_000) }
                await("$name initially renders") { text(player).contains(token) }
                for (target in listOf(3_000L, 6_500L, 1_500L)) {
                    main { player.seekTo(7_500) }
                    await("$name clears expired cue") { text(player).isBlank() }
                    val started = SystemClock.elapsedRealtime()
                    main { player.seekTo(target) }
                    await("$name restores active caption at $target without playing from zero") { text(player).contains(token) }
                    var actualPosition = 0L
                    var playing = true
                    main { actualPosition = player.currentPosition; playing = player.playWhenReady }
                    assertEquals("Video stays at the requested paused position", target, actualPosition)
                    assertFalse("Caption recovery does not start playback", playing)
                    File(folder, "$token-seek-times.txt").appendText("target=$target restoredMs=${SystemClock.elapsedRealtime() - started}\n")
                }
                screenshot(folder, "$token-backward-seek")
                main { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
                await("Off clears recovered captions") { text(player).isBlank() }
                main { player.seekTo(3_000) }
                ins.waitForIdleSync()
                assertTrue("Seeking while Off never resurrects a cached caption", text(player).isBlank())
            }
            fixture(name, token, 3_000) { player, folder ->
                await("$name cold resume restores the previously unseen active cue") { text(player).contains(token) }
                var position = 0L
                main { position = player.currentPosition }
                assertEquals("Cold resume preserves the requested position", 3_000L, position)
                screenshot(folder, "$token-cold-resume")
            }
        }
    }

    @Test fun switchingLanguagesAtPausedSeekNeverReplaysPreviousTrack() = fixture("case07-multi-en-fr.mkv", "Case07", 3_000) { player, folder ->
        await("English cue at cold resume") { text(player).contains("Case07 English") }
        main {
            val french = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }[1]
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setOverrideForType(TrackSelectionOverride(french.mediaTrackGroup, listOf(0))).build()
        }
        await("French replaces English at the paused timestamp") { text(player).contains("Case07FR") && !text(player).contains("English dialogue") }
        main { player.seekTo(7_500) }
        await("French ends at its real end") { text(player).isBlank() }
        main { player.seekTo(3_000) }
        await("Only French returns after backward seek") { text(player).contains("Case07FR") && !text(player).contains("English dialogue") }
        screenshot(folder, "Case07-French-backward-seek")
    }

    private fun fixture(name: String, token: String, resume: Long, check: (Player, File) -> Unit) {
        val context = ins.targetContext
        require(context.packageName.endsWith(".regression"))
        (context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
            .setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val folder = File(context.filesDir, "mkv-caption-seek").apply { mkdirs() }
        ZipInputStream(ins.context.assets.open("caption-fixtures.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) File(folder, name).outputStream().use { zip.copyTo(it) }
            }
        }
        val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
        val exits = AtomicInteger()
        try {
            main { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("Window ready") { host.hasWindowFocus() }
            main { host.setContent { MaterialTheme(colorScheme = NikColors) {
                PlayerScreen(PlayingMedia(MediaItem(token, name, null, null), Uri.fromFile(File(folder, name)).toString(),
                    CatalogType.MOVIES, progressKey = "$token-$resume", profileKey = "caption-fixture",
                    resumePositionMillis = resume, playWhenReady = false), onBack = { exits.incrementAndGet() },
                    onRetry = {}, onRetryAlternateDecoder = {}, onPlaybackAuthorizationFailure = {},
                    onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                    playbackEngine = PlaybackEngine.MEDIA3, startFullscreen = true, controlsTimeoutSeconds = 0)
            } } }
            var active: Player? = null
            await("Native player and text tracks are ready") {
                main { active = playerView(host.window.decorView)?.player }
                var ready = false
                main { ready = active?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT } == true && active?.playbackState == Player.STATE_READY }
                ready
            }
            val player = requireNotNull(active)
            main {
                val first = player.currentTracks.groups.first { it.type == C.TRACK_TYPE_TEXT }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(first.mediaTrackGroup, listOf(0))).build()
            }
            check(player, folder)
            main { assertSame("No hidden engine replacement", player, playerView(host.window.decorView)?.player) }
            assertEquals(0, exits.get())
        } finally { main { host.finish() } }
    }

    private fun screenshot(folder: File, name: String) {
        ins.waitForIdleSync()
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
}
