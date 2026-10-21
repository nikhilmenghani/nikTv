package com.nikhil.niktv.discovery

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/** Redirect automatic/spatial entry; preserve explicit popup-origin restoration after entry. */
internal class DiscoveryHeaderEntry {
    private var entered = false
    fun redirect(direction: FocusDirection): Boolean {
        val redirect = !entered || direction != FocusDirection.Enter
        entered = true // Set before requestFocus, which can itself enter this group.
        return redirect
    }
}

/** Select the active tab during focus search, before Back can receive or paint focus. */
@Composable
internal fun Modifier.discoveryHeaderFocusEntry(selected: FocusRequester): Modifier {
    val entry = remember { DiscoveryHeaderEntry() }
    return focusProperties {
        onEnter = {
            if (entry.redirect(requestedFocusDirection) && !runCatching { selected.requestFocus() }.getOrDefault(false))
                cancelFocusChange()
        }
    }.focusGroup()
}
