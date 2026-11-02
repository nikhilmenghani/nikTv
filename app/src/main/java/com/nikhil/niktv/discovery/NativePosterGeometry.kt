package com.nikhil.niktv.discovery

internal data class NativePosterGeometry(val width: Float, val posterHeight: Float, val textScale: Float) {
    val railHeight get() = posterHeight + 23f // contained focus inset + rail padding
}

/** Same responsive width/type scale as Home; make room for accessibility fonts. */
internal fun nativePosterGeometry(width: Float, height: Float, fontScale: Float): NativePosterGeometry {
    val tile = discoveryPosterWidth(width, height)
    val scale = (tile / 120f).coerceIn(.85f, 1.15f)
    return NativePosterGeometry(tile, maxOf((tile - 8f) * 1.5f,
        114f * scale * fontScale.coerceAtLeast(1f) + 36f), scale)
}
