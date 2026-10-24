package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.ui.NikTvState
import com.nikhil.niktv.ui.isTvLikeDevice

internal val LocalDiscoverySearchHeaderFocus = staticCompositionLocalOf<FocusRequester?> { null }
internal val LocalDiscoverySearchQueryFocus = staticCompositionLocalOf<FocusRequester?> { null }
internal val LocalDiscoverySearchHeaderFocused = staticCompositionLocalOf { false }

/** Search keeps its existing data owner and editing policy inside discovery navigation. */
@Composable
internal fun DiscoverySearchScreen(
    state: NikTvState,
    back: () -> Unit,
    select: (DashboardSurface) -> Unit,
    navigate: (String) -> Unit,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp,
        LocalContext.current.isTvLikeDevice(configuration))
    val header = remember(state.savedProfile?.cacheKey()) { FocusRequester() }
    val body = remember(state.savedProfile?.cacheKey()) { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }
    val surface = state.discoverySurface ?: DashboardSurface.HOME
    CompositionLocalProvider(LocalDiscoverySearchHeaderFocus provides header, LocalDiscoverySearchQueryFocus provides body,
        LocalDiscoverySearchHeaderFocused provides headerFocused) {
        Column(Modifier.fillMaxSize().background(Color(0xFF090B10))
            .windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.fillMaxWidth().onFocusChanged { headerFocused = it.hasFocus }.focusGroup()) {
            if (phone) DiscoveryPhoneNavigation(surface, header, body, back, select, navigate,
                menu, { menu = it }, profile = state.savedProfile?.name.orEmpty(), searchActive = true)
            else DiscoveryTopNavigation(surface, state.savedProfile?.name.orEmpty(), header, body,
                back, select, navigate, {}, menu, { menu = it }, null, searchActive = true)
            }
            // Search owns initial focus. The header only supplies an Up/Down path;
            // it must not steal query focus or activate the software keyboard.
            Box(Modifier.weight(1f).fillMaxWidth().focusGroup()) { content() }
        }
    }
}

/** Follow main-pane precedence when another overlay is retained above Search. */
internal fun usesDiscoverySearchShell(enabled: Boolean, searchOpen: Boolean,
    settingsOpen: Boolean, downloadsOpen: Boolean, matchesOpen: Boolean): Boolean =
    enabled && searchOpen && !settingsOpen && !downloadsOpen && !matchesOpen
