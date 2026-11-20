package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nikhil.niktv.discovery.EasyBrowseEntry
import com.nikhil.niktv.discovery.EasyMediaTile
import com.nikhil.niktv.discovery.easySaveAction
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem

internal fun CatalogType?.easyPlayerListTitle() = when (this) {
    CatalogType.LIVE_TV -> "Channels"
    CatalogType.SERIES -> "Episodes"
    else -> "Playlist"
}

@Composable
internal fun EasyPlayerListButton(type: CatalogType, modifier: Modifier, open: () -> Unit) {
    NikTvSecondaryActionButton(open, modifier, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Icon(Icons.Default.ViewList, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(type.easyPlayerListTitle(), maxLines = 1)
    }
}

@Composable
internal fun EasyPlayerQueue(items: List<MediaItem>, type: CatalogType?, playingId: String,
    hasMore: Boolean, loadingMore: Boolean, loadMore: () -> Boolean, dismiss: () -> Unit, select: (MediaItem, Int) -> Unit,
    favoriteIds: Set<String>, toggleFavorite: ((MediaItem) -> Unit)?) {
    val current = items.indexOfFirst { it.id == playingId }
    val initial = remember { current.coerceAtLeast(0) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val entry = remember { FocusRequester() }
    val keys = remember(items) { queueOccurrences(items.map(MediaItem::id)) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .66f).dp
    var requestedMore by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(12.dp).initialFocusEntry(entry),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(type.easyPlayerListTitle(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    NikTvSecondaryActionButton(dismiss, if (items.isEmpty()) Modifier.focusRequester(entry) else Modifier) {
                        Text("Close")
                    }
                }
                LazyColumn(Modifier.heightIn(max = maxHeight), state = list, contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(items, key = { index, _ -> keys[index].toString() }) { index, item ->
                        val episode = if (type == CatalogType.SERIES) item.episodeIdentityLabel() else null
                        val title = if (episode != null) item.episodeTitleWithoutIdentity().ifBlank { episode } else item.title
                        EasyMediaTile(EasyBrowseEntry(keys[index].toString(), title, Icons.Default.PlayArrow,
                            episode?.takeUnless { it == title }, status = "Playing".takeIf { index == current }),
                            open = { if (index == current) dismiss() else select(item, index) },
                            actions = toggleFavorite?.let { toggle -> listOf(easySaveAction(item.id in favoriteIds) { toggle(item) }) }.orEmpty(),
                            modifier = if (index == initial) Modifier.focusRequester(entry) else Modifier)
                    }
                    if (hasMore || requestedMore) item(key = "page") {
                        NikTvSecondaryActionButton({
                            if (!hasMore) dismiss()
                            else if (!loadingMore) requestedMore = loadMore() || requestedMore
                        }, Modifier.fillMaxWidth()) {
                            Text(if (loadingMore) "Loading…" else if (hasMore) "Show more" else "Close list")
                        }
                    }
                }
            }
        }
    }
}
