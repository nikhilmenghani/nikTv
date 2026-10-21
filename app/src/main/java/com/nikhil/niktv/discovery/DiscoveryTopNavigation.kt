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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.R
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.ui.NikTvSecondaryActionButton
import com.nikhil.niktv.ui.NikDropdownMenuItem
import com.nikhil.niktv.ui.StableTileFocusViewport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

@Composable
internal fun DiscoveryBrowseMenu(expanded: Boolean, change: (Boolean) -> Unit,
    surface: DashboardSurface, classic: (String) -> Unit, customize: (() -> Unit)?) {
    val setExperience = LocalSetDiscoveryExperience.current
    Box {
        DiscoveryAction("Browse menu", Icons.Default.MoreHoriz, iconOnly = true) { change(true) }
        DropdownMenu(expanded, { change(false) }, containerColor = Color(0xFF202020), shape = RoundedCornerShape(12.dp)) {
            NikDropdownMenuItem(text = { Text("Use original UI") }, onClick = { change(false); setExperience(false) })
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
    headerFocus: () -> Unit, menu: Boolean, changeMenu: (Boolean) -> Unit, customize: (() -> Unit)?,
    backdrop: String? = null, backdropBounds: Rect = Rect.Zero,
    categoryManagerOpen: Boolean = false, categoriesEnabled: Boolean = false,
    categoryPopupChanged: (Boolean) -> Unit = {}) {
    val tabs = rememberScrollState()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
    fun textWidth(text: String) = with(density) { measurer.measure(text, style, maxLines = 1).size.width.toDp() }
    val naturalProfile = textWidth("00:00 p.m.") + 68.dp
    val height = maxOf(56.dp, (24f * density.fontScale + 24f).dp)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
    val widths = discoveryHeaderWidths(maxWidth.value, maxWidth.value, naturalProfile.value)
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        DiscoveryGlassPanel(Modifier.widthIn(max = widths.menu.dp).height(height), backdrop, backdropBounds) {
            CompositionLocalProvider(LocalDiscoveryGlassButtons provides true) {
            Row(Modifier.fillMaxHeight().padding(5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV", Modifier.fillMaxSize()
                        .graphicsLayer { scaleX = 1.8f; scaleY = 1.8f })
                }
                DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = back)
                DiscoveryAction("Search", Icons.Default.Search, horizontalPadding = 10.dp, onFocus = headerFocus) { classic("Search") }
                StableTileFocusViewport {
                    Row(Modifier.weight(1f, fill = false).horizontalScroll(tabs), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        trialSurfaces.forEach { tab ->
                            DiscoveryAction(tab.trialTitle(), visualLabel = tab.trialTitle().removeSuffix(" 2"),
                                selected = surface == tab, horizontalPadding = 12.dp,
                                modifier = Modifier.semantics { contentDescription = tab.trialTitle() }
                                    .then(if (tab == surface) Modifier.focusRequester(tabRequester).focusProperties { down = playRequester } else Modifier),
                                onFocus = headerFocus) { select(tab) }
                        }
                        DiscoveryAction("Live TV", horizontalPadding = 12.dp, onFocus = headerFocus) { classic("Live TV") }
                        DiscoveryAction("My List", horizontalPadding = 12.dp, onFocus = headerFocus) { classic("My List") }
                    }
                }
                if (categoriesEnabled) DiscoveryCategoryAccess(surface, categoryManagerOpen, popupChanged = categoryPopupChanged)
                DiscoveryBrowseMenu(menu, changeMenu, surface, classic, customize)
            }
            }
        }
        Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
        DiscoveryClockProfile(profile, Modifier.width(widths.profile.dp).height(height), backdrop, backdropBounds) { classic("Profile") }
    }
    }
}

@Composable
private fun DiscoveryClockProfile(profile: String, modifier: Modifier, backdrop: String?, backdropBounds: Rect, open: () -> Unit) {
    val context = LocalContext.current
    val time by produceState(DateFormat.getTimeFormat(context).format(Date())) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = DateFormat.getTimeFormat(context).format(Date())
        }
    }
    var focused by remember { mutableStateOf(false) }
    DiscoveryGlassPanel(modifier, backdrop, backdropBounds, focused) {
    Surface(onClick = open, modifier = Modifier.fillMaxSize().onFocusChanged { focused = it.isFocused }
        .semantics { contentDescription = "Profile: $profile" },
        shape = RoundedCornerShape(10.dp), color = Color.Transparent) {
        Row(Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).background(Color(0xFFE50925), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(profile.firstOrNull()?.uppercase() ?: "N", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    }
}

@Composable
internal fun DiscoveryPhoneNavigation(surface: DashboardSurface, tab: FocusRequester, play: FocusRequester,
    back: () -> Unit, select: (DashboardSurface) -> Unit, classic: (String) -> Unit,
    menu: Boolean, changeMenu: (Boolean) -> Unit,
    categoryManagerOpen: Boolean = false, categoriesEnabled: Boolean = false,
    categoryPopupChanged: (Boolean) -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = back)
            Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV", Modifier.size(36.dp))
            Spacer(Modifier.weight(1f))
            DiscoveryAction("Search", Icons.Default.Search, iconOnly = true) { classic("Search") }
            if (categoriesEnabled) DiscoveryCategoryAccess(surface, categoryManagerOpen, compact = true, popupChanged = categoryPopupChanged)
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

/** A direct entry to the same profile/type-scoped selections used by classic browsing. */
@Composable
internal fun DiscoveryCategoryAccess(surface: DashboardSurface, managerOpen: Boolean, compact: Boolean = false,
    popupChanged: (Boolean) -> Unit = {}) {
    val configure = LocalConfigureProviderDiscovery.current
    val requester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var chooseType by remember { mutableStateOf(false) }
    var openedHere by remember { mutableStateOf(false) }
    var managerSeen by remember { mutableStateOf(false) }
    fun restore() { scope.launch {
        repeat(30) {
            withFrameNanos { }
            if (view.hasWindowFocus() && runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
        }
    } }
    LaunchedEffect(managerOpen) {
        if (openedHere && managerOpen) managerSeen = true
        if (openedHere && managerSeen && !managerOpen) {
            openedHere = false; managerSeen = false; restore()
        }
    }
    DisposableEffect(chooseType) { popupChanged(chooseType); onDispose { popupChanged(false) } }
    DiscoveryAction("IPTV categories", Icons.Default.Tune, iconOnly = compact, visualLabel = "Categories",
        modifier = Modifier.focusRequester(requester)) {
        val type = when (surface) { DashboardSurface.MOVIES -> CatalogType.MOVIES; DashboardSurface.SERIES -> CatalogType.SERIES; else -> null }
        if (type == null) chooseType = true else { openedHere = true; configure(type) }
    }
    if (chooseType) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
        fun dismiss() { chooseType = false; restore() }
        AlertDialog(onDismissRequest = ::dismiss, containerColor = Color(0xFF15171D),
            shape = RoundedCornerShape(16.dp), title = { Text("IPTV categories") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose which categories appear on your screens.", color = Color.LightGray)
                    listOf(CatalogType.MOVIES, CatalogType.SERIES, CatalogType.LIVE_TV).forEachIndexed { index, type ->
                        NikTvSecondaryActionButton(onClick = { chooseType = false; openedHere = true; configure(type) },
                            modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier)) {
                            Icon(when (type) { CatalogType.MOVIES -> Icons.Default.Movie; CatalogType.SERIES -> Icons.Default.VideoLibrary; else -> Icons.Default.LiveTv }, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp)); Text(type.title)
                        }
                    }
                }
            }, confirmButton = { NikTvSecondaryActionButton(onClick = ::dismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") } })
    }
}
