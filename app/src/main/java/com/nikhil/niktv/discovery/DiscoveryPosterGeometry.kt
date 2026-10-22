package com.nikhil.niktv.discovery

internal const val DiscoveryPosterFocusScale = 1.05f

/** Half of the scaled poster's growth plus room for its outline/shadow. */
internal fun discoveryPosterFocusInset(width: Float, aspect: Float = 2f / 3f): Float =
    width.coerceAtLeast(0f) / aspect * (DiscoveryPosterFocusScale - 1f) / 2f + 2f

/** Shared ordinary-poster size for discovery rails and full collections. */
internal fun discoveryPosterWidth(width: Float, height: Float): Float = minOf(
    ((width - 64f) / 7.4f - 12f).coerceIn(92f, 132f),
    ((height - 224f) / 3f).coerceIn(88f, 132f)
)

internal fun discoveryCollectionColumns(available: Float, tile: Float): Int =
    ((available - 24f + 12f) / (tile + 12f)).toInt().coerceAtLeast(1)

internal fun discoveryGridTarget(index: Int, delta: Int, columns: Int, count: Int): Int? {
    if (index !in 0 until count) return null
    val target = index + delta
    if (delta == -1 && index % columns == 0) return null
    if (delta == 1 && (index % columns == columns - 1 || target >= count)) return null
    if (delta == columns && index / columns == (count - 1) / columns) return null
    return target.coerceAtMost(count - 1).takeIf { it >= 0 }
}
