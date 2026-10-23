package com.nikhil.niktv.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.AlternateSourceTarget
import com.nikhil.niktv.data.AlternateStream
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PortalProfile
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Actual PlayerScreen + NikTvViewModel alternate sessions, isolated from installed profiles.
 * The two selectable URLs intentionally serve the same silent HLS fixture: this checks
 * source/quality replacement state, headers and pause intent, not visual quality differences.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AlternatePlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun silently(block: () -> Unit) {
        check(context.packageName.endsWith(".regression")) { "Use -PregressionBuild=true; never run against personal app data" }
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        try { block() } finally { manager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
    }
    private fun await(message: String, timeout: Long = 25_000L, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            Thread.sleep(75L)
        }
        fail(message)
    }

    private fun find(node: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        if (node == null || !node.refresh()) return null
        if (node.text?.toString() == value || node.contentDescription?.toString() == value) return node
        for (index in 0 until node.childCount) find(node.getChild(index), value)?.let { return it }
        return null
    }
    private fun label(value: String) = find(instrumentation.uiAutomation.rootInActiveWindow, value)
    private fun click(value: String) {
        await("Missing control: $value") { label(value) != null }
        var node = label(value)
        while (node != null) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        fail("Control could not be activated: $value")
    }

    private fun awaitPausedFixturePicture() {
        // Inspect the colored silent testsrc video, away from toolbar/transport
        // controls. A retained still or a native paused frame must remain visible.
        repeat(10) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val visible = if (bitmap == null) false else try {
                var colored = 0
                for (y in bitmap.height / 3 until bitmap.height / 2 step 16)
                    for (x in bitmap.width / 4 until bitmap.width / 2 step 16) {
                        val pixel = bitmap.getPixel(x, y)
                        val channels = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                        if (channels.max() > 72 && channels.max() - channels.min() > 48) colored++
                    }
                colored > 20
            } finally { bitmap.recycle() }
            if (visible) return
            Thread.sleep(500L)
        }
        fail("Paused VLC replacement must show its retained or decoded fixture picture instead of a black screen")
    }

    private inner class LocalHls : AutoCloseable {
        private val listener = ServerSocket(0)
        private val workers = Executors.newFixedThreadPool(6)
        private val clients = Collections.synchronizedList(mutableListOf<Socket>())
        private val files = mutableMapOf<String, ByteArray>()
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val rejected = AtomicInteger()
        val headers = mapOf("User-Agent" to "NikTV-Alternate-Device-Fixture", "Referer" to "https://fixture.invalid/")
        val base = "http://127.0.0.1:${listener.localPort}"
        init {
            ZipInputStream(instrumentation.context.assets.open("playback-fixtures.zip")).use { zip ->
                while (true) { val item = zip.nextEntry ?: break; files[item.name] = zip.readBytes() }
            }
            Thread({
                while (!listener.isClosed) try {
                    val client = listener.accept()
                    clients.add(client)
                    workers.submit { serve(client) }
                } catch (_: Exception) { }
            }, "alternate-fixture-http").apply { isDaemon = true; start() }
        }
        private fun serve(client: Socket) {
            try {
                client.soTimeout = 5_000
                val input = client.getInputStream().bufferedReader()
                val first = input.readLine() ?: return
                val path = first.split(' ').getOrNull(1)?.substringBefore('?')?.removePrefix("/") ?: return
                paths.add(path)
                val received = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) break
                    received[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                fun reply(status: String, body: ByteArray, mime: String = "application/octet-stream", extra: String = "") {
                    val output = client.getOutputStream()
                    output.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nContent-Type: $mime\r\nConnection: close\r\n$extra\r\n".toByteArray())
                    if (!first.startsWith("HEAD")) output.write(body)
                    output.flush()
                }
                if (headers.any { (name, value) -> received[name.lowercase()] != value }) {
                    rejected.incrementAndGet(); reply("403 Forbidden", byteArrayOf()); return
                }
                val name = path.substringAfterLast('/')
                val asset = if (name == "a.m3u8" || name == "b.m3u8") "full.m3u8" else name
                val body = files[asset] ?: run {
                    rejected.incrementAndGet(); reply("404 Not Found", byteArrayOf()); return
                }
                val mime = if (asset.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
                val offset = received["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                if (offset != null && offset in body.indices)
                    reply("206 Partial Content", body.copyOfRange(offset, body.size), mime,
                        "Content-Range: bytes $offset-${body.lastIndex}/${body.size}\r\n")
                else reply("200 OK", body, mime)
            } catch (_: Exception) { }
            finally { clients.remove(client); runCatching { client.close() } }
        }
        fun sources(target: AlternateSourceTarget) = listOf("a", "b").map { id ->
            AlternateStream(id, "Fixture ${id.uppercase()}", "$base/${target.season ?: 0}-${target.episode ?: 0}/$id.m3u8",
                headers, "HLS", if (id == "a") 720 else 360)
        }
        override fun close() {
            listener.close()
            synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } }
            workers.shutdownNow()
        }
    }

    private inner class Harness(val engine: PlaybackEngine, val server: LocalHls,
        private val fixtureItem: MediaItem = MediaItem("alternate-device-movie", "Generated alternate fixture", null, "not-a-provider-url"),
        private val sourceLookup: (suspend (AlternateSourceTarget) -> List<AlternateStream>)? = null) : AutoCloseable {
        val firstFrames = AtomicInteger()
        val progress = AtomicLong(-1L)
        val errors = AtomicInteger()
        val lookups = Collections.synchronizedList(mutableListOf<AlternateSourceTarget>())
        val engineSelections = Collections.synchronizedList(mutableListOf<PlaybackEngine>())
        private val livePosition = AtomicReference<(() -> Long)?>(null)
        val activity: PlaybackFixtureHostActivity
        val vm: NikTvViewModel
        init {
            check(context.packageName.endsWith(".regression")) { "Use -PregressionBuild=true; never run against personal app data" }
            instrumentation.setInTouchMode(false)
            activity = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
            var created: NikTvViewModel? = null
            main {
                created = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = NikTvViewModel(context.applicationContext as Application) as T
                })[NikTvViewModel::class.java]
                val model = requireNotNull(created)
                // The isolated host deliberately has no profile startup, notifications,
                // recommendations or persistence collectors participating in this test.
                model.viewModelScope.coroutineContext.cancelChildren()
                @Suppress("UNCHECKED_CAST")
                val state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                    .get(model) as MutableStateFlow<NikTvState>
                state.value = NikTvState(savedProfile = PortalProfile("Local fixture", "https://provider.invalid", "00:00:00:00:00:00"),
                    session = null, restoring = false)
                model.alternateSourceLookup = { target -> lookups.add(target); sourceLookup?.invoke(target) ?: server.sources(target) }
                activity.setContent {
                    val current by model.state.collectAsState()
                    MaterialTheme {
                        val media = current.nowPlaying
                        if (media == null) Text("Isolated alternate playback fixture")
                        else PlayerScreen(media = media, onBack = model::closePlayer,
                            onRetry = { model.retryPlayback(it, media) },
                            onRetryAlternateDecoder = { errors.incrementAndGet() },
                            onPlaybackAuthorizationFailure = { errors.incrementAndGet() },
                            onPlayPrevious = model::playPreviousEpisode, onPlayNext = model::playNextEpisode,
                            // Observe in memory. Do not write even synthetic history to storage.
                            onProgress = { _, position, _ -> progress.set(position) },
                            onPlaybackIntentChanged = { model.setPlaybackIntent(media.playbackAttemptId, it) },
                            onPlaybackEngineChanged = { engineSelections.add(it) },
                            onVideoFrameRendered = { firstFrames.incrementAndGet() },
                            onChooseAlternateSource = { position, reader ->
                                livePosition.set(reader); model.openPlayingAlternateSources(position, media, reader)
                            }, alternateSourceChooserOpen = current.alternateSources != null,
                            playbackEngine = engine, controlsTimeoutSeconds = PLAYER_CONTROLS_TIMEOUT_INFINITE)
                        current.alternateSources?.let { picker ->
                            AlternateSourceDialog(picker, model::closeAlternateSources, model::findAlternateSources, model::chooseAlternateStream)
                        }
                    }
                }
            }
            vm = requireNotNull(created)
        }
        fun start(series: Boolean = false, season: Int = 1, episode: Int = 2) {
            main {
                val show = if (sourceLookup == null) fixtureItem.copy(id = "alternate-device-series", title = "Generated alternate series") else fixtureItem
                val item = if (series) show.copy(id = "alternate-device-episode-$episode", title = "Episode $episode", seasonNumber = season, episodeNumber = episode) else fixtureItem
                vm.openAlternateSources(AlternateSourceRequest(item, if (series) CatalogType.SERIES else CatalogType.MOVIES,
                    show.takeIf { series }, season.takeIf { series }, episode.takeIf { series }))
            }
            await("Alternate lookup must return without a portal session") {
                vm.state.value.alternateSources?.streams?.isNotEmpty() == true || vm.state.value.alternateSources?.error != null
            }
            val choices = requireNotNull(vm.state.value.alternateSources)
            assertTrue("Lookup failed: ${choices.error}", choices.streams.isNotEmpty())
            main { vm.chooseAlternateStream(choices.streams.first().id) }
            await("$engine alternate HLS must render") { firstFrames.get() > 0 }
            await("$engine alternate HLS must advance") { progress.get() > 1_000L }
            assertNull(vm.state.value.session)
            assertNotNull(vm.state.value.nowPlaying?.alternateSource)
        }
        fun position(): Long {
            var value = -1L
            main { value = requireNotNull(livePosition.get()) { "Open source chooser before reading position" }.invoke() }
            return value
        }
        fun sources() {
            await("Player controls must settle before opening source choices") {
                label("Source and quality") != null || label("More controls") != null
            }
            if (label("Source and quality") == null) click("More controls")
            click("Source and quality")
            await("Source and quality chooser must open") { vm.state.value.alternateSources != null && label("Play from another source") != null }
        }
        fun verifyIsolation() {
            assertNull("Alternate playback must not require a provider session", vm.state.value.session)
            assertTrue("Original catalog remains untouched", vm.state.value.items.isEmpty())
            assertTrue("No IPTV URL mappings are written", vm.state.value.playbackUrls.isEmpty())
            assertEquals("Required headers must reach playlists and segments", 0, server.rejected.get())
            assertEquals("No decoder/auth recovery was needed", 0, errors.get())
        }
        override fun close() {
            main { vm.closeAlternateSources(); vm.closePlayer(); activity.finish() }
            livePosition.set(null)
            instrumentation.waitForIdleSync()
            Thread.sleep(350L)
        }
    }

    @Test fun media3AlternatePlaybackAndQualityReplacement() = qualityReplacement(PlaybackEngine.MEDIA3)
    @Test fun flowAlternatePlaybackAndQualityReplacement() = qualityReplacement(PlaybackEngine.FLOW)
    @Test fun vlcAlternatePlaybackAndQualityReplacement() = qualityReplacement(PlaybackEngine.VLC)
    @Test fun mpvAlternatePlaybackAndQualityReplacement() {
        org.junit.Assume.assumeTrue(mpvSupportedSdk(android.os.Build.VERSION.SDK_INT))
        qualityReplacement(PlaybackEngine.MPV)
    }

    /** Explicitly opt in: these checks make public media requests, never private IPTV requests. */
    @Test fun movyMoviePlaysInExo() = movyPlayback(PlaybackEngine.MEDIA3)
    @Test fun movyMoviePlaysInVlc() = movyPlayback(PlaybackEngine.VLC)
    @Test fun movyMoviePlaysInFlow() = movyPlayback(PlaybackEngine.FLOW)
    @Test fun movyMoviePlaysInMpv() {
        org.junit.Assume.assumeTrue(mpvSupportedSdk(android.os.Build.VERSION.SDK_INT))
        movyPlayback(PlaybackEngine.MPV)
    }

    private fun movyPlayback(engine: PlaybackEngine) = silently {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("publicAlternateNetwork") == "true")
        val resolver = com.nikhil.niktv.data.AlternateSourceResolver()
        LocalHls().use { server ->
            val item = MediaItem("tmdb-movie:603", "The Matrix", null, null, externalTmdbId = 603)
            Harness(engine, server, item, { target ->
                resolver.resolve(target.copy(provider = com.nikhil.niktv.data.AlternateSourceProvider.MOVY))
                    .filter { it.route == com.nikhil.niktv.data.AlternatePlaybackRoute.NATIVE }
            }).use { harness ->
                harness.start()
                harness.sources()
                val before = harness.position()
                Thread.sleep(3_000L)
                assertTrue("$engine Movy playback must continue advancing", harness.position() > before + 1_000L)
                assertTrue(harness.vm.state.value.nowPlaying!!.alternateSource!!.streams.all {
                    it.route == com.nikhil.niktv.data.AlternatePlaybackRoute.NATIVE
                })
                harness.verifyIsolation()
            }
        }
    }

    @Test fun publicMoviePlaysInAllAvailableNikTvEngines() = silently {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("publicAlternateNetwork") == "true")
        val resolver = com.nikhil.niktv.data.AlternateSourceResolver()
        val engines = listOf(PlaybackEngine.MEDIA3, PlaybackEngine.VLC, PlaybackEngine.FLOW) +
            if (mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) listOf(PlaybackEngine.MPV) else emptyList()
        for (engine in engines) LocalHls().use { server ->
            val item = MediaItem("tmdb-movie:603", "The Matrix", null, null)
            Harness(engine, server, item, resolver::resolve).use { harness ->
                harness.start()
                val before = harness.progress.get()
                Thread.sleep(2_000L)
                harness.sources()
                val position = harness.position()
                assertTrue("$engine public movie must continue playing", position > before)
                click("Close")
                assertEquals(603, harness.lookups.single().tmdbId)
                harness.verifyIsolation()
                android.util.Log.i("NikTvAlternateFixture", "publicMovie=603 engine=$engine firstFrames=${harness.firstFrames.get()} positionMs=$position")
            }
        }
    }

    @Test fun publicSeriesEpisodePlaysInNikTv() = silently {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("publicAlternateNetwork") == "true")
        val resolver = com.nikhil.niktv.data.AlternateSourceResolver()
        LocalHls().use { server ->
            val series = MediaItem("tmdb-series:1668", "Friends", null, null)
            Harness(PlaybackEngine.MEDIA3, server, series, resolver::resolve).use { harness ->
                harness.start(series = true, season = 3, episode = 25)
                assertEquals(1668, harness.lookups.single().tmdbId)
                assertEquals(3, harness.lookups.single().season)
                assertEquals(25, harness.lookups.single().episode)
                harness.verifyIsolation()
                android.util.Log.i("NikTvAlternateFixture", "publicSeries=1668 season=3 episode=25 firstFrames=${harness.firstFrames.get()} positionMs=${harness.progress.get()}")
            }
        }
    }

    private fun qualityReplacement(engine: PlaybackEngine) = silently {
        LocalHls().use { server -> Harness(engine, server).use { harness ->
            harness.start()
            val initial = requireNotNull(harness.vm.state.value.nowPlaying)
            harness.sources()
            val openedAt = harness.position()
            Thread.sleep(1_400L)
            val selectedAt = harness.position()
            assertTrue("$engine current stream must keep playing while chooser is open", selectedAt > openedAt + 500L)
            val framesBeforeSwitch = harness.firstFrames.get()
            harness.progress.set(-1L)
            click("Fixture B")
            await("$engine alternate quality selection must commit") {
                harness.vm.state.value.nowPlaying?.alternateSource?.selectedId == "b" && harness.vm.state.value.alternateSources == null
            }
            val replacement = requireNotNull(harness.vm.state.value.nowPlaying)
            assertEquals(initial.progressKey, replacement.progressKey)
            assertNotEquals(initial.playbackAttemptId, replacement.playbackAttemptId)
            assertTrue("Quality change must sample the live position, rather than rewind to dialog open",
                replacement.resumePositionMillis >= selectedAt - 250L && replacement.resumePositionMillis <= selectedAt + 3_000L)
            assertTrue(replacement.playWhenReady)
            await("$engine replaced source must render and keep progressing") {
                harness.firstFrames.get() > framesBeforeSwitch && harness.progress.get() > replacement.resumePositionMillis + 500L
            }

            click("Pause")
            await("Pause intent must reach the committed alternate session") { harness.vm.state.value.nowPlaying?.playWhenReady == false }
            harness.sources()
            val pausedAt = harness.position()
            Thread.sleep(700L)
            assertTrue("$engine must remain paused while choosing another quality", kotlin.math.abs(harness.position() - pausedAt) < 300L)
            val pausedFrames = harness.firstFrames.get()
            click("Fixture A")
            await("Paused quality selection must commit") { harness.vm.state.value.nowPlaying?.alternateSource?.selectedId == "a" }
            val pausedReplacement = requireNotNull(harness.vm.state.value.nowPlaying)
            assertFalse("Quality change must preserve pause intent", pausedReplacement.playWhenReady)
            assertTrue(kotlin.math.abs(pausedReplacement.resumePositionMillis - pausedAt) < 750L)
            Thread.sleep(750L)
            assertFalse(harness.vm.state.value.nowPlaying!!.playWhenReady)
            var expectedResume = pausedAt
            if (engine == PlaybackEngine.VLC) {
                awaitPausedFixturePicture()
                assertFalse("Showing the paused picture must not resume playback", harness.vm.state.value.nowPlaying!!.playWhenReady)
            }
            click("Play")
            if (engine == PlaybackEngine.VLC) {
                // VLC learns the replacement's duration only once its paused
                // demuxer starts. Seek during restoration, once the control is
                // available, rather than asking for an unavailable paused action.
                await("VLC seek control must appear during restoration") { label("Forward 10 seconds") != null }
                assertNotNull("Exercise manual seek while startup positioning is pending", label("Restoring playback position…"))
                click("Forward 10 seconds")
                expectedResume = pausedAt + 9_000L
            }
            await("$engine explicit play must resume the replacement") {
                harness.firstFrames.get() > pausedFrames && harness.progress.get() > expectedResume + 500L
            }

            harness.sources()
            val attempt = harness.vm.state.value.nowPlaying!!.playbackAttemptId
            val beforeCancel = harness.position()
            click("Close")
            await("Cancel must close source chooser") { harness.vm.state.value.alternateSources == null }
            Thread.sleep(850L)
            assertEquals("Cancel must not recreate playback", attempt, harness.vm.state.value.nowPlaying!!.playbackAttemptId)
            assertTrue("Cancel must leave playback progressing", harness.position() > beforeCancel + 300L)
            // D-pad OK should reopen the originating source control after dismissal.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Source chooser must restore its D-pad origin") { harness.vm.state.value.alternateSources != null }
            click("Close")
            assertEquals("Cached source choices do not repeat lookup", 1, harness.lookups.size)
            harness.verifyIsolation()
            android.util.Log.i("NikTvAlternateFixture", "engine=$engine frames=${harness.firstFrames.get()} latestPositionMs=${harness.progress.get()} requests=${server.paths.size} lookups=${harness.lookups.size}")
        } }
    }

    @Test fun alternateSeriesNavigationResolvesOnlyTheRequestedEpisode() = silently {
        LocalHls().use { server -> Harness(PlaybackEngine.MEDIA3, server).use { harness ->
            harness.start(series = true)
            val original = requireNotNull(harness.vm.state.value.nowPlaying)
            click("Next")
            await("Alternate Next must resolve episode 3") { harness.vm.state.value.nowPlaying?.media?.episodeNumber == 3 }
            assertEquals(0L, harness.vm.state.value.nowPlaying!!.resumePositionMillis)
            click("Previous")
            await("Alternate Previous must resolve episode 2") { harness.vm.state.value.nowPlaying?.media?.episodeNumber == 2 }
            assertEquals(original.progressKey, harness.vm.state.value.nowPlaying!!.progressKey)
            assertEquals(listOf(2, 3, 2), harness.lookups.map { it.episode })
            assertTrue(harness.lookups.all { it.season == 1 && it.series })
            harness.verifyIsolation()
        } }
    }

    @Test fun alternateSessionSwitchesEnginesThroughTheRealPlayerOverlayWithoutRepeatingLookup() = silently {
        LocalHls().use { server -> Harness(PlaybackEngine.MEDIA3, server).use { harness ->
            harness.start()
            val original = requireNotNull(harness.vm.state.value.nowPlaying)
            val targets = buildList {
                add(PlaybackEngine.VLC)
                if (mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) add(PlaybackEngine.MPV)
                add(PlaybackEngine.FLOW)
                add(PlaybackEngine.MEDIA3)
            }
            var current = PlaybackEngine.MEDIA3
            for (target in targets) {
                // Obtain the live supplier from the current decoder through its
                // actual Source control, then close before opening the engine picker.
                harness.sources()
                val before = harness.position()
                click("Close")
                await("Source picker must close before choosing an engine") { harness.vm.state.value.alternateSources == null }
                val origin = "Player: ${current.playerChoiceLabel()}"
                // State dismissal precedes removal of the separate dialog window.
                // Wait for player semantics before deciding whether its toolbar is collapsed.
                await("Player controls must settle before opening the engine picker") {
                    label(origin) != null || label("More controls") != null
                }
                if (label(origin) == null) click("More controls")
                click(origin)
                await("In-player engine overlay must open") { label("Choose player") != null }
                val frames = harness.firstFrames.get()
                click(target.playerChoiceLabel())
                await("Engine selection must reach the existing player callback") { harness.engineSelections.lastOrNull() == target }
                await("$target must render the existing alternate source") { harness.firstFrames.get() > frames }

                harness.sources()
                val after = harness.position()
                // A rendered opening frame can precede asynchronous native resume.
                // Record the trajectory without waiting until a wrong clock catches up.
                val resumeSamples = mutableListOf(after)
                repeat(4) { Thread.sleep(250L); resumeSamples += harness.position() }
                android.util.Log.i("NikTvAlternateFixture", "seekTrace=$current->$target beforeMs=$before positions=$resumeSamples")
                // Allow a small native timestamp rounding tolerance; switching
                // must never restart the VOD at zero or its original resume point.
                assertTrue("$current -> $target rewound from $before to $after", after >= before - 1_000L)
                Thread.sleep(1_000L)
                assertTrue("$target replacement must keep playing", harness.position() > after + 400L)
                click("Close")
                await("Source inspection must close") { harness.vm.state.value.alternateSources == null }

                val active = requireNotNull(harness.vm.state.value.nowPlaying)
                assertEquals("Engine changes retain the committed session", original.playbackAttemptId, active.playbackAttemptId)
                assertEquals(original.progressKey, active.progressKey)
                assertEquals(original.url, active.url)
                assertEquals(original.streamRequestHeaders, active.streamRequestHeaders)
                assertEquals(original.alternateSource, active.alternateSource)
                assertTrue(active.playWhenReady)
                assertEquals("Engine changes reuse the resolved source list", 1, harness.lookups.size)
                harness.verifyIsolation()
                android.util.Log.i("NikTvAlternateFixture", "engineSwitch=$current->$target beforeMs=$before afterMs=$after frames=${harness.firstFrames.get()} lookups=${harness.lookups.size}")
                current = target
            }
            assertEquals(targets, harness.engineSelections.toList())
        } }
    }
}
