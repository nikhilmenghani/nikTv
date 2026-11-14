package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/** Save episode coordinates, never layout-specific row coordinates, across mode/rotation changes. */
@Composable
internal fun rememberSeriesEpisodeViewport(identity: String, columns: Int): LazyListState {
    var anchors by rememberSaveable { mutableStateOf(mapOf<String, List<Int>>()) }
    val state = key(identity, columns) {
        val saved = anchors[identity]
        rememberLazyListState(seriesEpisodeViewportIndex(saved?.getOrNull(0) ?: 0, columns), saved?.getOrNull(1) ?: 0)
    }
    LaunchedEffect(identity, columns, state) {
        snapshotFlow { Triple(state.isScrollInProgress, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
            .distinctUntilChanged().collect { (moving, row, offset) ->
                if (!moving) anchors = anchors + (identity to listOf(row * columns, offset))
            }
    }
    return state
}

/** Row-based virtualization shares the existing episode viewport/restore policy with list view. */
@Composable
internal fun <T> SeriesEpisodeGrid(
    items: List<T>, columns: Int, state: LazyListState, key: (T) -> String,
    modifier: Modifier = Modifier, tail: (@Composable () -> Unit)? = null,
    visibleRows: Int? = null,
    content: @Composable (Int, T, Modifier) -> Unit,
) {
    val rows = remember(items, columns) { items.chunked(columns) }
    BoxWithConstraints(modifier) {
    val rowHeight = visibleRows?.let { ((maxHeight - 8.dp - 10.dp * (it - 1)) / it)
        .coerceAtLeast(68.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(rows, key = { _, row -> key(row.first()) }) { rowIndex, row ->
            Row(Modifier.fillMaxWidth().then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { column, item ->
                    androidx.compose.runtime.key(key(item)) { content(rowIndex * columns + column, item,
                        Modifier.weight(1f).then(if (rowHeight != null) Modifier.fillMaxHeight() else Modifier)) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (tail != null) item("episode-grid-tail") { tail() }
    }
    }
}

internal fun seriesEpisodeListColumns(smallestWidth: Int, width: Int, television: Boolean) =
    if (!television && smallestWidth >= 600 && width >= 840) 2 else 1

internal fun seriesEpisodeGridColumns(shortWindow: Boolean) = if (shortWindow) 6 else 5
internal fun seriesEpisodeViewportIndex(index: Int, columns: Int) = index.coerceAtLeast(0) / columns.coerceAtLeast(1)
internal fun seriesEpisodeLastVisible(row: Int, columns: Int, count: Int) =
    if (row < 0 || count == 0) -1 else minOf((row + 1) * columns - 1, count - 1)

/** Null exits to the toolbar; edges stay put and incomplete final rows remain reachable. */
internal fun seriesEpisodeGridMove(index: Int, count: Int, columns: Int, dx: Int = 0, dy: Int = 0): Int? {
    if (count <= 0) return null
    if (dy < 0) return (index - columns).takeIf { it >= 0 }
    if (dy > 0) return if ((index / columns + 1) * columns >= count) index else minOf(index + columns, count - 1)
    if (dx < 0) return if (index % columns == 0) null else index - 1
    if (dx > 0) return if (index % columns == columns - 1 || index == count - 1) index else index + 1
    return index
}
