package com.nikhil.niktv.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.ui.StableTileFocusViewport

internal data class PortalMenuItem(val label: String, val icon: ImageVector, val selected: Boolean,
    val modifier: Modifier = Modifier, val click: () -> Unit)

/** Shared wide chrome: stable focus origins, content-sized menu and inline search. */
@Composable
internal fun PortalWideNavigation(profile: String, openProfile: () -> Unit, back: () -> Unit,
    items: List<PortalMenuItem>, backModifier: Modifier = Modifier, searchModifier: Modifier = Modifier,
    searchOpen: Boolean = false, openSearch: (() -> Unit)? = null, closeSearch: () -> Unit = {},
    query: (@Composable (Modifier) -> Unit)? = null,
    options: @Composable (Modifier) -> Unit) {
    val owner = remember { SharedDiscoveryHeader() }
    val backTarget = remember { FocusRequester() }
    val optionsTarget = remember { FocusRequester() }
    val closeTarget = remember { FocusRequester() }
    val queryTarget = remember { FocusRequester() }
    val targets = remember(items.map { it.label }) { items.map { FocusRequester() } }
    val selectedIndex = items.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0
    val entry = targets.getOrNull(selectedIndex) ?: backTarget
    owner.focusTargets["Back"] = backTarget
    owner.focusTargets["Options"] = optionsTarget
    owner.focusTargets["Query"] = queryTarget
    owner.focusTargets["Close search"] = closeTarget
    items.forEachIndexed { index, item -> owner.focusTargets[item.label] = targets[index] }
    val keyboard = LocalSoftwareKeyboardController.current
    val reveal = animateFloatAsState(if (searchOpen) 1f else 0f, tween(180), label = "portalSearchReveal")
    fun close() {
        owner.focusedTab = "Search"
        owner.searchRequester.requestFocus()
        keyboard?.hide()
        closeSearch()
    }
    val imeShown = rememberPortalImeVisibility()
    BackHandler(searchOpen) { if (imeShown) keyboard?.hide() else close() }
    fun Modifier.target(label: String, requester: FocusRequester, left: FocusRequester? = null,
        right: FocusRequester? = null): Modifier = focusRequester(requester)
        .onFocusChanged { if (it.isFocused) owner.focusedTab = label }
        .focusProperties { left?.let { this.left = it }; right?.let { this.right = it } }
        .onPreviewKeyEvent { event ->
            val next = when (event.key) { Key.DirectionLeft -> left; Key.DirectionRight -> right; else -> null }
            if (next == null) false else { if (event.type == KeyEventType.KeyDown) next.requestFocus(); true }
        }
    CompositionLocalProvider(LocalSharedDiscoveryHeader provides owner) {
        PortalWideHeader(profile, openProfile,
            Modifier.discoveryHeaderFocusEntry(if (searchOpen) queryTarget else entry,
                selectedNow = { owner.entryRequester(if (searchOpen) queryTarget else entry) }), unified = true, attachedSearch = searchOpen) {
            if (!searchOpen) DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true,
                focusHint = false, modifier = backModifier.target("Back", backTarget,
                    right = if (openSearch != null) owner.searchRequester else targets.firstOrNull()), onClick = back)
            if (openSearch != null) DiscoveryAction("Search", Icons.Default.Search, iconOnly = true,
                selected = searchOpen, focusHint = false,
                modifier = searchModifier.target("Search", owner.searchRequester,
                    left = if (searchOpen) null else backTarget, right = if (searchOpen) queryTarget else targets.firstOrNull()),
                onClick = openSearch)
            if (searchOpen && query != null) {
                query(Modifier.weight(1f).focusRequester(queryTarget)
                    .onFocusChanged { if (it.isFocused) owner.focusedTab = "Query" }
                    .focusProperties { left = owner.searchRequester; right = closeTarget }
                    .graphicsLayer { alpha = reveal.value; translationX = (1f - reveal.value) * 12.dp.toPx() })
                DiscoveryAction("Close search", Icons.Default.Close, iconOnly = true, focusHint = false,
                    modifier = Modifier.target("Close search", closeTarget, left = queryTarget), onClick = ::close)
            } else {
                StableTileFocusViewport {
                    Row(Modifier.weight(1f, fill = false).graphicsLayer { alpha = 1f - reveal.value }
                        .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        items.forEachIndexed { index, item ->
                            DiscoveryAction(item.label, item.icon, selected = item.selected,
                                modifier = item.modifier.target(item.label, targets[index],
                                    left = targets.getOrNull(index - 1) ?: if (openSearch != null) owner.searchRequester else backTarget,
                                    right = targets.getOrNull(index + 1) ?: optionsTarget)
                                    .semantics { contentDescription = item.label; role = Role.Tab; selected = item.selected }, onClick = item.click)
                        }
                    }
                }
                options(Modifier.target("Options", optionsTarget, left = targets.lastOrNull() ?: backTarget))
            }
        }
    }
}
