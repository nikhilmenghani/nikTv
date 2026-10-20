package com.nikhil.niktv.discovery

import android.text.format.DateFormat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.R
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.ui.NikDropdownMenuItem
import com.nikhil.niktv.ui.StableTileFocusViewport
import kotlinx.coroutines.delay
import java.util.Date

@Composable
internal fun DiscoveryBrowseMenu(expanded: Boolean, change: (Boolean) -> Unit,
    surface: DashboardSurface, classic: (String) -> Unit, customize: (() -> Unit)?) {
    Box {
        DiscoveryAction("Browse menu", Icons.Default.MoreHoriz, iconOnly = true) { change(true) }
        DropdownMenu(expanded, { change(false) }, containerColor = Color(0xFF202020), shape = RoundedCornerShape(12.dp)) {
            listOf("Home", "Live TV", "Movies", "Series", "My List", "Offline", "Settings", "Profile").forEach { label ->
                NikDropdownMenuItem(text = { Text(label) }, onClick = { change(false); classic(label) })
            }
            customize?.let { action -> NikDropdownMenuItem(text = { Text("Customize ${surface.trialTitle()}") }, onClick = { change(false); action() }) }
        }
    }
}

/** Compact navigation with the app's rounded-rectangle styling. */
@Composable
internal fun DiscoveryTopNavigation(surface: DashboardSurface, profile: String,
    tabRequester: FocusRequester, playRequester: FocusRequester,
    back: () -> Unit, select: (DashboardSurface) -> Unit, classic: (String) -> Unit,
    headerFocus: () -> Unit, menu: Boolean, changeMenu: (Boolean) -> Unit, customize: (() -> Unit)?) {
    val tabs = rememberLazyListState()
    LaunchedEffect(surface) { tabs.scrollToItem(trialSurfaces.indexOf(surface).coerceAtLeast(0)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(10.dp), color = Color(0xFF171B24).copy(alpha = .88f),
            border = BorderStroke(1.dp, Color(0xFF454B58))) {
            Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = back)
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV", Modifier.size(36.dp))
                }
                DiscoveryAction("Search", Icons.Default.Search, horizontalPadding = 10.dp, onFocus = headerFocus) { classic("Search") }
                StableTileFocusViewport {
                    LazyRow(Modifier.weight(1f), state = tabs, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        items(trialSurfaces) { tab ->
                            DiscoveryAction(tab.trialTitle(), visualLabel = tab.trialTitle().removeSuffix(" 2"),
                                selected = surface == tab, horizontalPadding = 12.dp,
                                modifier = Modifier.semantics { contentDescription = tab.trialTitle() }
                                    .then(if (tab == surface) Modifier.focusRequester(tabRequester).focusProperties { down = playRequester } else Modifier),
                                onFocus = headerFocus) { select(tab) }
                        }
                        item("live") { DiscoveryAction("Live TV", horizontalPadding = 12.dp, onFocus = headerFocus) { classic("Live TV") } }
                        item("list") { DiscoveryAction("My List", horizontalPadding = 12.dp, onFocus = headerFocus) { classic("My List") } }
                    }
                }
                DiscoveryBrowseMenu(menu, changeMenu, surface, classic, customize)
            }
        }
        DiscoveryClockProfile(profile) { classic("Profile") }
    }
}

@Composable
private fun DiscoveryClockProfile(profile: String, open: () -> Unit) {
    val context = LocalContext.current
    val time by produceState(DateFormat.getTimeFormat(context).format(Date())) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = DateFormat.getTimeFormat(context).format(Date())
        }
    }
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = open, modifier = Modifier.onFocusChanged { focused = it.isFocused }
        .semantics { contentDescription = "Profile: $profile" },
        shape = RoundedCornerShape(10.dp), color = Color(0xFF171B24).copy(alpha = .88f),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF454B58))) {
        Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, color = Color.LightGray, fontSize = 12.sp)
            Box(Modifier.size(34.dp).background(Color(0xFFE50925), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(profile.firstOrNull()?.uppercase() ?: "N", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun DiscoveryPhoneNavigation(surface: DashboardSurface, tab: FocusRequester, play: FocusRequester,
    back: () -> Unit, select: (DashboardSurface) -> Unit, classic: (String) -> Unit,
    menu: Boolean, changeMenu: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = back)
            Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV", Modifier.size(36.dp))
            Spacer(Modifier.weight(1f))
            DiscoveryAction("Search", Icons.Default.Search, iconOnly = true) { classic("Search") }
            DiscoveryBrowseMenu(menu, changeMenu, surface, classic, null)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            trialSurfaces.forEach { entry ->
                DiscoveryAction(entry.trialTitle(), visualLabel = entry.trialTitle().removeSuffix(" 2"),
                    selected = entry == surface, horizontalPadding = 4.dp,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .then(if (entry == surface) Modifier.focusRequester(tab).focusProperties { down = play } else Modifier),
                    onClick = { select(entry) })
            }
            DiscoveryAction("Live TV", horizontalPadding = 4.dp, modifier = Modifier.weight(1f)) { classic("Live TV") }
        }
    }
}
