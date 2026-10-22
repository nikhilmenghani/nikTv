package com.nikhil.niktv.discovery

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.nikhil.niktv.ui.tileFocusScrollDistance

internal data class DiscoveryFeedIndices(val header: Int?, val hero: Int, val season: Int, val firstRail: Int)
internal fun discoveryFeedIndices(scrollingHeader: Boolean, series: Boolean): DiscoveryFeedIndices {
    val hero = if (scrollingHeader) 1 else 0
    return DiscoveryFeedIndices(if (scrollingHeader) 0 else null, hero, hero + 1, hero + 1 + if (series) 1 else 0)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscoveryVerticalFocusViewport(coordinator: DiscoveryScrollCoordinator, content: @Composable () -> Unit) {
    val spec = remember(coordinator) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (coordinator.blocksAutomaticReveal) 0f else tileFocusScrollDistance(offset, size, containerSize)
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

internal fun discoveryHorizontalFocusScrollDistance(offset: Float, size: Float, containerSize: Float, edgePadding: Float): Float {
    val padding = edgePadding.coerceIn(0f, ((containerSize - size) / 2f).coerceAtLeast(0f))
    return tileFocusScrollDistance(offset - padding, size + 2f * padding, containerSize)
}

/** Keep the scaled poster and its border inside the row, including on the way back. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscoveryHorizontalFocusViewport(edgePadding: Dp, content: @Composable () -> Unit) {
    val padding = with(LocalDensity.current) { edgePadding.toPx() }
    val spec = remember(padding) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                discoveryHorizontalFocusScrollDistance(offset, size, containerSize, padding)
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** One cancellable owner for revealing, aligning and focusing a vertical destination. */
internal class DiscoveryScrollCoordinator(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var destination: Int? = null
    private var automaticRevealOwners = 0
    val isMoving: Boolean get() = job?.isActive == true
    val blocksAutomaticReveal: Boolean get() = isMoving || automaticRevealOwners > 0
    fun cancel() { job?.cancel(); job = null; destination = null }

    /** Keep an old focused row from undoing a menu reveal while its header composes. */
    suspend fun <T> withAutomaticRevealSuppressed(block: suspend () -> T): T {
        automaticRevealOwners++
        return try { block() } finally { automaticRevealOwners-- }
    }

    private fun start(index: Int, block: suspend () -> Unit): Job {
        cancel()
        destination = index
        return scope.launch(start = CoroutineStart.LAZY) { block() }.also {
            job = it
            it.start()
        }
    }

    fun align(state: LazyListState, index: Int, reduceMotion: Boolean) {
        // A focus callback during move must not cancel its own reveal animation.
        if (destination == index && job?.isActive == true) return
        start(index) { scroll(state, index, reduceMotion) }
    }

    fun move(state: LazyListState, index: Int, reduceMotion: Boolean, focus: suspend () -> Boolean): Job = start(index) {
        val layout = state.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        // The vertical viewport suppresses implicit relocation while we own the move.
        // A composed destination can therefore take focus before it is fully revealed.
        val focused = item != null && focus()
        coroutineScope {
            // Offscreen destinations take focus as soon as composed. The child shares
            // cancellation with the destination; it never owns another scroll.
            var aligned = false
            val transfer = if (focused) null else launch(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(2_000L) {
                    while (isActive) {
                        val current = state.layoutInfo
                        val row = current.visibleItemsInfo.firstOrNull { it.index == index }
                        val revealed = row != null
                        if ((revealed || aligned) && focus()) break
                        withFrameNanos { }
                    }
                }
            }
            scroll(state, index, reduceMotion)
            aligned = true // Oversized rows may never fit completely; still focus them.
            transfer?.join()
        }
    }

    /** Same bounded curve and cancellation owner for the full collection grid. */
    fun moveGrid(state: LazyGridState, index: Int, columns: Int, alignRow: Boolean,
        reduceMotion: Boolean, focus: () -> Boolean): Job = start(index) {
        coroutineScope {
            val transfer = launch(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(2_000L) {
                    while (isActive) {
                        if (focus()) break
                        withFrameNanos { }
                    }
                }
            }
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            val first = info.visibleItemsInfo.firstOrNull()
            val offset = item?.offset?.y?.toFloat() ?: first?.let {
                it.offset.y + (index / columns - it.index / columns) * (it.size.height + info.mainAxisItemSpacing)
            }?.toFloat() ?: 0f
            val height = item?.size?.height ?: first?.size?.height ?: 0
            val available = info.viewportEndOffset - info.afterContentPadding
            val distance = when {
                alignRow -> offset
                offset < 0 -> offset
                offset + height > available -> offset + height - available
                else -> 0f
            }
            if (reduceMotion) state.scroll { scrollBy(distance) }
            else state.animateScrollBy(distance, tween(240, easing = FastOutSlowInEasing))
            transfer.join()
        }
    }

    private suspend fun scroll(state: LazyListState, index: Int, reduceMotion: Boolean) {
        if (reduceMotion) { state.scrollToItem(index); return }
        // A bounded curve avoids the spring's long one-pixel settling tail. Use the
        // same path for composed and offscreen rows, updating estimated geometry as
        // the destination composes rather than starting a second alignment spring.
        // UserInput priority also prevents a lower-priority implicit focus reveal
        // from canceling this move. A real touch scroll or a new direction can cancel it.
        state.scroll(MutatePriority.UserInput) {
            val layout = LazyLayoutScrollScope(state, this)
            if (layout.calculateDistanceTo(index) == 0) return@scroll
            var previousFraction = 0f
            animate(0f, 1f, animationSpec = tween(240, easing = FastOutSlowInEasing)) { fraction, _ ->
                val remaining = layout.calculateDistanceTo(index).toFloat()
                val portion = ((fraction - previousFraction) / (1f - previousFraction).coerceAtLeast(.000001f)).coerceIn(0f, 1f)
                scrollBy(remaining * portion)
                previousFraction = fraction
            }
        }
    }
}
