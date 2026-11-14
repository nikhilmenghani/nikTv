package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Fade in viewport coordinates so cropping cannot remove either edge. */
@Composable
internal fun DiscoveryHeroShade(modifier: Modifier = Modifier, softEdges: Boolean = false,
    bottomFadeStart: Float = .60f) {
    val softVertical = remember(bottomFadeStart) {
        val start = bottomFadeStart.coerceIn(.25f, .999f)
        // Fade through the upper episode rows rather than spreading almost
        // transparent shading all the way to the artwork's bottom edge.
        val end = start + (1f - start) * .60f
        Brush.verticalGradient(*featherStops(.25f, end - start, .55f, 1f,
            gentle = true, endAt = end))
    }
    Box(modifier.background(if (softEdges) softVertical else discoveryHeroShadeBrush())
        .background(if (softEdges) softArtworkSideBrush else heroSideBrush))
}

// Ease both ends of the feather so it meets the image and background without
// a visible seam. Squaring the curve leaves most of the artwork unobscured.
private fun featherAlpha(t: Float): Float {
    val eased = t * t * t * (t * (t * 6f - 15f) + 10f)
    return eased * eased
}

private fun featherStops(startWidth: Float, endWidth: Float, startOpacity: Float = 1f,
    endOpacity: Float = 1f, gentle: Boolean = false, endAt: Float = 1f) = buildList {
    fun alpha(t: Float) = if (gentle) t * t * (3f - 2f * t) else featherAlpha(t)
    for (step in 0..32) {
        val t = step / 32f
        val remaining = 1f - t
        add(t * startWidth to Color(0xFF090B10).copy(alpha = alpha(remaining) * startOpacity))
    }
    for (step in 0..32) {
        val t = step / 32f
        add(endAt - endWidth + t * endWidth to Color(0xFF090B10).copy(alpha = alpha(t) * endOpacity))
    }
}.toTypedArray()

private val heroEdgeStops = featherStops(.10f, .18f)
private val heroSideBrush = Brush.horizontalGradient(*featherStops(.015f, .015f))
// A full-screen series backdrop needs a broad, low-opacity feather rather
// than the narrow edge treatment of a scrolling Home hero.
private val softArtworkSideBrush = Brush.horizontalGradient(*featherStops(.12f, .12f, .25f, .40f, gentle = true))

internal fun discoveryHeroShadeBrush(startY: Float = 0f, endY: Float = Float.POSITIVE_INFINITY) =
    Brush.verticalGradient(*heroEdgeStops, startY = startY, endY = endY)
