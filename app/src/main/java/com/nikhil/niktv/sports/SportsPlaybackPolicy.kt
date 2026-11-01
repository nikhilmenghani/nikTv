package com.nikhil.niktv.sports

import android.content.Context
import android.app.ActivityManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.ui.DecoderSessionFailures
import com.nikhil.niktv.ui.decoderCandidateOrder
import com.nikhil.niktv.ui.decoderFailureName

/** Sports never reads or writes IPTV's historical device/series decoder preferences. */
internal object SportsDecoderPolicy {
    private val sessions = DecoderSessionFailures()
    fun candidates(session: String, names: List<String>): List<String> =
        decoderCandidateOrder(names, sessions.failed(session), preferSoftware = false)
    fun record(error: Throwable, session: String): String? {
        val name = decoderFailureName(generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString("\n")) ?: return null
        sessions.record(session, name)
        return name
    }
    fun canRecover(count: Int) = count < 3

    @androidx.annotation.OptIn(UnstableApi::class)
    fun selector(session: String) = MediaCodecSelector { mime, secure, tunneling ->
        val available = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        val byName = available.associateBy { it.name }
        candidates(session, available.map { it.name }).mapNotNull(byName::get)
    }
}

/** Own construction policy; tuning Sports cannot silently retune IPTV's live/VOD buffers. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object SportsPlaybackPolicy {
    const val vlcNetworkCacheMillis = 1500
    fun vlcOptions(hls: Boolean): List<String> = buildList {
        add("--network-caching=$vlcNetworkCacheMillis")
        if (hls) addAll(listOf("--adaptive-lowlatency=0", "--adaptive-livedelay=30000",
            "--adaptive-maxbuffer=30000", "--no-ts-trust-pcr"))
    }

    fun mpvOptions(): Map<String, String> = linkedMapOf(
        "config" to "no", "load-scripts" to "no", "osc" to "no",
        "input-default-bindings" to "no", "input-vo-keyboard" to "no", "vo" to "null",
        "gpu-context" to "android", "gpu-api" to "opengl", "hwdec" to "mediacodec-copy",
        "ao" to "aaudio,audiotrack", "video-sync" to "audio", "idle" to "yes",
        "keep-open" to "no", "pause" to "yes", "cache" to "yes", "cache-pause-initial" to "no",
        "cache-pause-wait" to "1", "demuxer-max-bytes" to "33554432", "demuxer-max-back-bytes" to "0",
        "network-timeout" to "15", "demuxer-readahead-secs" to "15", "osd-level" to "0", "tls-verify" to "yes")

    fun renderers(context: Context, engine: PlaybackEngine, session: String): DefaultRenderersFactory =
        (if (engine == PlaybackEngine.FLOW) com.nikhil.niktv.ui.flowInspiredRenderers(context)
        else DefaultRenderersFactory(context)).apply {
            setEnableDecoderFallback(true)
            setMediaCodecSelector(SportsDecoderPolicy.selector(session))
        }

    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    fun configure(context: Context, builder: ExoPlayer.Builder, engine: PlaybackEngine) {
        if (engine == PlaybackEngine.FLOW) {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory = manager?.memoryClass ?: 256
            val small = manager?.isLowRamDevice == true || memory <= 256
            val bytes = when { small -> 4; memory <= 384 -> 12; else -> 32 } * 1024 * 1024
            builder.setTrackSelector(DefaultTrackSelector(context, AdaptiveTrackSelection.Factory(
                5_000, 32_500, AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, .7f)))
                .setBandwidthMeter(com.nikhil.niktv.ui.flowInspiredBandwidthMeter(context))
                .experimentalSetDynamicSchedulingEnabled(false)
                .setLoadControl(DefaultLoadControl.Builder().setAllocator(DefaultAllocator(true, 64 * 1024))
                    .setBufferDurationsMs(if (small) 8_000 else 15_000, if (small) 18_000 else 30_000, 750, 3_000)
                    .setBackBuffer(0, false).setTargetBufferBytes(bytes).setPrioritizeTimeOverSizeThresholds(false).build())
        } else builder.setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 750, 3_000)
            .setTargetBufferBytes(16 * 1024 * 1024).build())
    }
}
