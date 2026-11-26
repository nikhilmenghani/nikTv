package com.nikhil.niktv.torrent

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android torrent engine + player. The desktop peer only seeds the generated fixture;
 * no desktop downloader, range bridge, NikBot, account or portal credentials are involved. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TorrentStreamingDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = ins.runOnMainSync(block)
    private fun await(label: String, seconds: Int = 45, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + seconds * 1000L
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail(label)
    }
    private fun playerView(view: View): PlayerView? = when (view) {
        is PlayerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { playerView(view.getChildAt(it)) }
        else -> null
    }

    @Test fun includedCaptionsAreVerifiedWithoutInvalidatingTheVideoReader() = runBlocking {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("torrent_peer_port")?.toIntOrNull()
        assumeTrue("Controlled caption seeder required", port != null && args.containsKey("torrent_subtitle_srt_sha256"))
        val descriptor = File(context.filesDir, "torrent-fixture.torrent")
        val evidence = File(context.getExternalFilesDir(null), "torrent-caption-evidence").apply { mkdirs() }
        val trace = File(evidence, "engine-trace.txt").apply { writeText("") }
        val engine = TorrentStreamEngine(context, TorrentEngineOptions(discovery = false,
            peerHost = "127.0.0.1", peerPort = port!!, allowLocalDescriptor = true,
            trace = { trace.appendText(it + "\n") }))
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        try {
            val videos = engine.prepare(descriptor.toURI().toString())
            val videoUrl = engine.play(videos.single().index)
            val captions = engine.subtitles
            assertEquals("Only this video's English and Hindi sidecars match", 2, captions.size)
            assertEquals(setOf("en", "hi"), captions.map { it.language }.toSet())
            assertTrue(captions.none { it.name.contains("other-show") })
            val prefix = ByteArray(32768)
            // Retain one production video reader across both real HTTP caption
            // downloads. A shared video generation would break its second read.
            engine.openRange(0, prefix.lastIndex.toLong()).use { video ->
                java.io.DataInputStream(video).readFully(prefix, 0, 16384)
                for (caption in captions) {
                    val extension = caption.name.substringAfterLast('.')
                    val connection = URL(caption.url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5_000; connection.readTimeout = 45_000
                    try {
                        assertEquals(200, connection.responseCode)
                        val bytes = connection.inputStream.use { it.readBytes() }
                        assertEquals(caption.sizeBytes, bytes.size.toLong())
                        val expected = args.getString(if (extension == "srt") "torrent_subtitle_srt_sha256" else "torrent_subtitle_vtt_sha256")
                        assertEquals("$extension bytes are hash verified after partfile migration", expected, digest(bytes))
                        File(evidence, "selected-$extension.txt").writeText("index=${caption.index} bytes=${bytes.size} sha256=${digest(bytes)}\n")
                    } finally { connection.disconnect() }
                    val head = URL(caption.url).openConnection() as HttpURLConnection
                    head.requestMethod = "HEAD"; head.connectTimeout = 5_000; head.readTimeout = 5_000
                    try { assertEquals(200, head.responseCode); assertEquals(caption.sizeBytes, head.contentLengthLong) }
                    finally { head.disconnect() }
                    val invalid = URL(caption.url).openConnection() as HttpURLConnection
                    invalid.setRequestProperty("Range", "bytes=${caption.sizeBytes}-")
                    try { assertEquals(416, invalid.responseCode) } finally { invalid.disconnect() }
                }
                java.io.DataInputStream(video).readFully(prefix, 16384, 16384)
            }
            assertEquals("Caption GET never cancels or changes video range", args.getString("torrent_prefix_sha256"), digest(prefix))
            assertNull("Subtitle work leaves video failure state untouched", engine.status.value.error)
            File(evidence, "result.txt").writeText("PASS matching2 verifiedSrt verifiedVtt sharedPieceMigration independentVideoReader HEAD 416\nvideo=$videoUrl\n")
        } catch (failure: Throwable) {
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally { engine.close() }
    }

    @Test fun playsBeforeCompletionSeeksAndSurvivesRotation() = runBlocking {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val args = InstrumentationRegistry.getArguments()
        val peerPort = args.getString("torrent_peer_port")?.toIntOrNull()
        assumeTrue("Controlled local seeder required", peerPort != null)
        val descriptor = File(context.filesDir, "torrent-fixture.torrent")
        assertTrue("Install the legal test descriptor into app private files first", descriptor.isFile)
        val evidence = File(context.getExternalFilesDir(null), "torrent-evidence").apply { mkdirs() }
        val notes = mutableListOf<String>()
        val currentTrace = File(evidence, "current-trace.txt").apply { writeText("") }
        val candidateTrace = File(evidence, "candidate-trace.txt").apply { writeText("") }
        val engine = TorrentStreamEngine(context, TorrentEngineOptions(discovery = false,
            downloadLimitBytesPerSecond = 524288, peerHost = "127.0.0.1", peerPort = peerPort!!,
            allowLocalDescriptor = true, trace = { currentTrace.appendText(it + "\n") }))
        var host: PlaybackFixtureHostActivity? = null
        var prefetched: PreparedTorrentEpisode? = null
        var candidateOwned: TorrentStreamEngine? = null
        try {
            val files = engine.prepare(descriptor.toURI().toString())
            assertEquals("One video in generated fixture", 1, files.size)
            val url = engine.play(files.single().index)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Range", "bytes=0-32767")
            try {
                assertEquals(206, connection.responseCode)
                val received = connection.inputStream.use { it.readBytes() }
                assertEquals(32768, received.size)
                val digest = MessageDigest.getInstance("SHA-256").digest(received).joinToString("") { "%02x".format(it) }
                assertEquals("Verified torrent bytes match fixture", args.getString("torrent_prefix_sha256"), digest)
            } finally { connection.disconnect() }
            host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
            val activity = requireNotNull(host)
            val visibleMedia = mutableStateOf(PlayingMedia(MediaItem("torrent-proof", "On-device torrent streaming", null, null), url,
                CatalogType.MOVIES, progressKey = "torrent-proof", externalPlayback = true))
            main {
                activity.setContent { MaterialTheme(colorScheme = NikColors) {
                    PlayerScreen(visibleMedia.value,
                        onBack = {}, onRetry = {}, onRetryAlternateDecoder = {}, onPlaybackAuthorizationFailure = {},
                        onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                        playbackEngine = PlaybackEngine.MEDIA3, startFullscreen = true, controlsTimeoutSeconds = 0)
                } }
            }
            var player: Player? = null
            await("Player decoded video before torrent completion") {
                var ready = false
                main {
                    player = playerView(activity.window.decorView)?.player
                    val native = player as? androidx.media3.exoplayer.ExoPlayer
                    native?.videoDecoderCounters?.ensureUpdated()
                    ready = player?.isPlaying == true && (native?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 10
                }
                ready
            }
            val active = requireNotNull(player)
            fun snapshot(label: String) {
                val status = engine.status.value
                notes += "$label downloaded=${status.downloadedBytes}/${status.totalBytes} peers=${status.peers} stage=${status.stage}"
                assertTrue("Playback must precede full download", status.downloadedBytes < status.totalBytes)
                ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                    try { File(evidence, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            }
            snapshot("playing")
            // Production begins prefetch only after six contiguous MiB are
            // available beyond the current reader, never after just one frame.
            withTimeout(90_000) {
                while (torrentPrefetchGate(engine.status.value, paused = true) != TorrentPrefetchGate.RUN) {
                    var currentPlaying = false
                    main { currentPlaying = active.isPlaying }
                    assertTrue("Current playback remains smooth before the prefetch gate opens", currentPlaying)
                    delay(200)
                }
            }
            notes += "prefetch-gate opened_buffer=${engine.status.value.bufferedBytes}"
            // A second native session must yield without pausing the first one.
            val candidate = TorrentStreamEngine(context, TorrentEngineOptions(discovery = false,
                downloadLimitBytesPerSecond = 131072, peerHost = "127.0.0.1", peerPort = peerPort,
                allowLocalDescriptor = true, trace = { candidateTrace.appendText(it + "\n") })).also { candidateOwned = it }
            val prefetchWorker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var preparing: Deferred<String>? = null
            var gate: Job? = null
            try {
                candidate.setBackgroundPaused(true)
                val candidates = candidate.prepare(descriptor.toURI().toString())
                val preparingVideo = prefetchWorker.async { candidate.play(candidates.single().index) }
                preparing = preparingVideo
                delay(700)
                assertTrue(candidate.status.value.backgroundPaused)
                assertFalse("Paused background work cannot claim readiness", preparingVideo.isCompleted)
                var currentPlaying = false
                main { currentPlaying = active.isPlaying }
                assertTrue("Pausing prefetch leaves current playback alone", currentPlaying)
                gate = prefetchWorker.launch {
                    var paused = true
                    engine.status.collect { current ->
                        when (torrentPrefetchGate(current, paused)) {
                            TorrentPrefetchGate.CANCEL -> throw CancellationException("Current playback closed")
                            TorrentPrefetchGate.PAUSE -> if (!paused) { paused = true; candidate.setBackgroundPaused(true) }
                            TorrentPrefetchGate.RUN -> if (paused) { paused = false; candidate.setBackgroundPaused(false) }
                        }
                    }
                }
                withTimeout(90_000) {
                    while (!preparingVideo.isCompleted) {
                        main { currentPlaying = active.isPlaying }
                        assertTrue("Background download must not stall current playback", currentPlaying)
                        delay(200)
                    }
                }
                val nextUrl = preparingVideo.await()
                gate?.cancelAndJoin()
                assertTrue(nextUrl.startsWith("http://127.0.0.1:"))
                notes += "concurrent-prefetch downloaded=${candidate.status.value.downloadedBytes} rate_cap=131072 current_uninterrupted=true"
                // Exercise the same bounded lease adoption used by Play Next,
                // then verify its cached URL without replacing the current video.
                candidate.setBackgroundPaused(true)
                val prepared = PreparedTorrentEpisode(
                    TorrentEpisodeTarget(com.nikhil.niktv.discovery.DiscoveryCard(1, true, "Generated fixture"),
                        com.nikhil.niktv.data.TmdbEpisode(1, 2, "Generated fixture", null, null, null)),
                    TorrentSource("fixture", "Generated fixture", "", descriptor.toURI().toString(),
                        candidates.single().sizeBytes, 1, null, "Isolated test"),
                    candidates.single(), nextUrl, candidate,
                    CoroutineScope(SupervisorJob() + Dispatchers.IO), AtomicBoolean(false))
                prefetched = prepared
                prepared.adopt()
                assertFalse("Adopting a ready episode resumes its own transfer", candidate.status.value.backgroundPaused)
                val adopted = URL(prepared.url).openConnection() as HttpURLConnection
                adopted.connectTimeout = 5_000; adopted.readTimeout = 5_000
                adopted.setRequestProperty("Range", "bytes=0-32767")
                try {
                    assertEquals(206, adopted.responseCode)
                    val digest = MessageDigest.getInstance("SHA-256").digest(adopted.inputStream.use { it.readBytes() })
                        .joinToString("") { "%02x".format(it) }
                    assertEquals("Adopted playback URL serves the verified prepared episode", args.getString("torrent_prefix_sha256"), digest)
                } finally { adopted.disconnect() }
                notes += "prepared-adoption verified_prefix=true resumed=true current_uninterrupted=true"
            } catch (error: Throwable) {
                File(evidence, "failure.txt").writeText("${error.javaClass.simpleName}: ${error.message}\ncurrent=${engine.status.value}\ncandidate=${candidate.status.value}")
                throw error
            } finally {
                withContext(NonCancellable) { gate?.cancelAndJoin(); preparing?.cancelAndJoin() }
                prefetchWorker.cancel()
                if (prefetched == null) candidate.close()
            }
            candidate.setBackgroundPaused(true)
            main { assertTrue("Yielding the prepared transfer preserves current player", active.isPlaying) }
            fun seek(target: Long, label: String) {
                var frames = 0
                main { frames = (active as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
                val start = SystemClock.uptimeMillis()
                main { active.seekTo(target) }
                await("$label resumes decoded frames") {
                    var resumed = false
                    main {
                        val counters = (active as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters
                        counters?.ensureUpdated()
                        resumed = active.isPlaying && active.playbackState == Player.STATE_READY &&
                            active.currentPosition in target..target + 20_000 && (counters?.renderedOutputBufferCount ?: 0) > frames + 10
                    }
                    resumed
                }
                notes += "$label resume_ms=${SystemClock.uptimeMillis() - start}"
                snapshot(label)
            }
            seek(450_000, "forward-seek")
            seek(30_000, "backward-seek")
            main { active.pause() }
            await("Pause stops playback") { var paused = false; main { paused = !active.isPlaying }; paused }
            main { active.play() }
            await("Resume restarts playback") { var playing = false; main { playing = active.isPlaying }; playing }
            main { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("Rotation retains the same player and session") {
                var same = false
                main { same = playerView(activity.window.decorView)?.player === active && active.isPlaying }
                same && activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            snapshot("landscape")
            val next = requireNotNull(prefetched)
            var previousFrames = 0
            main { previousFrames = (active as androidx.media3.exoplayer.ExoPlayer).videoDecoderCounters?.renderedOutputBufferCount ?: 0 }
            val handoffStarted = SystemClock.uptimeMillis()
            next.adopt()
            main {
                visibleMedia.value = PlayingMedia(MediaItem("torrent-proof-next", "Prepared next episode", null, null), next.url,
                    CatalogType.MOVIES, progressKey = "torrent-proof-next", externalPlayback = true)
            }
            await("Prepared episode decodes after the in-place player handoff", seconds = 10) {
                var decoded = false
                main {
                    val second = playerView(activity.window.decorView)?.player as? androidx.media3.exoplayer.ExoPlayer
                    second?.videoDecoderCounters?.ensureUpdated()
                    val requiredFrames = if (second === active) previousFrames + 10 else 10
                    decoded = second?.isPlaying == true && second.playbackState == Player.STATE_READY &&
                        second.currentMediaItem?.localConfiguration?.uri.toString() == next.url &&
                        second.currentPosition in 0L..10_000L &&
                        (second.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > requiredFrames
                }
                decoded
            }
            notes += "handoff_ms=${SystemClock.uptimeMillis() - handoffStarted} decoded_next=true reused_prepared_url=true"
            ins.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { File(evidence, "prepared-handoff.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            // Only release the old session after the prepared URL has resumed
            // and produced decoded frames in the real shared player.
            engine.close()
            File(evidence, "result.txt").writeText(notes.joinToString("\n"))
            main { activity.finish() }
            host = null
            engine.close()
            next.close()
            await("Closing torrent releases loopback server", seconds = 8) {
                runCatching {
                    (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 500; readTimeout = 500 }.useResponse()
                }.isFailure
            }
            await("Closing prepared episode releases its loopback server", seconds = 8) {
                runCatching {
                    (URL(next.url).openConnection() as HttpURLConnection).apply { connectTimeout = 500; readTimeout = 500 }.useResponse()
                }.isFailure
            }
        } finally {
            host?.let { main { it.finish() } }
            prefetched?.close()
            candidateOwned?.close()
            engine.close()
        }
    }
    private fun HttpURLConnection.useResponse(): Int = try { responseCode } finally { disconnect() }
}
