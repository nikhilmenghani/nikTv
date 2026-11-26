package com.nikhil.niktv.discovery

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.ui.isTvLikeDevice
import com.nikhil.niktv.ui.NikTvSecondaryActionButton
import com.nikhil.niktv.ui.NikDropdownMenuItem
import com.nikhil.niktv.ui.StableTileFocusViewport
import com.nikhil.niktv.ui.usesShortWideLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

@Composable
internal fun DiscoveryBrowseMenu(expanded: Boolean, change: (Boolean) -> Unit,
    surface: DashboardSurface, navigate: (String) -> Unit, customize: (() -> Unit)?,
    categoryManagerOpen: Boolean = false, categoryPopupChanged: (Boolean) -> Unit = {}) {
    val openLanPlayback = com.nikhil.niktv.ui.LocalOpenLanPlayback.current
    val configure = LocalConfigureProviderDiscovery.current
    val isExperiment = LocalDiscoveryExperiment.current
    val options = LocalDiscoveryLibraryOptions.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val phone = usesDiscoveryPhoneLayout(configuration, LocalContext.current.isTvLikeDevice(configuration))
    var categoryTrigger by remember { mutableIntStateOf(0) }
    if ((phone || isExperiment) && surface != DashboardSurface.HOME && options?.live != true) DiscoveryCategoryAccess(surface, categoryManagerOpen, popupChanged = categoryPopupChanged, showButton = false, openTrigger = categoryTrigger)
    Box {
        PortalOptionsButton("Options") { change(true) }
        PortalOptionsMenu(expanded, { change(false) }) {
            if (options?.live == true) NikDropdownMenuItem(text = { Text("IPTV categories") },
                onClick = { change(false); configure(CatalogType.LIVE_TV) })
            if ((phone || isExperiment) && surface != DashboardSurface.HOME && options?.live != true) NikDropdownMenuItem(text = { Text("IPTV categories") },
                onClick = { change(false); categoryTrigger++ })
            customize?.let { action -> NikDropdownMenuItem(text = { Text("TMDB categories") },
                onClick = { change(false); action() }) }
            NikDropdownMenuItem(text = { Text("Play on NikTV") }, onClick = { change(false); openLanPlayback() })
            NikDropdownMenuItem(text = { Text("Settings") }, onClick = { change(false); navigate("Settings") })
        }
    }
}

internal class SharedDiscoveryHeader {
    var focusedTab: String? = null
    val content = movableContentOf<DiscoveryHeaderContent> { header ->
        with(header) {
            DiscoveryTopNavigationLayout(surface, profile, tabRequester, playRequester, back, select, navigate,
                headerFocus, menu, changeMenu, customize, categoryManagerOpen,
                categoriesEnabled, categoryPopupChanged, library, searchActive, this@SharedDiscoveryHeader)
        }
    }
}

internal val LocalSharedDiscoveryHeader = staticCompositionLocalOf<SharedDiscoveryHeader?> { null }

/** Selection semantics and focus are immediate. Only the small drawn mark animates. */
@Composable
internal fun Modifier.discoveryTabIndicator(active: Boolean, color: Color): Modifier {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val television = context.isTvLikeDevice(configuration)
    // TV selection is immediate. Skip the transition state and draw pass on hidden marks.
    if (television && !active) return this
    val opacity = if (television) null else animateFloatAsState(if (active) 1f else 0f, tween(120), label = "discoveryTabIndicator")
    return drawWithContent {
        drawContent()
        val alpha = opacity?.value ?: 1f
        if (alpha > 0f) {
            val width = 18.dp.toPx()
            val thickness = 2.dp.toPx()
            drawRoundRect(color.copy(alpha = color.alpha * alpha),
                topLeft = Offset((size.width - width) / 2, size.height - 5.dp.toPx()),
                size = Size(width, thickness), cornerRadius = CornerRadius(thickness / 2))
        }
    }
}

internal data class DiscoveryHeaderContent(
    val surface: DashboardSurface, val profile: String, val tabRequester: FocusRequester, val playRequester: FocusRequester,
    val back: () -> Unit, val select: (DashboardSurface) -> Unit, val navigate: (String) -> Unit,
    val headerFocus: () -> Unit, val menu: Boolean, val changeMenu: (Boolean) -> Unit, val customize: (() -> Unit)?,
    val categoryManagerOpen: Boolean, val categoriesEnabled: Boolean,
    val categoryPopupChanged: (Boolean) -> Unit, val library: DiscoveryLibraryDestination?, val searchActive: Boolean
)

/** Compact navigation with the app's rounded-rectangle styling. */
@Composable
internal fun DiscoveryTopNavigation(surface: DashboardSurface, profile: String,
    tabRequester: FocusRequester, playRequester: FocusRequester,
    back: () -> Unit, select: (DashboardSurface) -> Unit, navigate: (String) -> Unit,
    headerFocus: () -> Unit, menu: Boolean, changeMenu: (Boolean) -> Unit, customize: (() -> Unit)?,
    categoryManagerOpen: Boolean = false, categoriesEnabled: Boolean = false,
    categoryPopupChanged: (Boolean) -> Unit = {},
    library: DiscoveryLibraryDestination? = null, searchActive: Boolean = false, shareAcrossTabs: Boolean = false) {
    val shared = LocalSharedDiscoveryHeader.current.takeIf { shareAcrossTabs }
    if (shared != null) shared.content(DiscoveryHeaderContent(surface, profile, tabRequester, playRequester,
        back, select, navigate, headerFocus, menu, changeMenu, customize,
        categoryManagerOpen, categoriesEnabled, categoryPopupChanged, library, searchActive))
    else DiscoveryTopNavigationLayout(surface, profile, tabRequester, playRequester, back, select, navigate, headerFocus,
        menu, changeMenu, customize, categoryManagerOpen, categoriesEnabled, categoryPopupChanged,
        library, searchActive)
}

// Let the prepared scene show through the glass without loading a second copy of the artwork.
@Composable
private fun DiscoveryTopNavigationLayout(surface: DashboardSurface, profile: String,
    tabRequester: FocusRequester, playRequester: FocusRequester,
    back: () -> Unit, select: (DashboardSurface) -> Unit, navigate: (String) -> Unit,
    headerFocus: () -> Unit, menu: Boolean, changeMenu: (Boolean) -> Unit, customize: (() -> Unit)?,
    categoryManagerOpen: Boolean, categoriesEnabled: Boolean,
    categoryPopupChanged: (Boolean) -> Unit, library: DiscoveryLibraryDestination?, searchActive: Boolean,
    shared: SharedDiscoveryHeader? = null) {
    val experiment = LocalDiscoveryExperiment.current
    val tabs = rememberScrollState()
    val tabTargets = remember { List(trialSurfaces.size + 2) { FocusRequester() } }
    PortalWideHeader(profile, { navigate("Profile") }, Modifier.discoveryHeaderFocusEntry(tabRequester)) {
        DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, focusHint = false, onClick = back)
        DiscoveryAction("Search", Icons.Default.Search, iconOnly = true, selected = searchActive, horizontalPadding = 10.dp,
            focusHint = false,
            modifier = if (searchActive) Modifier.focusRequester(tabRequester).focusProperties { down = playRequester } else Modifier,
            onFocus = headerFocus) { navigate("Search") }
        StableTileFocusViewport {
            Row(Modifier.weight(1f, fill = false).horizontalScroll(tabs).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                fun tabFocus(index: Int) = Modifier.focusRequester(tabTargets[index])
                    .focusProperties {
                        start = tabTargets.getOrNull(index - 1) ?: FocusRequester.Default
                        end = tabTargets.getOrNull(index + 1) ?: FocusRequester.Default
                    }
                trialSurfaces.forEachIndexed { index, tab ->
                    val active = !searchActive && library == null && surface == tab
                    val icon = when (tab) {
                        DashboardSurface.HOME -> Icons.Default.Home
                        DashboardSurface.MOVIES -> Icons.Default.Movie
                        DashboardSurface.SERIES -> Icons.Default.Tv
                        DashboardSurface.LIVE_TV -> Icons.Default.LiveTv
                    }
                    DiscoveryAction(tab.trialTitle(), icon, visualLabel = tab.trialTitle().removeSuffix(" 2"),
                        selected = active, horizontalPadding = 10.dp,
                        modifier = tabFocus(index).discoveryNavigationTab(tab.trialTitle(), active, shared, tabRequester, playRequester),
                        onFocus = headerFocus) { if (experiment) navigate(tab.trialTitle()) else select(tab) }
                }
                DiscoveryAction("Live TV", Icons.Default.LiveTv, selected = library == DiscoveryLibraryDestination.LIVE_TV, horizontalPadding = 10.dp,
                    modifier = tabFocus(tabTargets.lastIndex - 1)
                        .discoveryNavigationTab("Live TV", library == DiscoveryLibraryDestination.LIVE_TV, shared, tabRequester, playRequester),
                    onFocus = headerFocus) { navigate("Live TV") }
                DiscoveryAction("My List", Icons.Default.VideoLibrary, selected = library == DiscoveryLibraryDestination.MY_LIST, horizontalPadding = 10.dp,
                    modifier = tabFocus(tabTargets.lastIndex)
                        .discoveryNavigationTab("My List", library == DiscoveryLibraryDestination.MY_LIST, shared, tabRequester, playRequester),
                    onFocus = headerFocus) { navigate("My List") }
            }
        }
        DiscoveryBrowseMenu(menu, changeMenu, surface, navigate, customize)
    }
}

@Composable
private fun Modifier.discoveryNavigationTab(label: String, active: Boolean, shared: SharedDiscoveryHeader?,
    tabRequester: FocusRequester, playRequester: FocusRequester): Modifier {
    val inactiveRequester = remember { FocusRequester() }
    return semantics { contentDescription = label; selected = active; role = Role.Tab }
        .onFocusChanged { focus ->
            if (focus.isFocused) shared?.focusedTab = label
            else if (shared?.focusedTab == label) shared.focusedTab = null
        }
        .focusRequester(if (active) tabRequester else inactiveRequester)
        .focusProperties { down = if (active) playRequester else FocusRequester.Default }
}

@Composable
internal fun DiscoveryClockProfile(profile: String, modifier: Modifier, backdrop: String?, backdropBounds: Rect, openPortals: () -> Unit) {
    val context = LocalContext.current
    val openUserProfiles = com.nikhil.niktv.ui.LocalOpenUserProfiles.current
    val viewer = com.nikhil.niktv.ui.LocalSelectedUserProfile.current
    val viewerName = viewer?.name ?: profile
    val viewerAvatar = viewer?.avatar?.ifBlank { viewer.name.take(1).uppercase() }
        ?: profile.firstOrNull()?.uppercase() ?: "N"
    val time by produceState(DateFormat.getTimeFormat(context).format(Date())) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = DateFormat.getTimeFormat(context).format(Date())
        }
    }
    val portalFocusReturn = LocalPortalFocusReturn.current
    BoxWithConstraints(modifier) {
        val shortWide = LocalConfiguration.current.usesShortWideLayout(context.isTvLikeDevice(LocalConfiguration.current))
        val portalWidth = portalHeaderWidth()
        val showPortals = portalWidth > 0.dp
        LaunchedEffect(showPortals) { if (!showPortals) portalFocusReturn.cancel() }
        val clockWidth = discoveryProfileHeaderWidth(clock = true)
        val showClock = !shortWide && with(LocalDensity.current) { maxWidth.roundToPx() >= clockWidth.roundToPx() }
        val personWidth = if (showClock) (124f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp else if (shortWide) 46.dp else 56.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            if (showPortals) DiscoveryGlassPanel(Modifier.width(portalWidth).fillMaxHeight(), backdrop, backdropBounds,
                fitArtwork = backdrop != null && LocalMobileGlassFit.current, topAligned = backdrop != null && LocalMobileGlassTopAligned.current,
                fillWidth = backdrop != null && LocalMobileGlassFillWidth.current) {
                Box(Modifier.fillMaxSize().padding(5.dp), contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalDiscoveryGlassButtons provides true, LocalDiscoveryHeaderActionSizing provides true) {
                        DirectPortalButtons()
                    }
                }
            }
            DiscoveryGlassPanel(Modifier.width(personWidth).fillMaxHeight(), backdrop, backdropBounds,
                fitArtwork = backdrop != null && LocalMobileGlassFit.current, topAligned = backdrop != null && LocalMobileGlassTopAligned.current,
                fillWidth = backdrop != null && LocalMobileGlassFillWidth.current) {
                Row(Modifier.fillMaxSize().padding(5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showClock) Text(time, color = Color.White, fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    CompositionLocalProvider(LocalDiscoveryGlassButtons provides true, LocalDiscoveryHeaderActionSizing provides true) {
                        DiscoveryAction("Profile: $viewerName", visualLabel = viewerAvatar,
                            containerColor = viewer?.let { Color(it.color) } ?: Color(0xFFE50925), horizontalPadding = 0.dp,
                            modifier = Modifier.size(discoveryHeaderActionSize())
                                .semantics { contentDescription = "Switch profile: $viewerName" },
                            onClick = { portalFocusReturn.cancel(); openUserProfiles() })
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiscoveryPhoneNavigation(surface: DashboardSurface, tab: FocusRequester, play: FocusRequester,
    back: () -> Unit, select: (DashboardSurface) -> Unit, navigate: (String) -> Unit,
    menu: Boolean, changeMenu: (Boolean) -> Unit,
    categoryManagerOpen: Boolean = false, categoriesEnabled: Boolean = false,
    categoryPopupChanged: (Boolean) -> Unit = {},
    library: DiscoveryLibraryDestination? = null, profile: String = "", searchActive: Boolean = false) {
    DiscoveryPhoneHeader(surface, false, tab, play, back, select, navigate, menu, changeMenu,
        customize = null, profile = profile, categoryManagerOpen = categoryManagerOpen,
        categoryPopupChanged = categoryPopupChanged, searchActive = searchActive)

}

/** Category configuration uses the same profile/type-scoped selections as the modern catalog. */
@Composable
internal fun DiscoveryCategoryAccess(surface: DashboardSurface, managerOpen: Boolean, compact: Boolean = false,
    popupChanged: (Boolean) -> Unit = {}, onFocus: () -> Unit = {}, modifier: Modifier = Modifier,
    showButton: Boolean = true, openTrigger: Int = 0) {
    val configure = LocalConfigureProviderDiscovery.current
    val requester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var chooseType by remember { mutableStateOf(false) }
    var openedHere by remember { mutableStateOf(false) }
    var managerSeen by remember { mutableStateOf(false) }
    fun restore() { if (!showButton) return; scope.launch {
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
    fun openCategories() {
        val type = when (surface) { DashboardSurface.MOVIES -> CatalogType.MOVIES; DashboardSurface.SERIES -> CatalogType.SERIES; else -> null }
        if (type == null) chooseType = true else { openedHere = true; configure(type) }
    }
    LaunchedEffect(openTrigger) { if (openTrigger > 0) openCategories() }
    if (showButton) DiscoveryAction("IPTV categories", if (compact || LocalCollapsingDiscoveryActions.current) Icons.Default.Tune else null, iconOnly = compact,
        modifier = modifier.focusRequester(requester), onFocus = onFocus) {
        openCategories()
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
