package com.nikhil.niktv.ui

import com.nikhil.niktv.model.Category

private val collectionFingerprint = Regex("[a-f0-9]{16}")

/** Collection membership fingerprints invalidate caches, not the user's chosen display position. */
internal fun categoryDisplayOrderId(category: Category): String = category.id.let { id ->
    if ((id.startsWith("@collection:") || id.startsWith("@custom:")) &&
        id.substringAfterLast(':').matches(collectionFingerprint)) id.substringBeforeLast(':') else id
}

/** Unknown/new items retain their source order after the saved items. Never adds hidden items. */
internal fun <T> orderedForDisplay(items: List<T>, order: List<String>, key: (T) -> String): List<T> {
    if (order.isEmpty()) return items
    val ranks = order.distinct().withIndex().associate { it.value to it.index }
    return items.sortedBy { ranks[key(it)] ?: Int.MAX_VALUE }
}

/** Editing a filtered subset must not erase the positions of temporarily unavailable items. */
internal fun mergeDisplayOrder(previous: List<String>, visible: List<String>): List<String> {
    val desired = visible.distinct()
    val affected = desired.toSet()
    val replacements = desired.iterator()
    return buildList {
        previous.distinct().forEach { id -> add(if (id in affected && replacements.hasNext()) replacements.next() else id) }
        while (replacements.hasNext()) add(replacements.next())
    }.distinct()
}

internal data class OrderChoice(val id: String, val title: String)
