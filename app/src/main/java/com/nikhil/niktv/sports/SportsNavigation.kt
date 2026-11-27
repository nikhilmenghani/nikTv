package com.nikhil.niktv.sports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.discovery.PortalOptionsButton
import com.nikhil.niktv.discovery.PortalOptionsMenu
import com.nikhil.niktv.discovery.PortalPhoneDock
import com.nikhil.niktv.discovery.PortalPhoneHeader
import com.nikhil.niktv.discovery.PortalWideHeader
import com.nikhil.niktv.discovery.PortalWideNavigation
import com.nikhil.niktv.discovery.PortalMenuItem
import com.nikhil.niktv.discovery.PortalSearchField
import com.nikhil.niktv.discovery.PortalSearchActivation
import com.nikhil.niktv.discovery.discoveryTabIndicator
import com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout
import com.nikhil.niktv.ui.isTvLikeDevice
import com.nikhil.niktv.ui.NikDropdownMenuItem
import com.nikhil.niktv.ui.initialFocusEntry
import kotlinx.coroutines.launch

internal data class SportsHeaderAction(val label: String, val icon: ImageVector,
    val selected: Boolean = false, val onClick: () -> Unit)

/** Keep fixed-size actions outside the title's space on every Sports destination. */
@Composable
internal fun SportsHeader(title: String, back: () -> Unit, actions: List<SportsHeaderAction>,
    backFocus: FocusRequester, menuFocus: FocusRequester, downFocus: FocusRequester? = null,
    menuItems: List<PortalMenuItem>, query: String = "", setQuery: (String) -> Unit = {},
    searchFocus: FocusRequester = remember { FocusRequester() }, closeSearch: (() -> Unit)? = null,
    searchPlaceholder: String = "Find a team or channel") {
    val phone = sportsPhoneLayout()
    val viewer = com.nikhil.niktv.ui.LocalSelectedUserProfile.current
    val openProfiles = com.nikhil.niktv.ui.LocalOpenUserProfiles.current
    val firstAction = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    var searchActivation by remember { mutableIntStateOf(0) }
    val searchActivationState = remember { PortalSearchActivation() }
    val searchAction = actions.firstOrNull { it.label.startsWith("Search ") }
    fun close(action: (() -> Unit)? = null) {
        // Establish the surviving origin before replacing the popup with a dialog.
        runCatching { menuFocus.requestFocus() }
        action?.invoke()
        expanded = false
    }
    @Composable fun options(modifier: Modifier = Modifier) {
        Box {
            PortalOptionsButton(if (title == "Playfy TV") "Playfy options" else "Sports options",
                selected = actions.any { it.label == "Categories" && it.selected },
                modifier = modifier.focusRequester(menuFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && downFocus != null) {
                            runCatching { downFocus.requestFocus() }; true
                        } else false
                    }, open = { expanded = true })
            PortalOptionsMenu(expanded, { close() },
                modifier = Modifier.initialFocusEntry(firstAction)) {
                actions.filter { phone || it !== searchAction }.forEachIndexed { index, action ->
                    NikDropdownMenuItem(text = { Text(action.label, color = Color.White) },
                        leadingIcon = { Icon(action.icon, null, tint = Color.White) },
                        trailingIcon = { if (action.selected) Icon(Icons.Default.Check, null, tint = Color.White) },
                        modifier = Modifier.then(if (index == 0) Modifier.focusRequester(firstAction) else Modifier)
                            .semantics { selected = action.selected },
                        onClick = { close(action.onClick) })
                }
            }
        }
    }
    if (!phone) {
        PortalWideNavigation(viewer?.name.orEmpty(), openProfiles, back, menuItems,
            backModifier = Modifier.focusRequester(backFocus), searchOpen = searchAction?.selected == true,
            openSearch = searchAction?.let { action -> { searchActivation++; if (!action.selected) action.onClick() } },
            closeSearch = { closeSearch?.invoke() ?: searchAction?.onClick?.invoke() },
            query = { modifier -> PortalSearchField(query, setQuery, searchPlaceholder,
                modifier.focusRequester(searchFocus), androidx.compose.ui.text.input.KeyboardType.Text,
                activationRequest = searchActivation, compact = true, activationState = searchActivationState, submit = {}) },
            options = { options(it) })
    } else {
        PortalPhoneHeader {
            viewer?.let { profile ->
                DiscoveryAction("Switch profile: ${profile.name}", visualLabel = profile.avatar.ifBlank { profile.name.take(1).uppercase() },
                    containerColor = Color(profile.color), horizontalPadding = 0.dp,
                    modifier = Modifier.size(48.dp).focusRequester(backFocus).semantics { contentDescription = "Switch profile: ${profile.name}" },
                    onClick = openProfiles)
            }
            Text(title, Modifier.weight(1f).semantics { contentDescription = "Sports page title" },
                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            options()
        }
    }
}

@Composable
internal fun sportsPhoneLayout(): Boolean {
    val configuration = LocalConfiguration.current
    return usesDiscoveryPhoneLayout(configuration, LocalContext.current.isTvLikeDevice(configuration))
}

internal val SportsTab.navigationIcon
    get() = when (this) {
        SportsTab.EVENTS -> Icons.Default.LiveTv
        SportsTab.CHANNELS -> Icons.Default.Sports
        SportsTab.CATEGORIES -> Icons.Default.Category
        SportsTab.HIGHLIGHTS -> Icons.Default.VideoLibrary
    }

/** Matches the IPTV phone dock; tablets and televisions keep navigation in the header. */
@Composable
internal fun SportsBottomNavigation(tab: SportsTab, select: (SportsTab) -> Unit,
    requesters: Map<SportsTab, FocusRequester>, content: FocusRequester? = null, modifier: Modifier = Modifier,
    portalEntryFocus: FocusRequester? = null) {
    PortalPhoneDock(modifier) {
        sportsMainTabs.forEach { destination ->
            val active = destination == tab
            var focused by remember(destination) { mutableStateOf(false) }
            Surface(onClick = { select(destination) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).focusRequester(requesters.getValue(destination))
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionUp, Key.DirectionDown) && content != null) {
                            runCatching { content.requestFocus() }; true
                        } else false
                    }
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { selected = active; contentDescription = destination.title }
                    .discoveryTabIndicator(active, Color(0xFFFF6778)),
                shape = RoundedCornerShape(8.dp), color = if (active) Color(0x663D3E45) else Color.Transparent,
                border = BorderStroke(2.dp, if (focused) Color.White else Color.Transparent),
                contentColor = if (active) Color.White else SportsMuted) {
                Column(Modifier.padding(horizontal = 2.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(destination.navigationIcon, null, Modifier.size(22.dp), tint = if (active) Color(0xFFFF6778) else SportsMuted)
                    Text(destination.title, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        if (com.nikhil.niktv.discovery.LocalDirectPortalActions.current.size > 1) SportsPortalMenu(Modifier.weight(1f), portalEntryFocus)
    }
}

@Composable
private fun SportsPortalMenu(modifier: Modifier, entryFocus: FocusRequester?) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val portals = com.nikhil.niktv.discovery.LocalDirectPortalActions.current
    val ownTrigger = remember { FocusRequester() }
    val trigger = entryFocus ?: ownTrigger
    val selectedItem = remember { FocusRequester() }
    val selectedId = portals.firstOrNull { it.selected }?.id ?: portals.firstOrNull()?.id
    fun close() {
        runCatching { trigger.requestFocus() }
        expanded = false
    }
    Box(modifier) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .focusRequester(trigger).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Portals" },
            shape = RoundedCornerShape(8.dp), color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(2.dp, if (focused) Color.White else Color.Transparent)) {
            Column(Modifier.padding(horizontal = 2.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Default.Subscriptions, null, Modifier.size(22.dp), tint = Color(0xFFB1B1B9))
                Text("Portals", fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFFB1B1B9), fontWeight = FontWeight.Medium)
            }
        }
        DropdownMenu(expanded, ::close, containerColor = Color(0xEE171B24), shape = RoundedCornerShape(10.dp),
            modifier = Modifier.initialFocusEntry(selectedItem)) {
            portals.forEach { portal -> key(portal.id) { NikDropdownMenuItem(text = { Text(portal.name) },
                leadingIcon = { Icon(portal.icon, null) },
                trailingIcon = { if (portal.selected) Icon(Icons.Default.Check, "Selected portal") },
                modifier = (if (portal.id == selectedId) Modifier.focusRequester(selectedItem) else Modifier)
                    .semantics { selected = portal.selected },
                onClick = { close(); if (!portal.selected) portal.open() }) } }
        }
    }
}

@Composable
internal fun SportsDropdown(label: String, value: String, options: List<Pair<String, String>>,
    select: (String) -> Unit, modifier: Modifier = Modifier, valueOnly: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val trigger = remember { FocusRequester() }
    val selectedItem = remember { FocusRequester() }
    val portalFocus = com.nikhil.niktv.discovery.LocalPortalFocusReturn.current
    val selectedKey = options.firstOrNull { it.first == value || it.second == value }?.first ?: options.firstOrNull()?.first
    fun close(key: String? = null) {
        portalFocus.cancel()
        runCatching { trigger.requestFocus() }
        expanded = false
        key?.let(select)
    }
    Box(modifier) {
        Surface(onClick = { portalFocus.cancel(); expanded = true }, shape = SportsShape,
            color = if (focused) Color.White else Color(0xFF242831).copy(alpha = .85f),
            contentColor = if (focused) Color.Black else Color.White,
            border = androidx.compose.foundation.BorderStroke(if (focused) 2.dp else 1.dp,
                if (focused) Color.White else Color(0xFF454B58)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).focusRequester(trigger).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "$label: $value" }) {
            Row(Modifier.padding(horizontal = if (valueOnly) 8.dp else 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (valueOnly) value else "$label: $value", Modifier.weight(1f), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded, { close() }, containerColor = SportsSurface,
            modifier = if (expanded) Modifier.initialFocusEntry(selectedItem) else Modifier) {
            options.forEach { (key, title) ->
                val active = key == value || title == value
                DropdownMenuItem(text = { Text(title, color = Color.White) },
                    modifier = Modifier.then(if (key == selectedKey) Modifier.focusRequester(selectedItem) else Modifier)
                        .semantics { selected = active },
                    trailingIcon = { if (active) Icon(Icons.Default.Check, null, tint = Color.White) },
                    onClick = { close(key) })
            }
        }
    }
}
