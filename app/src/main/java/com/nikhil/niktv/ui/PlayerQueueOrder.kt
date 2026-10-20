package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem

/** Promote pins within the active queue without adding a second channel occurrence. */
internal fun orderedPlayerQueue(items: List<MediaItem>, pinnedIds: List<String> = emptyList()): List<MediaItem> {
    val remaining = linkedMapOf<String, MediaItem>()
    items.forEach { item -> if (item.id !in remaining) remaining[item.id] = item }
    return buildList {
        pinnedIds.forEach { id -> remaining.remove(id)?.let { add(it) } }
        addAll(remaining.values)
    }
}
