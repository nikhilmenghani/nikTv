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
    val phone = usesDiscoveryPhoneLayout(configuration, tv)
    val trialCollection = state.discoveryExperiment && state.discoverySurface != null && state.modernTmdbSection != null
    val navigationSurface = if (trialCollection) requireNotNull(state.discoverySurface) else DashboardSurface.HOME
    val navigationLibrary = destination.takeUnless { trialCollection }
    var glassSource by remember(destination, state.savedProfile?.cacheKey()) { mutableStateOf<MobileGlassSource?>(null) }
    val publishGlass: (MobileGlassSource?) -> Unit = remember(destination, state.savedProfile?.cacheKey()) { { glassSource = it } }
    val tab = remember(destination) { FocusRequester() }
    val body = remember(destination) { FocusRequester() }
    val options = remember(destination) { DiscoveryLibraryOptions(destination == DiscoveryLibraryDestination.LIVE_TV) }
    var menu by remember(destination) { mutableStateOf(false) }
    val remote = tv || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(destination, remote, trialCollection) {
        if (remote && !trialCollection && state.modernIptvCategory == null) repeat(8) {
            withFrameNanos { }
            if (runCatching { tab.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
    if (destination == DiscoveryLibraryDestination.LIVE_TV) DiscoveryBackdrop(presentation, android.provider.Settings.Global.getFloat(LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f)
    CompositionLocalProvider(LocalDiscoveryExperiment provides (LocalDiscoveryExperiment.current || trialCollection),
        LocalLibraryTab provides tab, LocalDiscoveryLibraryOptions provides options, LocalMobileGlassArtwork provides glassSource?.artwork,
        LocalMobileGlassBounds provides (glassSource?.bounds ?: androidx.compose.ui.geometry.Rect.Zero),
        LocalMobileGlassFit provides (glassSource?.fit ?: false), LocalMobileGlassTopAligned provides (glassSource?.topAligned ?: false),
        LocalMobileGlassFillWidth provides (glassSource?.fillWidth ?: false), LocalPublishMobileGlassSource provides publishGlass) {
    val artworkHero = !trialCollection && destination == DiscoveryLibraryDestination.MY_LIST &&
        libraryFavoriteHeroes(state.favorites, state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey().orEmpty()).isNotEmpty()
    DiscoveryArtworkShell(artworkHero, phone, header = {
        if (phone) DiscoveryPhoneNavigation(navigationSurface, tab, body, back, select, navigate,
            menu, { menu = it }, library = navigationLibrary, profile = state.savedProfile?.name.orEmpty())
        else DiscoveryTopNavigation(navigationSurface, state.savedProfile?.name.orEmpty(), tab, body,
            back, select, navigate, {}, menu, { menu = it }, null, library = navigationLibrary)
    }, content = {
        Box(Modifier.fillMaxSize().focusRequester(body).focusGroup()) { content() }
    })
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

/** Live TV keeps its category browser; My List shares Home's hero and media rails. */
internal fun useEstablishedLibraryBrowser(destination: DiscoveryLibraryDestination?, phone: Boolean): Boolean =
    destination == DiscoveryLibraryDestination.LIVE_TV
