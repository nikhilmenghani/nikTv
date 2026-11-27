package com.nikhil.niktv.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.ui.NikTvState
import com.nikhil.niktv.ui.isTvLikeDevice

internal val LocalInlineSearchHeader = staticCompositionLocalOf<(@Composable (@Composable () -> Unit) -> Unit)?> { null }
internal val LocalSuppressDiscoveryHeader = staticCompositionLocalOf { false }

internal val LocalDiscoverySearchHeaderFocus = staticCompositionLocalOf<FocusRequester?> { null }
internal val LocalDiscoverySearchQueryFocus = staticCompositionLocalOf<FocusRequester?> { null }
internal val LocalDiscoverySearchHeaderFocused = staticCompositionLocalOf { false }

/** Search keeps its existing data owner and editing policy inside discovery navigation. */
@Composable
internal fun DiscoverySearchScreen(state: NikTvState, back: () -> Unit,
    select: (DashboardSurface) -> Unit, navigate: (String) -> Unit,
    content: @Composable () -> Unit) = DiscoverySearchScreen(state, back, select, navigate, underlay = {}, content = content)

@Composable
internal fun DiscoverySearchScreen(
    state: NikTvState,
    back: () -> Unit,
    select: (DashboardSurface) -> Unit,
    navigate: (String) -> Unit,
    underlay: @Composable () -> Unit, content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val phone = usesDiscoveryPhoneLayout(configuration,
        LocalContext.current.isTvLikeDevice(configuration))
    val header = remember(state.savedProfile?.cacheKey()) { FocusRequester() }
    val body = remember(state.savedProfile?.cacheKey()) { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }
    val surface = state.discoverySurface ?: DashboardSurface.HOME
    val shared = LocalSharedDiscoveryHeader.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(200)) }
    val closeSearch: () -> Unit = {
        if (!closing) {
            closing = true
            keyboard?.hide()
            shared?.focusedTab = "Search"
            shared?.searchRequester?.let { runCatching { it.requestFocus() } }
            scope.launch {
                if (!phone) reveal.animateTo(0f, tween(180))
                back()
            }
        }
    }
    BackHandler(!phone) { closeSearch() }
    val inlineHeader: (@Composable (@Composable () -> Unit) -> Unit)? = if (phone) null else ({ query ->
        CompositionLocalProvider(LocalInlineSearchQuery provides query) {
            DiscoveryTopNavigation(surface, state.savedProfile?.displayName.orEmpty(), header, body,
                closeSearch, select, { if (it == "Search") closeSearch() else navigate(it) }, {}, menu, { menu = it }, null, searchActive = true, shareAcrossTabs = true)
        }
    })
    CompositionLocalProvider(LocalDiscoverySearchHeaderFocus provides header, LocalDiscoverySearchQueryFocus provides body,
        LocalDiscoverySearchHeaderFocused provides headerFocused, LocalInlineSearchHeader provides inlineHeader,
        LocalInlineSearchClose provides closeSearch, LocalInlineSearchProgress provides if (phone) null else reveal.value) {
      Box(Modifier.fillMaxSize().background(Color(0xFF090B10))) {
        if (!phone) {
            Box(Modifier.fillMaxSize().focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()) {
                CompositionLocalProvider(LocalSuppressDiscoveryHeader provides true) { underlay() }
            }
            Box(Modifier.fillMaxSize().focusProperties { canFocus = false }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = closeSearch))
        }
        Column(Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)) {
          if (phone) {
            Box(Modifier.fillMaxWidth().onFocusChanged { headerFocused = it.hasFocus }.focusGroup()) {
            if (phone) DiscoveryPhoneNavigation(surface, header, body, back, select, navigate,
                menu, { menu = it }, profile = state.savedProfile?.displayName.orEmpty(), searchActive = true)
            else DiscoveryTopNavigation(surface, state.savedProfile?.displayName.orEmpty(), header, body,
                back, select, navigate, {}, menu, { menu = it }, null, searchActive = true)
            }
          }
            // Search owns initial focus. The header only supplies an Up/Down path;
            // it must not steal query focus or activate the software keyboard.
            Box(Modifier.weight(1f).fillMaxWidth().focusGroup()) { content() }
        }
      }
    }
}

/** Follow main-pane precedence when another overlay is retained above Search. */
internal fun usesDiscoverySearchShell(searchOpen: Boolean,
    settingsOpen: Boolean, downloadsOpen: Boolean, matchesOpen: Boolean): Boolean =
    searchOpen && !settingsOpen && !downloadsOpen && !matchesOpen
