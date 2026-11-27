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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
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
    categoryManagerOpen: Boolean = false, categoryPopupChanged: (Boolean) -> Unit = {}, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    val openLanPlayback = com.nikhil.niktv.ui.LocalOpenLanPlayback.current
    val configure = LocalConfigureProviderDiscovery.current
    val isExperiment = LocalDiscoveryExperiment.current
    val options = LocalDiscoveryLibraryOptions.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val phone = usesDiscoveryPhoneLayout(configuration, LocalContext.current.isTvLikeDevice(configuration))
    var categoryTrigger by remember { mutableIntStateOf(0) }
    if ((phone || isExperiment) && surface != DashboardSurface.HOME && options?.live != true) DiscoveryCategoryAccess(surface, categoryManagerOpen, popupChanged = categoryPopupChanged, showButton = false, openTrigger = categoryTrigger)
    Box {
        PortalOptionsButton("Options", modifier, onFocus = onFocus) { change(true) }
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
    var menuWidth by mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
    var focusedTab: String? = null
    val focusTargets = mutableMapOf<String, FocusRequester>()
    val searchRequester = FocusRequester()
    val closeSearchRequester = FocusRequester()
    var searchActivation by mutableIntStateOf(0)
    var consumedSearchActivation = 0
    fun entryRequester(fallback: FocusRequester): FocusRequester =
        if (focusedTab == "Search") searchRequester else focusTargets[focusedTab] ?: fallback
    val content = movableContentOf<DiscoveryHeaderContent> { header ->
        with(header) {
            DiscoveryTopNavigationLayout(surface, profile, tabRequester, playRequester, back, select, navigate,
                headerFocus, menu, changeMenu, customize, categoryManagerOpen,
                categoriesEnabled, categoryPopupChanged, library, searchActive, this@SharedDiscoveryHeader)
        }
    }
}

internal val LocalSharedDiscoveryHeader = staticCompositionLocalOf<SharedDiscoveryHeader?> { null }
internal val LocalInlineSearchQuery = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
internal val LocalInlineSearchClose = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalInlineSearchProgress = staticCompositionLocalOf<Float?> { null }

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
    if (LocalSuppressDiscoveryHeader.current) { Spacer(Modifier.height(80.dp)); return }
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
    val query = LocalInlineSearchQuery.current.takeIf { searchActive }
    var menuVisible by remember { mutableStateOf(query == null) }
    LaunchedEffect(query != null) { menuVisible = query == null }
    val menuReveal = animateFloatAsState(if (menuVisible) 1f else 0f, tween(180), label = "menuReturnReveal")
    val searchReveal = LocalInlineSearchProgress.current ?: 1f
    val searchOwner = LocalSharedDiscoveryHeader.current
    val stableSearch = searchOwner?.searchRequester ?: remember { FocusRequester() }
    val backTarget = remember { FocusRequester() }
    val optionsTarget = remember { FocusRequester() }
    searchOwner?.focusTargets?.apply {
        put("Back", backTarget); put("Options", optionsTarget)
        (trialSurfaces.map { it.trialTitle() } + listOf("Live TV", "My List")).forEachIndexed { index, label -> put(label, tabTargets[index]) }
    }
    LaunchedEffect(query == null) {
        // The movable header crosses the search shell. Restore its surviving
        // origin after attachment, without entering through Back or Home.
        if (query == null && searchOwner?.focusedTab == "Search") stableSearch.requestFocus()
    }
    PortalWideHeader(profile, { navigate("Profile") },
        Modifier.discoveryHeaderFocusEntry(if (searchOwner?.focusedTab == "Search") stableSearch else tabRequester,
            enabled = query == null, selectedNow = { searchOwner?.entryRequester(tabRequester) ?: tabRequester }), unified = true, attachedSearch = query != null) {
        if (query == null) DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, focusHint = false,
            modifier = Modifier.focusRequester(backTarget).focusProperties { right = stableSearch },
            onFocus = { searchOwner?.focusedTab = "Back"; headerFocus() }, onClick = back)
        DiscoveryAction("Search", Icons.Default.Search, iconOnly = true, selected = searchActive, horizontalPadding = 10.dp,
            focusHint = false, modifier = Modifier.focusRequester(stableSearch).focusProperties {
                left = if (query == null) backTarget else FocusRequester.Cancel
                right = if (query == null) tabTargets.first() else playRequester
            },
            onFocus = { shared?.focusedTab = "Search"; headerFocus() }) {
                searchOwner?.let { it.searchActivation++ }
                if (query == null) navigate("Search")
            }
        if (query != null) {
            Box(Modifier.weight(1f).graphicsLayer { alpha = searchReveal; translationX = (1f - searchReveal) * 16.dp.toPx() }) { query() }
        } else {
        StableTileFocusViewport {
            Row(Modifier.weight(1f, fill = false).graphicsLayer { alpha = menuReveal.value; translationX = -(1f - menuReveal.value) * 16.dp.toPx() }.horizontalScroll(tabs).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                fun tabFocus(index: Int) = Modifier.focusRequester(tabTargets[index])
                    .focusProperties {
                        start = tabTargets.getOrNull(index - 1) ?: stableSearch
                        left = tabTargets.getOrNull(index - 1) ?: stableSearch
                        end = tabTargets.getOrNull(index + 1) ?: optionsTarget
                        right = tabTargets.getOrNull(index + 1) ?: optionsTarget
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                            val target = if (event.key == Key.DirectionLeft) tabTargets.getOrNull(index - 1) ?: stableSearch
                                else tabTargets.getOrNull(index + 1) ?: optionsTarget
                            if (event.type == KeyEventType.KeyDown) target.requestFocus()
                            true
                        } else false
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
                        onFocus = headerFocus) { shared?.focusedTab = tab.trialTitle(); if (experiment) navigate(tab.trialTitle()) else select(tab) }
                }
                DiscoveryAction("Live TV", Icons.Default.LiveTv, selected = library == DiscoveryLibraryDestination.LIVE_TV, horizontalPadding = 10.dp,
                    modifier = tabFocus(tabTargets.lastIndex - 1)
                        .discoveryNavigationTab("Live TV", library == DiscoveryLibraryDestination.LIVE_TV, shared, tabRequester, playRequester),
                    onFocus = headerFocus) { shared?.focusedTab = "Live TV"; navigate("Live TV") }
                DiscoveryAction("My List", Icons.Default.VideoLibrary, selected = library == DiscoveryLibraryDestination.MY_LIST, horizontalPadding = 10.dp,
                    modifier = tabFocus(tabTargets.lastIndex)
                        .discoveryNavigationTab("My List", library == DiscoveryLibraryDestination.MY_LIST, shared, tabRequester, playRequester),
                    onFocus = headerFocus) { shared?.focusedTab = "My List"; navigate("My List") }
            }
        }
        DiscoveryBrowseMenu(menu, changeMenu, surface, navigate, customize,
            modifier = Modifier.focusRequester(optionsTarget).focusProperties { left = tabTargets.last() },
            onFocus = { searchOwner?.focusedTab = "Options"; headerFocus() })
        }
    }
}

@Composable
private fun Modifier.discoveryNavigationTab(label: String, active: Boolean, shared: SharedDiscoveryHeader?,
    tabRequester: FocusRequester, playRequester: FocusRequester): Modifier {
    return semantics { contentDescription = label; selected = active; role = Role.Tab }
        .onFocusChanged { focus ->
            if (focus.isFocused) shared?.focusedTab = label
        }
        .then(if (active) Modifier.focusRequester(tabRequester) else Modifier)
        .focusProperties { down = if (active) playRequester else FocusRequester.Default }
}

@Composable
internal fun DiscoveryClockProfile(profile: String, modifier: Modifier, backdrop: String?, backdropBounds: Rect, openPortals: () -> Unit) {
    ProfilePortalMenu(profile, modifier, backdrop, backdropBounds)
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
