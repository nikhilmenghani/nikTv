package com.nikhil.niktv.sports

import android.content.Intent
import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.ui.withStreamDrm

@androidx.annotation.OptIn(UnstableApi::class)
class SportsStreamAuditDeviceTest {
    @Test fun auditEveryCurrentSportsSource() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Exhaustive provider audit requires explicit authorization",
            InstrumentationRegistry.getArguments().getString("sportsStreamAudit") == "true")
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        val output = File(context.filesDir, "sports-stream-audit.json")
        val started = Instant.now().toString()
        val repository = SportsRepository(country = { sportsDeviceCountry(context) })
        val resolver = SportsStreamResolver(repository.transport, SportsWebResolver(context)::resolve)
        val activity = instrumentation.startActivitySync(Intent(context, SportsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsActivity
        val results = mutableListOf<JsonObject>()
        val metadataErrors = mutableListOf<JsonObject>()
        val entries = mutableListOf<Triple<SportsEvent, Int, SportsStream>>()
        val probes = linkedMapOf<String, JsonObject>()
        var events = emptyList<SportsEvent>()
        fun save(complete: Boolean = false) {
            output.writeText(buildJsonObject {
                put("startedAt", started); put("updatedAt", Instant.now().toString()); put("complete", complete)
                put("country", sportsDeviceCountry(context)); put("eventCount", events.size); put("sourceEntries", entries.size)
                put("testedEntries", results.size); put("uniqueProbes", probes.size)
                put("passedEntries", results.count { it.text("result") == "PASS" })
                put("failedEntries", results.count { it.text("result") != "PASS" })
                put("metadataErrors", JsonArray(metadataErrors)); put("results", JsonArray(results))
            }.toString())
        }
        try {
            events = repository.events(refresh = true)
            assertTrue("The live catalogue must load", events.isNotEmpty())
            events.forEachIndexed { eventIndex, event ->
                try { repository.streams(event.slug).forEachIndexed { index, stream -> entries += Triple(event, index, stream) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { metadataErrors += buildJsonObject { put("event", event.title); put("slug", event.slug); put("result", "METADATA_UNAVAILABLE") } }
                android.util.Log.i("NikTvSportsAudit", "Metadata ${eventIndex + 1}/${events.size}; source entries ${entries.size}")
                save()
            }
            for ((entryIndex, entry) in entries.withIndex()) {
                val (event, index, stream) = entry
                val signature = listOf(stream.link, stream.api, stream.type.toString(), stream.drmType, stream.drmLicense,
                    stream.headers.toSortedMap().entries.joinToString { "${it.key}:${it.value}" }).joinToString("\n")
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                val duplicate = probes[fingerprint]
                val outcome = duplicate ?: run {
                    val began = SystemClock.elapsedRealtime()
                    var player: ExoPlayer? = null
                    val firstFrame = AtomicBoolean()
                    val frames = AtomicInteger()
                    val failure = AtomicReference<PlaybackException?>()
                    var renderedAt = 0L
                    var advanced = 0L
                    var reason = "RESOLUTION_FAILED"
                    var httpCode: Int? = null
                    try {
                        val resolved = resolver.resolve(stream)
                        reason = "STARTUP_TIMEOUT"
                        instrumentation.runOnMainSync {
                            player = diagnosticExoPlayer(activity, resolved)
                            player!!.setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
                            player!!.addListener(object : Player.Listener {
                                override fun onRenderedFirstFrame() { firstFrame.set(true) }
                                override fun onPlayerError(error: PlaybackException) { failure.set(error) }
                            })
                            activity.setContentView(PlayerView(activity).apply {
                                layoutParams = ViewGroup.LayoutParams(-1, -1); this.player = player; keepScreenOn = true
                            })
                        }
                        val playbackStart = SystemClock.elapsedRealtime()
                        var firstPosition: Long? = null
                        while (SystemClock.elapsedRealtime() - playbackStart < 17_000) {
                            if (failure.get() != null) break
                            if (firstFrame.get()) {
                                if (renderedAt == 0L) renderedAt = SystemClock.elapsedRealtime() - playbackStart
                                instrumentation.runOnMainSync {
                                    val position = player!!.currentPosition
                                    if (firstPosition == null) firstPosition = position
                                    advanced = position - firstPosition!!
                                }
                                if (advanced >= 3000 && frames.get() >= 15) { reason = "PASS"; break }
                            } else if (SystemClock.elapsedRealtime() - playbackStart >= 12_000) break
                            SystemClock.sleep(150)
                        }
                        failure.get()?.let { error ->
                            reason = error.errorCodeName
                            var cause: Throwable? = error
                            while (cause != null) {
                                if (cause is HttpDataSource.InvalidResponseCodeException) { httpCode = cause.responseCode; break }
                                cause = cause.cause
                            }
                        }
                        if (firstFrame.get() && reason == "STARTUP_TIMEOUT") reason = "PLAYBACK_STALLED"
                    } catch (_: TimeoutCancellationException) { reason = "RESOLUTION_TIMEOUT" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { reason = "RESOLUTION_OR_INITIALIZATION_FAILED" }
                    finally { instrumentation.runOnMainSync { player?.release() } }
                    buildJsonObject {
                        put("result", if (reason == "PASS") "PASS" else "FAIL"); put("reason", reason)
                        put("elapsedMs", SystemClock.elapsedRealtime() - began); put("firstFrame", firstFrame.get())
                        put("frames", frames.get()); put("positionAdvancedMs", advanced)
                        if (renderedAt != 0L) put("firstFrameMs", renderedAt)
                        httpCode?.let { put("httpCode", it) }
                    }.also { probes[fingerprint] = it }
                }
                results += buildJsonObject {
                    put("event", event.title); put("slug", event.slug); put("sourceIndex", index); put("source", stream.title)
                    put("type", stream.type); put("fingerprint", fingerprint); put("reusedIdenticalProbe", duplicate != null)
                    outcome.forEach { (key, value) -> put(key, value) }
                }
                save()
                android.util.Log.i("NikTvSportsAudit", "Checked ${entryIndex + 1}/${entries.size}; unique ${probes.size}; ${outcome.text("result")}; ${outcome.text("reason")}")
            }
            save(complete = true)
            assertEquals("Every current source entry must have an outcome", entries.size, results.size)
            assertTrue("The catalogue must contain sources", entries.isNotEmpty())
        } finally { save(complete = results.size == entries.size && entries.isNotEmpty()); instrumentation.runOnMainSync { activity.finish() } }
    }

    /** Test-only diagnostic engine. Production Sports continues to use NikTV's shared player UI. */
    private fun diagnosticExoPlayer(context: Context, stream: SportsStream): ExoPlayer {
        val playing = stream.asPlayingMedia("Sports audit sample")
        val headers = playing.streamRequestHeaders
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: SPORTS_USER_AGENT)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .withStreamDrm(playing.streamDrm)
        val session = java.util.UUID.randomUUID().toString()
        val builder = ExoPlayer.Builder(context, SportsPlaybackPolicy.renderers(context, PlaybackEngine.MEDIA3, session))
            .setMediaSourceFactory(sources)
        SportsPlaybackPolicy.configure(context, builder, PlaybackEngine.MEDIA3)
        val player = builder.build()
        try {
            player.volume = 0f // Silent diagnostic: does not change the user's device volume.
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            player.setHandleAudioBecomingNoisy(true)
            val item = MediaItem.Builder().setUri(playing.url).apply {
                when (playing.playbackFormat) {
                    "HLS" -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    "DASH" -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }.withStreamDrm(playing.streamDrm, headers).build()
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
            return player
        } catch (failure: Exception) {
            player.release()
            throw failure
        }
    }
}
