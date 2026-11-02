package com.nikhil.niktv.discovery

/** Preserve stable identity; after removal enter its nearest surviving neighbor. */
internal fun nativeRowReplacement(previous: List<String>, current: List<String>, selected: String): String? =
    selected.takeIf { it in current } ?: current.getOrNull(previous.indexOf(selected).coerceAtLeast(0).coerceAtMost((current.size - 1).coerceAtLeast(0)))
