package com.nikhil.niktv.discovery

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import com.nikhil.niktv.ui.isTvLikeDevice
import com.nikhil.niktv.ui.usesShortWideLayout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transformations
import com.nikhil.niktv.data.thumbnailArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Window coordinates keep native hero islands and the outer glass chrome aligned. */
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

@Composable
internal fun discoveryHeaderActionSize() = if (LocalConfiguration.current.usesShortWideLayout(
    LocalContext.current.isTvLikeDevice(LocalConfiguration.current))) 36.dp else 44.dp

@Composable
internal fun detailActionSize(): androidx.compose.ui.unit.Dp {
    val configuration = LocalConfiguration.current
    val tv = LocalContext.current.isTvLikeDevice(configuration)
    return if (configuration.usesShortWideLayout(tv)) {
        if (LocalDetailActionGlass.current) 36.dp else 40.dp
    } else if (LocalDetailActionGlass.current && tv) 40.dp else 48.dp
}

@Composable
internal fun discoveryPrimaryActionWidth(phone: Boolean = false): androidx.compose.ui.unit.Dp {
    val density = LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = androidx.compose.material3.LocalTextStyle.current.copy(
        fontSize = (if (phone) 15 else 12).sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    val measured = remember(measurer, density, style, phone) {
        val label = listOf("Open", "Play", "Resume").maxOf {
            measurer.measure(androidx.compose.ui.text.AnnotatedString(it), style, softWrap = false).size.width
        }
        with(density) { label.toDp() } + (if (phone) 68.dp else 66.dp)
    }
    // Reserve the widest label before loading, regardless of Play/Resume/Open state.
    return maxOf((if (phone) 120f else 112f * density.fontScale.coerceAtLeast(1f)).dp, measured)
}

internal val LocalDetailActionGlass = staticCompositionLocalOf { false }
internal val LocalMobileGlassEnabled = staticCompositionLocalOf { false }
internal val LocalStableHeroGlass = staticCompositionLocalOf { false }
internal val WideDiscoveryGlassBase = Color(0xFF10131A)
internal val LocalMobileGlassBounds = staticCompositionLocalOf { Rect.Zero }
// Scrolling changes coordinates, not the header's content or measured layout.
internal val LocalMobileGlassMovingBounds = staticCompositionLocalOf<(() -> Rect)?> { null }
internal val LocalMobileGlassArtwork = staticCompositionLocalOf<String?> { null }
@Composable
internal fun DiscoveryMobileGlassContainer(modifier: Modifier = Modifier, focused: Boolean = false,
    artworkOpacity: Float = .68f, tint: Color = Color(0x71101520), content: @Composable () -> Unit) {
    if (LocalMobileGlassEnabled.current) CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        val stable = LocalStableHeroGlass.current
        val detail = LocalDetailActionGlass.current
        DiscoveryGlassPanel(modifier, if (stable) null else LocalMobileGlassArtwork.current,
            if (stable) Rect.Zero else LocalMobileGlassBounds.current,
            focused = focused,
            artworkOpacity = artworkOpacity,
            baseColor = if (stable) WideDiscoveryGlassBase else if (detail) Color(0x66242A34) else Color(0x77171B24),
            tint = if (detail) Color(0x44242A34) else tint,
            fitArtwork = !stable && LocalMobileGlassFit.current, topAligned = !stable && LocalMobileGlassTopAligned.current,
            fillWidth = !stable && LocalMobileGlassFillWidth.current) { content() }
    }
    else Box(modifier) { content() }
}

internal val LocalDiscoveryGlassButtons = staticCompositionLocalOf { false }
internal val LocalDiscoveryHeaderActionSizing = staticCompositionLocalOf { false }

internal data class DiscoveryHeaderWidths(val menu: Float, val profile: Float)
internal fun discoveryHeaderWidths(available: Float, desiredMenu: Float, desiredProfile: Float): DiscoveryHeaderWidths {
    val profile = desiredProfile.coerceAtMost(available * .3f)
    return DiscoveryHeaderWidths(desiredMenu.coerceAtMost((available - profile - 12f).coerceAtLeast(0f)), profile)
}

/** Blur only cached artwork, never the controls or live screen content. Works on older Fire OS too. */
@Composable
internal fun DiscoveryGlassPanel(modifier: Modifier, artwork: String?, backdropBounds: Rect,
    focused: Boolean = false, fitArtwork: Boolean = false, topAligned: Boolean = false, fillWidth: Boolean = false,
    baseColor: Color = Color(0x77171B24), artworkOpacity: Float = .68f, tint: Color = Color(0x71101520),
    content: @Composable BoxScope.() -> Unit) {
    val hasArtwork = !artwork.isNullOrBlank() && artworkOpacity > 0f
    val painter = if (hasArtwork) {
        val context = LocalContext.current
        // MemoryInfo is a system-service call. Neutral glass needs neither it nor an image request.
        val permitted by key(context, artwork) {
            produceState(false) {
                value = withContext(Dispatchers.Default) { !discoveryArtworkPressure(context) }
            }
        }
        if (permitted) rememberAsyncImagePainter(remember(context, artwork) {
            ImageRequest.Builder(context).data(thumbnailArtworkUrl(artwork, 1280))
                // Decode near the final glass texture size before applying blur.
                .size(192, 108).transformations(DiscoveryGlassTransformation()).build()
        }) else null
    } else null
    val position = if (hasArtwork) remember { mutableStateOf(Offset.Zero) } else null
    val movingBounds = if (painter != null) LocalMobileGlassMovingBounds.current else null
    val shape = remember { RoundedCornerShape(10.dp) }
    val glassHighlight = remember {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f), Color.Transparent, Color.Black.copy(alpha = .12f)))
    }
    val placement = if (position != null) Modifier.onGloballyPositioned { position.value = it.positionInWindow() } else Modifier
    Box(modifier.then(placement).clip(shape)
        .background(baseColor).border(if (focused) 2.dp else 1.dp,
            if (focused) Color.White else Color(0x777F899B), shape)
        .drawBehind {
            if (painter != null && position != null) {
                val bounds = movingBounds?.invoke() ?: backdropBounds
                val origin = position.value
                val imageRect = discoveryGlassImageRect(bounds, painter.intrinsicSize, fitArtwork, topAligned, fillWidth)
                if (imageRect != null) clipRect(bounds.left - origin.x, bounds.top - origin.y,
                    bounds.right - origin.x, bounds.bottom - origin.y) {
                    withTransform({ translate(imageRect.left - origin.x, imageRect.top - origin.y) }) {
                        with(painter) { draw(Size(imageRect.width, imageRect.height), alpha = artworkOpacity) }
                    }
                }
            }
            drawRect(tint)
            drawRect(glassHighlight)
        }, content = content)
}
