package com.nikhil.niktv.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal data class DirectPortalAction(val name: String, val icon: ImageVector, val selected: Boolean = false, val id: String = name, val open: () -> Unit)
internal val LocalDirectPortalActions = staticCompositionLocalOf<List<DirectPortalAction>> { emptyList() }
internal class PortalFocusReturn {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private var pending: String? = null
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    fun clicked(id: String) { pending = id }
    fun cancel() { pending = null }
    val preferred: FocusRequester? get() = pending?.let(requesters::get)
    val waiting: Boolean get() = pending != null
    fun restore(): Boolean {
        val success = preferred?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
        if (success) pending = null
        return success
    }
}
internal val LocalPortalFocusReturn = staticCompositionLocalOf { PortalFocusReturn() }

@Composable
internal fun DirectPortalButtons(modifier: Modifier = Modifier, labels: Boolean = false) {
    val focusReturn = LocalPortalFocusReturn.current
    Row(modifier.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.keyCode in listOf(19, 20, 21, 22, 61)) focusReturn.cancel()
        false
    }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LocalDirectPortalActions.current.forEach { action ->
            DiscoveryAction(action.name, action.icon, iconOnly = !labels, selected = action.selected,
                horizontalPadding = 6.dp,
                modifier = (if (labels) Modifier.weight(1f).heightIn(min = 48.dp) else Modifier.size(44.dp))
                    .focusRequester(focusReturn.requester(action.id)), onClick = {
                        if (!action.selected) focusReturn.clicked(action.id) else focusReturn.cancel()
                        action.open()
                    })
        }
    }
}
