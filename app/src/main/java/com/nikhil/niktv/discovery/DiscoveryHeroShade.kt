package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Fade in viewport coordinates so cropping cannot remove either edge. */
@Composable
internal fun DiscoveryHeroShade(modifier: Modifier = Modifier) {
    Box(modifier.background(discoveryHeroShadeBrush()).background(heroSideBrush))
}

// Ease both ends of the feather so it meets the image and background without
// a visible seam. Squaring the curve leaves most of the artwork unobscured.
private fun featherAlpha(t: Float): Float {
    val eased = t * t * t * (t * (t * 6f - 15f) + 10f)
    return eased * eased
}

private fun featherStops(startWidth: Float, endWidth: Float) = buildList {
    for (step in 0..32) {
        val t = step / 32f
        val remaining = 1f - t
        add(t * startWidth to Color(0xFF090B10).copy(alpha = featherAlpha(remaining)))
    }
    for (step in 0..32) {
        val t = step / 32f
        add(1f - endWidth + t * endWidth to Color(0xFF090B10).copy(alpha = featherAlpha(t)))
    }
}.toTypedArray()

private val heroEdgeStops = featherStops(.10f, .18f)
private val heroSideBrush = Brush.horizontalGradient(*featherStops(.015f, .015f))

internal fun discoveryHeroShadeBrush(startY: Float = 0f, endY: Float = Float.POSITIVE_INFINITY) =
    Brush.verticalGradient(*heroEdgeStops, startY = startY, endY = endY)
