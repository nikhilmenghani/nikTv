package com.nikhil.niktv.ui

import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.view.SurfaceView
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
import java.util.zip.ZipInputStream

/** Local HTTP only, separate app package, silent generated fixtures. No provider or profile access. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackEngineFixtureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, limit: Long = 20_000L, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + limit
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(50L)
        }
        fail(label)
    }

    private fun host(): PlaybackFixtureHostActivity {
        check(context.packageName.endsWith(".regression"))
        File(context.cacheDir, "retry.wav").delete()
        return instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
    }

    private inner class FixtureServer : AutoCloseable {
        private val socket = ServerSocket(0)
        private val workers = Executors.newCachedThreadPool()
        private val clients = Collections.synchronizedList(mutableListOf<Socket>())
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val current = AtomicInteger()
        val peak = AtomicInteger()
        var requiredHeaders: Map<String, String> = emptyMap()
        val rejected = AtomicInteger()
        var missingOnce: String? = null
        val missing = AtomicInteger()
        private val files = mutableMapOf<String, ByteArray>()
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
                fun reply(status: String, body: ByteArray, type: String = "application/octet-stream", extra: String = "") {
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nContent-Type: $type\r\nConnection: close\r\n$extra\r\n".toByteArray())
                    if (!first.startsWith("HEAD")) out.write(body)
                    out.flush()
                }
                if (path == "catalog") { Thread.sleep(200L); reply("200 OK", "catalog-metadata".toByteArray(), "application/json"); return }
                if (path == "hang") { Thread.sleep(30_000L); return }
                if (requiredHeaders.any { (name, value) -> headers[name.lowercase()] != value }) {
                    rejected.incrementAndGet(); reply("403 Forbidden", byteArrayOf()); return
                }
                if (path == missingOnce && missing.getAndIncrement() == 0) { reply("404 Not Found", byteArrayOf()); return }
                if (path == "redirect") { reply("302 Found", byteArrayOf(), extra = "Location: /live\r\n"); return }
                val name = when (path) { "live", "vod-hls" -> "full.m3u8"; "opaque-dash" -> "manifest.mpd"; else -> path }
                var body = files[name] ?: run { reply("404 Not Found", byteArrayOf()); return }
                if (path == "live") body = body.toString(Charsets.UTF_8).replace("#EXT-X-ENDLIST", "").toByteArray()
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
                    io.github.aedev.flow.player.factory.PlayerFactory().createRenderersFactory(context)
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
            fun text(node: android.view.accessibility.AccessibilityNodeInfo?): String {
                if (node == null) return ""
                return node.text.toString() + " " + (0 until node.childCount).joinToString(" ") { text(node.getChild(it)) }
            }
            try {
                await("Hanging VLC startup must expose an actionable error", 35_000L) {
                    val content = text(instrumentation.uiAutomation.rootInActiveWindow)
                    content.contains("25 seconds") || content.contains("VLC could not open")
                }
                assertEquals("Unknown startup failure must not refresh credentials", 0, PlaybackRegressionActivity.automaticRetries)
            } finally { main { activity.finish() } }
        }
    }

    @Test fun audioFocusAndHeadphoneRemovalPreserveUserPauseAcrossEngines() {
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
                    instrumentation.uiAutomation.executeShellCommand(
                        "am broadcast -a android.media.AUDIO_BECOMING_NOISY -p ${context.packageName}").close()
                    await("$engine must pause on headphone disconnection") { !probe.playing }
                }
            }
        } finally { main { manager.abandonAudioFocusRequest(request); activity.finish() } }
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
