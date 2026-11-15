package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One independently scrolling rail; watch status never changes a card's bounds. */
@Composable
internal fun <T> SeriesEpisodeRow(
    items: List<T>, state: LazyListState, key: (T) -> String,
    modifier: Modifier = Modifier, tail: (@Composable () -> Unit)? = null,
    content: @Composable (Int, T, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val cardHeight = (maxHeight - 8.dp).coerceIn(0.dp, 264.dp)
        val cardWidth = cardHeight * (2f / 3f)
        StableTileFocusViewport {
            LazyRow(Modifier.fillMaxSize(), state = state,
                contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                    content(index, item, Modifier.width(cardWidth).height(cardHeight))
                }
                if (tail != null) item("episode-row-tail") { Box(Modifier.width(cardWidth).height(cardHeight)) { tail() } }
            }
        }
    }
}
