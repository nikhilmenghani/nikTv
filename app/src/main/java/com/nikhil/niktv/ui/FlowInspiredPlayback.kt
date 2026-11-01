package com.nikhil.niktv.ui

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.nikhil.niktv.model.PlaybackEngine
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/** Standalone IPTV tuning: no bundled YouTube application or renderer dependencies. */
internal object FlowInspiredConfig {
    const val LOW_MEMORY_MAX_SAFE_MAIN_MIN_BUFFER_MS = 8_000
    const val LOW_MEMORY_MAX_SAFE_MAIN_BUFFER_MS = 18_000
    const val MAX_SAFE_MAIN_MIN_BUFFER_MS = 20_000
    const val MAX_SAFE_MAIN_BUFFER_MS = 45_000
    const val LOW_MEMORY_MAIN_TARGET_BUFFER_BYTES = 4 * 1024 * 1024
    const val MID_MEMORY_MAIN_TARGET_BUFFER_BYTES = 12 * 1024 * 1024
    const val MAIN_TARGET_BUFFER_BYTES = 32 * 1024 * 1024
    const val ABR_MIN_BUFFER_FOR_QUALITY_INCREASE_MS = 5_000
    const val ABR_MAX_BUFFER_FOR_QUALITY_DECREASE_MS = 32_500
    const val AUTO_BANDWIDTH_FRACTION = .7f
    const val ENABLE_DYNAMIC_SCHEDULING = false
    const val ALLOCATOR_BUFFER_SIZE = 64 * 1024
}

@UnstableApi
internal fun flowInspiredRenderers(context: Context) = DefaultRenderersFactory(context)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    .setEnableDecoderFallback(true)

@UnstableApi
internal fun flowInspiredBandwidthMeter(context: Context) = DefaultBandwidthMeter.Builder(context)
    .setInitialBitrateEstimate(5_000_000L)
    .setResetOnNetworkTypeChange(true)
    .build()

internal fun PlaybackEngine.isMedia3Player() = this == PlaybackEngine.MEDIA3 || this == PlaybackEngine.EXOPLAYER || this == PlaybackEngine.FLOW

internal data class FlowBufferPlan(val minimumMs: Int, val maximumMs: Int, val targetBytes: Int)

/** Flow's heap tiers, with NikTV's existing live startup/rebuffer thresholds and no live rewind. */
internal fun flowBufferPlan(memoryClassMb: Int, lowRam: Boolean, live: Boolean): FlowBufferPlan {
    val small = lowRam || memoryClassMb <= 256
    val constrained = small || memoryClassMb <= 384
    val minimum = if (live) 15_000 else 20_000
    val maximum = if (live) 30_000 else 45_000
    return FlowBufferPlan(
        minOf(minimum, if (small) FlowInspiredConfig.LOW_MEMORY_MAX_SAFE_MAIN_MIN_BUFFER_MS else FlowInspiredConfig.MAX_SAFE_MAIN_MIN_BUFFER_MS),
        minOf(maximum, if (small) FlowInspiredConfig.LOW_MEMORY_MAX_SAFE_MAIN_BUFFER_MS else FlowInspiredConfig.MAX_SAFE_MAIN_BUFFER_MS),
        when {
            small -> FlowInspiredConfig.LOW_MEMORY_MAIN_TARGET_BUFFER_BYTES
            constrained -> FlowInspiredConfig.MID_MEMORY_MAIN_TARGET_BUFFER_BYTES
            else -> FlowInspiredConfig.MAIN_TARGET_BUFFER_BYTES
        }
    )
}

@UnstableApi
@androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
internal fun configureFlowInspiredPlayer(context: Context, builder: ExoPlayer.Builder, live: Boolean) {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val plan = flowBufferPlan(manager?.memoryClass ?: 256, manager?.isLowRamDevice == true, live)
    val selector = DefaultTrackSelector(context, AdaptiveTrackSelection.Factory(
        FlowInspiredConfig.ABR_MIN_BUFFER_FOR_QUALITY_INCREASE_MS,
        FlowInspiredConfig.ABR_MAX_BUFFER_FOR_QUALITY_DECREASE_MS,
        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        FlowInspiredConfig.AUTO_BANDWIDTH_FRACTION
    ))
    // Retain normal IPTV language/subtitle selection; do not impose YouTube codec preferences.
    builder.setTrackSelector(selector)
        .setBandwidthMeter(flowInspiredBandwidthMeter(context))
        .experimentalSetDynamicSchedulingEnabled(FlowInspiredConfig.ENABLE_DYNAMIC_SCHEDULING)
        .setLoadControl(DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, FlowInspiredConfig.ALLOCATOR_BUFFER_SIZE))
            .setBufferDurationsMs(plan.minimumMs, plan.maximumMs, 750, 3_000)
            .setBackBuffer(0, false)
            .setTargetBufferBytes(plan.targetBytes)
            // IPTV can have far higher bitrates than YouTube. Let the byte budget win.
            .setPrioritizeTimeOverSizeThresholds(false)
            .build())
}
