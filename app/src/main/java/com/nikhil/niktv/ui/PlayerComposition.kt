package com.nikhil.niktv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType

/** Route remote Back before a native video child can consume it to drop focus. */
@Composable
internal fun Modifier.playerRemoteBack(controlsVisible: Boolean): Modifier {
    val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var ownsBackPress by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { event ->
        if ((controlsVisible || ownsBackPress) && dispatcher != null && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && !ownsBackPress) {
                ownsBackPress = true
                dispatcher.onBackPressed()
            } else if (event.type == KeyEventType.KeyUp) ownsBackPress = false
            true
        } else false
    }
}

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
internal fun rememberPlayerControlsTransition(visible: Boolean): MutableTransitionState<Boolean> =
    remember { MutableTransitionState(visible) }.apply { targetState = visible }

@Composable
internal fun BoxScope.PlayerControlsLayer(
    transition: MutableTransitionState<Boolean>,
    content: @Composable BoxScope.() -> Unit
) {
    AnimatedVisibility(
        visibleState = transition,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(240, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(200, easing = FastOutSlowInEasing))
    ) {
        Box(Modifier.fillMaxSize().focusProperties { canFocus = transition.targetState }, content = content)
    }
}

@Composable
internal fun BoxScope.PlayerOverlayLayer(content: @Composable BoxScope.() -> Unit) {
    content()
}
