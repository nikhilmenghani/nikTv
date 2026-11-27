package com.nikhil.niktv.discovery

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged

/** Redirect automatic/spatial entry; preserve explicit popup-origin restoration after entry. */
internal class DiscoveryHeaderEntry {
    var hasFocus = false
    private var entered = false
    fun redirect(direction: FocusDirection): Boolean {
        // Horizontal moves can cross the tabs' scroll container while staying
        // inside the header. Redirecting those moves traps focus on the active tab.
        val redirect = !entered || direction == FocusDirection.Up || direction == FocusDirection.Down ||
            direction == FocusDirection.Next || direction == FocusDirection.Previous
        entered = true // Set before requestFocus, which can itself enter this group.
        return redirect
    }
}

/** Select the active tab during focus search, before Back can receive or paint focus. */
@Composable
internal fun Modifier.discoveryHeaderFocusEntry(selected: FocusRequester, enabled: Boolean = true,
    selectedNow: () -> FocusRequester = { selected }): Modifier {
    val entry = remember { DiscoveryHeaderEntry() }
    val portalReturn = LocalPortalFocusReturn.current
    return focusProperties {
        onEnter = {
            if (enabled && !entry.hasFocus && entry.redirect(requestedFocusDirection) && !runCatching { (portalReturn.preferred ?: selectedNow()).requestFocus() }.getOrDefault(false))
                cancelFocusChange()
        }
    }.onFocusChanged { entry.hasFocus = it.hasFocus }.focusGroup()
}
