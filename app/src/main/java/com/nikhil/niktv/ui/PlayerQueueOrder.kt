package com.nikhil.niktv.ui

import com.nikhil.niktv.model.MediaItem

/** Pins are shortcuts; the complete provider list retains its original order. */
internal fun orderedPlayerQueue(items: List<MediaItem>, pinnedIds: List<String> = emptyList()): List<MediaItem> {
    // The player can receive a queue that already includes the pinned prefix.
    // Remove that prefix before rebuilding it, preserving the provider occurrence.
    val counts = items.groupingBy { it.id }.eachCount().toMutableMap()
    val providerItems = items.dropWhile { item ->
        (counts.getValue(item.id) > 1).also { remove ->
            if (remove) counts[item.id] = counts.getValue(item.id) - 1
        }
    }.distinctBy { it.id }
    val byId = providerItems.associateBy { it.id }
    return buildList {
        pinnedIds.distinct().forEach { id -> byId[id]?.let { add(it) } }
        addAll(providerItems)
    }
}
