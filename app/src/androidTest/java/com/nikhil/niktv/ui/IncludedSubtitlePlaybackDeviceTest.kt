package com.nikhil.niktv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

/** Included-file captions through the real chooser/player, without online providers or accounts. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class IncludedSubtitlePlaybackDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = ins.runOnMainSync(block)
    private fun await(label: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) { if (check()) return; Thread.sleep(50) }
        NavigationAuditEvidence.capture("included-caption-failure")
        fail(label)
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        ins.uiAutomation.clearCache()
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            n ?: return null
            if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
            return (0 until n.childCount).firstNotNullOfOrNull { find(n.getChild(it)) }
        }
        return find(ins.uiAutomation.rootInActiveWindow)
    }
    private fun focused(label: String): Boolean {
        var n = node(label)
        while (n != null && !n.isFocusable) n = n.parent
        return n?.isFocused == true
    }
    private fun focus(label: String) {
        await("Focus target exists: $label") { node(label) != null }
        var n = node(label)
        while (n != null && !n.isFocusable) n = n.parent
        assertTrue("Focus $label", n?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
        await("Focused $label") { focused(label) }
    }
    private fun key(code: Int) = ins.sendKeyDownUpSync(code)
    private fun playerView(view: View): PlayerView? = when (view) {
        is PlayerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { playerView(view.getChildAt(it)) }
        else -> null
    }
    private fun openCaptions() {
        if (node("More controls") == null && node("Hide extra controls") == null) {
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad enters the transport") { focused("Play") || focused("Pause") }
        }
        if (node("Hide extra controls") == null) {
            focus("More controls"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Settings opens") { node("Hide extra controls") != null }
        } else focus("Hide extra controls")
        repeat(24) {
            if (!focused("Subtitles")) { key(KeyEvent.KEYCODE_DPAD_LEFT); ins.waitForIdleSync() }
        }
        assertTrue("D-pad reaches Subtitles", focused("Subtitles"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Subtitles opens") { node("Close") != null }
    }

    @Test fun media3IncludedCaptionsLoadSwitchSeekOffAndReturn() = exercise(PlaybackEngine.MEDIA3)
    @Test fun vlcIncludedCaptionsLoadSwitchSeekOffAndReturn() = exercise(PlaybackEngine.VLC)
    @Test fun mpvIncludedCaptionsLoadSwitchSeekOffAndReturn() = exercise(PlaybackEngine.MPV)

    private fun exercise(engine: PlaybackEngine) {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        (context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
            .setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val captions = object : NanoHTTPD("127.0.0.1", 0) {
            val requests = AtomicInteger()
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                val body = when (session.uri) {
                    "/en.srt" -> "1\n00:00:02,000 --> 00:00:07,000\nIncluded English cue\n"
                    "/fr.vtt" -> "WEBVTT\n\n00:02.000 --> 00:07.000\nSous-titres français\n"
                    else -> return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Unavailable")
                }
                return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body)
            }
        }
        captions.start()
        val focusEvents = CopyOnWriteArrayList<String>()
        ins.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                val label = event.contentDescription ?: event.source?.contentDescription
                    ?: event.text.firstOrNull() ?: event.source?.getChild(0)?.text
                label?.let { focusEvents.add(it.toString()) }
            }
        }
        PlaybackEngineFixtureTest().FixtureServer().use { movie ->
            val exits = AtomicInteger()
            val frames = AtomicInteger()
            val host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
            var player: Player? = null
            var vlc: MediaPlayer? = null
            fun pauseAt(position: Long) {
                main {
                    player?.pause(); player?.seekTo(position)
                    vlc?.pause(); vlc?.time = position
                }
                await("$engine paused seek reaches $position") {
                    var arrived = false
                    main { arrived = player?.let { !it.playWhenReady && it.currentPosition in (position - 500)..(position + 500) }
                        ?: vlc?.let { !it.isPlaying && it.time in (position - 500)..(position + 500) } ?: false }
                    arrived
                }
            }
            fun verifyPaused() {
                main { assertTrue("Caption selection preserves Pause", player?.playWhenReady == false || vlc?.isPlaying == false) }
            }
            try {
                main { host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await("Fixture window owns focus") { host.hasWindowFocus() }
                ins.setInTouchMode(false)
                val base = "http://127.0.0.1:${captions.listeningPort}"
                val media = PlayingMedia(MediaItem("included-$engine", "Included caption fixture", null, null),
                    "${movie.base}/movie.mp4", CatalogType.MOVIES, progressKey = "included-$engine",
                    externalPlayback = true, profileKey = "included-caption-fixture", sidecarSubtitles = listOf(
                        PlaybackSidecarSubtitle("en", "English", "en", "$base/en.srt", "Show.en.srt"),
                        PlaybackSidecarSubtitle("fr", "French", "fr", "$base/fr.vtt", "Show.fr.vtt"),
                        PlaybackSidecarSubtitle("missing", "Unavailable", null, "$base/missing.srt", "Show.missing.srt")))
                main { host.setContent { MaterialTheme(colorScheme = NikColors) {
                    PlayerScreen(media, onBack = { exits.incrementAndGet() }, onRetry = {}, onRetryAlternateDecoder = {},
                        onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                        playbackEngine = engine, startFullscreen = true, controlsTimeoutSeconds = 0,
                        onVideoFrameRendered = { frames.incrementAndGet() })
                } } }
                await("$engine decoded video") { frames.get() > 0 }
                main { player = playerView(host.window.decorView)?.player }
                if (engine == PlaybackEngine.VLC) {
                    val owner = VlcPlaybackSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
                    vlc = ((owner.get(null) as AtomicReference<*>).get() as VlcPlaybackSession).player
                }
                pauseAt(3_000)
                openCaptions()
                await("Off initially focused") { focused("Off") }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Included English focused") { focused("Included · English") }
                val beforeSelection = focusEvents.size
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Selection returns directly to captions control") { node("Close") == null && focused("Subtitles") }
                assertTrue("Selecting an included caption must not visit unrelated player controls: ${focusEvents.drop(beforeSelection)}",
                    focusEvents.drop(beforeSelection).none { it in setOf("Back", "Previous", "More controls", "Play", "Pause") })
                await("Included SRT renders") { node("Included English cue") != null }
                verifyPaused()

                openCaptions()
                await("Selected included track keeps initial focus") { focused("Included · English") }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("French is adjacent") { focused("Included · French") }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Included VTT renders") { node("Close") == null && node("Sous-titres français") != null }
                assertEquals("Only selected files are fetched", 2, captions.requests.get())
                verifyPaused()
                pauseAt(8_000)
                await("Caption clears past cue end") { node("Sous-titres français") == null }
                pauseAt(3_000)
                await("Caption returns after backward seek") { node("Sous-titres français") != null }
                NavigationAuditEvidence.capture("included-caption-visible-$engine")

                openCaptions()
                await("French retains focus on reopen") { focused("Included · French") }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Unavailable included caption remains reachable") { focused("Included · Unavailable") }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Failed caption has an actionable error without losing row focus") {
                    focused("Included · Unavailable") && node("Included subtitle returned HTTP 503.") != null
                }
                verifyPaused()
                key(KeyEvent.KEYCODE_BACK)
                await("Failure preserves the active caption") { node("Close") == null && node("Sous-titres français") != null }
                openCaptions()
                await("The active caption remains selected") { focused("Included · French") }
                repeat(2) { key(KeyEvent.KEYCODE_DPAD_UP) }
                await("Off remains reachable") { focused("Off") }
                key(KeyEvent.KEYCODE_DPAD_CENTER); key(KeyEvent.KEYCODE_BACK)
                await("Off clears captions and restores exact control") {
                    node("Close") == null && node("Sous-titres français") == null && focused("Subtitles")
                }
                assertEquals("Caption controls never exit playback", 0, exits.get())
            } finally {
                main { host.finish() }
                ins.setInTouchMode(true)
                ins.uiAutomation.setOnAccessibilityEventListener(null)
                vlc?.let { active -> await("VLC releases its input") { active.isReleased } }
                captions.stop()
            }
        }
    }
}
