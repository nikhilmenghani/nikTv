package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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

internal enum class DiscoveryLibraryDestination { LIVE_TV, MY_LIST }

/** Reuse the catalogue/queue owners; only navigation chrome belongs to discovery. */
@Composable
internal fun DiscoveryLibraryScreen(state: NikTvState, destination: DiscoveryLibraryDestination,
    back: () -> Unit, select: (DashboardSurface) -> Unit, navigate: (String) -> Unit,
    presentation: DiscoveryCard? = null, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val tv = LocalContext.current.isTvLikeDevice(configuration)
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, tv)
    val tab = remember(destination) { FocusRequester() }
    val body = remember(destination) { FocusRequester() }
    var menu by remember(destination) { mutableStateOf(false) }
    val remote = tv || LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(destination, remote) {
        if (remote) repeat(8) {
            withFrameNanos { }
            if (runCatching { tab.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
    DiscoveryBackdrop(presentation, android.provider.Settings.Global.getFloat(LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f)
    CompositionLocalProvider(LocalLibraryTab provides tab) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (phone) DiscoveryPhoneNavigation(DashboardSurface.HOME, tab, body, back, select, navigate,
            menu, { menu = it }, library = destination)
        else DiscoveryTopNavigation(DashboardSurface.HOME, state.savedProfile?.name.orEmpty(), tab, body,
            back, select, navigate, {}, menu, { menu = it }, null, library = destination)
        Box(Modifier.weight(1f).fillMaxWidth().focusRequester(body).focusGroup()) { content() }
    }
    }
    }
}

/** Keep secondary overlays and detail routes out of the library navigation shell. */
internal fun discoveryLibraryDestination(enabled: Boolean, home: Boolean, type: CatalogType,
    favorites: Boolean, overlay: Boolean, detail: Boolean): DiscoveryLibraryDestination? = when {
    !enabled || overlay || detail -> null
    favorites -> DiscoveryLibraryDestination.MY_LIST
    !home && type == CatalogType.LIVE_TV -> DiscoveryLibraryDestination.LIVE_TV
    else -> null
}
