package com.nikhil.niktv.sports

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.PlaybackFixtureHostActivity
import com.nikhil.niktv.ui.SportsFixtureHostActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Bounded real Sports check, not an exhaustive provider/link audit. No IPTV profiles. */
class SportsLiveStabilityDeviceTest {
    /** Two patches of decoded picture, away from the loading spinner and controls. */
    private fun pictureFingerprint(): Long? {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return null
        return try {
            var hash = 1125899906842597L
            for (left in floatArrayOf(.20f, .62f)) for (y in 0 until 16) for (x in 0 until 16) {
                val px = (bitmap.width * (left + x * .01f)).toInt().coerceIn(0, bitmap.width - 1)
                val py = (bitmap.height * (.30f + y * .015f)).toInt().coerceIn(0, bitmap.height - 1)
                hash = hash * 31 + bitmap.getPixel(px, py)
            }
            hash
        } finally { bitmap.recycle() }
    }

    @Test fun currentLiveEventSustainsCompatibleNativePlayers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val settings = SportsPlaybackPreferences(context)
        val previousSettings = settings.settings.first()
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        var host: PlaybackFixtureHostActivity? = null
        val outcomes = mutableListOf<JsonObject>()
        val required = mutableSetOf<PlaybackEngine>()
        val successful = mutableSetOf<PlaybackEngine>()
        fun await(limit: Long, condition: () -> Boolean): Boolean {
            val until = SystemClock.elapsedRealtime() + limit
            while (SystemClock.elapsedRealtime() < until) { if (condition()) return true; SystemClock.sleep(100) }
            return condition()
        }
        fun labels(): List<String> {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            fun walk(node: android.view.accessibility.AccessibilityNodeInfo?): List<String> = if (node == null) emptyList()
                else listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) + (0 until node.childCount).flatMap { walk(node.getChild(it)) }
            return walk(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun clock(): Long? = labels().firstOrNull { it.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")) }
            ?.split(':')?.fold(0L) { total, part -> total * 60 + part.toLong() }
        try {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
            lateinit var vm: SportsViewModel
            instrumentation.runOnMainSync { vm = ViewModelProvider(requireNotNull(host))[SportsViewModel::class.java] }
            assertTrue("Sports catalogue completes", await(45_000) { !vm.state.value.loading })
            assumeTrue("Current Sports catalogue is unavailable", vm.state.value.error == null)
            val live = vm.state.value.events.filter { it.status(Instant.now()) == SportsStatus.LIVE }
            assumeTrue("No currently live Sports event is listed", live.isNotEmpty())
            val event = live.firstOrNull { it.title.contains("India", true) || it.sport.contains("Cricket", true) } ?: live.first()
            instrumentation.runOnMainSync { vm.open(event) }
            assertTrue("Source listing completes", await(35_000) { !vm.state.value.loadingStreams })
            assumeTrue("Live event has no available source list", vm.state.value.streamError == null && vm.state.value.streams.isNotEmpty())
            val indexed = vm.state.value.streams.withIndex().toList()
            val choices = indexed.filter { it.value.type == 7 }.take(2) + indexed.filter { it.value.type == 0 }.take(2)
            assumeTrue("Live event has no directly supported native choices", choices.isNotEmpty())
            for (choice in choices) {
                instrumentation.runOnMainSync { vm.play(choice.index) }
                if (!await(35_000) { vm.state.value.resolving == null }) continue
                if (vm.state.value.playingIndex != choice.index) continue
                val stream = vm.state.value.playing ?: continue
                val engines = if (stream.drmType.isNotBlank()) listOf(PlaybackEngine.MEDIA3, PlaybackEngine.FLOW)
                    else listOf(PlaybackEngine.MEDIA3, PlaybackEngine.FLOW, PlaybackEngine.VLC, PlaybackEngine.MPV)
                required.addAll(engines)
                for (engine in engines.filterNot { it in successful }) {
                    // Preserve the production compatible-engine fallback; do not
                    // mislabel an authenticated Media3 session as native VLC.
                    val effectiveEngine = if (engine == PlaybackEngine.VLC &&
                        com.nikhil.niktv.ui.vlcHeaderOptions(stream.asPlayingMedia(event.title).streamRequestHeaders) == null)
                        PlaybackEngine.MEDIA3 else engine
                    settings.setPlaybackEngine(engine); settings.setPlayerControlsTimeoutSeconds(0)
                    val activity = instrumentation.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
                    val first = AtomicInteger(); val frames = AtomicInteger()
                    val start = SystemClock.elapsedRealtime()
                    try {
                        instrumentation.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                            SportsPlayer(stream, event.title, onBack = {}, onVideoFrameRendered = { first.incrementAndGet() },
                                onVideoFramesRendered = { frames.set(it) })
                        } } }
                        val rendered = await(25_000) { first.get() > 0 }
                        val firstMs = SystemClock.elapsedRealtime() - start
                        var advanced = false
                        var advancingIntervals = 0
                        var stalledIntervals = 0
                        var longestStall = 0
                        val pictures = linkedSetOf<Long>()
                        if (rendered) {
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                            SystemClock.sleep(1_000)
                            val beforeFrames = frames.get(); val beforeClock = clock()
                            var previousFrames = beforeFrames
                            var previousClock = beforeClock
                            for (sample in 1..30) {
                                SystemClock.sleep(1_000)
                                if (labels().any { it.contains("can’t be played") || it.contains("could not open", true) }) break
                                val currentFrames = frames.get(); val currentClock = clock()
                                if (effectiveEngine == PlaybackEngine.VLC || effectiveEngine == PlaybackEngine.MPV)
                                    pictureFingerprint()?.let(pictures::add)
                                val progressed = if (effectiveEngine == PlaybackEngine.MEDIA3 || effectiveEngine == PlaybackEngine.FLOW)
                                    currentFrames > previousFrames
                                else previousClock != null && currentClock != null && currentClock > previousClock
                                if (progressed) { advancingIntervals++; stalledIntervals = 0 } else stalledIntervals++
                                longestStall = maxOf(longestStall, stalledIntervals)
                                previousFrames = currentFrames; previousClock = currentClock
                            }
                            advanced = advancingIntervals >= 24 && longestStall < 4 &&
                                if (effectiveEngine == PlaybackEngine.MEDIA3 || effectiveEngine == PlaybackEngine.FLOW) frames.get() >= beforeFrames + 100
                                else beforeClock != null && (clock() ?: beforeClock) >= beforeClock + 25 && pictures.size >= 4
                        }
                        val errorVisible = labels().any { it.contains("can’t be played") || it.contains("could not open", true) }
                        val passed = rendered && advanced && !errorVisible
                        if (passed) successful.add(engine)
                        outcomes += buildJsonObject {
                            put("engine", engine.name); put("effectiveEngine", effectiveEngine.name)
                            put("sourceType", stream.type); put("protected", stream.drmType.isNotBlank())
                            put("firstFrameMs", firstMs); put("renderedFrames", frames.get()); put("advanced", advanced)
                            put("advancingIntervals", advancingIntervals); put("longestNoProgressSamples", longestStall)
                            put("distinctPictureSamples", pictures.size)
                            put("errorVisible", errorVisible); put("passed", passed)
                        }
                        android.util.Log.i("NikTvStability", "sports-real ${outcomes.last()}")
                    } finally {
                        instrumentation.runOnMainSync { activity.setContent {}; activity.finish() }
                        instrumentation.waitForIdleSync()
                        val vlcDrained = CompletableDeferred<Unit>(); val mpvDrained = CompletableDeferred<Unit>()
                        com.nikhil.niktv.sports.player.NativePlaybackWorker.scope.launch { vlcDrained.complete(Unit) }
                        com.nikhil.niktv.sports.player.MpvNativePlaybackWorker.scope.launch { mpvDrained.complete(Unit) }
                        withTimeout(15_000) { vlcDrained.await(); mpvDrained.await() }
                    }
                }
                if (successful.containsAll(PlaybackEngine.entries.filter { it in listOf(PlaybackEngine.MEDIA3, PlaybackEngine.FLOW, PlaybackEngine.VLC, PlaybackEngine.MPV) })) break
            }
            assumeTrue("No Sports source could be resolved for playback", required.isNotEmpty())
            assertTrue("Not all compatible Sports players sustained 30 seconds: successes=$successful required=$required", successful.containsAll(required))
        } finally {
            File(context.externalCacheDir, "sports-live-stability.json").writeText(buildJsonObject {
                put("outcomes", JsonArray(outcomes)); put("requiredEngines", JsonArray(required.map { JsonPrimitive(it.name) }))
                put("passedEngines", JsonArray(successful.map { JsonPrimitive(it.name) }))
            }.toString())
            instrumentation.runOnMainSync { host?.finish() }
            settings.setPlaybackEngine(previousSettings.engine); settings.setPlayerControlsTimeoutSeconds(previousSettings.timeout)
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
