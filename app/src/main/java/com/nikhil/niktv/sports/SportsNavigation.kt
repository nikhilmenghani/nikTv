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
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp), shape = SportsShape,
        color = SportsSurface, contentColor = Color.White) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV",
                Modifier.size(if (phone) 32.dp else 42.dp).graphicsLayer { scaleX = 1.6f; scaleY = 1.6f })
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
    return usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, LocalContext.current.isTvLikeDevice(configuration))
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
        }
    }
}

@Composable
internal fun SportsDropdown(label: String, value: String, options: List<Pair<String, String>>,
    select: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        DiscoveryAction("$label: $value", Icons.Default.ExpandMore, modifier = Modifier.fillMaxWidth(), onClick = { expanded = true })
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
