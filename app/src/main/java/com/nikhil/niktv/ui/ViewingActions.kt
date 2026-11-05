package com.nikhil.niktv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem

internal val LocalMarkUnwatched = staticCompositionLocalOf<((MediaItem, CatalogType, MediaItem?) -> Unit)?> { null }

@Composable
internal fun MarkUnwatchedMenuItem(item: MediaItem, type: CatalogType?, series: MediaItem? = null, dismiss: () -> Unit) {
    val action = LocalMarkUnwatched.current ?: return
    val resolvedType = type ?: return
    if (resolvedType != CatalogType.MOVIES && !(resolvedType == CatalogType.SERIES && (series != null || item.episodeNumber != null))) return
    NikDropdownMenuItem(text = { Text("Mark as unwatched") },
        leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
        onClick = { dismiss(); action(item, resolvedType, series) })
}
