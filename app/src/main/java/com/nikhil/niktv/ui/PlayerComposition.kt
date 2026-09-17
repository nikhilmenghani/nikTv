package com.nikhil.niktv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

private val LocalPlayerTvDevice = staticCompositionLocalOf<Boolean?> { null }

@Composable
internal fun rememberPlayerTvDevice(): Boolean {
    LocalPlayerTvDevice.current?.let { return it }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration.uiMode) { context.isTvLikeDevice(configuration) }
}

// Follow the active input mode so touch interaction does not inherit D-pad highlights.
@Composable
internal fun playerUsesDirectionalInput(): Boolean =
    rememberPlayerTvDevice() || LocalInputModeManager.current.inputMode == InputMode.Keyboard

// Deliberately non-inline: each layer gets its own generated Compose method.
// Inlining the entire player into Box exceeded ART's method compilation limit
// on Fire TV. Animation is confined to controls, never the video surface.
@Composable
internal fun PlayerSurfaceHost(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    // Resolve hardware capabilities once, rather than querying PackageManager
    // for every control and again for every control's focus modifier.
    val isTv = rememberPlayerTvDevice()
    CompositionLocalProvider(LocalPlayerTvDevice provides isTv) {
        Box(modifier = modifier, content = content)
    }
}

@Composable
internal fun BoxScope.PlayerVideoLayer(content: @Composable BoxScope.() -> Unit) {
    content()
}

@Composable
internal fun BoxScope.PlayerControlsLayer(
    visible: Boolean,
    content: @Composable BoxScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(240, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(200, easing = FastOutSlowInEasing))
    ) {
        Box(Modifier.fillMaxSize().focusProperties { canFocus = visible }, content = content)
    }
}

@Composable
internal fun PlayerExtraControls(visible: Boolean, content: @Composable RowScope.() -> Unit) {
    // Keep the focus-ring clearance in the layout even while the actions are hidden.
    // TV actions are 56dp; touch actions are at most 48dp, with 4dp clearance per edge.
    Box(
        modifier = Modifier.height(if (rememberPlayerTvDevice()) 64.dp else 56.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(240)) + expandHorizontally(tween(320, easing = FastOutSlowInEasing), expandFrom = Alignment.End),
        exit = fadeOut(tween(200)) + shrinkHorizontally(tween(280, easing = FastOutSlowInEasing), shrinkTowards = Alignment.End)
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp).focusProperties { canFocus = visible },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
    }
}

internal fun Modifier.playerSecondaryFocus(
    requester: FocusRequester,
    order: List<FocusRequester>,
    toggle: FocusRequester,
    playback: FocusRequester,
    up: FocusRequester
): Modifier {
    val index = order.indexOf(requester)
    val previous = order.getOrNull(index - 1) ?: playback
    val next = order.getOrNull(index + 1) ?: toggle
    return focusRequester(requester)
        .focusProperties {
            left = previous
            right = next
            this.up = up
        }
        .playerDpadFocusRoutes(left = previous, right = next, up = up)
}

@Composable
internal fun BoxScope.PlayerOverlayLayer(content: @Composable BoxScope.() -> Unit) {
    content()
}
