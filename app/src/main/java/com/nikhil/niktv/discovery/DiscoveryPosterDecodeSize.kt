package com.nikhil.niktv.discovery

import kotlin.math.ceil

internal data class DiscoveryPosterDecodeSize(val width: Int, val height: Int)

/** Decode near the displayed pixel footprint rather than one fixed size on every tablet. */
internal fun discoveryPosterDecodeSize(widthPx: Int, aspect: Float, focusScale: Float = DiscoveryPosterFocusScale): DiscoveryPosterDecodeSize {
    val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: 2f / 3f
    val scale = focusScale.takeIf { it.isFinite() }?.coerceIn(1f, DiscoveryPosterFocusScale) ?: 1f
    val width = ceil(widthPx.coerceAtLeast(1) * scale.toDouble()).toInt()
        .coerceIn(1, if (ratio < 1f) 780 else 1280)
    return DiscoveryPosterDecodeSize(width, ceil(width / ratio.toDouble()).toInt().coerceIn(1, 1280))
}
