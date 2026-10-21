package com.nikhil.niktv.discovery

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Materialize a saved horizontal destination within the vertical move's lifetime. */
internal suspend fun focusDiscoveryRowEntry(
    selected: String?,
    keys: List<String>,
    allKey: String?,
    allIsRowItem: Boolean,
    isFullyVisible: (Int) -> Boolean,
    reveal: suspend (Int) -> Unit,
    request: (String) -> Boolean,
): Boolean {
    val key = selected?.takeIf { it in keys || it == allKey }
        ?: keys.firstOrNull() ?: allKey ?: return false
    val index = keys.indexOf(key).takeIf { it >= 0 }
        ?: keys.size.takeIf { key == allKey && allIsRowItem }
    if (index != null && !isFullyVisible(index)) reveal(index)
    // A newer Up/Down request may have canceled us while the lazy row was composing.
    currentCoroutineContext().ensureActive()
    return request(key)
}
