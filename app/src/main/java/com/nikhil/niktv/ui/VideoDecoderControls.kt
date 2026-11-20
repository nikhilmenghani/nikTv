package com.nikhil.niktv.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

internal enum class VideoDecoderMode(val label: String, val explanation: String) {
    AUTO("Auto", "Recommended. Use compatible decoder recovery."),
    HARDWARE("Hardware", "Prefer hardware; allow software fallback if it fails."),
    SOFTWARE("Software", "Use software decoding. High-resolution video may stutter.")
}

/** Decoder capabilities belong to this device; this setting must never sync to another one. */
internal object VideoDecoderPreferences {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("player_video_decoder", Context.MODE_PRIVATE)
    fun read(context: Context) = runCatching {
        VideoDecoderMode.valueOf(prefs(context).getString("mode", VideoDecoderMode.AUTO.name)!!)
    }.getOrDefault(VideoDecoderMode.AUTO)
    fun save(context: Context, mode: VideoDecoderMode) { prefs(context).edit().putString("mode", mode.name).apply() }
}

internal fun videoDecoderModeOrder(mode: VideoDecoderMode, names: List<String>, hardware: Set<String>, software: Set<String>): List<String> =
    when (mode) {
        VideoDecoderMode.AUTO -> names
        VideoDecoderMode.HARDWARE -> names.sortedBy { if (it in hardware) 0 else 1 }
        VideoDecoderMode.SOFTWARE -> names.filter { it in software }
    }

internal data class ActiveVideoDecoder(val name: String, val hardware: Boolean?) {
    val label get() = when (hardware) { true -> "Hardware"; false -> "Software"; null -> "Decoder" }
    val badge get() = when (hardware) { true -> "HW"; false -> "SW"; null -> "?" }
    val description get() = "$label · $name"
}

/** One instance per local player, so late release/init events cannot overwrite its replacement. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoDecoderTelemetry {
    var active by mutableStateOf<ActiveVideoDecoder?>(null)
        private set
    @Volatile private var kinds = emptyMap<String, Boolean?>()

    fun selector(base: MediaCodecSelector, mode: VideoDecoderMode) = MediaCodecSelector { mime, secure, tunneling ->
        val candidates = base.getDecoderInfos(mime, secure, tunneling)
        if (mime.startsWith("video/")) {
            kinds = candidates.associate { it.name to when {
                it.hardwareAccelerated -> true
                it.softwareOnly -> false
                else -> null
            } }
            val byName = candidates.associateBy { it.name }
            videoDecoderModeOrder(mode, candidates.map { it.name },
                candidates.filter { it.hardwareAccelerated }.mapTo(mutableSetOf()) { it.name },
                candidates.filter { it.softwareOnly }.mapTo(mutableSetOf()) { it.name }).mapNotNull(byName::get)
        } else candidates // Decoder selection never filters audio or subtitles.
    }

    fun attach(player: ExoPlayer) {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                active = ActiveVideoDecoder(decoderName, kinds[decoderName])
                android.util.Log.w("NikTvVideoDecoder", "Active video decoder: ${active?.description}")
            }
            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (active?.name == decoderName) active = null
            }
        })
    }
}
