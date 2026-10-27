package com.nikhil.niktv.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.ParentalControls
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.StalkerPortalClient
import com.nikhil.niktv.data.adultContentLabel
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalSession
import com.nikhil.niktv.model.PortalType
import com.nikhil.niktv.model.SeriesStartSeason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit opt-in private-provider smoke test, never part of ordinary fixture validation.
 * Run only with -e iptvProviderSmoke true against com.nikhil.niktv.debug on an authorized device.
 * Reads existing credentials in-process; no VM, profile activation, history, guide or catalogue-cache writes.
 * Provider authentication/traffic protection can update operational endpoint/cooldown preferences.
 * Real production decoder failures can also update their existing fallback preferences.
 * Never log source objects, provider responses, exception messages, credentials or URLs.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class IptvProviderStabilityDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val engines = listOf(PlaybackEngine.MEDIA3, PlaybackEngine.FLOW, PlaybackEngine.VLC, PlaybackEngine.MPV)
    private val types = listOf(CatalogType.MOVIES, CatalogType.SERIES, CatalogType.LIVE_TV)
    private val outcomes = JSONArray()

    private data class Sample(val item: MediaItem, val type: CatalogType, val series: MediaItem? = null)
    private class Blocked(val code: String) : Exception()

    @Test fun existingWioSamplesSustainPlaybackAcrossAllFourEngines() {
        assumeTrue("Private provider test requires explicit authorization flag",
            InstrumentationRegistry.getArguments().getString("iptvProviderSmoke") == "true")
        check(context.packageName == "com.nikhil.niktv.debug") { "Normal Dev target required" }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        var client: StalkerPortalClient? = null
        var abort: String? = null
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != 0) throw Blocked("unable_to_mute")
            val profile = runBlocking {
                ProfileStore(context).profiles.first().singleOrNull {
                    it.name.equals("Wio", ignoreCase = true) && it.portalType == PortalType.STALKER
                }
            } ?: throw Blocked("unique_wio_stalker_profile_unavailable")
            val provider = StalkerPortalClient(context).also { client = it }
            val session = providerCall { provider.authenticate(profile) }
            for (type in types) {
                if (abort != null) {
                    engines.forEach { record(type, it, "BLOCKED", abort!!) }
                    continue
                }
                val sample = try { chooseSample(provider, session, profile, type) }
                catch (blocked: Blocked) {
                    engines.forEach { record(type, it, "BLOCKED", blocked.code) }
                    // A missing nonadult sample is local to this type; any provider error stops requests.
                    if (blocked.code != "no_safe_sample_within_three_catalogue_attempts") abort = blocked.code
                    continue
                }
                for (engine in engines) {
                    if (abort != null) { record(type, engine, "BLOCKED", abort!!); continue }
                    if (engine == PlaybackEngine.MPV && !mpvSupportedSdk(android.os.Build.VERSION.SDK_INT)) {
                        record(type, engine, "BLOCKED", "mpv_sdk_unsupported")
                        continue
                    }
                    // Resolve just before each sequential playback; never hold/open concurrent streams.
                    val resolved = try { providerCall { provider.playableUrl(session, sample.item, type) } }
                    catch (blocked: Blocked) {
                        record(type, engine, "BLOCKED", blocked.code)
                        abort = blocked.code
                        continue
                    }
                    exercise(sample, engine, resolved)
                }
            }
        } catch (blocked: Blocked) {
            abort = blocked.code
        } catch (failure: Exception) {
            abort = safeFailureCode(failure)
        } finally {
            client?.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
            // Fill unattempted cases explicitly; unavailable providers/samples never count as passes.
            for (type in types) for (engine in engines) {
                val exists = (0 until outcomes.length()).any {
                    val row = outcomes.getJSONObject(it)
                    row.getString("catalog") == type.name && row.getString("engine") == engine.name
                }
                if (!exists) record(type, engine, "BLOCKED", abort ?: "not_attempted")
            }
            saveOutcomes()
        }
        val passed = (0 until outcomes.length()).count { outcomes.getJSONObject(it).getString("status") == "PASS" }
        assertTrue("Provider matrix incomplete: $passed/12 passed; inspect sanitized external-cache report", passed == 12)
    }

    private fun chooseSample(client: StalkerPortalClient, session: PortalSession, profile: PortalProfile,
                             type: CatalogType): Sample {
        val categories = providerCall { client.categories(session, type) }
            .filter { it.id != "*" && safeCategory(profile, it) }
            .sortedWith(compareByDescending<Category> { it.title.contains("english", ignoreCase = true) }
                .thenByDescending { it.title.contains("24", ignoreCase = true) && type == CatalogType.LIVE_TV })
        // At most three first-page catalogue requests per type; no full catalogue, search or paging sweep.
        for (category in categories.take(3)) {
            val page = providerCall { client.catalogPage(session, category, 1, includeEpg = false) }
            val item = page.items.firstOrNull { safeItem(profile, type, it) && !it.command.isNullOrBlank() } ?: continue
            if (type == CatalogType.MOVIES) {
                if (!providerCall { client.movieHasEpisodes(session, item) }) return Sample(item, type)
            } else if (type == CatalogType.SERIES) {
                val episodes = providerCall { client.episodeSeason(session, item, SeriesStartSeason.FIRST) }
                val episode = episodes.episodes.firstOrNull {
                    safeItem(profile, type, it) && !it.command.isNullOrBlank()
                } ?: continue
                return Sample(episode, type, item)
            } else return Sample(item, type)
        }
        throw Blocked("no_safe_sample_within_three_catalogue_attempts")
    }

    private fun safeCategory(profile: PortalProfile, category: Category) =
        !category.isAdult && !adultContentLabel(category.title) &&
            !ParentalControls.adultCategory(context, profile.cacheKey(), category.type, category.id)

    private fun safeItem(profile: PortalProfile, type: CatalogType, item: MediaItem) =
        !item.isAdult && !adultContentLabel(item.title) &&
            !ParentalControls.adultCategory(context, profile.cacheKey(), type, item.portalCategoryId)

    private fun <T> providerCall(block: suspend () -> T): T = try {
        runBlocking { withTimeout(45_000) { block() } }
    } catch (failure: Exception) { throw Blocked(safeFailureCode(failure)) }

    private fun exercise(sample: Sample, engine: PlaybackEngine, url: String) {
        val firstFrame = AtomicLong(-1)
        val frameCount = AtomicInteger()
        val retryRequests = AtomicInteger()
        val unexpectedNavigation = AtomicInteger()
        var activity: PlaybackFixtureHostActivity? = null
        var status = "FAIL"
        var reason = "unclassified"
        var nativeStart: Long? = null
        var nativeEnd: Long? = null
        var advancingFrameIntervals = 0
        val pictures = linkedSetOf<Long>()
        val started = SystemClock.elapsedRealtime()
        try {
            val host = instrumentation.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlaybackFixtureHostActivity
            activity = host
            val media = PlayingMedia(
                sample.item.copy(title = "IPTV ${sample.type.title} sample", logo = null, description = null,
                    liveProgramme = null, liveSchedule = emptyList()), url, sample.type,
                series = sample.series?.copy(title = "IPTV series sample", logo = null, description = null),
                progressKey = "provider-smoke:${sample.type.name}:${engine.name}",
                profileKey = "provider-smoke", directFullscreen = true)
            instrumentation.runOnMainSync {
                host.setContent {
                    MaterialTheme(colorScheme = NikColors) {
                        PlayerScreen(media, onBack = { unexpectedNavigation.incrementAndGet() },
                            onRetry = { retryRequests.incrementAndGet() },
                            onRetryAlternateDecoder = { retryRequests.incrementAndGet() },
                            onPlaybackAuthorizationFailure = { retryRequests.incrementAndGet() },
                            onPlayPrevious = { unexpectedNavigation.incrementAndGet() },
                            onPlayNext = { unexpectedNavigation.incrementAndGet() },
                            onProgress = { _, _, _ -> }, playbackEngine = engine,
                            controlsTimeoutSeconds = 0, startFullscreen = true,
                            showLiveRecordingAndGuide = false,
                            onVideoFrameRendered = { firstFrame.compareAndSet(-1, SystemClock.elapsedRealtime() - started) },
                            onVideoFramesRendered = { frameCount.set(it) })
                    }
                }
            }
            val deadline = started + 25_000
            while (firstFrame.get() < 0 && SystemClock.elapsedRealtime() < deadline) {
                checkHealthy(retryRequests, unexpectedNavigation)
                Thread.sleep(100)
            }
            if (firstFrame.get() !in 0L..25_000L) throw Blocked("first_frame_timeout")
            // Reveal controls only when hidden. Never click Play/Pause while measuring advancement.
            if (control("Pause") == null && control("Play") == null)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            nativeStart = shownPosition()
            var previousFrames = frameCount.get()
            repeat(15) {
                Thread.sleep(1_000)
                checkHealthy(retryRequests, unexpectedNavigation)
                val frames = frameCount.get()
                if (frames > previousFrames) advancingFrameIntervals++
                previousFrames = frames
                nativeEnd = shownPosition() ?: nativeEnd
                if (engine == PlaybackEngine.VLC || engine == PlaybackEngine.MPV) fingerprint()?.let(pictures::add)
            }
            val advances = if (engine == PlaybackEngine.MEDIA3 || engine == PlaybackEngine.FLOW)
                advancingFrameIntervals >= 8
            else (nativeStart != null && nativeEnd != null && nativeEnd!! - nativeStart!! >= 10) || pictures.size >= 4
            if (!advances) throw Blocked("sustained_progress_not_observed")
            status = "PASS"
            reason = "first_frame_and_fifteen_second_advancement"
        } catch (blocked: Blocked) { reason = blocked.code }
        catch (failure: Exception) { reason = safeFailureCode(failure) }
        finally {
            // Dispose composition first, then finish. The next engine cannot overlap this session.
            activity?.let { host -> instrumentation.runOnMainSync { host.setContent {}; host.finish() } }
            instrumentation.waitForIdleSync()
            var cleanupFailed = false
            try {
                // Native teardown is queued off-main. Drain both ordered workers before another stream.
                val vlcDrained = CompletableDeferred<Unit>()
                val mpvDrained = CompletableDeferred<Unit>()
                NativePlaybackWorker.scope.launch { vlcDrained.complete(Unit) }
                MpvNativePlaybackWorker.scope.launch { mpvDrained.complete(Unit) }
                // Await independently of the native workers so a hung driver cannot hold the timeout.
                runBlocking { withTimeout(15_000) { vlcDrained.await(); mpvDrained.await() } }
            } catch (_: Exception) {
                cleanupFailed = true
                status = "FAIL"
                reason = "native_cleanup_timeout_stop"
            }
            record(sample.type, engine, status, reason, firstFrame.get(), nativeStart, nativeEnd,
                advancingFrameIntervals, pictures.size, retryRequests.get())
            if (cleanupFailed) throw Blocked("native_cleanup_timeout_stop")
        }
    }

    private fun checkHealthy(retries: AtomicInteger, navigation: AtomicInteger) {
        if (retries.get() > 0) throw Blocked("player_requested_recovery")
        if (navigation.get() > 0) throw Blocked("unexpected_completion_or_navigation")
        if (control("This title can’t be played right now") != null || control("Retry with fresh link") != null)
            throw Blocked("player_error_overlay")
    }

    private fun control(label: String, prefix: Boolean = false): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val description = node.contentDescription?.toString().orEmpty()
            if ((if (prefix) description.startsWith(label) else description == label) ||
                (!prefix && node.text?.toString() == label)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun shownPosition(): Long? = control("Current playback time ", true)?.contentDescription?.toString()
        ?.removePrefix("Current playback time ")?.split(':')?.mapNotNull { it.toLongOrNull() }
        ?.takeIf { it.isNotEmpty() }?.fold(0L) { total, part -> total * 60 + part }

    /** In-memory centre sampling only; no screenshot or media is saved. Excludes transport/title edges. */
    private fun fingerprint(): Long? {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return null
        return try {
            var hash = 1125899906842597L
            for (y in 0 until 20) for (x in 0 until 32) {
                // Two central picture patches avoid the centre loading spinner as well as controls.
                val horizontal = if (x < 16) .22f + x * .012f else .60f + (x - 16) * .012f
                val px = (bitmap.width * horizontal).toInt().coerceIn(0, bitmap.width - 1)
                val py = (bitmap.height * (.30f + y * .012f)).toInt().coerceIn(0, bitmap.height - 1)
                hash = hash * 31 + bitmap.getPixel(px, py)
            }
            hash
        } finally { bitmap.recycle() }
    }

    private fun safeFailureCode(failure: Exception): String = when {
        generateSequence(failure as Throwable?) { it.cause }.any {
            it.javaClass.simpleName in setOf("PortalHttpException", "PortalRateLimitCooldownException")
        } -> "provider_http_or_cooldown_stop"
        failure is kotlinx.coroutines.TimeoutCancellationException -> "provider_timeout_stop"
        else -> "failure_${failure.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "").take(64)}"
    }

    private fun record(type: CatalogType, engine: PlaybackEngine, status: String, reason: String,
                       firstFrameMs: Long = -1, start: Long? = null, end: Long? = null,
                       intervals: Int = 0, pictures: Int = 0, retries: Int = 0) {
        outcomes.put(JSONObject().put("catalog", type.name).put("engine", engine.name)
            .put("status", status).put("reason", reason).put("firstFrameMs", firstFrameMs)
            .put("nativeStartSeconds", start ?: JSONObject.NULL).put("nativeEndSeconds", end ?: JSONObject.NULL)
            .put("advancingFrameIntervals", intervals).put("distinctCentrePictures", pictures)
            .put("recoveryRequests", retries))
        saveOutcomes()
    }

    private fun saveOutcomes() {
        val directory = context.externalCacheDir ?: context.cacheDir
        File(directory, "iptv-provider-stability.json").writeText(JSONObject()
            .put("updatedAtMillis", System.currentTimeMillis()).put("outcomes", outcomes)
            .put("limits", "Opt-in real-provider smoke: no history, no audio, no URLs; 15s per engine, not long-run proof")
            .toString(2))
    }
}
