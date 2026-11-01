package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*

internal enum class DiscoveryLibraryDestination { LIVE_TV, MY_LIST, PROVIDERS }

internal class DiscoveryLibraryOptions(val live: Boolean)
internal val LocalDiscoveryLibraryOptions = staticCompositionLocalOf<DiscoveryLibraryOptions?> { null }

/** Reuse the catalogue/queue owners; only navigation chrome belongs to discovery. */
@Composable
internal fun DiscoveryLibraryScreen(state: NikTvState, destination: DiscoveryLibraryDestination,
    back: () -> Unit, select: (DashboardSurface) -> Unit, navigate: (String) -> Unit,
    presentation: DiscoveryCard? = null, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val tv = LocalContext.current.isTvLikeDevice(configuration)
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, tv)
    var glassSource by remember(destination, state.savedProfile?.cacheKey()) { mutableStateOf<MobileGlassSource?>(null) }
    val publishGlass: (MobileGlassSource?) -> Unit = remember(destination, state.savedProfile?.cacheKey()) { { glassSource = it } }
    val tab = remember(destination) { FocusRequester() }
    val body = remember(destination) { FocusRequester() }
    val options = remember(destination) { DiscoveryLibraryOptions(destination == DiscoveryLibraryDestination.LIVE_TV) }
    var menu by remember(destination) { mutableStateOf(false) }
    val remote = tv || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(destination, remote) {
        if (remote && state.modernIptvCategory == null) repeat(8) {
            withFrameNanos { }
            if (runCatching { tab.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
    if (destination == DiscoveryLibraryDestination.LIVE_TV) DiscoveryBackdrop(presentation, android.provider.Settings.Global.getFloat(LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f)
    CompositionLocalProvider(LocalLibraryTab provides tab, LocalDiscoveryLibraryOptions provides options, LocalMobileGlassArtwork provides glassSource?.artwork,
        LocalMobileGlassBounds provides (glassSource?.bounds ?: androidx.compose.ui.geometry.Rect.Zero),
        LocalMobileGlassFit provides (glassSource?.fit ?: false), LocalMobileGlassTopAligned provides (glassSource?.topAligned ?: false),
        LocalMobileGlassFillWidth provides (glassSource?.fillWidth ?: false), LocalPublishMobileGlassSource provides publishGlass) {
    val phoneHero = phone && destination == DiscoveryLibraryDestination.MY_LIST &&
        libraryFavoriteHeroes(state.favorites, state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey().orEmpty()).isNotEmpty()
    if (phoneHero) Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxSize().focusRequester(body).focusGroup()) { content() }
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(
            listOf(Color(0xE6090B10), Color(0x99090B10), Color.Transparent)))) {
            DiscoveryPhoneNavigation(DashboardSurface.HOME, tab, body, back, select, navigate,
                menu, { menu = it }, library = destination, profile = state.savedProfile?.name.orEmpty())
        }
    } else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (phone) DiscoveryPhoneNavigation(DashboardSurface.HOME, tab, body, back, select, navigate,
            menu, { menu = it }, library = destination, profile = state.savedProfile?.name.orEmpty())
        else DiscoveryTopNavigation(DashboardSurface.HOME, state.savedProfile?.name.orEmpty(), tab, body,
            back, select, navigate, {}, menu, { menu = it }, null, library = destination)
        Box(Modifier.weight(1f).fillMaxWidth().focusRequester(body).focusGroup()) { content() }
    }
    }
    }
}

/** Keep secondary overlays and detail routes out of the library navigation shell. */
internal fun discoveryLibraryDestination(home: Boolean, type: CatalogType,
    favorites: Boolean, overlay: Boolean, detail: Boolean,
    discoveryPageVisible: Boolean = false): DiscoveryLibraryDestination? = when {
    overlay || detail || discoveryPageVisible -> null
    favorites -> DiscoveryLibraryDestination.MY_LIST
    !home && type == CatalogType.LIVE_TV -> DiscoveryLibraryDestination.LIVE_TV
    else -> null
}

/** Established Live TV categories/collections stay inside the modern navigation shell. */
internal fun useEstablishedLibraryBrowser(destination: DiscoveryLibraryDestination?, phone: Boolean): Boolean =
    destination == DiscoveryLibraryDestination.LIVE_TV || (phone && destination != null)
