package com.nikhil.niktv.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

internal data class DirectPortalAction(val name: String, val icon: ImageVector, val selected: Boolean = false,
    val id: String = name, val opensNewWindow: Boolean = false, val open: () -> Unit)
internal val LocalDirectPortalActions = staticCompositionLocalOf<List<DirectPortalAction>> { emptyList() }
internal class PortalFocusReturn {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private var pending by mutableStateOf<String?>(null)
    private val routeOwners = mutableStateListOf<Any>()
    private val headers = linkedMapOf<Any, FocusRequester>()
    fun registerHeader(owner: Any, requester: FocusRequester) { headers[owner] = requester }
    fun unregisterHeader(owner: Any) { headers.remove(owner) }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    fun ownRoute(owner: Any) { routeOwners += owner }
    fun releaseRoute(owner: Any) { routeOwners -= owner }
    val routeOwned: Boolean get() = routeOwners.isNotEmpty()
    val pendingId: String? get() = pending
    fun clicked(id: String) { pending = id }
    fun cancel() { pending = null }
    val preferred: FocusRequester? get() = pending?.let { headers.values.lastOrNull() ?: requesters[it] }
    val waiting: Boolean get() = pending != null
    fun restore(): Boolean {
        val success = preferred?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
        if (success) pending = null
        return success
    }
}
internal val LocalPortalFocusReturn = staticCompositionLocalOf { PortalFocusReturn() }

/** Reserve exactly the visible portal targets, their gaps and the glass inset. */
@Composable
internal fun portalHeaderWidth(): androidx.compose.ui.unit.Dp {
    val count = LocalDirectPortalActions.current.size
    if (count <= 1) return 0.dp
    val size = discoveryHeaderActionSize()
    return with(androidx.compose.ui.platform.LocalDensity.current) {
        // Layout rounds each button, gap and inset independently. Reserve their
        // pixel sum so the last button never loses a pixel to cumulative rounding.
        (size.roundToPx() * count + 4.dp.roundToPx() * (count - 1) + 5.dp.roundToPx() * 2).toDp()
    }
}

@Composable
internal fun discoveryProfileHeaderWidth(clock: Boolean): androidx.compose.ui.unit.Dp {
    val person = if (clock) (124f * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
        else if (discoveryHeaderActionSize() == 36.dp) 46.dp else 56.dp
    return person
}

@Composable
internal fun DirectPortalButtons(modifier: Modifier = Modifier, labels: Boolean = false) {
    val focusReturn = LocalPortalFocusReturn.current
    val actions = LocalDirectPortalActions.current
    LaunchedEffect(actions.map { it.id }) {
        if (actions.size <= 1 || actions.none { it.id == focusReturn.pendingId }) focusReturn.cancel()
    }
    if (actions.size <= 1) return
    val selectedIds = actions.filter { it.selected }.map { it.id }
    val pendingId = focusReturn.pendingId
    val routeOwned = focusReturn.routeOwned
    val view = LocalView.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(pendingId, selectedIds, routeOwned, windowFocused) {
        if (pendingId == null || pendingId !in selectedIds || routeOwned || !windowFocused) return@LaunchedEffect
        // The dashboard has its own route-aware entry owner. Other shells restore
        // after their initial focus/layout pass and only once the new portal is selected.
        withFrameNanos { }
        repeat(20) {
            withFrameNanos { }
            if (focusReturn.routeOwned || focusReturn.pendingId != pendingId) return@LaunchedEffect
            if (view.hasWindowFocus() && focusReturn.restore()) return@LaunchedEffect
        }
    }
    // Dynamic portal names must remain readable. A content-sized strip also
    // keeps every portal reachable when more providers are added later.
    val scroll = rememberScrollState()
    Row(modifier.then(if (labels) Modifier.horizontalScroll(scroll) else Modifier)
        .semantics { contentDescription = "Portals" }.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.keyCode in listOf(19, 20, 21, 22, 61)) focusReturn.cancel()
        false
    }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { action -> key(action.id) {
            DiscoveryAction(action.name, action.icon, iconOnly = !labels, selected = action.selected,
                horizontalPadding = if (labels) 12.dp else 6.dp,
                modifier = (if (labels) Modifier.heightIn(min = 48.dp) else Modifier.size(discoveryHeaderActionSize()))
                    .semantics { selected = action.selected }
                    .focusRequester(focusReturn.requester(action.id)), onClick = {
                        if (action.selected || action.opensNewWindow) focusReturn.cancel() else focusReturn.clicked(action.id)
                        action.open()
                    })
        } }
    }
}
