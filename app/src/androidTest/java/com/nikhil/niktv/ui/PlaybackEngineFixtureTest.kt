package com.nikhil.niktv.ui

import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.view.SurfaceView
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.MediaItem as NikItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first

/** Local HTTP only, separate app package, silent generated fixtures. No provider or profile access. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackEngineFixtureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun control(text: String, prefix: Boolean = false): android.view.accessibility.AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            val description = node.contentDescription?.toString().orEmpty()
            if ((if (prefix) description.startsWith(text) else description == text) ||
                node.text?.toString()?.lineSequence()?.any { it == text } == true) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun clickControl(text: String, prefix: Boolean = false) {
        await("Player control becomes visible: $text") { control(text, prefix) != null }
        // A replaced controls layer can still expose its outgoing accessibility node
        // during the 200 ms fade. Reacquire only when Android rejected the action;
        // an accepted click is never repeated (which could navigate twice).
        await("Reachable player control: $text", 2_000L) {
            var node = control(text, prefix)
            repeat(8) { if (node?.isClickable != true) node = node?.parent }
            node?.let { it.isClickable && it.isEnabled && it.isVisibleToUser &&
                it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
    }
    private fun shownPosition(): Long? = control("Current playback time ", true)?.contentDescription?.toString()
        ?.removePrefix("Current playback time ")?.split(':')?.mapNotNull { it.toLongOrNull() }?.fold(0L) { total, v -> total * 60 + v }
    private fun observeControlFocus(): List<String> {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                val label = event.contentDescription ?: event.source?.contentDescription
                    ?: event.text.firstOrNull() ?: event.source?.getChild(0)?.text
                if (label != null) events.add(label.toString())
            }
        }
        return events
    }
    private fun await(label: String, limit: Long = 20_000L, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + limit
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(50L)
        }
        fail(label)
    }

    /** Inject the logged renderer failure after a real -10s seek; no IPTV requests. */
    @Test fun softwareDecoderRewindFailureRecoversPlayingAndPausedWithoutRefreshingSource() {
        check(context.packageName.endsWith(".regression"))
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val software = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
            .getDecoderInfos(MimeTypes.VIDEO_H264, false, false).first {
                it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.", true)
            }.name
        for (requested in listOf(true, false)) FixtureServer().use { server ->
            server.requiredHeaders = mapOf("X-NikTV-Fixture" to "decoder-recovery")
            val activity = host()
            val scope = "rewind-software-$requested"
            val prefs = context.getSharedPreferences("player_engine_fallbacks", android.content.Context.MODE_PRIVATE)
            val preference = "mtk:$scope"
            val prior = prefs.getInt(preference, 0)
            prefs.edit().putInt(preference, 1).commit()
            val frames = AtomicInteger()
            val refreshes = AtomicInteger()
            val recoveries = AtomicInteger()
            val original = PlayingMedia(NikItem("rewind", "Rewind recovery fixture", null, null),
                "${server.base}/movie.mp4", CatalogType.SERIES, resumePositionMillis = 12_000,
                progressKey = scope, profileKey = "isolated-rewind", playbackFormat = "MP4",
                streamRequestHeaders = server.requiredHeaders)
            lateinit var vm: NikTvViewModel
            fun currentPlayer(): ExoPlayer? {
                var result: ExoPlayer? = null
                main {
                    fun walk(view: android.view.View) {
                        if (view is androidx.media3.ui.PlayerView) result = view.player as? ExoPlayer
                        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
                    }
                    walk(activity.window.decorView)
                }
                return result
            }
            fun position(player: ExoPlayer): Long { var value = 0L; main { value = player.currentPosition }; return value }
            try {
                main {
                    vm = androidx.lifecycle.ViewModelProvider(activity)[NikTvViewModel::class.java]
                    vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
                    @Suppress("UNCHECKED_CAST")
                    val state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                        .get(vm) as kotlinx.coroutines.flow.MutableStateFlow<NikTvState>
                    state.value = NikTvState(restoring = false, nowPlaying = original)
                    activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                        val state by vm.state.collectAsState()
                        val playing = state.nowPlaying
                        if (playing == null) androidx.compose.material3.Text("Recreating decoder fixture")
                        else PlayerScreen(playing, onBack = vm::closePlayer,
                            onRetry = { refreshes.incrementAndGet() }, onPlaybackAuthorizationFailure = { refreshes.incrementAndGet() },
                            onRetryAlternateDecoder = { at -> recoveries.incrementAndGet(); vm.retryPlaybackWithAlternateDecoder(at, playing) },
                            onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                            onPlaybackIntentChanged = { vm.setPlaybackIntent(playing.playbackAttemptId, it) },
                            playbackEngine = com.nikhil.niktv.model.PlaybackEngine.MEDIA3, controlsTimeoutSeconds = 0,
                            startFullscreen = true, onVideoFrameRendered = { frames.incrementAndGet() })
                    } }
                }
                await("Software player renders the initial resume") { frames.get() > 0 && currentPlayer()?.let { position(it) >= 12_000 } == true }
                showStabilityControls(activity, original.media.title)
                await("Native rewind is available") { control("Back 10 seconds") != null }
                if (!requested) {
                    clickControl("Pause")
                    await("Pause intent is committed before seeking") { vm.state.value.nowPlaying?.playWhenReady == false && control("Play") != null }
                }
                val failedPlayer = requireNotNull(currentPlayer())
                val beforeSeek = position(failedPlayer)
                instrumentation.setInTouchMode(false)
                var rewind = requireNotNull(control("Back 10 seconds"))
                while (!rewind.isFocusable && rewind.parent != null) rewind = rewind.parent
                assertTrue(rewind.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS))
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                await("The real D-pad -10s button seeks before the renderer fault") { position(failedPlayer) <= beforeSeek - 8_000 }
                val seekPosition = position(failedPlayer)
                val frameCount = frames.get()
                main {
                    failedPlayer.createMessage { _, _ ->
                        throw androidx.media3.exoplayer.ExoPlaybackException.createForRenderer(
                            IllegalStateException("Decoder failed: $software"), "MediaCodecVideoRenderer", 0,
                            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(), C.FORMAT_HANDLED,
                            false, PlaybackException.ERROR_CODE_DECODING_FAILED)
                    }.send()
                }
                await("Automatic recovery creates a new player and renders a frame even when paused") {
                    vm.state.value.nowPlaying?.let { it.playbackAttemptId != original.playbackAttemptId && it.decoderRecoveryCount == 1 } == true &&
                        currentPlayer()?.let { it !== failedPlayer } == true && frames.get() > frameCount
                }
                val resumed = requireNotNull(vm.state.value.nowPlaying)
                assertEquals(1, recoveries.get())
                assertEquals("No portal lookup/reauthentication is needed", 0, refreshes.get())
                assertEquals(original.url, resumed.url)
                assertEquals(original.streamRequestHeaders, resumed.streamRequestHeaders)
                assertEquals(0, server.rejected.get())
                assertEquals(original.decoderSessionId, resumed.decoderSessionId)
                assertEquals("Automatic recovery preserves explicit pause/play intent", requested, resumed.playWhenReady)
                assertTrue("Recovery remains near the rewind target", kotlin.math.abs(resumed.resumePositionMillis - seekPosition) <= 2_000)
                val recoveredPlayer = requireNotNull(currentPlayer())
                main { assertEquals(requested, recoveredPlayer.playWhenReady); assertNull(recoveredPlayer.playerError) }
                val recoveredAt = position(recoveredPlayer)
                Thread.sleep(600)
                if (requested) assertTrue("Playing recovery continues advancing", position(recoveredPlayer) > recoveredAt)
                else assertEquals("Paused recovery does not autoplay", recoveredAt, position(recoveredPlayer))
                assertNull(control("Decoder recovery paused. Retry or switch player."))
                NavigationAuditEvidence.capture("rewind-software-recovered-${if (requested) "playing" else "paused"}")
            } finally {
                main { activity.finish() }
                prefs.edit().putInt(preference, prior).commit()
                instrumentation.setInTouchMode(true)
            }
        }
    }

    @Test fun bottomSettingsPhoneLandscapeRetainRealPlayerNavigation() {
        assertTrue(instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90))
        try { bottomSettingsUseRealIptvAndSportsPlayersAcrossEngines() }
        finally { instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) }
    }

    @Test fun media3BackUnwindsPlayerControls() = exerciseBackLayers(com.nikhil.niktv.model.PlaybackEngine.MEDIA3)
    @Test fun vlcBackUnwindsPlayerControls() = exerciseBackLayers(com.nikhil.niktv.model.PlaybackEngine.VLC)

    @Test fun mpvBackUnwindsPlayerControls() = exerciseBackLayers(com.nikhil.niktv.model.PlaybackEngine.MPV)
    @Test fun liveMedia3DownBackHidesControls() = exerciseBackLayers(com.nikhil.niktv.model.PlaybackEngine.MEDIA3, true)
    @Test fun liveVlcDownBackHidesControls() = exerciseBackLayers(com.nikhil.niktv.model.PlaybackEngine.VLC, true)
    @Test fun liveMpvDownBackHidesControls() = exerciseBackLayers(com.nikhil.niktv.model.PlaybackEngine.MPV, true)

    private fun exerciseBackLayers(engine: com.nikhil.niktv.model.PlaybackEngine, live: Boolean = false) {
        check(context.packageName.endsWith(".regression"))
        fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
        fun focused(label: String): Boolean {
            var node = control(label)
            while (node != null && !node.isFocusable) node = node.parent
            return node?.isFocused == true
        }
        fun activate(label: String) {
            await("$label accepts focus") {
                var node = control(label)
                while (node != null && !node.isFocusable) node = node.parent
                focused(label) || node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS) == true
            }
            await("$label owns focus before activation") { focused(label) }
            key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        }
        FixtureServer().use { server ->
            val activity = host()
            val frames = AtomicInteger()
            val exits = AtomicInteger()
            val focusEvents = observeControlFocus()
            val item = NikItem("back-current", "Player Back fixture", null, null)
            val next = NikItem("back-next", "Next fixture episode", null, null)
            try {
                instrumentation.setInTouchMode(false)
                main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                    CompositionLocalProvider(LocalViewerLayout provides com.nikhil.niktv.data.ViewerLayout.EASY) {
                        val media = remember { PlayingMedia(item, "${server.base}/movie.mp4", if (live) CatalogType.LIVE_TV else CatalogType.SERIES,
                            episodeQueue = listOf(item, next), progressKey = "back-$engine", profileKey = "isolated-back") }
                        PlayerScreen(media, onBack = { exits.incrementAndGet() }, onRetry = {}, onRetryAlternateDecoder = {},
                            onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                            playbackEngine = engine, controlsTimeoutSeconds = 0, startFullscreen = true,
                            onVideoFrameRendered = { frames.incrementAndGet() })
                    }
                } } }
                await("The local video renders") { frames.get() > 0 }
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                await("D-pad OK reveals the player controls") { control("More controls") != null }
                key(android.view.KeyEvent.KEYCODE_BACK)
                await("Initial Back hides controls") { control("More controls") == null }
                key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down focuses Pause") { focused("Pause") }
                val downBackAt = focusEvents.size
                key(android.view.KeyEvent.KEYCODE_BACK)
                await("One Back after Down hides controls") { control("More controls") == null }
                assertEquals(0, exits.get())
                assertTrue("Dismissal must not visit Previous", focusEvents.drop(downBackAt).none { it.contains("Previous", true) })
                key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                await("Down reopens controls") { focused("Pause") }
                if (live) return
                activate("Episodes")
                await("Episodes opens a separate queue") { control("Next fixture episode") != null }
                val beforeQueueBack = focusEvents.size
                key(android.view.KeyEvent.KEYCODE_BACK)
                await("Back from Episodes returns to Episodes with settings closed") {
                    control("Next fixture episode") == null && focused("Episodes") && control("More controls") != null
                }
                assertTrue("Back from Episodes must not move through transport controls: ${focusEvents.drop(beforeQueueBack)}",
                    focusEvents.drop(beforeQueueBack).none { it in listOf("Back", "Play", "Pause", "More controls") })
                assertEquals(0, exits.get())
                key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Right returns to More controls immediately") { focused("More controls") }
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                await("More controls opens over the transport") { control("Hide extra controls") != null }
                key(android.view.KeyEvent.KEYCODE_BACK)
                await("Back closes settings without hiding the transport") { focused("More controls") }
                NavigationAuditEvidence.capture("player-back-transport-$engine")
                key(android.view.KeyEvent.KEYCODE_BACK)
                await("Next Back hides the transport while staying in playback") { control("More controls") == null }
                assertEquals(0, exits.get())
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                await("OK reopens controls on Play or Pause") { focused("Pause") || focused("Play") }
                key(android.view.KeyEvent.KEYCODE_BACK)
                await("Back hides reopened controls") { control("More controls") == null }
                key(android.view.KeyEvent.KEYCODE_BACK)
                await("Back from the bare video leaves playback") { exits.get() == 1 }
            } catch (failure: AssertionError) {
                NavigationAuditEvidence.capture("player-back-failure-$engine")
                throw failure
            } finally {
                main { activity.finish() }
                instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
                instrumentation.setInTouchMode(true)
            }
        }
    }

    @Test fun bottomSettingsUseRealIptvAndSportsPlayersAcrossEngines() {
        check(context.packageName.endsWith(".regression"))
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        fun rect(label: String, prefix: Boolean = false): android.graphics.Rect {
            var node = checkNotNull(control(label, prefix))
            while (!node.isFocusable && node.parent != null) node = node.parent
            return android.graphics.Rect().also(node::getBoundsInScreen)
        }
        fun focused(label: String, prefix: Boolean = false): Boolean {
            var node = control(label, prefix)
            while (node != null && !node.isFocusable) node = node.parent
            return node?.isFocused == true
        }
        for (sports in listOf(false, true)) for (engine in listOf(
            com.nikhil.niktv.model.PlaybackEngine.MEDIA3, com.nikhil.niktv.model.PlaybackEngine.VLC)) {
            FixtureServer().use { server ->
                val activity = host()
                val frames = AtomicInteger()
                val retries = AtomicInteger()
                val title = "Compact controls fixture"
                try {
                    main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                        val media = remember { PlayingMedia(NikItem("ribbon", title, null, null), "${server.base}/movie.mp4",
                            CatalogType.MOVIES, progressKey = "ribbon-$sports-$engine", profileKey = "isolated-ribbon") }
                        if (sports) com.nikhil.niktv.sports.player.PlayerScreen(media, onBack = {},
                            onRetry = { retries.incrementAndGet() }, onRetryAlternateDecoder = { retries.incrementAndGet() }, onPlaybackAuthorizationFailure = { retries.incrementAndGet() },
                            onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                            playbackEngine = engine, controlsTimeoutSeconds = 0, startFullscreen = true,
                            onVideoFrameRendered = { frames.incrementAndGet() })
                        else PlayerScreen(media, onBack = {}, onRetry = { retries.incrementAndGet() },
                            onRetryAlternateDecoder = { retries.incrementAndGet() }, onPlaybackAuthorizationFailure = { retries.incrementAndGet() }, onPlayPrevious = {}, onPlayNext = {},
                            onProgress = { _, _, _ -> }, playbackEngine = engine, controlsTimeoutSeconds = 0, startFullscreen = true,
                            onVideoFrameRendered = { frames.incrementAndGet() })
                    } } }
                    await("$sports/$engine renders the local fixture") { frames.get() > 0 }
                    showStabilityControls(activity, title)
                    await("Native duration exposes seek and rewind controls") {
                        control("Current playback time ", true) != null && control("Back 10 seconds") != null
                    }
                    instrumentation.waitForIdleSync()
                    val play = rect("Pause")
                    val seek = rect("Current playback time ", true)
                    clickControl("More controls")
                    await("Inline settings are mounted") { control("Subtitles") != null && control("Video fit:", true) != null }
                    instrumentation.waitForIdleSync()
                    NavigationAuditEvidence.capture("bottom-settings-production-open-$sports-$engine")
                    assertEquals("Settings do not change the transport height", play.height(), rect("Pause").height())
                    assertTrue("Settings share the bottom transport baseline (including pixel rounding)",
                        kotlin.math.abs(play.centerY() - rect("Subtitles").centerY()) <= 1)
                    assertEquals("Settings leave seek in place", seek, rect("Current playback time ", true))
                    assertTrue("Controls share a horizontal row", rect("Subtitles").top == rect("Video fit:", true).top)
                    NavigationAuditEvidence.capture("bottom-settings-${if (sports) "sports" else "iptv"}-${engine.name.lowercase()}-quick")
                    instrumentation.setInTouchMode(false)
                    if (!sports) {
                        clickControl("Playback speed")
                        await("The real speed popup is open") { control("Playback speed adjustment") != null }
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        await("Speed returns directly to its mounted origin") {
                            control("Playback speed adjustment") == null && focused("Playback speed")
                        }
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                        await("Right after speed dismissal reaches Video fit") { focused("Video fit:", true) }
                        Thread.sleep(180L)
                        assertTrue("No delayed restoration steals focus back to Speed", focused("Video fit:", true))
                    }
                    clickControl("Video fit:", true)
                    await("Real resize picker opens") { control("Video resize") != null }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    await("Real picker returns to its exact origin") { control("Video resize") == null && focused("Video fit:", true) }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    await("Right immediately reaches Picture") { focused("Choose picture mode:", true) }
                    Thread.sleep(200)
                    assertTrue("Dialog handoff never corrects a subsequent arrow", focused("Choose picture mode:", true))
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    await("Picture mode picker opens") { control("Picture mode") != null }
                    await("Picture settings accepts remote focus") {
                        var node = control("Settings")
                        while (node != null && !node.isFocusable) node = node.parent
                        focused("Settings") || node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS) == true
                    }
                    await("Settings is focused in the picture picker") { focused("Settings") }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    await("The detailed picture editor opens") { control("Adjust while watching the video.") != null }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    await("Back from the editor restores its Settings button") { control("Picture mode") != null && focused("Settings") }
                    NavigationAuditEvidence.capture("picture-editor-back-$sports-$engine")
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    await("Right after Back goes straight to Skip") { focused("Skip") }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    await("Back from the picker restores Picture") { control("Picture mode") == null && focused("Choose picture mode:", true) }
                    await("Inline controls include existing stream info and timeout") { control("Playback information") != null && control("Controls timeout:", true) != null }
                    clickControl("Playback information")
                    await("Real information dialog opens") { control("Details for the current stream") != null }
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    await("Info returns to its tool") { control("Details for the current stream") == null && focused("Playback information") }
                    NavigationAuditEvidence.capture("bottom-settings-${if (sports) "sports" else "iptv"}-${engine.name.lowercase()}")
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    await("Closing returns directly to the surviving settings trigger") { control("Subtitles") == null && focused("More controls") }
                    assertEquals("Settings never reload or reauthenticate the stream", 0, retries.get())
                } finally { main { activity.finish() }; instrumentation.setInTouchMode(true) }
            }
        }
    }

    @Test fun failedEpisodeNavigationCanBeRetriedWithDpadWithoutLeavingThePlayer() {
        check(context.packageName.endsWith(".regression"))
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        for (sports in listOf(false, true)) for (engine in listOf(
            com.nikhil.niktv.model.PlaybackEngine.MEDIA3, com.nikhil.niktv.model.PlaybackEngine.VLC)) {
            FixtureServer().use { server ->
                val activity = host()
                val frames = AtomicInteger()
                val next = AtomicInteger()
                val previous = AtomicInteger()
                try {
                    main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                        val episode = remember { NikItem("current", "Navigation failure fixture", null, null,
                            seasonNumber = 1, episodeNumber = 2) }
                        val media = remember { PlayingMedia(episode, "${server.base}/movie.mp4", CatalogType.SERIES,
                            previousEpisode = episode.copy(id = "previous", episodeNumber = 1),
                            nextEpisode = episode.copy(id = "next", episodeNumber = 3),
                            progressKey = "failed-navigation-$sports-$engine", profileKey = "isolated-failure") }
                        // A failed provider request leaves the committed player mounted.
                        // Its next request must remain usable after the diagnostic closes.
                        if (sports) com.nikhil.niktv.sports.player.PlayerScreen(media, onBack = {}, onRetry = {},
                            onRetryAlternateDecoder = {}, onPlaybackAuthorizationFailure = {},
                            onPlayPrevious = { previous.incrementAndGet() }, onPlayNext = { next.incrementAndGet() },
                            onProgress = { _, _, _ -> }, playbackEngine = engine, controlsTimeoutSeconds = 0,
                            startFullscreen = false, onVideoFrameRendered = { frames.incrementAndGet() })
                        else PlayerScreen(media, onBack = {}, onRetry = {}, onRetryAlternateDecoder = {},
                            onPlaybackAuthorizationFailure = {}, onPlayPrevious = { previous.incrementAndGet() },
                            onPlayNext = { next.incrementAndGet() }, onProgress = { _, _, _ -> },
                            playbackEngine = engine, controlsTimeoutSeconds = 0, startFullscreen = false,
                            onVideoFrameRendered = { frames.incrementAndGet() })
                    } } }
                    await("$sports/$engine renders the navigation fixture") { frames.get() > 0 }
                    // The 20-second sample immediately enters the real 30-second
                    // next-episode prompt. Dismiss it before testing manual controls.
                    await("The short sample offers automatic Next") { control("Cancel") != null }
                    clickControl("Cancel")
                    await("The automatic prompt has closed") { control("Play now") == null }
                    showStabilityControls(activity, "Navigation failure fixture")
                    instrumentation.setInTouchMode(false)
                    if (control("Next") == null)
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    await("D-pad opens the native episode controls") { control("Next") != null && control("Previous") != null }
                    fun press(label: String) {
                        await("$sports/$engine: the current $label control accepts focus") {
                            var node = control(label)
                            var focused = false
                            while (node != null && !focused) {
                                focused = node.isFocused || node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                                node = node.parent
                            }
                            focused
                        }
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                        instrumentation.sendStatus(0, android.os.Bundle().apply {
                            putString("stream", "\n$sports/$engine: $label next=${next.get()} previous=${previous.get()}\n")
                        })
                    }
                    press("Next")
                    await("First next request is submitted") { next.get() == 1 }
                    press("Next")
                    await("Next is retryable without a replacement media item") { next.get() == 2 }
                    press("Previous")
                    await("Previous is usable after the failed Next") { previous.get() == 1 }
                    press("Previous")
                    await("Previous can also be retried") { previous.get() == 2 }
                    NavigationAuditEvidence.capture("failed-navigation-retry-$sports-$engine")
                } catch (failure: Throwable) {
                    NavigationAuditEvidence.capture("failed-navigation-error-$sports-$engine")
                    throw failure
                } finally { main { activity.finish() }; instrumentation.setInTouchMode(true) }
            }
        }
    }

    @Test fun offlineRecordedTransportStreamSeeksWithoutLongPlaybackStalls() = exerciseLocalFileSeek(false)
    @Test fun externalLocalVideoSeeksThroughProductionViewIntent() = exerciseLocalFileSeek(true)

    private fun exerciseLocalFileSeek(external: Boolean) {
        check(context.packageName.endsWith(".regression"))
        assertTrue(instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90))
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val source = InstrumentationRegistry.getArguments().getString("offlineSeekPath")?.let(::File)
        val mp4 = source?.extension.equals("mp4", ignoreCase = true)
        val mime = if (mp4) "video/mp4" else "video/mp2t"
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "seek-audit-${java.util.UUID.randomUUID()}.${if (mp4) "mp4" else "ts"}")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/NikTV/PlaybackAudit/")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            }))
        resolver.openOutputStream(uri)!!.use { output ->
            if (source != null) source.inputStream().use { it.copyTo(output) }
            else ZipInputStream(instrumentation.context.assets.open("playback-fixtures.zip")).use { zip ->
                val segments = sortedMapOf<String, ByteArray>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (Regex("seg[0-9]+\\.ts").matches(entry.name)) segments[entry.name] = zip.readBytes()
                }
                segments.values.forEach(output::write)
            }
        }
        val activity: androidx.activity.ComponentActivity = if (external)
            instrumentation.startActivitySync(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .setClass(context, com.nikhil.niktv.ExternalVideoActivity::class.java)
                .putExtra(Intent.EXTRA_TITLE, "Offline recording seek fixture")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) as androidx.activity.ComponentActivity
            else host()
        val initialFrames = AtomicInteger()
        val rendered = AtomicInteger()
        val lastFrameUs = AtomicLong(Long.MIN_VALUE)
        val videoFrames = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, Long>>()
        val retries = AtomicInteger()
        fun native(): ExoPlayer? {
            var found: ExoPlayer? = null
            main {
                fun walk(view: android.view.View) {
                    if (view is androidx.media3.ui.PlayerView) found = view.player as? ExoPlayer
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
                walk(activity.window.decorView)
            }
            return found
        }
        try {
            if (!external) main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                val media = remember { PlayingMedia(NikItem("offline-recording", "Offline recording seek fixture", null, null),
                    uri.toString(), CatalogType.MOVIES, progressKey = "isolated-offline-seek", offlinePlayback = true,
                    playbackFormat = if (mp4) "MP4" else "MPEG-TS") }
                PlayerScreen(media, onBack = {}, onRetry = { retries.incrementAndGet() },
                    onRetryAlternateDecoder = { retries.incrementAndGet() }, onPlaybackAuthorizationFailure = { retries.incrementAndGet() },
                    onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> }, controlsTimeoutSeconds = 0,
                    startFullscreen = true, onVideoFrameRendered = { initialFrames.incrementAndGet() })
            } } }
            await("The production local-file player renders") {
                if (!external) initialFrames.get() > 0 else {
                    val current = native()
                    var ready = false
                    main { ready = current?.let { (it.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 } == true }
                    ready
                }
            }
            showStabilityControls(activity)
            val player = requireNotNull(native())
            var length = 0L
            main {
                length = player.duration
                assertTrue("The recording exposes seeking", player.isCurrentMediaItemSeekable)
                player.setVideoFrameMetadataListener { presentationUs, _, _, _ ->
                    rendered.incrementAndGet()
                    lastFrameUs.set(presentationUs)
                    videoFrames.add(presentationUs to SystemClock.elapsedRealtime())
                    while (videoFrames.size > 256) videoFrames.poll()
                }
            }
            assertTrue("The recording has a bounded duration", length > 10_000L)
            await("The renderer exposes frame timestamps") { lastFrameUs.get() != Long.MIN_VALUE }
            var frameOffsetUs = 0L
            main { frameOffsetUs = lastFrameUs.get() - player.currentPosition * 1_000L }
            fun slider(): android.view.accessibility.AccessibilityNodeInfo? {
                if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                fun walk(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    node ?: return null
                    if (node.packageName?.toString() == context.packageName &&
                        node.rangeInfo?.let { it.max > it.min } == true) return node
                    for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                    return null
                }
                return walk(instrumentation.uiAutomation.rootInActiveWindow)
            }
            fun selectedTime(): Long {
                val range = requireNotNull(slider()?.rangeInfo)
                return (((range.current - range.min) / (range.max - range.min)).toDouble() * length).toLong()
            }
            val delays = mutableListOf<Long>()
            fun awaitSeek(target: Long, before: Int, started: Long, input: String) {
                var firstDestinationFrameAt = 0L
                await("$input renders after seeking to $target", 15_000L) {
                    var ready = false
                    main { assertNull(player.playerError); ready = player.playbackState == Player.STATE_READY &&
                        player.currentPosition >= target - 750L && kotlin.math.abs(player.currentPosition - target) < 3_000L && player.isPlaying }
                    val expectedUs = target * 1_000L + frameOffsetUs
                    firstDestinationFrameAt = videoFrames.firstOrNull { frame ->
                        frame.second >= started && frame.first in (expectedUs - 1_000_000L)..(expectedUs + 2_500_000L)
                    }?.second ?: 0L
                    ready && rendered.get() > before && firstDestinationFrameAt > 0L
                }
                val elapsed = firstDestinationFrameAt - started
                delays += elapsed
                android.util.Log.i("NikTvOfflineSeek", "mode=${if (external) "external" else "offline"} input=$input targetMs=$target durationMs=$length seekToFrameMs=$elapsed")
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream",
                    "\n${if (external) "external" else "offline"}: $input target=$target/${length}ms seek-to-frame=${elapsed}ms\n") })
            }
            for (fraction in listOf(.65, .20, .80)) {
                showStabilityControls(activity)
                await("The current player's enabled seek bar is attached") { activity.hasWindowFocus() && slider() != null }
                val bounds = android.graphics.Rect().also { requireNotNull(slider()).getBoundsInScreen(it) }
                assertTrue("Seek bar has visible input bounds", bounds.width() > 0 && bounds.height() > 0)
                val x = bounds.left + (bounds.width() * fraction).toFloat()
                val y = bounds.exactCenterY()
                val before = rendered.get()
                val started = SystemClock.elapsedRealtime()
                val downAt = SystemClock.uptimeMillis()
                instrumentation.setInTouchMode(true)
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
                }
                instrumentation.waitForIdleSync()
                awaitSeek(selectedTime(), before, started, "touch")
                Thread.sleep(300L)
            }
            showStabilityControls(activity)
            instrumentation.setInTouchMode(false)
            var progress = requireNotNull(slider())
            var accepted = false
            while (!accepted) {
                accepted = progress.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                if (accepted || progress.parent == null) break
                progress = progress.parent
            }
            assertTrue("The seek group accepts D-pad focus", accepted)
            val before = rendered.get()
            val started = SystemClock.elapsedRealtime()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.waitForIdleSync()
            awaitSeek(selectedTime(), before, started, "dpad")
            for ((label, delta) in listOf("Back 10 seconds" to -10_000L, "Forward 10 seconds" to 10_000L)) {
                var target = 0L
                main { target = (player.currentPosition + delta).coerceIn(0L, length) }
                val frame = rendered.get()
                val pressedAt = SystemClock.elapsedRealtime()
                clickControl(label)
                awaitSeek(target, frame, pressedAt, label)
            }
            assertEquals("Offline seeks never refresh portal authorization", 0, retries.get())
            assertTrue("Local recording seeks should render within two seconds: $delays", delays.all { it < 2_000L })
            NavigationAuditEvidence.capture("offline-recording-seek")
        } finally {
            main { activity.finish() }
            resolver.delete(uri, null, null)
            instrumentation.setInTouchMode(true)
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun host(): PlaybackFixtureHostActivity {
        check(context.packageName.endsWith(".regression"))
        File(context.cacheDir, "retry.wav").delete()
        return instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
    }

    internal inner class FixtureServer : AutoCloseable {
        private val socket = ServerSocket(0)
        private val workers = Executors.newCachedThreadPool()
        private val clients = Collections.synchronizedList(mutableListOf<Socket>())
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val current = AtomicInteger()
        val peak = AtomicInteger()
        @Volatile var requiredHeaders: Map<String, String> = emptyMap()
        @Volatile var beforeResponse: ((String) -> Unit)? = null
        @Volatile var beforeBody: ((String) -> Unit)? = null
        val rejected = AtomicInteger()
        var missingOnce: String? = null
        val missing = AtomicInteger()
        private val files = mutableMapOf<String, ByteArray>()
        private val startedAt = SystemClock.elapsedRealtime()
        val base = "http://127.0.0.1:${socket.localPort}"
        init {
            ZipInputStream(instrumentation.context.assets.open("playback-fixtures.zip")).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
            }
            workers.submit {
                while (!socket.isClosed) try {
                    val client = socket.accept(); clients.add(client)
                    workers.submit { serve(client) }
                } catch (_: Exception) { }
            }
        }
        private fun serve(client: Socket) {
            val active = current.incrementAndGet(); peak.accumulateAndGet(active, ::maxOf)
            try {
                client.soTimeout = 5000
                val input = client.getInputStream().bufferedReader()
                val first = input.readLine() ?: return
                val path = first.split(' ').getOrNull(1)?.substringBefore('?')?.removePrefix("/") ?: return
                paths.add(path)
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                beforeResponse?.invoke(path)
                fun reply(status: String, body: ByteArray, type: String = "application/octet-stream", extra: String = "") {
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nContent-Type: $type\r\nConnection: close\r\n$extra\r\n".toByteArray())
                    if (!first.startsWith("HEAD")) {
                        beforeBody?.let { hook -> out.flush(); hook(path) }
                        out.write(body)
                    }
                    out.flush()
                }
                if (path == "catalog") { Thread.sleep(200L); reply("200 OK", "catalog-metadata".toByteArray(), "application/json"); return }
                if (path == "hang") { Thread.sleep(30_000L); return }
                if (requiredHeaders.any { (name, value) -> headers[name.lowercase()] != value }) {
                    rejected.incrementAndGet(); reply("403 Forbidden", byteArrayOf()); return
                }
                if (path == missingOnce && missing.getAndIncrement() == 0) { reply("404 Not Found", byteArrayOf()); return }
                if (path == "redirect") { reply("302 Found", byteArrayOf(), extra = "Location: /live\r\n"); return }
                val name = when (path) { "live", "live-dvr", "vod-hls" -> "full.m3u8"; "opaque-dash" -> "manifest.mpd"; else -> path }
                var body = files[name] ?: run { reply("404 Not Found", byteArrayOf()); return }
                if (path == "live") body = body.toString(Charsets.UTF_8).replace("#EXT-X-ENDLIST", "").toByteArray()
                if (path == "live-dvr") {
                    val lines = body.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() && it != "#EXT-X-ENDLIST" }.toList()
                    val segments = (30 + (SystemClock.elapsedRealtime() - startedAt) / 2_000).toInt().coerceAtMost(60)
                    body = (lines.take(4) + lines.drop(4).chunked(2).take(segments).flatten()).joinToString("\n", postfix = "\n").toByteArray()
                }
                val type = when { name.endsWith("m3u8") -> "application/vnd.apple.mpegurl"; name.endsWith("mpd") -> "application/dash+xml"; name.endsWith("mp4") -> "video/mp4"; name.endsWith("ts") -> "video/mp2t"; else -> "application/octet-stream" }
                val range = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                if (range != null && range in body.indices) {
                    reply("206 Partial Content", body.copyOfRange(range, body.size), type,
                        "Content-Range: bytes $range-${body.lastIndex}/${body.size}\r\n")
                } else reply("200 OK", body, type)
            } catch (_: Exception) { }
            finally { current.decrementAndGet(); clients.remove(client); runCatching { client.close() } }
        }
        override fun close() {
            socket.close(); synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } }
            workers.shutdownNow()
        }
    }

    private val stabilityEngines: List<com.nikhil.niktv.model.PlaybackEngine> get() {
        val engines = listOf(
            com.nikhil.niktv.model.PlaybackEngine.MEDIA3,
            com.nikhil.niktv.model.PlaybackEngine.FLOW,
            com.nikhil.niktv.model.PlaybackEngine.VLC,
            com.nikhil.niktv.model.PlaybackEngine.MPV
        )
        val requested = InstrumentationRegistry.getArguments().getString("stabilityEngines")
            ?.split(',')?.map { it.trim().uppercase(java.util.Locale.ROOT) }?.filter { it.isNotEmpty() }.orEmpty()
        require(requested.all { name -> engines.any { it.name == name } }) { "Unknown stabilityEngines argument" }
        return engines.filter { (requested.isEmpty() || it.name in requested) &&
            (it != com.nikhil.niktv.model.PlaybackEngine.MPV || mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) }
            .also { require(it.isNotEmpty()) { "No supported stability engines selected" } }
    }

    /** Samples only the synthetic video's centre, excluding title, clock and controls. */
    private fun stabilityVideoFingerprint(): Long {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Synthetic video screenshot unavailable" }
        return try {
            var value = 1125899906842597L
            for (y in 0 until 20) for (x in 0 until 32) {
                val px = (bitmap.width * (.34f + x * .01f)).toInt().coerceIn(0, bitmap.width - 1)
                val py = (bitmap.height * (.30f + y * .015f)).toInt().coerceIn(0, bitmap.height - 1)
                value = value * 31 + bitmap.getPixel(px, py)
            }
            value
        } finally { bitmap.recycle() }
    }

    private fun assertStabilityNoError(label: String, retries: AtomicInteger?) {
        retries?.let { assertEquals("$label must not silently reconnect/recreate the player", 0, it.get()) }
        assertNull("$label must not show a playback error", control("This title can’t be played right now"))
    }

    private fun showStabilityControls(activity: androidx.activity.ComponentActivity, expectedTitle: String? = null,
        afterReplacement: Boolean = false) {
        await("Current fixture host owns the resumed window", 3_000L) {
            var ready = false
            main { ready = activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                activity.hasWindowFocus() && !activity.isFinishing && !activity.isDestroyed }
            ready
        }
        // A new progressKey initially hides controls. Do not mistake the outgoing
        // layer's Pause/Play semantics for the replacement's visible controls.
        if (afterReplacement) Thread.sleep(300L)
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNull("Opening controls must not hide a playback failure", control("This title can’t be played right now"))
            val visible = control("Pause") != null || control("Play") != null
            if (visible && (expectedTitle == null || control(expectedTitle) != null)) return
            if (!visible) {
                // A single central video tap cannot activate a stale focused Next
                // button. Separate taps exceed the gesture's double-tap timeout.
                val point = FloatArray(2)
                main {
                    val view = activity.window.decorView
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    point[0] = location[0] + view.width * .5f
                    point[1] = location[1] + view.height * .4f
                }
                val now = SystemClock.uptimeMillis()
                for (action in intArrayOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, now, action, point[0], point[1], 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue("Fixture video accepts touch", instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                Thread.sleep(350L)
            } else Thread.sleep(50L)
        }
        fail("Playback controls belong to the current selection: $expectedTitle")
    }

    private fun sampleAdvancingSyntheticVideo(label: String, seconds: Int, retries: AtomicInteger?): Pair<Int, Int> {
        val fingerprints = linkedSetOf<Long>()
        var peakPss = 0
        var unchanged = 0
        var previous: Long? = null
        repeat(seconds) {
            Thread.sleep(1_000)
            assertStabilityNoError(label, retries)
            val sample = stabilityVideoFingerprint()
            if (sample == previous) unchanged++ else unchanged = 0
            assertTrue("$label synthetic video must not freeze for four consecutive samples", unchanged < 4)
            previous = sample
            fingerprints += sample
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            peakPss = maxOf(peakPss, memory.totalPss)
        }
        assertTrue("$label must visibly render advancing video, rather than only report READY", fingerprints.size > 1)
        return fingerprints.size to peakPss
    }

    private fun exerciseIptvProductionMatrix(type: CatalogType) {
        check(context.packageName.endsWith(".regression"))
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val oldVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        try {
            for (engine in stabilityEngines) FixtureServer().use { server ->
                val label = "IPTV-$type-$engine"
                val activity = host()
                val index = mutableIntStateOf(0)
                val frames = AtomicInteger()
                val selectionFrames = Array(2) { AtomicInteger() }
                val firstFrameAt = AtomicLong(-1L)
                val retries = AtomicInteger()
                val nextCalls = AtomicInteger()
                val previousCalls = AtomicInteger()
                val started = SystemClock.elapsedRealtime()
                val queue = (0..1).map { number -> NikItem("matrix-$number", "$type fixture ${number + 1}", null, null,
                    seasonNumber = if (type == CatalogType.SERIES) 1 else null,
                    episodeNumber = if (type == CatalogType.SERIES) number + 1 else null) }
                var nativeStart: Long? = null
                var nativeEnd: Long? = null
                var visualSamples = 0
                var peakPss = 0
                try {
                    main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                        val selected = index.intValue
                        val live = type == CatalogType.LIVE_TV
                        val media = remember(selected) { PlayingMedia(queue[selected],
                            "${server.base}/${if (live) "live-dvr" else "movie.mp4"}?selection=$selected",
                            type, previousEpisode = queue.getOrNull(selected - 1) ?: queue.last().takeIf { live },
                            nextEpisode = queue.getOrNull(selected + 1) ?: queue.first().takeIf { live },
                            episodeQueue = queue,
                            series = if (type == CatalogType.SERIES) NikItem("matrix-series", "Series fixture", null, null) else null,
                            resumePositionMillis = if (live) 0 else 4_000,
                            progressKey = "matrix:$engine:$type:$selected", profileKey = "isolated-matrix-profile",
                            playbackFormat = if (live) "HLS" else "MP4", allowLiveWindowSeeking = live) }
                        PlayerScreen(media, onBack = {}, onRetry = { retries.incrementAndGet() },
                            onRetryAlternateDecoder = { retries.incrementAndGet() }, onPlaybackAuthorizationFailure = { retries.incrementAndGet() },
                            onPlayPrevious = { previousCalls.incrementAndGet(); index.intValue = if (live) 1 - selected else (selected - 1).coerceAtLeast(0) },
                            onPlayNext = { nextCalls.incrementAndGet(); index.intValue = if (live) 1 - selected else (selected + 1).coerceAtMost(1) },
                            onProgress = { _, _, _ -> }, playbackEngine = engine, controlsTimeoutSeconds = 0,
                            startFullscreen = true,
                            onVideoFrameRendered = { frames.incrementAndGet(); selectionFrames[selected].incrementAndGet()
                                firstFrameAt.compareAndSet(-1L, SystemClock.elapsedRealtime() - started) })
                    } } }
                    await("$label first frame", 25_000) { assertStabilityNoError(label, retries); frames.get() > 0 }
                    showStabilityControls(activity, queue[0].title)
                    if (type == CatalogType.LIVE_TV) {
                        assertNull("Baseline IPTV must not expose Sports DVR controls", control("Watch live"))
                        assertNull("Baseline IPTV live does not rewind", control("Back 10 seconds"))
                    }
                    if (type != CatalogType.LIVE_TV) await("$label restores its four-second resume") { (shownPosition() ?: -1) >= 4 }
                    nativeStart = shownPosition()
                    val samples = sampleAdvancingSyntheticVideo(label, if (type == CatalogType.LIVE_TV) 12 else 2, retries)
                    visualSamples = samples.first; peakPss = samples.second
                    nativeEnd = shownPosition()
                    // Sliding live windows can renumber their positions. VOD has a fixed clock.
                    if (type != CatalogType.LIVE_TV) assertTrue("$label native clock advances", requireNotNull(nativeEnd) > requireNotNull(nativeStart))
                    clickControl("Pause")
                    await("$label pause is applied") { control("Play") != null }
                    Thread.sleep(700)
                    val paused = shownPosition()
                    val pausedPicture = stabilityVideoFingerprint()
                    Thread.sleep(900)
                    assertEquals("$label pause retains the displayed native time", paused, shownPosition())
                    assertEquals("$label pause freezes video output", pausedPicture, stabilityVideoFingerprint())
                    if (type != CatalogType.LIVE_TV) {
                        clickControl("Back 10 seconds")
                        await("$label rewinds while paused") { shownPosition()?.let { it <= 1 } == true }
                        clickControl("Forward 10 seconds")
                        await("$label seeks forward while paused") { shownPosition()?.let { it in 9..11 } == true }
                        assertNotNull("$label seeking retains user pause", control("Play"))
                    }
                    clickControl("Play")
                    await("$label resumes") { control("Pause") != null }
                    sampleAdvancingSyntheticVideo("$label-resumed", 2, retries)
                    val beforeNext = selectionFrames[1].get()
                    clickControl("Next")
                    await("$label Next commits a rendered replacement") { index.intValue == 1 && selectionFrames[1].get() > beforeNext }
                    showStabilityControls(activity, queue[1].title, afterReplacement = true)
                    sampleAdvancingSyntheticVideo("$label-next", 2, retries)
                    assertEquals(1, nextCalls.get())
                    val beforePrevious = selectionFrames[0].get()
                    clickControl("Previous")
                    await("$label Previous commits a rendered replacement") { index.intValue == 0 && selectionFrames[0].get() > beforePrevious }
                    showStabilityControls(activity, queue[0].title, afterReplacement = true)
                    sampleAdvancingSyntheticVideo("$label-previous", 2, retries)
                    assertEquals(1, previousCalls.get())
                    assertStabilityNoError(label, retries)
                } finally {
                    android.util.Log.i("NikTvStability", "domain=IPTV catalog=$type engine=$engine firstFrameMs=${firstFrameAt.get()} " +
                        "nativeStartSec=$nativeStart nativeEndSec=$nativeEnd visualDistinctSamples=$visualSamples peakPssKb=$peakPss " +
                        "next=${nextCalls.get()} previous=${previousCalls.get()} retries=${retries.get()} maxHttpConnections=${server.peak.get()} " +
                        "limits=local-silent-fixture-short-observation-no-provider-or-dropped-frame-measurement")
                    main { activity.finish() }
                    Thread.sleep(350)
                }
            }
        } finally { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, oldVolume, 0) }
    }

    @Test fun productionIptvMoviesPlayResumeSeekAndNavigateAcrossAllEngines() = exerciseIptvProductionMatrix(CatalogType.MOVIES)
    @Test fun productionIptvSeriesPlayResumeSeekAndNavigateAcrossAllEngines() = exerciseIptvProductionMatrix(CatalogType.SERIES)
    @Test fun productionIptvLivePlaysPausesAndNavigatesAcrossAllEngines() = exerciseIptvProductionMatrix(CatalogType.LIVE_TV)

    @Test fun productionClearSportsPlaysPausesAndSwitchesSourcesAcrossAllEngines() {
        check(context.packageName.endsWith(".regression"))
        val preferences = com.nikhil.niktv.sports.SportsPlaybackPreferences(context)
        val oldSettings = runBlocking { preferences.settings.first() }
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val oldVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        try {
            preferences.setPlayerControlsTimeoutSeconds(0)
            for (engine in stabilityEngines) FixtureServer().use { server ->
                preferences.setPlaybackEngine(engine)
                val activity = instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
                val index = mutableIntStateOf(0)
                val frames = AtomicInteger()
                val selectionFrames = Array(2) { AtomicInteger() }
                val started = SystemClock.elapsedRealtime()
                val firstFrameAt = AtomicLong(-1L)
                val selections = AtomicInteger()
                val sources = (0..1).map { com.nikhil.niktv.sports.SportsStream("${server.base}/live-dvr?source=$it", "Matrix stream ${it + 1}") }
                var samples = 0 to 0
                try {
                    main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                        val selected = index.intValue
                        com.nikhil.niktv.sports.SportsPlayer(sources[selected], "Sports matrix fixture", onBack = {},
                            streams = sources, selectedIndex = selected,
                            onSelectStream = { selections.incrementAndGet(); index.intValue = it },
                            onVideoFrameRendered = { frames.incrementAndGet(); selectionFrames[selected].incrementAndGet()
                                firstFrameAt.compareAndSet(-1L, SystemClock.elapsedRealtime() - started) })
                    } } }
                    await("Sports $engine first frame", 25_000) { assertNull(control("This title can’t be played right now")); frames.get() > 0 }
                    showStabilityControls(activity, "Sports matrix fixture")
                    // A fresh server per engine gives the advancing live window room to grow.
                    samples = sampleAdvancingSyntheticVideo("Sports-$engine", 12, null)
                    clickControl("Pause")
                    await("Sports $engine pause") { control("Play") != null }
                    Thread.sleep(700)
                    val paused = stabilityVideoFingerprint()
                    Thread.sleep(900)
                    assertEquals("Sports $engine pause freezes actual video", paused, stabilityVideoFingerprint())
                    clickControl("Play")
                    await("Sports $engine resumes") { control("Pause") != null }
                    sampleAdvancingSyntheticVideo("Sports-$engine-resumed", 2, null)
                    val beforeNext = selectionFrames[1].get()
                    clickControl("Next")
                    await("Sports $engine switches to the next rendered source") { index.intValue == 1 && selectionFrames[1].get() > beforeNext }
                    showStabilityControls(activity, "Sports matrix fixture", afterReplacement = true)
                    sampleAdvancingSyntheticVideo("Sports-$engine-next", 2, null)
                    val beforePrevious = selectionFrames[0].get()
                    clickControl("Previous")
                    await("Sports $engine returns to the previous rendered source") { index.intValue == 0 && selectionFrames[0].get() > beforePrevious }
                    showStabilityControls(activity, "Sports matrix fixture", afterReplacement = true)
                    sampleAdvancingSyntheticVideo("Sports-$engine-previous", 2, null)
                    assertEquals("Only explicit source selections occur", 2, selections.get())
                    assertNull(control("This title can’t be played right now"))
                } finally {
                    android.util.Log.i("NikTvStability", "domain=SPORTS protected=false engine=$engine firstFrameMs=${firstFrameAt.get()} " +
                        "visualDistinctSamples=${samples.first} peakPssKb=${samples.second} sourceSelections=${selections.get()} " +
                        "maxHttpConnections=${server.peak.get()} limits=local-silent-clear-HLS-short-observation-no-provider-dropped-frame-or-internal-retry-measurement")
                    main { activity.finish() }
                    Thread.sleep(350)
                }
            }
        } finally {
            preferences.setPlaybackEngine(oldSettings.engine)
            preferences.setPlayerControlsTimeoutSeconds(oldSettings.timeout)
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, oldVolume, 0)
        }
    }

    @Test fun sportsNativeWindowRewindsReturnsLiveAndContinuesInPip() {
        check(context.packageName.endsWith(".regression"))
        val server = FixtureServer()
        val activity = instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
        val firstFrames = AtomicInteger()
        val renderedFrames = AtomicInteger()
        val reconnects = AtomicInteger()
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val previousVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        fun find(description: String, prefix: Boolean = false): android.view.accessibility.AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            fun walk(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                val label = node.contentDescription?.toString().orEmpty()
                if (if (prefix) label.startsWith(description) else label == description) return node
                for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
                return null
            }
            return walk(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun click(description: String) {
            var node = find(description)
            repeat(8) { if (node?.isClickable != true) node = node?.parent }
            assertTrue("Reachable control: $description", node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        fun position(): Long? = find("Current playback time ", true)?.contentDescription?.toString()
            ?.removePrefix("Current playback time ")?.split(':')?.mapNotNull { it.toLongOrNull() }
            ?.fold(0L) { total, value -> total * 60 + value }
        try {
            val media = PlayingMedia(NikItem("sports-dvr-fixture", "Sports window fixture", null, null),
                "${server.base}/live-dvr", CatalogType.LIVE_TV, playbackFormat = "HLS", progressKey = "sports-dvr-fixture",
                allowLiveWindowSeeking = true)
            main { activity.setContent {
                androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                    com.nikhil.niktv.sports.player.PlayerScreen(media, playbackDomain = PlaybackDomain.SPORTS, onBack = {}, onRetry = { reconnects.incrementAndGet() },
                        onRetryAlternateDecoder = { reconnects.incrementAndGet() },
                        onPlaybackAuthorizationFailure = { reconnects.incrementAndGet() },
                        onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                        controlsTimeoutSeconds = 60, startFullscreen = true, showLiveRecordingAndGuide = false,
                        onVideoFrameRendered = { firstFrames.incrementAndGet() },
                        onVideoFramesRendered = { renderedFrames.set(it) })
                }
            } }
            await("Sports live fixture renders") { firstFrames.get() > 0 }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            await("Opening the overlay reveals the native-window seekbar") { (position() ?: 0) > 20 }
            val before = requireNotNull(position())
            click("Back 10 seconds")
            await("Rewind seeks inside the native window") { position()?.let { it <= before - 6 } == true }
            assertNotNull(find("Watch live"))
            click("Watch live")
            await("Watch live seeks to native default without restarting") { position()?.let { it >= before - 4 } == true }
            click("More controls")
            click("More player tools")
            await("Sports exposes the PiP control") { find("Picture in Picture") != null }
            val framesBeforePip = renderedFrames.get()
            click("Picture in Picture")
            await("Sports activity enters PiP") { activity.isInPictureInPictureMode }
            await("Video keeps rendering in PiP") { renderedFrames.get() > framesBeforePip + 15 }
            assertEquals("Seeking and PiP retain the same stream/player", 0, reconnects.get())
        } finally {
            main { activity.finish() }
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, previousVolume, 0)
            server.close()
        }
    }

    @Test fun playerErrorCanBeHiddenAndReenteredWithRemote() {
        check(context.packageName.endsWith(".regression"))
        val store = com.nikhil.niktv.sports.SportsPlaybackPreferences(context)
        val oldEngine = runBlocking { store.playbackEngine.first() }
        try {
            for (engine in listOf(com.nikhil.niktv.model.PlaybackEngine.MEDIA3, com.nikhil.niktv.model.PlaybackEngine.VLC)) {
                runBlocking { store.setPlaybackEngine(engine) }
                FixtureServer().use { server ->
                    val activity = instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
                    try {
                        main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                            com.nikhil.niktv.sports.SportsPlayer(com.nikhil.niktv.sports.SportsStream("${server.base}/missing.mp4", "Missing fixture"),
                                "Error focus fixture", onBack = {})
                        } } }
                        fun retryFocused(): Boolean {
                            var node = control("Retry with fresh link")
                            repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
                            return false
                        }
                        await("$engine error enters Retry", 35_000) { retryFocused() }
                        clickControl("Choose player")
                        await("$engine player picker opens") { control("Close") != null }
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        fun chooserFocused(): Boolean {
                            var node = control("Choose player")
                            repeat(8) { if (node?.isFocused == true) return true; node = node?.parent }
                            return false
                        }
                        await("$engine picker returns focus to its originating button") { control("Close") == null && chooserFocused() }
                        Thread.sleep(300)
                        assertTrue("$engine Retry must not steal picker return focus", chooserFocused())
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
                        await("$engine Up returns from picker action to Retry") { retryFocused() }
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                        await("$engine navigation outside the error can return to Retry") { retryFocused() }
                        repeat(2) {
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                            await("$engine Back hides the error overlay") { control("Choose player") == null && control("Retry with fresh link") == null }
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                            await("$engine OK reenters Retry") { retryFocused() }
                        }
                    } finally { main { activity.finish() } }
                }
            }
        } finally { runBlocking { store.setPlaybackEngine(oldEngine) } }
    }

    @Test fun sportsDoubleTapAndMiddleSwipeUseTheStreamQueue() {
        check(context.packageName.endsWith(".regression"))
        val server = FixtureServer()
        val activity = instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
        val selected = mutableIntStateOf(0)
        val frames = AtomicInteger()
        val streams = (0..2).map { com.nikhil.niktv.sports.SportsStream("${server.base}/live-dvr?source=$it", "Fixture source ${it + 1}") }
        fun pointer(action: Int, x: Float, y: Float, down: Long) {
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0))
        }
        fun doubleTap(fraction: Float) {
            val metrics = activity.resources.displayMetrics
            repeat(2) {
                val down = SystemClock.uptimeMillis()
                pointer(android.view.MotionEvent.ACTION_DOWN, metrics.widthPixels * fraction, metrics.heightPixels * .45f, down)
                SystemClock.sleep(25)
                pointer(android.view.MotionEvent.ACTION_UP, metrics.widthPixels * fraction, metrics.heightPixels * .45f, down)
                SystemClock.sleep(65)
            }
        }
        try {
            main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                com.nikhil.niktv.sports.SportsPlayer(streams[selected.intValue], "Sports gesture fixture", onBack = {},
                    onVideoFrameRendered = { frames.incrementAndGet() }, streams = streams, selectedIndex = selected.intValue,
                    onSelectStream = { selected.intValue = it })
            } } }
            await("First Sports source renders") { frames.get() > 0 }
            var before = frames.get()
            doubleTap(.8f)
            await("Right double tap selects and renders the next stream") { selected.intValue == 1 && frames.get() > before }
            before = frames.get()
            doubleTap(.2f)
            await("Left double tap returns to the previous stream") { selected.intValue == 0 && frames.get() > before }
            val metrics = activity.resources.displayMetrics
            val down = SystemClock.uptimeMillis()
            pointer(android.view.MotionEvent.ACTION_DOWN, metrics.widthPixels * .5f, metrics.heightPixels * .65f, down)
            for (step in 1..8) {
                SystemClock.sleep(20)
                pointer(android.view.MotionEvent.ACTION_MOVE, metrics.widthPixels * .5f,
                    metrics.heightPixels * (.65f - step * .045f), down)
            }
            pointer(android.view.MotionEvent.ACTION_UP, metrics.widthPixels * .5f, metrics.heightPixels * .29f, down)
            await("Middle swipe reveals the stream list") { control("Choose what to play") != null }
            await("The source list contains other streams") { control("Fixture source 3") != null }
            before = frames.get()
            clickControl("Fixture source 3")
            await("Selecting a stream in the list renders it") { selected.intValue == 2 && frames.get() > before }
        } finally { main { activity.finish() }; server.close() }
    }

    @Test fun sportsControlsApplyTimeoutPersistEngineAndPreservePlayback() {
        check(context.packageName.endsWith(".regression"))
        val iptv = com.nikhil.niktv.data.ProfileStore(context)
        val iptvEngine = runBlocking { iptv.playbackEngine.first() }
        val iptvTimeout = runBlocking { iptv.playerControlsTimeoutSeconds.first() }
        val store = com.nikhil.niktv.sports.SportsPlaybackPreferences(context)
        val oldTimeout = runBlocking { store.playerControlsTimeoutSeconds.first() }
        val oldEngine = runBlocking { store.playbackEngine.first() }
        runBlocking { store.setPlayerControlsTimeoutSeconds(3); store.setPlaybackEngine(com.nikhil.niktv.model.PlaybackEngine.MEDIA3) }
        val server = FixtureServer()
        val activity = instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
        val frames = AtomicInteger()
        try {
            main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                com.nikhil.niktv.sports.SportsPlayer(com.nikhil.niktv.sports.SportsStream("${server.base}/live-dvr", "Fixture stream"),
                    "Sports controls fixture", onBack = {}, onVideoFrameRendered = { frames.incrementAndGet() })
            } } }
            await("Sports renders after loading its own preferences") { frames.get() > 0 }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            await("Pause is available") { control("Pause") != null }
            clickControl("Pause")
            await("Pause changes the control to Play") { control("Play") != null }
            Thread.sleep(1_200)
            val paused = shownPosition()
            Thread.sleep(1_200)
            assertEquals("User pause freezes the native position", paused, shownPosition())
            clickControl("Play")
            await("Playback resumes") { shownPosition()?.let { it > (paused ?: 0L) } == true }
            if (control("More controls") != null) clickControl("More controls")
            clickControl("More player tools")
            await("Sports timeout value is visible") { control("Controls timeout: 3s") != null }
            clickControl("Controls timeout: 3s")
            val next = nextPlayerControlsTimeoutSeconds(3)
            await("Sports applies the timeout callback") { control("Controls timeout: ${playerControlsTimeoutLabel(next)}") != null }
            assertEquals(next, runBlocking { store.playerControlsTimeoutSeconds.first() })
            assertEquals("Sports timeout leaves IPTV unchanged", iptvTimeout, runBlocking { iptv.playerControlsTimeoutSeconds.first() })
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Back returns to the quick row") { control("Quick controls") != null }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Back closes settings before the transport timeout resumes") { control("Quick controls") == null }
            await("The new timeout actually hides the overlay", (next + 4) * 1_000L) {
                control("More controls") == null && control("Pause") == null && control("Play") == null
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            await("Main controls reopen after timeout") { control("More controls") != null }
            clickControl("More controls")
            clickControl("More player tools")
            await("Reopening retains the new timeout") { control("Controls timeout: ${playerControlsTimeoutLabel(next)}") != null }
            clickControl("Back to quick controls")
            clickControl("Video fit:", true)
            await("Sports resize uses the real picker") { control("Video resize") != null }
            clickControl("Skip")
            await("Resize closes without interrupting playback") { control("Video resize") == null }
            clickControl("Choose picture mode:", true)
            await("Picture mode opens") { control("Picture mode") != null && control("Apply") != null && control("Skip") != null }
            clickControl("Skip")
            await("Picture mode closes") { control("Apply") == null && control("Skip") == null }
            clickControl("More player tools")
            clickControl("Playback information")
            await("Playback information opens") { control("Details for the current stream") != null }
            clickControl("Close")
            await("Information popup closes") { control("Details for the current stream") == null }
            clickControl("Back to quick controls")
            clickControl("Player:", true)
            await("Engine picker is available") { control("Choose player") != null }
            val beforeSwitch = frames.get()
            clickControl("Flow")
            await("Engine selection is applied and video resumes") { frames.get() > beforeSwitch }
            assertEquals(com.nikhil.niktv.model.PlaybackEngine.FLOW, runBlocking { store.playbackEngine.first() })
            assertEquals("Choosing Flow for Sports leaves IPTV unchanged", iptvEngine, runBlocking { iptv.playbackEngine.first() })
            // The fixture's extensionless HLS URL needs MIME recovery in every
            // replacement player, including when returning to the original engine.
            await("Player control returns after switching") { control("Player:", true) != null }
            clickControl("Player:", true)
            await("Engine picker reopens") { control("Choose player") != null }
            val beforeReturn = frames.get()
            clickControl("ExoPlayer")
            await("Returning to ExoPlayer renders the opaque HLS source") { frames.get() > beforeReturn }
            assertEquals(com.nikhil.niktv.model.PlaybackEngine.MEDIA3, runBlocking { store.playbackEngine.first() })
            assertEquals(next, runBlocking { store.playerControlsTimeoutSeconds.first() })
        } finally {
            main { activity.finish() }
            runBlocking { store.setPlayerControlsTimeoutSeconds(oldTimeout); store.setPlaybackEngine(oldEngine) }
            server.close()
        }
    }

    @Test fun sportsWindowSeekingKeepsNextPreviousAndStartsEachSourceLive() {
        check(context.packageName.endsWith(".regression"))
        for ((engine, profile) in listOf(com.nikhil.niktv.model.PlaybackEngine.MEDIA3 to "fixture-wio", com.nikhil.niktv.model.PlaybackEngine.FLOW to "fixture-xtream")) {
            val server = FixtureServer()
            val activity = host()
            val selected = mutableIntStateOf(0)
            val frames = AtomicInteger()
            val retries = AtomicInteger()
            val queue = listOf(NikItem("a", "Channel A", null, null), NikItem("b", "Channel B", null, null))
            try {
                main { activity.setContent { androidx.compose.material3.MaterialTheme(colorScheme = NikColors) {
                    val index = selected.intValue
                    val media = remember(index) { PlayingMedia(queue[index], "${server.base}/live-dvr?channel=$index", CatalogType.LIVE_TV,
                        previousEpisode = queue[1 - index], nextEpisode = queue[1 - index], episodeQueue = queue,
                        profileKey = profile, progressKey = "$profile:$index", playbackFormat = "HLS",
                        allowLiveWindowSeeking = true) }
                    com.nikhil.niktv.sports.player.PlayerScreen(media, playbackDomain = PlaybackDomain.SPORTS, onBack = {}, onRetry = { retries.incrementAndGet() }, onRetryAlternateDecoder = { retries.incrementAndGet() },
                        onPlaybackAuthorizationFailure = { retries.incrementAndGet() }, onPlayPrevious = { selected.intValue = 1 - index },
                        onPlayNext = { selected.intValue = 1 - index }, onProgress = { _, _, _ -> },
                        controlsTimeoutSeconds = 30, playbackEngine = engine, startFullscreen = true,
                        showLiveRecordingAndGuide = false, onVideoFrameRendered = { frames.incrementAndGet() })
                } } }
                await("IPTV fixture starts in $engine") { frames.get() > 0 }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                await("Native IPTV seekbar appears") { (shownPosition() ?: 0L) > 20L }
                val live = requireNotNull(shownPosition())
                clickControl("Back 10 seconds")
                await("IPTV rewinds inside the source window") { shownPosition()?.let { it <= live - 6 } == true }
                val beforeNext = frames.get()
                clickControl("Next")
                await("Next channel starts playing") { selected.intValue == 1 && frames.get() > beforeNext }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                await("New channel starts live, not at the previous rewind point") { shownPosition()?.let { it >= live - 3 } == true }
                val beforePrevious = frames.get()
                clickControl("Previous")
                await("Previous channel still works") { selected.intValue == 0 && frames.get() > beforePrevious }
                assertEquals("Seeking must not trigger reconnects or provider URL reloads", 0, retries.get())
            } finally { main { activity.finish() }; server.close() }
        }
    }

    private inner class Probe(val engine: String, val server: FixtureServer, val host: PlaybackFixtureHostActivity,
        path: String, live: Boolean, start: String = "0", headers: Map<String, String> = emptyMap(), format: String = "HLS") : AutoCloseable {
        val started = SystemClock.elapsedRealtime()
        @Volatile var readyAt = 0L
        @Volatile var firstFrameAt = 0L
        @Volatile var ended = false
        @Volatile var error: String? = null
        @Volatile var nativeFailure: PlaybackException? = null
        @Volatile var playing = false
        var player: Player? = null
        var vlc: VlcPlaybackSession? = null
        var focus: VlcAudioFocus? = null
        var startupCreateMs = 0L
        var bufferingMs = 0L
        var sampledAt = started
        var peakPssKb = 0
        init {
            val media = PlayingMedia(NikItem("fixture", "Generated fixture", null, null), "${server.base}/$path",
                if (live) CatalogType.LIVE_TV else CatalogType.MOVIES,
                streamRequestHeaders = headers, playbackFormat = format)
            val createStart = SystemClock.elapsedRealtime()
            if (engine == "VLC") {
                vlc = VlcPlaybackSession.create(context, vlcPlaybackOptions(live, false, format == "HLS"))
                main {
                    val native = vlc!!.player
                    val layout = VLCVideoLayout(host)
                    host.setContentView(layout)
                    native.attachViews(layout, null, false, false)
                    native.setEventListener { event ->
                        when (event.type) {
                            MediaPlayer.Event.Playing -> { if (readyAt == 0L) readyAt = SystemClock.elapsedRealtime(); playing = true }
                            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                            MediaPlayer.Event.EndReached -> { playing = false; ended = true }
                            MediaPlayer.Event.EncounteredError -> error = "VLC native error"
                        }
                    }
                    focus = VlcAudioFocus(context, { play -> vlc!!.command { if (play) it.play() else it.pause() } },
                        { gain -> vlc!!.command { it.volume = (gain * 100).toInt() } }, {})
                }
                val nativeMedia = Media(vlc!!.lib, Uri.parse(media.url)).apply {
                    setHWDecoderEnabled(true, false); addOption(":network-caching=1500")
                    requireNotNull(vlcHeaderOptions(headers)).forEach(::addOption)
                }
                vlc!!.player.media = nativeMedia; nativeMedia.release()
                main { focus!!.request(true) }
            } else main {
                val surface = SurfaceView(host); host.setContentView(surface)
                player = if (engine == "MPV") MpvPlayerAdapter(context, media, 0L, start).apply { setVideoSurfaceView(surface) }
                else ExoPlayer.Builder(context, if (engine == "FLOW")
                    com.nikhil.niktv.ui.flowInspiredRenderers(context)
                    else androidx.media3.exoplayer.DefaultRenderersFactory(context))
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                    .setHandleAudioBecomingNoisy(true)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)))
                    .apply { if (engine == "FLOW") configureFlowInspiredPlayer(context, this, live)
                        else if (live) setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 30000, 750, 3000).setTargetBufferBytes(16 * 1024 * 1024).build()) }
                    .build().apply {
                        setVideoSurfaceView(surface)
                        setMediaItem(MediaItem.Builder().setUri(media.url).setMimeType(playbackMimeType(format, media.url)).build())
                    }
                player!!.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { if (firstFrameAt == 0L) firstFrameAt = SystemClock.elapsedRealtime() }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && readyAt == 0L) readyAt = SystemClock.elapsedRealtime()
                        if (state == Player.STATE_ENDED) ended = true
                    }
                    override fun onIsPlayingChanged(value: Boolean) { playing = value }
                    override fun onPlayerError(failure: PlaybackException) { error = failure.message; nativeFailure = failure }
                })
                player!!.prepare(); player!!.play()
            }
            startupCreateMs = SystemClock.elapsedRealtime() - createStart
        }
        fun position(): Long { var pos = 0L; main { pos = player?.currentPosition ?: vlc!!.player.time }; return pos }
        fun pause() = main { if (player != null) player!!.pause() else focus!!.request(false) }
        fun play() = main { if (player != null) player!!.play() else focus!!.request(true) }
        fun seek(position: Long) = main { if (player != null) player!!.seekTo(position) else vlc!!.player.setTime(position, false) }
        fun sample() {
            val now = SystemClock.elapsedRealtime()
            if (readyAt != 0L && !playing && !ended) bufferingMs += now - sampledAt
            sampledAt = now
            val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info); peakPssKb = maxOf(peakPssKb, info.totalPss)
            vlc?.player?.media?.let { media ->
                try { if (firstFrameAt == 0L && (media.stats?.displayedPictures ?: 0) > 0) firstFrameAt = now }
                finally { media.release() }
            }
        }
        fun report(label: String) {
            var dropped: Int? = null
            main { dropped = (player as? ExoPlayer)?.videoDecoderCounters?.droppedBufferCount }
            if (dropped == null && player is MpvPlayerAdapter) dropped = runBlocking { (player as MpvPlayerAdapter).diagnosticFrameDrops() }
            if (dropped == null) dropped = vlc?.player?.media?.let { media -> try { media.stats?.lostPictures } finally { media.release() } }
            android.util.Log.i("NikTvFixture", "$label engine=$engine createMs=$startupCreateMs readyMs=${readyAt-started} firstFrameMs=${if(firstFrameAt==0L) -1 else firstFrameAt-started} stalledMs=$bufferingMs dropped=$dropped peakPssKb=$peakPssKb maxConnections=${server.peak.get()} requests=${server.paths.size}")
        }
        override fun close() {
            main { focus?.close(); player?.release() }
            vlc?.let { runBlocking { it.close() } }
            Thread.sleep(350L)
        }
    }

    @Test fun mpvLongWindowStartPositionComparison() {
        val activity = host()
        try {
            val firstSegments = mutableListOf<Int>()
            for (start in listOf("0", "none")) FixtureServer().use { server ->
                Probe("MPV", server, activity, "live", true, start).use { probe ->
                    await("MPV start=$start must render: ${probe.error}") { probe.sample(); probe.firstFrameAt > 0L }
                    Thread.sleep(1200L); probe.sample(); probe.report("long-window-start-$start")
                    val segment = server.paths.firstOrNull { it.matches(Regex("seg\\d+\\.ts")) }
                    firstSegments += requireNotNull(segment).removePrefix("seg").removeSuffix(".ts").toInt()
                    assertNull(probe.error)
                }
            }
            android.util.Log.i("NikTvFixture", "MPV long-window firstSegments=$firstSegments")
            assertEquals(2, firstSegments.size)
        } finally { main { activity.finish() } }
    }

    @Test fun opaqueAuthenticatedManifestsAcrossEngines() {
        val activity = host()
        try {
            for (engine in listOf("MEDIA3", "MPV", "VLC")) for (dash in listOf(false, true)) FixtureServer().use { server ->
                val headers = if (engine == "VLC") mapOf("User-Agent" to "NikTV-fixture", "Referer" to "https://fixture.invalid/")
                    else mapOf("Authorization" to "Bearer synthetic", "X-Fixture" to "synthetic")
                server.requiredHeaders = headers
                Probe(engine, server, activity, if(dash) "opaque-dash" else "redirect", !dash,
                    headers = headers, format = if(dash) "DASH" else "HLS").use { probe ->
                    await("$engine ${if(dash) "DASH" else "redirected HLS"} must render, error=${probe.error}") { probe.sample(); probe.firstFrameAt > 0L }
                    val before = probe.position(); Thread.sleep(1500L); probe.sample()
                    assertTrue("$engine must advance", probe.position() > before)
                    assertNull(probe.error); assertEquals("Headers must survive redirect and segment requests", 0, server.rejected.get())
                    probe.report(if(dash) "opaque-dash" else "authenticated-redirect-hls")
                }
            }
        } finally { main { activity.finish() } }
    }

    @Test fun flowPlaysAuthenticatedLiveAndVodWithoutYoutubeResolution() {
        val activity = host()
        try {
            for ((path, format, live) in listOf(Triple("redirect", "HLS", true),
                Triple("opaque-dash", "DASH", false), Triple("movie.mp4", "MP4", false))) {
                FixtureServer().use { server ->
                    val headers = mapOf("User-Agent" to "NikTV-Flow-Fixture", "Authorization" to "Bearer local-only")
                    server.requiredHeaders = headers
                    Probe("FLOW", server, activity, path, live, headers = headers, format = format).use { probe ->
                        await("Flow $format must render: ${probe.error}") { probe.sample(); probe.firstFrameAt > 0L }
                        assertEquals(0, server.rejected.get())
                        val before = probe.position()
                        Thread.sleep(1000)
                        assertTrue(probe.position() > before)
                        probe.pause()
                        if (!live) {
                            probe.seek(19_500L)
                            Thread.sleep(700)
                            assertFalse(probe.ended)
                            probe.play()
                            await("Flow VOD completion") { probe.ended }
                        }
                        assertNull(probe.error)
                        probe.report("flow-$format")
                    }
                }
            }
        } finally { main { activity.finish() } }
    }

    @Test fun pausedEpisodeEndDoesNotCompleteUntilResume() {
        val activity = host()
        try {
            for (engine in listOf("MEDIA3", "MPV", "VLC")) FixtureServer().use { server ->
                Probe(engine, server, activity, "movie.mp4", false, format = "MP4").use { probe ->
                    await("$engine must start") { probe.sample(); probe.firstFrameAt > 0L }
                    probe.pause(); probe.seek(19_500L); Thread.sleep(1200L)
                    assertFalse("$engine paused at end must not complete", probe.ended)
                    probe.play(); await("$engine must complete after resume") { probe.ended }
                }
            }
        } finally { main { activity.finish() } }
    }
    @Test fun vlcHangingStartupShowsRetryWithoutInventingAuthorizationFailure() {
        check(context.packageName.endsWith(".regression"))
        FixtureServer().use { server ->
            val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
                .putExtra("engine", "VLC").putExtra("url", "${server.base}/hang")
                .putExtra("catalog", "LIVE_TV").putExtra("authorizationRetries", 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                await("Hanging VLC startup must expose an actionable error", 35_000L) {
                    control("This title can’t be played right now") != null &&
                        control("Retry with fresh link") != null && control("Choose player") != null
                }
                assertEquals("Unknown startup failure must not refresh credentials", 0, PlaybackRegressionActivity.automaticRetries)
            } finally { main { activity.finish() } }
        }
    }

    @Test fun audioFocusLossPreservesUserPauseAcrossEngines() {
        val activity = host()
        val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        try {
            for (engine in listOf("MEDIA3", "MPV", "VLC")) FixtureServer().use { server ->
                Probe(engine, server, activity, "movie.mp4", false, format = "MP4").use { probe ->
                    await("$engine must start") { probe.sample(); probe.firstFrameAt > 0L }
                    main { assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(request)) }
                    await("$engine must pause on transient focus loss") { !probe.playing }
                    probe.pause()
                    main { manager.abandonAudioFocusRequest(request) }
                    Thread.sleep(500L)
                    assertFalse("$engine gain must retain explicit pause", probe.playing)
                    probe.play(); await("$engine explicit play must resume") { probe.playing }
                }
            }
        } finally { main { manager.abandonAudioFocusRequest(request); activity.finish() } }
    }

    @Test fun headphoneDisconnectPausesPlaybackWhenSystemInjectionIsSupported() {
        fun disconnect(): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am broadcast -a android.media.AUDIO_BECOMING_NOISY -p ${context.packageName}"))
            .bufferedReader().use { it.readText() }
        val permission = disconnect()
        org.junit.Assume.assumeTrue("Android did not allow a completed synthetic headphone-disconnect broadcast",
            permission.contains("Broadcast completed", true))
        val activity = host()
        try {
            for (engine in listOf("MEDIA3", "MPV", "VLC")) FixtureServer().use { server ->
                Probe(engine, server, activity, "movie.mp4", false, format = "MP4").use { probe ->
                    await("$engine must start") { probe.sample(); probe.firstFrameAt > 0L }
                    disconnect()
                    await("$engine pauses when headphones disconnect") { !probe.playing }
                }
            }
        } finally { main { activity.finish() } }
    }

    @Test fun speculativeCatalogLoadDoesNotPrepareAnotherStreamAndDefersUnderPressure() = runBlocking {
        val activity = host()
        try {
            for (engine in listOf("MEDIA3", "MPV", "VLC")) for (permitted in listOf(true, false)) FixtureServer().use { server ->
                Probe(engine, server, activity, "live", true).use { probe ->
                    await("$engine must start") { probe.sample(); probe.firstFrameAt > 0L }
                    val before = probe.position()
                    val cache = SpeculativePageCache<String>(this, SystemClock::elapsedRealtime)
                    var metadataRequests = 0
                    cache.schedule("fixture-page", "synthetic", permitted) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            metadataRequests++
                            java.net.URL("${server.base}/catalog").openConnection().apply {
                                connectTimeout = 2000; readTimeout = 2000
                            }.getInputStream().bufferedReader().use { it.readText() }
                        }
                    }
                    kotlinx.coroutines.delay(1200L)
                    probe.sample()
                    assertTrue("Speculation must not interrupt $engine", probe.position() > before)
                    assertNull(probe.error)
                    assertEquals(if(permitted) "catalog-metadata" else null, cache.takeReady("fixture-page", "synthetic"))
                    assertEquals(if(permitted) 1 else 0, metadataRequests)
                    probe.report("preload-permitted-$permitted")
                }
            }
        } finally { main { activity.finish() } }
    }

    @Test fun liveMissingSegmentRecoveryUsesExistingSharedPolicy() {
        check(context.packageName.endsWith(".regression"))
        for (engine in listOf("MEDIA3", "MPV", "VLC")) FixtureServer().use { server ->
            server.missingOnce = "seg058.ts"
            val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
                .putExtra("engine", engine).putExtra("url", "${server.base}/live")
                .putExtra("catalog", "LIVE_TV").putExtra("format", "HLS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                await("$engine must request the missing segment and render", 35_000L) {
                    PlaybackRegressionActivity.firstFrames > 0 && server.missing.get() > 0
                }
                // This fixed live playlist eventually stops advancing. Verify the shared
                // EOF/stall policy can render again, without overlapping reconnect loops.
                await("$engine must render again after live-edge/EOF recovery", 40_000L) {
                    PlaybackRegressionActivity.firstFrames >= 2
                }
                assertEquals(0, PlaybackRegressionActivity.automaticRetries)
                assertTrue("Live reconnect policy remains bounded", PlaybackRegressionActivity.retries <= 1)
            } finally { main { activity.finish() }; Thread.sleep(500L) }
        }
    }

    @Test fun vlcVodReopensStalledSourceAndResumesWithoutProviderRetry() {
        check(context.packageName.endsWith(".regression"))
        FixtureServer().use { server ->
            val blockedAt = AtomicLong()
            val reopenedAt = AtomicLong()
            val release = java.util.concurrent.CountDownLatch(1)
            val opens = AtomicInteger()
            server.beforeResponse = { path ->
                if (path == "vod-hls" && opens.incrementAndGet() > 1) {
                    reopenedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                    release.countDown()
                }
            }
            server.beforeBody = { path ->
                if (path == "seg008.ts" && opens.get() == 1) {
                    blockedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                    release.await(40, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
                .putExtra("engine", "VLC").putExtra("url", "${server.base}/vod-hls")
                .putExtra("catalog", "MOVIES").putExtra("format", "HLS").putExtra("resume", 0L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                await("VLC renders before encountering the stalled segment") {
                    PlaybackRegressionActivity.firstFrames == 1 && blockedAt.get() > 0L
                }
                // VLC plays the already buffered segments before the picture
                // stops. Time from the blocked HTTP request includes that lead.
                await("VLC reopens the source while the original connection is still stalled", 30_000L) {
                    reopenedAt.get() > blockedAt.get()
                }
                val oldProgress = PlaybackRegressionActivity.progress
                await("VLC renders again after reconnecting at the saved position", 15_000L) {
                    PlaybackRegressionActivity.firstFrames >= 2
                }
                await("Playback continues past its pre-stall position", 9_000L) {
                    PlaybackRegressionActivity.progress >= oldProgress + 2_000L
                }
                assertEquals(2, opens.get())
                assertEquals(0, PlaybackRegressionActivity.retries)
                assertEquals(0, PlaybackRegressionActivity.automaticRetries)
                android.util.Log.i("NikTvFixture", "VLC VOD stalled-source reopenMs=${reopenedAt.get() - blockedAt.get()} resumedFrames=${PlaybackRegressionActivity.firstFrames}")
            } finally {
                release.countDown()
                main { activity.finish() }
            }
        }
    }

    @Test fun vlcLiveRefreshesWhenVideoStopsRendering() {
        check(context.packageName.endsWith(".regression"))
        FixtureServer().use { server ->
            val blockedAt = AtomicLong()
            val release = java.util.concurrent.CountDownLatch(1)
            server.beforeResponse = { path ->
                if (path == "seg052.ts" && PlaybackRegressionActivity.retries == 0) {
                    blockedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                    release.await(45, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
                .putExtra("engine", "VLC").putExtra("url", "${server.base}/live")
                .putExtra("catalog", "LIVE_TV").putExtra("format", "HLS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                await("Live video renders before the blocked segment", 35_000L) {
                    PlaybackRegressionActivity.firstFrames == 1 && blockedAt.get() > 0L
                }
                await("VLC requests one live refresh before the stalled connection times out", 28_000L) {
                    PlaybackRegressionActivity.retries == 1
                }
                val refreshedIn = SystemClock.elapsedRealtime() - blockedAt.get()
                release.countDown()
                await("Live video renders again on the replacement stream", 15_000L) {
                    PlaybackRegressionActivity.firstFrames >= 2
                }
                assertEquals(1, PlaybackRegressionActivity.retries)
                assertEquals(0, PlaybackRegressionActivity.automaticRetries)
                android.util.Log.i("NikTvFixture", "VLC live stalled-source refreshMs=$refreshedIn")
            } finally {
                release.countDown()
                main { activity.finish() }
            }
        }
    }

    @Test fun mpvRetainsAvailableHttpFailureCause() {
        val activity = host()
        try { FixtureServer().use { server ->
            server.requiredHeaders = mapOf("Authorization" to "Bearer synthetic")
            Probe("MPV", server, activity, "movie.mp4", false, format = "MP4").use { probe ->
                await("MPV must report the rejected transport") { probe.nativeFailure != null }
                val cause = probe.nativeFailure?.cause as? NativePlaybackFailure
                assertNotNull("MPV must preserve its available native cause", cause)
                assertEquals(NativeFailureKind.AUTHORIZATION, cause!!.kind)
                assertEquals(403, cause.httpStatus)
                assertFalse(cause.message.orEmpty().contains("127.0.0.1"))
            }
        } } finally { main { activity.finish() } }
    }

    @Test fun media3OpaqueManifestsWithoutFormatHintUseObservedHeaders() {
        check(context.packageName.endsWith(".regression"))
        for (dash in listOf(false, true)) FixtureServer().use { server ->
            server.requiredHeaders = mapOf("Authorization" to "Bearer synthetic")
            val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
                .putExtra("engine", "MEDIA3").putExtra("url", "${server.base}/${if(dash) "opaque-dash" else "redirect"}")
                .putExtra("headers", arrayOf("Authorization", "Bearer synthetic"))
                .putExtra("catalog", if(dash) "MOVIES" else "LIVE_TV")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                await("Opaque ${if(dash) "DASH" else "HLS"} must render without an extension or provider format hint") {
                    PlaybackRegressionActivity.firstFrames > 0
                }
                assertEquals(0, server.rejected.get())
                assertEquals(0, PlaybackRegressionActivity.retries)
            } finally { main { activity.finish() }; Thread.sleep(350L) }
        }
    }

    @Test fun vlcUnsupportedAuthenticationHeadersUseCompatibleEngineWithoutChangingPreference() {
        check(context.packageName.endsWith(".regression"))
        FixtureServer().use { server ->
            server.requiredHeaders = mapOf("Authorization" to "Bearer synthetic")
            val activity = instrumentation.startActivitySync(Intent(context, PlaybackRegressionActivity::class.java)
                .putExtra("engine", "VLC").putExtra("url", "${server.base}/movie.mp4")
                .putExtra("headers", arrayOf("Authorization", "Bearer synthetic"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                await("Unsupported VLC auth headers must play through the compatible engine") { PlaybackRegressionActivity.firstFrames > 0 }
                assertEquals(0, server.rejected.get())
                assertEquals("A source fallback must not change the user's engine setting", 0, PlaybackRegressionActivity.enginePreferenceChanges)
            } finally { main { activity.finish() }; Thread.sleep(350L) }
        }
    }

    @Test fun vlcStyledSubtitleTracksCanBeIdentifiedAndDisabledWithoutAutoselection() {
        val activity = host()
        val file = File(context.cacheDir, "synthetic.ass")
        file.writeText("""[Script Info]
ScriptType: v4.00+
PlayResX: 320
PlayResY: 180
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:19.00,Default,,0,0,0,,Synthetic subtitle
""")
        try { FixtureServer().use { server ->
            Probe("VLC", server, activity, "movie.mp4", false, format = "MP4").use { probe ->
                await("VLC video must render") { probe.sample(); probe.firstFrameAt > 0 }
                val player = probe.vlc!!.player
                val before = player.spuTracks.orEmpty().map { it.id }.toSet()
                main { assertTrue(player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.fromFile(file), false)) }
                var attached: Int? = null
                await("Native ASS track must have a reliable new identity") {
                    attached = attachedSubtitleTrackId(before, player.spuTracks.orEmpty().map { it.id }, player.spuTrack)
                    attached != null
                }
                assertEquals("Attaching a pending subtitle must not override Off", -1, player.spuTrack)
                main { assertTrue(player.setSpuTrack(attached!!)); player.setSpuTrack(-1) }
                Thread.sleep(300L)
                assertEquals(-1, player.spuTrack)
            }
        } } finally { file.delete(); main { activity.finish() } }
    }

}
