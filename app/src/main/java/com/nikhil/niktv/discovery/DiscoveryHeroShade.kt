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
    Box(modifier.background(Brush.verticalGradient(
        0f to Color(0xFF090B10), .25f to Color.Transparent,
        .55f to Color.Transparent, .82f to Color(0x99090B10), 1f to Color(0xFF090B10))))
}
