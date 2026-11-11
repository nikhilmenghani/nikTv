package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.MediaItem

internal data class ProviderCollectionOccurrence(val key: String, val media: MediaItem)

/** A shortcut and its original provider tile are separate browsing positions. */
internal fun providerCollectionOccurrences(pinned: List<MediaItem>, catalogue: List<MediaItem>): List<ProviderCollectionOccurrence> {
    val ordinals = mutableMapOf<String, Int>()
    return pinned.distinctBy { it.id }.map { ProviderCollectionOccurrence("pinned:${it.id}", it) } +
        catalogue.map { media ->
            val ordinal = ordinals.getOrDefault(media.id, 0)
            ordinals[media.id] = ordinal + 1
            ProviderCollectionOccurrence("provider:${media.id}:$ordinal", media)
        }
}

/** Removing a shortcut returns to the original tile rather than another shortcut. */
internal fun providerCollectionRestoreIndex(occurrences: List<ProviderCollectionOccurrence>,
    lastKey: String?, lastMediaId: String?): Int? {
    if (lastKey != null) occurrences.indexOfFirst { it.key == lastKey }.takeIf { it >= 0 }?.let { return it }
    if (lastMediaId == null) return null
    return occurrences.indexOfFirst { it.media.id == lastMediaId && it.key.startsWith("provider:") }.takeIf { it >= 0 }
        ?: occurrences.indexOfFirst { it.media.id == lastMediaId }.takeIf { it >= 0 }
}
