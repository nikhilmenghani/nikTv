package com.nikhil.niktv.discovery

import androidx.compose.animation.core.spring
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
import com.nikhil.niktv.ui.tileFocusScrollDistance

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscoveryVerticalFocusViewport(coordinator: DiscoveryScrollCoordinator, content: @Composable () -> Unit) {
    val spec = remember(coordinator) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (coordinator.isMoving) 0f else tileFocusScrollDistance(offset, size, containerSize)
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** One cancellable owner for revealing, aligning and focusing a vertical destination. */
internal class DiscoveryScrollCoordinator(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var destination: Int? = null
    val isMoving: Boolean get() = job?.isActive == true
    fun cancel() { job?.cancel(); job = null; destination = null }

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

    fun move(state: LazyListState, index: Int, reduceMotion: Boolean, focus: () -> Boolean): Job = start(index) {
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

    private suspend fun scroll(state: LazyListState, index: Int, reduceMotion: Boolean) {
        val info = state.layoutInfo
        val target = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (target == null) {
            if (reduceMotion) state.scrollToItem(index) else state.animateScrollToItem(index)
        } else {
            val distance = (target.offset - info.viewportStartOffset).toFloat()
            if (distance != 0f) {
                if (reduceMotion) state.scrollToItem(index)
                else state.animateScrollBy(distance, spring(dampingRatio = 1f, stiffness = 180f))
            }
        }
    }
}
