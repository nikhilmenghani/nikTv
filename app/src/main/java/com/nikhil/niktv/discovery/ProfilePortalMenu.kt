package com.nikhil.niktv.discovery

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.delay
import java.util.Date

/** One stable profile target; portal choices use space only while explicitly opened. */
@Composable
internal fun ProfilePortalMenu(profile: String, modifier: Modifier, backdrop: String?, bounds: Rect) {
    val context = LocalContext.current
    val viewer = LocalSelectedUserProfile.current
    val name = viewer?.name ?: profile
    val avatar = viewer?.avatar?.ifBlank { name.take(1).uppercase() } ?: name.take(1).ifBlank { "N" }
    val openProfiles = LocalOpenUserProfiles.current
    val actions = LocalDirectPortalActions.current
    val returning = LocalPortalFocusReturn.current
    val avatarFocus = remember { FocusRequester() }
    // Existing portal-entry owners (including cross-activity handoffs) target
    // the collapsed profile button instead of a now-unmounted portal icon.
    val portalEntries = actions.fold<DirectPortalAction, Modifier>(Modifier) { result, action -> result.focusRequester(returning.requester(action.id)) }
    val headerOwner = remember { Any() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var expanded by remember { mutableStateOf(false) }
    val selected = actions.filter { it.selected }.map { it.id }
    val pending = returning.pendingId
    val routeOwned = returning.routeOwned
    fun restorePortalFocus() {
        if (!expanded && windowFocused && !returning.routeOwned && returning.pendingId in selected)
            returning.restore()
    }
    DisposableEffect(returning) {
        returning.registerHeader(headerOwner, avatarFocus)
        onDispose { returning.unregisterHeader(headerOwner) }
    }
    SideEffect { restorePortalFocus() }
    LaunchedEffect(pending, selected, routeOwned, windowFocused) { restorePortalFocus() }
    val time by produceState(DateFormat.getTimeFormat(context).format(Date())) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = DateFormat.getTimeFormat(context).format(Date())
        }
    }
    BoxWithConstraints(modifier) {
        val short = LocalConfiguration.current.usesShortWideLayout(context.isTvLikeDevice(LocalConfiguration.current))
        val showClock = !short && maxWidth >= discoveryProfileHeaderWidth(clock = true)
        DiscoveryGlassPanel(Modifier.fillMaxSize(), backdrop, bounds,
            fitArtwork = backdrop != null && LocalMobileGlassFit.current,
            topAligned = backdrop != null && LocalMobileGlassTopAligned.current,
            fillWidth = backdrop != null && LocalMobileGlassFillWidth.current) {
            Row(Modifier.fillMaxSize().padding(5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showClock) Text(time, Modifier.weight(1f), color = Color.White, fontSize = 12.sp,
                    maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                CompositionLocalProvider(LocalDiscoveryGlassButtons provides true, LocalDiscoveryHeaderActionSizing provides true) {
                    DiscoveryAction("Profile and portals", visualLabel = avatar, focusHint = false,
                        selected = expanded, containerColor = viewer?.let { Color(it.color) } ?: Color(0xFFE50925),
                        horizontalPadding = 0.dp, modifier = Modifier.size(discoveryHeaderActionSize())
                            .then(portalEntries).focusRequester(avatarFocus).onPlaced { restorePortalFocus() }
                            .semantics { contentDescription = "Profile and portals: $name" },
                        onClick = { returning.cancel(); expanded = !expanded })
                }
            }
        }
        if (expanded) {
            val entry = remember { FocusRequester() }
            val initial = actions.firstOrNull { it.selected }?.id
            fun close() { expanded = false }
            val configuration = LocalConfiguration.current
            val availableHeight = (configuration.screenHeightDp.dp - (if (short) 46.dp else 56.dp) - 40.dp).coerceAtLeast(120.dp)
            PortalOptionsMenu(true, ::close, Modifier.widthIn(min = 200.dp, max = 288.dp).heightIn(max = availableHeight).initialFocusEntry(entry)) {
                Text(name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall, color = Color.White)
                actions.forEach { action -> key(action.id) {
                    NikDropdownMenuItem(text = { Text(action.name) },
                        modifier = if (action.id == initial) Modifier.focusRequester(entry) else Modifier,
                        leadingIcon = { Icon(action.icon, null, Modifier.size(20.dp)) },
                        trailingIcon = if (action.selected) ({ Icon(Icons.Default.Check, "Current portal", Modifier.size(18.dp)) }) else null,
                        onClick = {
                            if (action.selected || action.opensNewWindow) returning.cancel() else returning.clicked(action.id)
                            close(); action.open()
                        })
                } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = .12f))
                NikDropdownMenuItem(text = { Text("Switch profile") },
                    modifier = if (initial == null) Modifier.focusRequester(entry) else Modifier,
                    leadingIcon = { Icon(Icons.Default.ManageAccounts, null, Modifier.size(20.dp)) },
                    onClick = { returning.cancel(); close(); openProfiles() })
            }
        }
    }
}
