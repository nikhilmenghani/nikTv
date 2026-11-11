package com.nikhil.niktv.sports

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.discovery.DiscoveryGlassPanel
import com.nikhil.niktv.discovery.usesDiscoveryPhoneLayout
import com.nikhil.niktv.ui.isTvLikeDevice
import com.nikhil.niktv.ui.NikDropdownMenuItem
import com.nikhil.niktv.ui.initialFocusEntry
import com.nikhil.niktv.R
import kotlinx.coroutines.launch

internal data class SportsHeaderAction(val label: String, val icon: ImageVector,
    val selected: Boolean = false, val onClick: () -> Unit)

/** Keep fixed-size actions outside the title's space on every Sports destination. */
@Composable
internal fun SportsHeader(title: String, back: () -> Unit, actions: List<SportsHeaderAction>,
    backFocus: FocusRequester, menuFocus: FocusRequester,
    navigation: @Composable (Modifier) -> Unit) {
    val phone = sportsPhoneLayout()
    val scope = rememberCoroutineScope()
    val firstAction = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    fun close(action: (() -> Unit)? = null) {
        expanded = false
        scope.launch {
            withFrameNanos { }
            runCatching { menuFocus.requestFocus() }
            action?.invoke()
        }
    }
    Surface(Modifier.fillMaxWidth().padding(top = if (phone) 8.dp else 10.dp), shape = SportsShape,
        color = if (phone) Color.Transparent else SportsSurface, contentColor = Color.White) {
        Row(Modifier.fillMaxWidth().padding(if (phone) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV",
                Modifier.size(42.dp).graphicsLayer { scaleX = if (phone) 2.4f else 1.6f; scaleY = if (phone) 2.4f else 1.6f })
            DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true,
                modifier = Modifier.size(48.dp).focusRequester(backFocus), onClick = back)
            if (phone) Text(title, Modifier.weight(1f).semantics { contentDescription = "Sports page title" },
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            else navigation(Modifier.weight(1f))
            Box {
                DiscoveryAction("Sports options", Icons.Default.MoreVert, iconOnly = true,
                    modifier = Modifier.size(48.dp).focusRequester(menuFocus), onClick = { expanded = true })
                DropdownMenu(expanded, { close() }, containerColor = SportsSurface,
                    modifier = Modifier.initialFocusEntry(firstAction)) {
                    actions.forEachIndexed { index, action ->
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
    }
}

@Composable
internal fun sportsPhoneLayout(): Boolean {
    val configuration = LocalConfiguration.current
    return usesDiscoveryPhoneLayout(configuration, LocalContext.current.isTvLikeDevice(configuration))
}

/** Matches the IPTV phone dock; tablets and televisions keep navigation in the header. */
@Composable
internal fun SportsBottomNavigation(tab: SportsTab, select: (SportsTab) -> Unit,
    requesters: Map<SportsTab, FocusRequester>, content: FocusRequester? = null, modifier: Modifier = Modifier) {
    DiscoveryGlassPanel(modifier.padding(vertical = 8.dp).fillMaxWidth(), artwork = null,
        backdropBounds = androidx.compose.ui.geometry.Rect.Zero) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SportsTab.entries.forEach { destination ->
                val active = destination == tab
                val icon = when (destination) {
                    SportsTab.EVENTS -> Icons.Default.LiveTv
                    SportsTab.CHANNELS -> Icons.Default.Sports
                    SportsTab.CATEGORIES -> Icons.Default.Category
                    SportsTab.HIGHLIGHTS -> Icons.Default.VideoLibrary
                }
                Surface(onClick = { select(destination) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).focusRequester(requesters.getValue(destination))
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionUp, Key.DirectionDown) && content != null) {
                                runCatching { content.requestFocus() }; true
                            } else false
                        }
                        .semantics { selected = active; contentDescription = destination.title },
                    shape = RoundedCornerShape(8.dp), color = if (active) Color(0x663D3E45) else Color.Transparent,
                    contentColor = if (active) Color.White else SportsMuted) {
                    Column(Modifier.padding(horizontal = 2.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(icon, null, Modifier.size(22.dp), tint = if (active) Color(0xFFFF6778) else SportsMuted)
                        Text(destination.title, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            SportsPortalMenu(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SportsPortalMenu(modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val portals = com.nikhil.niktv.discovery.LocalDirectPortalActions.current
    Box(modifier) {
        Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .semantics { contentDescription = "Portals" }, shape = RoundedCornerShape(8.dp), color = Color.Transparent) {
            Column(Modifier.padding(horizontal = 2.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Default.Subscriptions, null, Modifier.size(22.dp), tint = Color(0xFFB1B1B9))
                Text("Portals", fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFFB1B1B9), fontWeight = FontWeight.Medium)
            }
        }
        DropdownMenu(expanded, { expanded = false }, containerColor = Color(0xEE171B24), shape = RoundedCornerShape(10.dp)) {
            portals.forEach { portal -> NikDropdownMenuItem(text = { Text(portal.name) },
                leadingIcon = { Icon(portal.icon, null) },
                trailingIcon = { if (portal.selected) Icon(Icons.Default.Check, "Selected portal") },
                modifier = Modifier.semantics { selected = portal.selected },
                onClick = { expanded = false; portal.open() }) }
        }
    }
}

@Composable
internal fun SportsDropdown(label: String, value: String, options: List<Pair<String, String>>,
    select: (String) -> Unit, modifier: Modifier = Modifier, valueOnly: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(onClick = { expanded = true }, shape = SportsShape,
            color = if (focused) Color.White else Color(0xFF242831).copy(alpha = .85f),
            contentColor = if (focused) Color.Black else Color.White,
            border = androidx.compose.foundation.BorderStroke(if (focused) 2.dp else 1.dp,
                if (focused) Color.White else Color(0xFF454B58)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "$label: $value" }) {
            Row(Modifier.padding(horizontal = if (valueOnly) 8.dp else 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (valueOnly) value else "$label: $value", Modifier.weight(1f), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded, { expanded = false }, containerColor = SportsSurface) {
            options.forEach { (key, title) ->
                val active = key == value || title == value
                DropdownMenuItem(text = { Text(title, color = Color.White) },
                    modifier = Modifier.semantics { selected = active },
                    trailingIcon = { if (active) Icon(Icons.Default.Check, null, tint = Color.White) },
                    onClick = { expanded = false; select(key) })
            }
        }
    }
}
