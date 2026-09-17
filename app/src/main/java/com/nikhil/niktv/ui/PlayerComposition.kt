package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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

// Deliberately non-inline: each layer gets its own generated Compose method.
// Inlining the entire player into Box exceeded ART's method compilation limit
// on Fire TV. These boundaries add no extra layout nodes or focus targets.
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
    if (visible) content()
}

@Composable
internal fun BoxScope.PlayerOverlayLayer(content: @Composable BoxScope.() -> Unit) {
    content()
}
