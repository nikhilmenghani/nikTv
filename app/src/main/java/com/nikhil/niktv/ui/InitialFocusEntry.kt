package com.nikhil.niktv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalWindowInfo

/** Choose the entry target before an automatic first-child focus can be painted. */
@Composable
internal fun Modifier.initialFocusEntry(target: FocusRequester, enabled: Boolean = true): Modifier {
    val entry = remember(target) { InitialFocusEntry() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(target, enabled, windowFocused) {
        if (enabled && windowFocused) {
            withFrameNanos { }
            entry.request { runCatching { target.requestFocus() }.getOrDefault(false) }
        }
    }
    return focusProperties {
        onEnter = {
            if (enabled && !entry.request { runCatching { target.requestFocus() }.getOrDefault(false) })
                cancelFocusChange()
        }
    }.focusGroup()
}

internal class InitialFocusEntry {
    private var entered = false
    fun request(focus: () -> Boolean): Boolean {
        if (entered) return true
        entered = true // requestFocus may recursively enter this same group.
        if (!focus()) { entered = false; return false }
        return true
    }
}
