package com.nikhil.niktv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

/** Focus animation invalidates drawing, never the poster/text composition or its layout. */
internal fun Modifier.tileFocusOutline(
    enabled: Boolean,
    progress: State<Float>,
    shape: Shape,
    restingColor: Color,
    focusedWidth: Dp
): Modifier = if (!enabled) this else drawWithCache {
    onDrawWithContent {
        drawContent()
        val fraction = progress.value.coerceIn(0f, 1f)
        val width = 1f * density + (focusedWidth.toPx() - density) * fraction
        // Keep the entire stroke inside the tile, including first/last columns.
        inset(width / 2f) {
            drawOutline(shape.createOutline(size, layoutDirection, this),
                lerp(restingColor, Color(0xFFF2F3F5), fraction), style = Stroke(width))
        }
    }
}

internal fun tileRowRevealDistance(start: Int, height: Int, viewportStart: Int, viewportEnd: Int): Float =
    tileFocusScrollDistance((start - viewportStart).toFloat(), height.toFloat(), (viewportEnd - viewportStart).toFloat())

/** Estimate an adjacent uncomposed row from actual grid geometry; don't snap it to the top. */
internal suspend fun LazyGridState.revealTileRow(index: Int, columns: Int) {
    val info = layoutInfo
    val anchor = info.visibleItemsInfo.minByOrNull { abs(it.index / columns - index / columns) } ?: return
    val rowDistance = index / columns - anchor.index / columns
    val start = anchor.offset.y + rowDistance * (anchor.size.height + info.mainAxisItemSpacing)
    val distance = tileRowRevealDistance(start, anchor.size.height, info.viewportStartOffset, info.viewportEndOffset)
    if (distance != 0f) animateScrollBy(distance, tween(160, easing = FastOutSlowInEasing))
}

/** Keep focus on attached tiles while Compose smoothly reveals their clipped edge.
 * Only an uncomposed row needs the explicit parking/reveal fallback.
 */
internal fun LazyGridState.focusVisibleTile(index: Int, requester: FocusRequester): Boolean {
    val info = layoutInfo
    val target = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    if (target.offset.y >= info.viewportEndOffset ||
        target.offset.y + target.size.height <= info.viewportStartOffset) return false
    return runCatching { requester.requestFocus() }.getOrDefault(false)
}
