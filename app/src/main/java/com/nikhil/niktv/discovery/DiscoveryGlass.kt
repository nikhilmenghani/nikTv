package com.nikhil.niktv.discovery

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transformations
import com.nikhil.niktv.data.thumbnailArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MobileGlassSource(val artwork: String?, val bounds: Rect,
    val fit: Boolean = false, val topAligned: Boolean = false, val fillWidth: Boolean = false)
internal val LocalPublishMobileGlassSource = staticCompositionLocalOf<(MobileGlassSource?) -> Unit> { {} }
internal val LocalMobileGlassFit = staticCompositionLocalOf { false }
internal val LocalMobileGlassTopAligned = staticCompositionLocalOf { false }
internal val LocalMobileGlassFillWidth = staticCompositionLocalOf { false }

/** Root coordinates are preserved, including negative origins when the hero scrolls offscreen. */
internal fun discoveryGlassImageRect(viewport: Rect, image: Size, fit: Boolean = false,
    topAligned: Boolean = false, fillWidth: Boolean = false): Rect? {
    if (viewport.isEmpty || !image.width.isFinite() || !image.height.isFinite() || image.width <= 0 || image.height <= 0) return null
    val scale = if (fillWidth) viewport.width / image.width else if (fit)
        minOf(viewport.width / image.width, viewport.height / image.height)
        else maxOf(viewport.width / image.width, viewport.height / image.height)
    val width = image.width * scale; val height = image.height * scale
    val x = viewport.left + (viewport.width - width) / 2
    val y = viewport.top + if (topAligned) 0f else (viewport.height - height) / 2
    return Rect(x, y, x + width, y + height)
}

internal val LocalMobileGlassEnabled = staticCompositionLocalOf { false }
internal val LocalMobileGlassBounds = staticCompositionLocalOf { Rect.Zero }
internal val LocalMobileGlassArtwork = staticCompositionLocalOf<String?> { null }
@Composable
internal fun DiscoveryMobileGlassContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (LocalMobileGlassEnabled.current) CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        DiscoveryGlassPanel(modifier, LocalMobileGlassArtwork.current, LocalMobileGlassBounds.current,
            fitArtwork = LocalMobileGlassFit.current, topAligned = LocalMobileGlassTopAligned.current,
            fillWidth = LocalMobileGlassFillWidth.current) { content() }
    }
    else Box(modifier) { content() }
}

internal val LocalDiscoveryGlassButtons = staticCompositionLocalOf { false }

internal data class DiscoveryHeaderWidths(val menu: Float, val profile: Float)
internal fun discoveryHeaderWidths(available: Float, desiredMenu: Float, desiredProfile: Float): DiscoveryHeaderWidths {
    val profile = desiredProfile.coerceAtMost(available * .3f)
    return DiscoveryHeaderWidths(desiredMenu.coerceAtMost((available - profile - 12f).coerceAtLeast(0f)), profile)
}

/** Blur only cached artwork, never the controls or live screen content. Works on older Fire OS too. */
@Composable
internal fun DiscoveryGlassPanel(modifier: Modifier, artwork: String?, backdropBounds: Rect,
    focused: Boolean = false, fitArtwork: Boolean = false, topAligned: Boolean = false, fillWidth: Boolean = false,
    baseColor: Color = Color(0x77171B24), content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    var position by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // MemoryInfo is a system-service call; never put that IPC on a focus/scroll frame.
    val permitted by key(context, artwork) {
        produceState(false) {
            value = withContext(Dispatchers.Default) { !discoveryArtworkPressure(context) }
        }
    }
    val painter = rememberAsyncImagePainter(remember(artwork, permitted) {
        ImageRequest.Builder(context).data(if (permitted) thumbnailArtworkUrl(artwork, 1280) else null)
            // The final glass texture is 96px wide. Decode near that size instead of
            // allocating a second full backdrop just to downsample it for blur.
            .size(192, 108).transformations(DiscoveryGlassTransformation()).build()
    })
    val shape = remember { RoundedCornerShape(10.dp) }
    val glassHighlight = remember {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f), Color.Transparent, Color.Black.copy(alpha = .12f)))
    }
    Box(modifier.onGloballyPositioned { position = it.positionInRoot() }.clip(shape)
        .background(baseColor).border(if (focused) 2.dp else 1.dp,
            if (focused) Color.White else Color(0x777F899B), shape)) {
        Canvas(Modifier.matchParentSize()) {
            val intrinsic = painter.intrinsicSize
            val imageRect = discoveryGlassImageRect(backdropBounds, intrinsic, fitArtwork, topAligned, fillWidth)
            if (imageRect != null) clipRect(backdropBounds.left - position.x, backdropBounds.top - position.y,
                backdropBounds.right - position.x, backdropBounds.bottom - position.y) {
                withTransform({ translate(imageRect.left - position.x, imageRect.top - position.y) }) {
                    with(painter) { draw(Size(imageRect.width, imageRect.height), alpha = .68f) }
                }
            }
            drawRect(Color(0x71101520))
            drawRect(glassHighlight)
        }
        content()
    }
}
