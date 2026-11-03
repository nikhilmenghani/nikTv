package com.nikhil.niktv.discovery

internal data class NativePosterGeometry(val width: Float, val posterHeight: Float, val textScale: Float) {
    val railHeight get() = posterHeight + 23f // contained focus inset + rail padding
}

/** Compact wide-screen posters; larger phone posters with the same artwork/caption design. */
internal fun nativePosterGeometry(width: Float, height: Float, fontScale: Float, phone: Boolean = false): NativePosterGeometry {
    val tile = if (phone) {
        val portraitWidth = phoneDiscoveryPosterWidth(width.toInt())
        // A short landscape phone still needs room for the header, navigation
        // and row heading. Do not apply this phone policy to tablets or TVs.
        val heightLimit = ((height - 224f).coerceAtLeast(132f) / 1.5f + 8f)
        minOf(portraitWidth, heightLimit)
    } else discoveryPosterWidth(width, height)
    val scale = (tile / 120f).coerceIn(.85f, 1.15f)
    return NativePosterGeometry(tile, maxOf((tile - 8f) * 1.5f,
        114f * scale * fontScale.coerceAtLeast(1f) + 36f), scale)
}
