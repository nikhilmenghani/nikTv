package com.nikhil.niktv.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Reserve control space without moving the artwork below the glass navigation. */
internal val LocalHeroNavigationInset = staticCompositionLocalOf { 0.dp }
internal val LocalDiscoverySceneArtwork = staticCompositionLocalOf { false }
internal val LocalDiscoverySceneBounds = staticCompositionLocalOf { Rect.Zero }

@Composable
internal fun DiscoveryArtworkShell(overlay: Boolean, phone: Boolean,
    backdropBehindRows: Boolean = false, reduceMotion: Boolean = false, initialSceneArtwork: String? = null,
    header: @Composable () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    var inset by remember(phone) { mutableStateOf(if (phone) 64.dp else 80.dp) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportBounds by remember { mutableStateOf(Rect.Zero) }
    var artwork by remember { mutableStateOf(initialSceneArtwork?.let { MobileGlassSource(it, Rect.Zero) }) }
    val artworkBounds = remember { mutableStateOf(Rect.Zero) }
    val movingBounds = remember { { artworkBounds.value } }
    val publish = remember { { value: MobileGlassSource? ->
        artworkBounds.value = value?.bounds ?: Rect.Zero
        artwork = value?.copy(bounds = Rect.Zero)
    } }
    Box(Modifier.fillMaxSize().background(if (overlay) Color(0xFF090B10) else Color.Transparent).safeDrawingPadding().onGloballyPositioned {
        viewportTop = it.positionInWindow().y
        viewportBounds = it.boundsInWindow()
    }) {
        if (overlay) {
            // Match Previous Home: one prepared backdrop behind the hero and
            // activity rows, independent of the recycled feed's composition.
            if (backdropBehindRows) DiscoveryBackdropArtwork(artwork?.artwork, reduceMotion)
            CompositionLocalProvider(LocalHeroNavigationInset provides inset,
                LocalDiscoverySceneArtwork provides backdropBehindRows,
                LocalDiscoverySceneBounds provides viewportBounds,
                LocalPublishMobileGlassSource provides publish) { content() }
            // Only the chrome observes artwork publication; row models remain independent.
            CompositionLocalProvider(LocalMobileGlassArtwork provides artwork?.artwork,
                LocalMobileGlassBounds provides (if (backdropBehindRows) viewportBounds else Rect.Zero),
                LocalMobileGlassMovingBounds provides if (backdropBehindRows) null else movingBounds,
                LocalMobileGlassFit provides (!backdropBehindRows && artwork?.fit == true),
                LocalMobileGlassTopAligned provides (!backdropBehindRows && artwork?.topAligned == true),
                LocalMobileGlassFillWidth provides (!backdropBehindRows && artwork?.fillWidth == true)) {
                Box(Modifier.fillMaxWidth().drawBehind {
                    val bounds = artworkBounds.value
                    if (!backdropBehindRows && !bounds.isEmpty &&
                        (if (phone) bounds.bottom <= viewportTop + inset.toPx() else bounds.top < viewportTop - 1f)) {
                        drawRect(Color(0xFF090B10))
                    }
                }.onSizeChanged { inset = with(density) { it.height.toDp() } }) { header() }
            }
        } else Column(Modifier.fillMaxSize()) {
            header()
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}
