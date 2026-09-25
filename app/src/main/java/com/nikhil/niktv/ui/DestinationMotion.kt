package com.nikhil.niktv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Animate the incoming destination only: outgoing grids must release focus and effects. */
@Composable
internal fun Modifier.destinationEntrance(key: Any, isTv: Boolean): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, tween(if (isTv) 160 else 200, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * (if (isTv) 6.dp else 12.dp).toPx()
    }
}
