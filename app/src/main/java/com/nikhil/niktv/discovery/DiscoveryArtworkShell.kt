package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Reserve control space without moving the artwork below the glass navigation. */
internal val LocalHeroNavigationInset = staticCompositionLocalOf { 0.dp }

@Composable
internal fun DiscoveryArtworkShell(overlay: Boolean, phone: Boolean,
    header: @Composable () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    var inset by remember(phone) { mutableStateOf(if (phone) 64.dp else 80.dp) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var artwork by remember { mutableStateOf<MobileGlassSource?>(null) }
    val publish = remember { { value: MobileGlassSource? -> artwork = value } }
    Box(Modifier.fillMaxSize().background(if (overlay) Color(0xFF090B10) else Color.Transparent).safeDrawingPadding().onGloballyPositioned { viewportTop = it.positionInWindow().y }) {
        if (overlay) {
            CompositionLocalProvider(LocalHeroNavigationInset provides inset,
                LocalPublishMobileGlassSource provides publish) { content() }
            // Only the chrome observes artwork publication; row models remain independent.
            CompositionLocalProvider(LocalMobileGlassArtwork provides artwork?.artwork,
                LocalMobileGlassBounds provides (artwork?.bounds ?: androidx.compose.ui.geometry.Rect.Zero),
                LocalMobileGlassFit provides (artwork?.fit ?: false),
                LocalMobileGlassTopAligned provides (artwork?.topAligned ?: false),
                LocalMobileGlassFillWidth provides (artwork?.fillWidth ?: false)) {
                Box(Modifier.fillMaxWidth().background(
                    if (artwork?.bounds?.top?.let { it < viewportTop - 1f } == true) Color(0xFF090B10) else Color.Transparent
                ).onSizeChanged { inset = with(density) { it.height.toDp() } }) { header() }
            }
        } else Column(Modifier.fillMaxSize()) {
            header()
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}
