package com.nikhil.niktv.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

internal data class CollectionPagination(val pending: Boolean, val load: () -> Unit)

/** Keep the footer alive until its replacement tile has accepted focus. */
@Composable
internal fun rememberCollectionPagination(
    ids: List<String>,
    loading: Boolean,
    grid: LazyGridState,
    requesters: MutableMap<String, FocusRequester>,
    loadMore: () -> Unit,
    itemOffset: Int = 1
): CollectionPagination {
    var startIds by remember { mutableStateOf<List<String>?>(null) }
    var observedLoading by remember { mutableStateOf(false) }
    var anchorIndex by remember { mutableIntStateOf(0) }
    var anchorOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(ids, loading, startIds) {
        val previous = startIds ?: return@LaunchedEffect
        if (loading) {
            observedLoading = true
            return@LaunchedEffect
        }
        if (ids == previous && !observedLoading) {
            // Give an asynchronously dispatched request time to enter loading.
            // A no-op/rejected request must not leave the footer busy forever.
            delay(250L)
        }
        val target = appendedFocusIndex(previous, ids)
        if (target >= 0) {
            // Lazy layouts otherwise follow the footer's stable key as it moves.
            grid.scrollToItem(anchorIndex, anchorOffset)
            withFrameNanos { }
            val gridIndex = target + itemOffset
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == gridIndex }) {
                // Reveal only as much as needed if a partial final row put the
                // new tile below the old footer; never align it at the top.
                val height = grid.layoutInfo.viewportSize.height
                grid.scrollToItem(gridIndex, -(height * 2 / 3))
            }
            val requester = requesters.getOrPut(ids[target]) { FocusRequester() }
            for (attempt in 0 until 6) {
                withFrameNanos { }
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) break
                delay(50L)
            }
        }
        startIds = null
        observedLoading = false
    }
    return CollectionPagination(startIds != null) {
        if (!loading && startIds == null) {
            anchorIndex = grid.firstVisibleItemIndex
            anchorOffset = grid.firstVisibleItemScrollOffset
            observedLoading = false
            startIds = ids.toList()
            loadMore()
        }
    }
}

internal data class ReturningTile(val modifier: Modifier, val open: () -> Unit)

@Composable
internal fun rememberReturningTile(open: () -> Unit): ReturningTile {
    var returning by rememberSaveable { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (returning) {
            for (attempt in 0 until 6) {
                withFrameNanos { }
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) break
                delay(50L)
            }
            returning = false
        }
    }
    return ReturningTile(Modifier.focusRequester(requester)) {
        returning = true
        open()
    }
}

internal fun appendedFocusIndex(previous: List<String>, current: List<String>): Int {
    val existing = previous.toSet()
    val firstNew = current.indexOfFirst { it !in existing }
    return if (firstNew >= 0) firstNew else current.lastIndex
}
