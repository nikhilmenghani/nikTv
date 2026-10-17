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
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.factory.PlayerFactory

internal fun PlaybackEngine.isMedia3Player() = this == PlaybackEngine.MEDIA3 || this == PlaybackEngine.EXOPLAYER || this == PlaybackEngine.FLOW

internal data class FlowBufferPlan(val minimumMs: Int, val maximumMs: Int, val targetBytes: Int)

/** Flow's heap tiers, with NikTV's existing live startup/rebuffer thresholds and no live rewind. */
internal fun flowBufferPlan(memoryClassMb: Int, lowRam: Boolean, live: Boolean): FlowBufferPlan {
    val small = lowRam || memoryClassMb <= 256
    val constrained = small || memoryClassMb <= 384
    val minimum = if (live) 15_000 else 20_000
    val maximum = if (live) 30_000 else 45_000
    return FlowBufferPlan(
        minOf(minimum, if (small) PlayerConfig.LOW_MEMORY_MAX_SAFE_MAIN_MIN_BUFFER_MS else PlayerConfig.MAX_SAFE_MAIN_MIN_BUFFER_MS),
        minOf(maximum, if (small) PlayerConfig.LOW_MEMORY_MAX_SAFE_MAIN_BUFFER_MS else PlayerConfig.MAX_SAFE_MAIN_BUFFER_MS),
        when {
            small -> PlayerConfig.LOW_MEMORY_MAIN_TARGET_BUFFER_BYTES
            constrained -> PlayerConfig.MID_MEMORY_MAIN_TARGET_BUFFER_BYTES
            else -> PlayerConfig.MAIN_TARGET_BUFFER_BYTES
        }
    )
}

@UnstableApi
@androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
internal fun configureFlowInspiredPlayer(context: Context, builder: ExoPlayer.Builder, live: Boolean) {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val plan = flowBufferPlan(manager?.memoryClass ?: 256, manager?.isLowRamDevice == true, live)
    val selector = DefaultTrackSelector(context, AdaptiveTrackSelection.Factory(
        PlayerConfig.ABR_MIN_BUFFER_FOR_QUALITY_INCREASE_MS,
        PlayerConfig.ABR_MAX_BUFFER_FOR_QUALITY_DECREASE_MS,
        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        PlayerConfig.AUTO_BANDWIDTH_FRACTION
    ))
    // Retain normal IPTV language/subtitle selection; do not impose YouTube codec preferences.
    builder.setTrackSelector(selector)
        .setBandwidthMeter(PlayerFactory().createBandwidthMeter(context))
        .experimentalSetDynamicSchedulingEnabled(PlayerConfig.ENABLE_DYNAMIC_SCHEDULING)
        .setLoadControl(DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, PlayerConfig.ALLOCATOR_BUFFER_SIZE))
            .setBufferDurationsMs(plan.minimumMs, plan.maximumMs, 750, 3_000)
            .setBackBuffer(0, false)
            .setTargetBufferBytes(plan.targetBytes)
            // IPTV can have far higher bitrates than YouTube. Let the byte budget win.
            .setPrioritizeTimeOverSizeThresholds(false)
            .build())
}
